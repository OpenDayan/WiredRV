package wiredrv.top

import chisel3._
import wiredrv.backend._
import wiredrv.common._
import wiredrv.frontend._

class WiredRV64S4Core(implicit p: CoreParams) extends Module {
  val io = IO(new Bundle {
    val imem = new TLBundleUH
    val dmem = new TLBundleUH
    val debug = Output(new Bundle {
      val commit = new CommitInfo
      val trap = new TrapInfo
      val gpr = Vec(32, UInt(p.xlen.W))
    })
  })

  val frontend = Module(new Frontend)
  val backend = Module(new Backend)

  frontend.io.redirects := backend.io.redirects
  frontend.io.invalidate := backend.io.icacheInvalidate
  backend.io.in <> frontend.io.inst
  io.imem <> frontend.io.bus
  io.dmem <> backend.io.dmem

  io.debug.commit := backend.io.commit
  io.debug.trap := backend.io.trap
  io.debug.gpr := backend.io.gpr
}
