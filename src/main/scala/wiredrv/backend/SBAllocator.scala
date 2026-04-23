package wiredrv.backend

import chisel3._
import chisel3.util._
import wiredrv.CoreParams

/*
* 本模块用于分配空闲 Varlatency Scoreboard 表项，并及时回收
* */
class SBAllocator(implicit p: CoreParams) extends Module {
    val io = IO(new Bundle {
        val allocate = Decoupled(UInt(p.longLatencySlotsBits.W))
        val release  = Flipped(Decoupled(UInt(p.longLatencySlotsBits.W)))
    })
    val ram = RegInit(VecInit.tabulate(p.longLatencySlots)(i => i.U(p.longLatencySlotsBits.W)))
    val rptr = RegInit(0.U(p.longLatencySlotsBits.W))
    val wptr = RegInit(p.longLatencySlots.U(p.longLatencySlotsBits.W))

    val space = RegInit(0.U((p.longLatencySlotsBits + 1).W))
    val usage = RegInit(p.longLatencySlots.U((p.longLatencySlotsBits + 1).W))

    io.allocate.valid := usage =/= 0.U
    io.release.ready := space =/= 0.U
    
    space := Mux(io.allocate.fire ^ io.release.fire, Mux(io.allocate.fire, space + 1.U, space - 1.U), space)
    usage := Mux(io.allocate.fire ^ io.release.fire, Mux(io.allocate.fire, usage - 1.U, usage + 1.U), usage)

    when(io.allocate.fire) {
        rptr := rptr + 1.U
    }

    when(io.release.fire) {
        wptr := wptr + 1.U
    }

}

