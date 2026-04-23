package wiredrv.bus

import chisel3._
import chisel3.util._
import wiredrv.CoreParams

class TLUHXbar(width: Int, nInputs: Int)(implicit p: CoreParams) extends Module {
  require(nInputs > 0, "TLUHXbar needs at least one input")

  val io = IO(new Bundle {
    val in = Vec(nInputs, Flipped(new TLBundleUH(width)))
    val out = new TLBundleUH(width)
  })

  val arb = Module(new Arbiter(new TLUHChannelA(width), nInputs))
  val respSel = RegInit(0.U(log2Ceil(nInputs).W))
  val waitingResp = RegInit(false.B)

  for (i <- 0 until nInputs) {
    arb.io.in(i) <> io.in(i).a
    io.in(i).d.valid := false.B
    io.in(i).d.bits := io.out.d.bits
  }

  io.out.a.valid := arb.io.out.valid && !waitingResp
  io.out.a.bits := 0.U.asTypeOf(new TLUHChannelA(width))
  when(arb.io.out.valid) {
    io.out.a.bits := arb.io.out.bits
  }
  arb.io.out.ready := io.out.a.ready && !waitingResp

  when(io.out.a.fire) {
    respSel := arb.io.chosen
    waitingResp := true.B
  }

  io.out.d.ready := io.in(respSel).d.ready && waitingResp
  io.in(respSel).d.valid := io.out.d.valid && waitingResp

  when(io.out.d.fire) {
    waitingResp := false.B
  }
}
