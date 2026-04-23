package wiredrv.backend

import chisel3._
import chisel3.util._
import wiredrv.common._

class ExecuteUnit(implicit p: CoreParams) extends Module {
  val io = IO(new Bundle {
    val in = Input(new ExecuteIn)
    val out = Output(new ExecuteOut)
  })

  val shamt = io.in.src2(5, 0).asUInt
  val aluRes = WireDefault(0.U(p.xlen.W))
  def sext32(x: UInt): UInt = Cat(Fill(p.xlen - 32, x(31)), x(31, 0))
  val srawRes = ((io.in.src1(31, 0).asSInt >> io.in.src2(4, 0)).asUInt)(31, 0)
  switch(io.in.info.fuOpType) {
    is(AluOp.add) { aluRes := io.in.src1 + io.in.src2 }
    is(AluOp.sub) { aluRes := io.in.src1 - io.in.src2 }
    is(AluOp.sll) { aluRes := io.in.src1 << shamt }
    is(AluOp.slt) { aluRes := (io.in.src1.asSInt < io.in.src2.asSInt).asUInt }
    is(AluOp.sltu) { aluRes := (io.in.src1 < io.in.src2).asUInt }
    is(AluOp.xor) { aluRes := io.in.src1 ^ io.in.src2 }
    is(AluOp.srl) { aluRes := io.in.src1 >> shamt }
    is(AluOp.sra) { aluRes := (io.in.src1.asSInt >> shamt).asUInt }
    is(AluOp.or) { aluRes := io.in.src1 | io.in.src2 }
    is(AluOp.and) { aluRes := io.in.src1 & io.in.src2 }
    is(AluOp.copy1) { aluRes := io.in.src1 }
    is(AluOp.copy2) { aluRes := io.in.src2 }
    is(AluOp.addw) { aluRes := sext32((io.in.src1 + io.in.src2)(31, 0)) }
    is(AluOp.subw) { aluRes := sext32((io.in.src1 - io.in.src2)(31, 0)) }
    is(AluOp.sllw) { aluRes := sext32((io.in.src1(31, 0) << io.in.src2(4, 0))(31, 0)) }
    is(AluOp.srlw) { aluRes := sext32((io.in.src1(31, 0) >> io.in.src2(4, 0))(31, 0)) }
    is(AluOp.sraw) { aluRes := sext32(srawRes) }
  }

  val bruTaken = WireDefault(false.B)
  switch(io.in.info.fuOpType) {
    is(BruOp.beq) { bruTaken := io.in.src1 === io.in.src2 }
    is(BruOp.bne) { bruTaken := io.in.src1 =/= io.in.src2 }
    is(BruOp.blt) { bruTaken := io.in.src1.asSInt < io.in.src2.asSInt }
    is(BruOp.bge) { bruTaken := io.in.src1.asSInt >= io.in.src2.asSInt }
    is(BruOp.bltu) { bruTaken := io.in.src1 < io.in.src2 }
    is(BruOp.bgeu) { bruTaken := io.in.src1 >= io.in.src2 }
  }

  val isJal = io.in.info.fuType === FuType.jmp && io.in.info.fuOpType === BruOp.jal
  val isJalr = io.in.info.fuType === FuType.jmp && io.in.info.fuOpType === BruOp.jalr
  val isBranch = io.in.info.isBranch
  val isCsr = io.in.info.isCsr && io.in.info.fuOpType =/= CsrOp.ecall
  val isEcall = io.in.info.fuType === FuType.csr && io.in.info.fuOpType === CsrOp.ecall
  val isFenceFlush = io.in.info.isFence && io.in.info.flushPipe
  val redirectTarget = Mux(isJalr, (io.in.src1 + io.in.imm) & (~1.U(p.xlen.W)), io.in.pc + io.in.imm)
  val isLsu = io.in.info.fuType === FuType.lsu
  val ctrlFlowTaken = isJal || isJalr || (isBranch && bruTaken)
  val ctrlMisaligned = ctrlFlowTaken && redirectTarget(1, 0) =/= 0.U
  val wbResult = Mux(isJal || isJalr, io.in.pc + 4.U,
    Mux(isLsu, io.in.src1 + io.in.imm,
      Mux(isCsr, io.in.csrReadData, aluRes)))

  io.out.pc := io.in.pc
  io.out.inst := io.in.inst
  io.out.rd := io.in.rd
  io.out.result := wbResult
  io.out.exception := ctrlMisaligned
  io.out.exceptionCode := 0.U
  io.out.invalidateIcache := false.B
  io.out.redirect.valid := false.B
  io.out.redirect.bits := 0.U.asTypeOf(new RedirectReq)

  when(isJalr && !ctrlMisaligned) {
    io.out.redirect.valid := true.B
    io.out.redirect.bits.target := redirectTarget(p.addrBits - 1, 0)
    io.out.redirect.bits.cause := RedirectCause.exuFlush
  }.elsewhen(isBranch && bruTaken && !ctrlMisaligned) {
    io.out.redirect.valid := true.B
    io.out.redirect.bits.target := redirectTarget(p.addrBits - 1, 0)
    io.out.redirect.bits.cause := RedirectCause.exuFlush
  }.elsewhen(isEcall) {
    io.out.redirect.valid := false.B
  }.elsewhen(isFenceFlush && !ctrlMisaligned) {
    io.out.invalidateIcache := true.B
    io.out.redirect.valid := true.B
    io.out.redirect.bits.target := (io.in.pc + 4.U)(p.addrBits - 1, 0)
    io.out.redirect.bits.cause := RedirectCause.exuFlush
  }

  when(ctrlMisaligned) {
    io.out.exceptionCode := 0.U
  }
}
