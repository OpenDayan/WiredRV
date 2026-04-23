package wiredrv

import chisel3._
import chisel3.util._
import wiredrv.bus._
import wiredrv.frontend._

class WiredCoreIO(implicit p: CoreParams) extends Bundle {
  val inst = Decoupled(new FetchPacket)
  val mem = new TLBundleUH(p.outerBusWidth)
}

class WiredCore(implicit p: CoreParams) extends Module {
  val io = IO(new WiredCoreIO)

  val frontend = Module(new Frontend)
  val imemToTl = Module(new BusToTLUH(p.innerBusWidth))
  val dmemToTl = Module(new BusToTLUH(p.innerBusWidth))
  val imemWidthUp = Module(new TLUHWidthAdapter(p.innerBusWidth, p.outerBusWidth))
  val dmemWidthUp = Module(new TLUHWidthAdapter(p.innerBusWidth, p.outerBusWidth))
  val xbar = Module(new TLUHXbar(p.outerBusWidth, 2))

  frontend.io.redirects.foreach { redir =>
    redir.valid := false.B
    redir.bits := 0.U
  }
  frontend.io.ifencei := false.B

  io.inst <> frontend.io.inst

  dmemToTl.io.in.a.valid := false.B
  dmemToTl.io.in.a.bits := 0.U.asTypeOf(dmemToTl.io.in.a.bits)
  dmemToTl.io.in.d.ready := true.B

  imemToTl.io.in <> frontend.io.bus
  imemWidthUp.io.in <> imemToTl.io.out
  dmemWidthUp.io.in <> dmemToTl.io.out
  xbar.io.in(0) <> imemWidthUp.io.out
  xbar.io.in(1) <> dmemWidthUp.io.out
  io.mem <> xbar.io.out
}
