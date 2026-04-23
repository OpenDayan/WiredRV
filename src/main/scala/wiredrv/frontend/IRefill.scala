package wiredrv.frontend

import chisel3._
import chisel3.util._
import wiredrv.common._

class IRefillIO(implicit p: CoreParams) extends Bundle {
  val req = Flipped(Decoupled(new IRefillReq))
  val tagWrite = Valid(new ICacheTagWrite)
  val dataWrite = Valid(new ICacheDataWrite)
  val replay = Valid(UInt(p.addrBits.W))
  val busy = Output(Bool())
  val bus = new TLBundleUH
}

class IRefill(implicit p: CoreParams) extends Module {
  val io = IO(new IRefillIO)

  val beatBytes = p.xlen / 8
  val beatsPerLine = p.icacheLineBytes / beatBytes
  val sIdle :: sReq :: sResp :: sTag :: Nil = Enum(4)

  val state = RegInit(sIdle)
  val reqReg = Reg(new IRefillReq)
  val beatIdx = RegInit(0.U(log2Ceil(beatsPerLine).W))

  io.req.ready := state === sIdle
  io.busy := state =/= sIdle

  when(io.req.fire) {
    reqReg := io.req.bits
    state := sReq
    beatIdx := 0.U
  }

  val lineBaseAddr = Cat(reqReg.missPc(p.addrBits - 1, p.icacheLineBits), 0.U(p.icacheLineBits.W))
  val beatAddr = lineBaseAddr + (beatIdx << log2Ceil(beatBytes)).asUInt

  io.bus.a.valid := state === sReq
  io.bus.a.bits.opcode := TLBundleUH.Get
  io.bus.a.bits.param := 0.U
  io.bus.a.bits.size := log2Ceil(beatBytes).U
  io.bus.a.bits.source := 0.U
  io.bus.a.bits.address := beatAddr
  io.bus.a.bits.mask := Fill(beatBytes, 1.U(1.W))
  io.bus.a.bits.data := 0.U
  io.bus.a.bits.corrupt := false.B
  io.bus.d.ready := true.B

  when(state === sReq && io.bus.a.fire) {
    state := sResp
  }

  io.dataWrite.valid := state === sResp && io.bus.d.fire
  io.dataWrite.bits.setIdx := reqReg.setIdx
  io.dataWrite.bits.beatIdx := beatIdx
  io.dataWrite.bits.way := reqReg.replaceWay
  io.dataWrite.bits.data := io.bus.d.bits.data

  when(io.bus.d.fire && state === sResp) {
    when(beatIdx === (beatsPerLine - 1).U) {
      state := sTag
    }.otherwise {
      beatIdx := beatIdx + 1.U
      state := sReq
    }
  }

  io.tagWrite.valid := state === sTag
  io.tagWrite.bits.setIdx := reqReg.setIdx
  io.tagWrite.bits.tag := reqReg.tag
  io.tagWrite.bits.way := reqReg.replaceWay

  io.replay.valid := state === sTag
  io.replay.bits := reqReg.missPc

  when(state === sTag) {
    state := sIdle
  }
}
