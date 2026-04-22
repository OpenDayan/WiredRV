// See README.md for license details.

package wiredrv

import chisel3._
import chisel3.util._
import difftest._
import _root_.circt.stage.ChiselStage

class Test extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(32.W)))
  })
  io.in.ready := 1.B
  val difftest = DifftestModule(new DiffInstrCommit, delay = 1, dontCare = true)
  difftest.valid  := io.in.valid
  difftest.pc     := 0.U
  difftest.instr  := io.in.bits
  difftest.skip   := 0.B
  difftest.isRVC  := 0.B
  difftest.rfwen  := 1.B
  difftest.wdest  := 0.U
  difftest.wpdest := 0.U
}

object Test extends App {
  ChiselStage.emitSystemVerilog(
    new Test(),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-preserve-aggregate=vec",
      "--split-verilog",
      "-o=generated/Test"
    )
  )
}
