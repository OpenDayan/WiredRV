package wiredrv.frontend

import chisel3._
import chisel3.util._
import wiredrv.common._

class IRefillIO(implicit p: CoreParams) extends Bundle {
  val req = Flipped(Decoupled(new IRefillReq))
  val refill = Valid(new ICacheRefillWrite)
  val replay = Valid(UInt(p.addrBits.W))
  val busy = Output(Bool())
  val bus = new TLBundleUH
}

class IRefill(implicit p: CoreParams) extends Module {
  val io = IO(new IRefillIO)

  val lineBits = p.icacheLineBytes * 8
  val beatBytes = p.xlen / 8
  val beatsPerLine = p.icacheLineBytes / beatBytes
  val sIdle :: sReq :: sResp :: sDone :: Nil = Enum(4)

  val state = RegInit(sIdle)
  val reqReg = Reg(new IRefillReq)
  val beatIdx = RegInit(0.U(log2Ceil(beatsPerLine).W))
  val lineBuf = RegInit(0.U(lineBits.W))

  io.req.ready := state === sIdle
  io.busy := state =/= sIdle

  when(io.req.fire) {
    reqReg := io.req.bits
    state := sReq
    beatIdx := 0.U
    lineBuf := 0.U
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

  when(state === sResp && io.bus.d.fire) {
    val lineWords = Wire(Vec(beatsPerLine, UInt(p.xlen.W)))
    lineWords := lineBuf.asTypeOf(lineWords)
    lineWords(beatIdx) := io.bus.d.bits.data
    lineBuf := lineWords.asUInt
    when(beatIdx === (beatsPerLine - 1).U) {
      state := sDone
    }.otherwise {
      beatIdx := beatIdx + 1.U
      state := sReq
    }
  }

  io.refill.valid := state === sDone
  io.refill.bits.setIdx := reqReg.setIdx
  io.refill.bits.tag := reqReg.tag
  io.refill.bits.way := reqReg.replaceWay
  io.refill.bits.line := lineBuf

  io.replay.valid := state === sDone
  io.replay.bits := reqReg.missPc

  when(state === sDone) {
    state := sIdle
  }
}
