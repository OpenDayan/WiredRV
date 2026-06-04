package wiredrv.backend

import chisel3._
import chisel3.util._
import wiredrv.CoreParams

class DecodeInfo extends Bundle {
  val src1Type = UInt(SrcType.width.W)
  val src2Type = UInt(SrcType.width.W)
  val fuType = UInt(FuType.width.W)
  val fuOpType = UInt(8.W)
  val rs1en = Bool()
  val rs2en = Bool()
  val rden = Bool()
  val rwen = Bool()
  val rdAsSrc = Bool()
  val tensorExpectsResp = Bool()
  val isRVT64 = Bool()
  val isRVT32 = Bool()
  val selImm = UInt(ImmSel.width.W)
  val isStore = Bool()
  val illegal = Bool()
  val flushPipe = Bool()
  val isLsu = Bool()
  val isAlu = Bool()
}

class DecodedInst(implicit p: CoreParams) extends Bundle {
  val pc = UInt(p.addrBits.W)
  val inst = UInt(32.W)
  val info = new DecodeInfo
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val rd = UInt(5.W)
  val imm = UInt(p.xlen.W)
  val csrAddr = UInt(12.W)
  val exceptionValid = Bool()
  val exceptionCode = UInt(16.W)
  val isUncached = Bool()
}

class Decode(implicit p: CoreParams) extends Module {
  val io = IO(new Bundle {
    val inst = Input(UInt(32.W))
    val out = Output(new DecodedInst)
  })

  def signExt(value: UInt, fromBits: Int): UInt =
    Cat(Fill(p.xlen - fromBits, value(fromBits - 1)), value(fromBits - 1, 0))

  val inst = io.inst
  val decodeBits = ListLookup(inst, RV64IDecodeTable.decodeDefault, RV64IDecodeTable.table)
  val isTensor32 = inst(6, 0) === "b0001011".U
  val isTensor64 = inst(6, 0) === "b0111111".U
  val isTensor = decodeBits(2) === FuType.tensor
  val tensorFunct3 = inst(14, 12)
  val isTcsrAccess = isTensor32 && inst(31, 27) === 0.U
  val isSbAccess = isTensor32 && inst(31, 27) === 1.U
  val isControlAccess = isTcsrAccess || isSbAccess
  val isTcsrRead = isTcsrAccess && tensorFunct3 === "b100".U
  val isSbRead = isSbAccess && (tensorFunct3 === "b100".U || tensorFunct3 === "b101".U)
  val isRespRead = isTcsrRead || isSbRead
  val isTcsrWrite = isTcsrAccess && tensorFunct3 === "b000".U
  val isSbWrite = isSbAccess && tensorFunct3 === "b000".U
  val isControlWrite = isTcsrWrite || isSbWrite
  val isAsyncTensor = isTensor64 || (isTensor32 && !isControlAccess)

  val info = WireInit(0.U.asTypeOf(new DecodeInfo))
  info.src1Type := Mux(isControlAccess, SrcType.none, decodeBits(0))
  info.src2Type := Mux(isControlAccess, SrcType.none, decodeBits(1))
  info.fuType := decodeBits(2)
  info.fuOpType := decodeBits(3)
  info.rwen := Mux(isTensor, isRespRead, decodeBits(4)(0))
  info.selImm := decodeBits(5)
  info.flushPipe := decodeBits(6)(0)
  info.illegal := decodeBits(7)(0)
  info.rs1en := info.src1Type === SrcType.reg
  info.rs2en := info.src2Type === SrcType.reg
  info.rden := info.rwen
  info.rdAsSrc := isTensor && (isControlWrite || isAsyncTensor)
  info.tensorExpectsResp := isTensor && isRespRead
  info.isRVT32 := isTensor32
  info.isRVT64 := isTensor64
  info.isLsu := decodeBits(2) === FuType.lsu
  info.isAlu := decodeBits(2) === FuType.alu
  info.isStore := info.isLsu && LSUOp.isStore(decodeBits(3))

  val immI = signExt(inst(31, 20), 12)
  val immS = signExt(Cat(inst(31, 25), inst(11, 7)), 12)
  val immB = signExt(Cat(inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W)), 13)
  val immU = signExt(Cat(inst(31, 12), 0.U(12.W)), 32)
  val immJ = signExt(Cat(inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W)), 21)
  val immZ = Cat(0.U((p.xlen - 5).W), inst(19, 15))

  val imm = WireDefault(0.U(p.xlen.W))
  switch(info.selImm) {
    is(ImmSel.i) { imm := immI }
    is(ImmSel.s) { imm := immS }
    is(ImmSel.b) { imm := immB }
    is(ImmSel.u) { imm := immU }
    is(ImmSel.j) { imm := immJ }
    is(ImmSel.z) { imm := immZ }
  }

  val isEcall = inst === RV64IInstructions.ECALL.value.U
  val isEbreak = inst === RV64IInstructions.EBREAK.value.U
  val exceptionCode = Mux(isEcall, 11.U, Mux(isEbreak, 3.U, Mux(info.illegal, 2.U, 0.U)))
  val exceptionValid = isEcall || isEbreak || info.illegal

  io.out := 0.U.asTypeOf(new DecodedInst)
  io.out.inst := inst
  io.out.info := info
  io.out.rs1 := inst(19, 15)
  io.out.rs2 := inst(24, 20)
  io.out.rd := inst(11, 7)
  io.out.imm := imm
  io.out.csrAddr := inst(31, 20)
  io.out.exceptionValid := exceptionValid
  io.out.exceptionCode := exceptionCode
  io.out.isUncached := false.B
}
