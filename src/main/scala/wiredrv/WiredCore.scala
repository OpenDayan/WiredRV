package wiredrv

import chisel3._
import chisel3.util._
import lmss.axi._
import wiredrv.bus._
import wiredrv.frontend._

class WiredCoreIO(implicit p: CoreParams) extends Bundle {
  val inst = Decoupled(new FetchPacket)
  val mem = new AxiBundle(AxiSlvParamsCalc(Seq(
    AxiParams(addrBits = p.addrBits, idBits = 4, dataBits = p.outerBusWidth),
    AxiParams(addrBits = p.addrBits, idBits = 4, dataBits = p.outerBusWidth)
  )))
}

class WiredCore(implicit p: CoreParams) extends Module {
  val io = IO(new WiredCoreIO)
  private val innerAxi = AxiParams(addrBits = p.addrBits, idBits = 4, dataBits = p.innerBusWidth)
  private val outerAxi = AxiParams(addrBits = p.addrBits, idBits = 4, dataBits = p.outerBusWidth)

  val frontend = Module(new Frontend)
  val imemToAxi = Module(new BusToAxi(p.innerBusWidth))
  val dmemStub = Wire(new AxiBundle(innerAxi))
  val imemWidthUp = Module(new AxiNarrowToWide(innerAxi, outerAxi, buffer = 4))
  val dmemWidthUp = Module(new AxiNarrowToWide(innerAxi, outerAxi, buffer = 4))
  val xbar = Module(new LmssAxiXbar(Seq(outerAxi, outerAxi)))
  frontend.io.redirects.foreach { redir =>
    redir.valid := false.B
    redir.bits := 0.U
  }

  val cnt = RegInit(7.U)
  cnt := cnt - 1.U

  when(cnt === 0.U) {
    frontend.io.redirects(0).valid := frontend.io.inst.bits.pc >= "h80000000".U
    val nxt = frontend.io.inst.bits.pc + (13 * 4).U
    when(nxt > "h80000100".U) {
      frontend.io.redirects(0).bits := "h80000000".U
    }.otherwise {
      frontend.io.redirects(0).bits := nxt
    }
  }
  frontend.io.ifencei := false.B

  io.inst <> frontend.io.inst

  dmemStub.aw.valid := false.B
  dmemStub.aw.bits := 0.U.asTypeOf(dmemStub.aw.bits)
  dmemStub.ar.valid := false.B
  dmemStub.ar.bits := 0.U.asTypeOf(dmemStub.ar.bits)
  dmemStub.w.valid := false.B
  dmemStub.w.bits := 0.U.asTypeOf(dmemStub.w.bits)
  dmemStub.b.ready := true.B
  dmemStub.r.ready := true.B

  imemToAxi.io.in <> frontend.io.bus
  imemWidthUp.io.mst <> imemToAxi.io.out
  dmemWidthUp.io.mst <> dmemStub

  xbar.io.upstream(0) <> imemWidthUp.io.slv
  xbar.io.upstream(1) <> dmemWidthUp.io.slv
  io.mem <> xbar.io.downstream.head
}
