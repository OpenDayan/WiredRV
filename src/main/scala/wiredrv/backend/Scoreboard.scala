package wiredrv.backend

import chisel3._
import chisel3.util._
import wiredrv.common._

class Scoreboard(implicit p: CoreParams) extends Module {
  val io = IO(new Bundle {
    val probeRs1 = Input(UInt(5.W))
    val probeRs2 = Input(UInt(5.W))
    val stall = Output(Bool())
    val fixed = Output(Vec(2, new FixedSlot))
    val long = Output(Vec(p.longLatencySlots, new LongSlot))
  })

  io.stall := false.B
  io.fixed := VecInit(Seq.fill(2)(0.U.asTypeOf(new FixedSlot)))
  io.long := VecInit(Seq.fill(p.longLatencySlots)(0.U.asTypeOf(new LongSlot)))
}
