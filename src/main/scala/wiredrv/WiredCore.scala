package wiredrv

import chisel3._
import chisel3.util._
import lmss.axi._
import wiredrv.bus._
import wiredrv.backend._
import wiredrv.frontend._

class WiredCoreIO(implicit p: CoreParams) extends Bundle {
  val inst = Decoupled(new FetchPacket)
  val mem = new AxiBundle(AxiSlvParamsCalc(Seq(
    AxiParams(addrBits = p.addrBits, idBits = 4, dataBits = p.outerBusWidth),
    AxiParams(addrBits = p.addrBits, idBits = 4, dataBits = p.outerBusWidth)
  )))
  val diffCommitValid = Output(Bool())
  val diffCommitPc = Output(UInt(p.addrBits.W))
  val diffCommitInst = Output(UInt(32.W))
  val diffCommitRfWen = Output(Bool())
  val diffCommitRd = Output(UInt(5.W))
  val diffCommitIsLoad = Output(Bool())
  val diffCommitIsStore = Output(Bool())
  val diffCommitIsDelayedWb = Output(Bool())
  val diffDelayedWbValid = Output(Bool())
  val diffDelayedWbRd = Output(UInt(5.W))
  val diffDelayedWbData = Output(UInt(p.xlen.W))
  val diffRf = Output(Vec(32, UInt(p.xlen.W)))
}

class WiredCore(implicit p: CoreParams) extends Module {
  val io = IO(new WiredCoreIO)
  private val innerAxi = AxiParams(addrBits = p.addrBits, idBits = 4, dataBits = p.innerBusWidth)
  private val outerAxi = AxiParams(addrBits = p.addrBits, idBits = 4, dataBits = p.outerBusWidth)

  val frontend = Module(new Frontend)
  val backend = Module(new Backend)
  val imemToAxi = Module(new BusToAxi(p.innerBusWidth))
  val dmemToAxi = Module(new BusToAxi(p.innerBusWidth))
  val imemWidthUp = Module(new AxiNarrowToWide(innerAxi, outerAxi, buffer = 4))
  val dmemWidthUp = Module(new AxiNarrowToWide(innerAxi, outerAxi, buffer = 4))
  val xbar = Module(new LmssAxiXbar(Seq(outerAxi, outerAxi)))

  backend.io.inst <> frontend.io.inst
  frontend.io.redirects := backend.io.redirects
  frontend.io.ifencei := backend.io.ifencei

  io.inst.valid := backend.io.commit.valid
  io.inst.bits := backend.io.commit.bits
  backend.io.commit.ready := io.inst.ready
  io.diffCommitValid := backend.io.diffCommitValid
  io.diffCommitPc := backend.io.diffCommitPc
  io.diffCommitInst := backend.io.diffCommitInst
  io.diffCommitRfWen := backend.io.diffCommitRfWen
  io.diffCommitRd := backend.io.diffCommitRd
  io.diffCommitIsLoad := backend.io.diffCommitIsLoad
  io.diffCommitIsStore := backend.io.diffCommitIsStore
  io.diffCommitIsDelayedWb := backend.io.diffCommitIsDelayedWb
  io.diffDelayedWbValid := backend.io.diffDelayedWbValid
  io.diffDelayedWbRd := backend.io.diffDelayedWbRd
  io.diffDelayedWbData := backend.io.diffDelayedWbData
  io.diffRf := backend.io.diffRf

  imemToAxi.io.in <> frontend.io.bus
  dmemToAxi.io.in <> backend.io.bus
  imemWidthUp.io.mst <> imemToAxi.io.out
  dmemWidthUp.io.mst <> dmemToAxi.io.out

  xbar.io.upstream(0) <> imemWidthUp.io.slv
  xbar.io.upstream(1) <> dmemWidthUp.io.slv
  io.mem <> xbar.io.downstream.head
}
