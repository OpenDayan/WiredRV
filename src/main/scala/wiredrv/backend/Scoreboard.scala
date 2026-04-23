package wiredrv.backend

import chisel3._
import chisel3.util._
import wiredrv.common._

class Scoreboard(implicit p: CoreParams) extends Module {
  val io = IO(new Bundle {
    val probeRs1 = Input(UInt(5.W))
    val probeRs2 = Input(UInt(5.W))
    val probeRd = Input(UInt(5.W))
    val issueFixed = Input(Valid(new FixedSlot))
    val advance = Input(Bool())
    val wbWriteback = Input(Valid(UInt(5.W)))
    val wbClear = Input(Valid(UInt(5.W)))
    val longAlloc = Input(Valid(new LongSlot))
    val longRelease = Input(Valid(UInt(5.W)))
    val stall = Output(Bool())
    val fixed = Output(Vec(2, new FixedSlot))
    val long = Output(Vec(p.longLatencySlots, new LongSlot))
  })

  val fixedSlots = RegInit(VecInit(Seq.fill(2)(0.U.asTypeOf(new FixedSlot))))
  val longSlots = RegInit(VecInit(Seq.fill(p.longLatencySlots)(0.U.asTypeOf(new LongSlot))))

  val fixedNext = WireInit(fixedSlots)
  when(io.wbWriteback.valid && fixedSlots(1).valid && fixedSlots(1).rd === io.wbWriteback.bits) {
    fixedNext(1).valid := false.B
  }
  when(io.wbClear.valid && fixedSlots(1).valid && fixedSlots(1).rd === io.wbClear.bits) {
    fixedNext(1).valid := false.B
  }
  when(io.advance) {
    fixedNext(1) := fixedSlots(0)
    fixedNext(0).valid := false.B
  }
  when(io.issueFixed.valid) {
    fixedNext(0) := io.issueFixed.bits
  }
  fixedSlots := fixedNext

  when(io.longRelease.valid && io.longRelease.bits.orR) {
    for (i <- 0 until p.longLatencySlots) {
      when(longSlots(i).valid && longSlots(i).rd === io.longRelease.bits) {
        longSlots(i).valid := false.B
      }
    }
  }

  when(io.longAlloc.valid && io.longAlloc.bits.valid) {
    val freeOH = PriorityEncoderOH(VecInit(longSlots.map(!_.valid)).asUInt)
    for (i <- 0 until p.longLatencySlots) {
      when(freeOH(i)) {
        longSlots(i) := io.longAlloc.bits
      }
    }
  }

  io.fixed := fixedSlots
  io.long := longSlots

  val rs1HazLong = io.probeRs1.orR && longSlots.map(s => s.valid && s.rd === io.probeRs1).reduce(_ || _)
  val rs2HazLong = io.probeRs2.orR && longSlots.map(s => s.valid && s.rd === io.probeRs2).reduce(_ || _)
  val rdHazLong = io.probeRd.orR && longSlots.map(s => s.valid && s.rd === io.probeRd).reduce(_ || _)

  io.stall := rs1HazLong || rs2HazLong || rdHazLong
}
