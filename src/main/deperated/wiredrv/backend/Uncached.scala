package wiredrv.backend

import chisel3._
import chisel3.util._
import wiredrv.common._

class UncachedIO(implicit p: CoreParams) extends Bundle {
  val req = Flipped(Decoupled(new LSUReq))
  val resp = Valid(new LSUResp)
  val busy = Output(Bool())
  val bus = new TLBundleUH
}

class Uncached(implicit p: CoreParams) extends Module {
  val io = IO(new UncachedIO)

  val sIdle :: sReq :: sResp :: sDone :: Nil = Enum(4)
  val state = RegInit(sIdle)
  val reqReg = Reg(new LSUReq)
  val respReg = Reg(new LSUResp)

  def accessBytes(funct3: UInt): UInt = {
    MuxLookup(funct3, 8.U)(Seq(
      "b000".U -> 1.U,
      "b001".U -> 2.U,
      "b010".U -> 4.U,
      "b011".U -> 8.U,
      "b100".U -> 1.U,
      "b101".U -> 2.U,
      "b110".U -> 4.U
    ))
  }

  def byteMask(addr: UInt, sizeBytes: UInt): UInt = {
    val base = Wire(UInt((p.xlen / 8).W))
    base := MuxLookup(sizeBytes, "hFF".U((p.xlen / 8).W))(Seq(
      1.U -> "h01".U((p.xlen / 8).W),
      2.U -> "h03".U((p.xlen / 8).W),
      4.U -> "h0F".U((p.xlen / 8).W),
      8.U -> "hFF".U((p.xlen / 8).W)
    ))
    (base << addr(2, 0))(p.xlen / 8 - 1, 0)
  }

  def loadResult(data: UInt, addr: UInt, funct3: UInt): UInt = {
    val shifted = data >> (addr(2, 0) << 3)
    val b = shifted(7, 0)
    val h = shifted(15, 0)
    val w = shifted(31, 0)
    MuxLookup(funct3, shifted)(Seq(
      "b000".U -> Cat(Fill(p.xlen - 8, b(7)), b),
      "b001".U -> Cat(Fill(p.xlen - 16, h(15)), h),
      "b010".U -> Cat(Fill(p.xlen - 32, w(31)), w),
      "b011".U -> shifted,
      "b100".U -> Cat(0.U((p.xlen - 8).W), b),
      "b101".U -> Cat(0.U((p.xlen - 16).W), h),
      "b110".U -> Cat(0.U((p.xlen - 32).W), w)
    ))
  }

  io.req.ready := state === sIdle
  io.resp.valid := state === sDone
  io.resp.bits := respReg
  io.busy := state =/= sIdle

  val sizeBytes = accessBytes(reqReg.funct3)
  val beatBytes = p.xlen / 8
  val alignedAddr = Cat(reqReg.addr(p.addrBits - 1, 3), 0.U(3.W))

  io.bus.a.valid := state === sReq
  io.bus.a.bits.opcode := Mux(reqReg.isStore, Mux(byteMask(reqReg.addr, sizeBytes).andR, TLBundleUH.PutFullData, TLBundleUH.PutPartialData), TLBundleUH.Get)
  io.bus.a.bits.param := 0.U
  io.bus.a.bits.size := log2Ceil(beatBytes).U
  io.bus.a.bits.source := 0.U
  io.bus.a.bits.address := alignedAddr
  io.bus.a.bits.mask := byteMask(reqReg.addr, sizeBytes)
  io.bus.a.bits.data := reqReg.data << (reqReg.addr(2, 0) << 3)
  io.bus.a.bits.corrupt := false.B
  io.bus.d.ready := state === sResp

  when(io.req.fire) {
    reqReg := io.req.bits
    state := sReq
  }

  when(state === sReq && io.bus.a.fire) {
    state := sResp
  }

  when(state === sResp && io.bus.d.fire) {
    respReg.exception := io.bus.d.bits.denied || io.bus.d.bits.corrupt
    respReg.exceptionCode := Mux(reqReg.isLoad, 5.U, 7.U)
    respReg.data := Mux(reqReg.isLoad, loadResult(io.bus.d.bits.data, reqReg.addr, reqReg.funct3), 0.U)
    state := sDone
  }

  when(state === sDone) {
    state := sIdle
  }
}
