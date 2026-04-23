// See README.md for license details.

package wiredrv

import chisel3._
import chisel3.util._
import difftest._
import _root_.circt.stage.ChiselStage

class Test extends Module with HasDiffTestInterfaces {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(32.W)))
  })
  io.in.ready := 1.B
  val difftest = DifftestModule(new DiffArchEvent, delay = 1, dontCare = true)
  val d0 = DifftestModule(new DiffPhyIntRegState(32), delay = 1, dontCare = true)
  val d1 = DifftestModule(new DiffCSRState, delay = 1, dontCare = true)
  val d2 = DifftestModule(new DiffInstrCommit, delay = 1, dontCare = true)
  val d3 = DifftestModule(new DiffTrapEvent, delay = 1, dontCare = true)
  difftest.valid  := io.in.valid
  difftest.coreid := 0.U
  difftest.interrupt := 0.U
  difftest.exceptionPC := io.in.bits
}

object Test extends App {
  ChiselStage.emitSystemVerilog(
    DifftestModule.top(new Test()),
    firtoolOpts = Array(
      "--mlir-disable-threading",
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=build/rtl"
    )
  )
}
