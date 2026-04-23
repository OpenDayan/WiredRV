package wiredrv.backend

import chisel3._
import chisel3.util._
import wiredrv.common._

object DRefillOp {
  val refill = 0.U(2.W)
  val inval = 1.U(2.W)
  val clean = 2.U(2.W)
  val flush = 3.U(2.W)
}

class DRefillIO(implicit p: CoreParams) extends Bundle {
  val req = Flipped(Decoupled(new DRefillReq))
  val resp = Valid(new DRefillResp)
  val refill = Valid(new DCacheRefillWrite)
  val invalidateSet = Valid(UInt(p.dcacheSetBits.W))
  val busy = Output(Bool())
  val bus = new TLBundleUH
}

class DRefill(implicit p: CoreParams) extends Module {
  val io = IO(new DRefillIO)

  private val lineBits = p.dcacheLineBytes * 8
  private val beatBytes = p.xlen / 8
  private val beatsPerLine = p.dcacheLineBytes / beatBytes
  private val beatIdxWidth = math.max(1, log2Ceil(beatsPerLine))

  val Seq(sIdle, sReq, sResp, sDone) = Enum(4)
  val state = RegInit(sIdle)
  val reqReg = Reg(new DRefillReq)
  val beatIdx = RegInit(0.U(beatIdxWidth.W))
  val lineBuf = RegInit(0.U(lineBits.W))
  val exceptionReg = RegInit(false.B)
  val exceptionCodeReg = RegInit(0.U(16.W))

  io.req.ready := state === sIdle
  io.busy := state =/= sIdle

  io.bus.a.valid := false.B
  io.bus.a.bits := 0.U.asTypeOf(io.bus.a.bits)
  io.bus.d.ready := state === sResp

  val lineBaseAddr = Cat(reqReg.addr(p.addrBits - 1, p.dcacheLineBits), 0.U(p.dcacheLineBits.W))
  val beatAddr = lineBaseAddr + (beatIdx << log2Ceil(beatBytes).U)

  when(io.req.fire) {
    reqReg := io.req.bits
    beatIdx := 0.U
    lineBuf := 0.U
    exceptionReg := false.B
    exceptionCodeReg := 0.U
    when(io.req.bits.op === DRefillOp.refill) {
      state := sReq
    }.otherwise {
      state := sDone
    }
  }

  when(state === sReq) {
    io.bus.a.valid := true.B
    io.bus.a.bits.opcode := TLBundleUH.Get
    io.bus.a.bits.param := 0.U
    io.bus.a.bits.size := log2Ceil(beatBytes).U
    io.bus.a.bits.source := 0.U
    io.bus.a.bits.address := beatAddr
    io.bus.a.bits.mask := Fill(beatBytes, 1.U(1.W))
    io.bus.a.bits.data := 0.U
    io.bus.a.bits.corrupt := false.B
    when(io.bus.a.fire) {
      state := sResp
    }
  }

  when(state === sResp && io.bus.d.fire) {
    val nextWords = WireInit(lineBuf.asTypeOf(Vec(beatsPerLine, UInt(p.xlen.W))))
    nextWords(beatIdx) := io.bus.d.bits.data
    lineBuf := nextWords.asUInt
    when(io.bus.d.bits.denied || io.bus.d.bits.corrupt) {
      exceptionReg := true.B
      exceptionCodeReg := 5.U
      state := sDone
    }.elsewhen(beatIdx === (beatsPerLine - 1).U) {
      state := sDone
    }.otherwise {
      beatIdx := beatIdx + 1.U
      state := sReq
    }
  }

  io.refill.valid := state === sDone && reqReg.op === DRefillOp.refill && !exceptionReg
  io.refill.bits.setIdx := reqReg.setIdx
  io.refill.bits.tag := reqReg.tag
  io.refill.bits.way := reqReg.replaceWay
  io.refill.bits.line := lineBuf

  io.invalidateSet.valid := state === sDone &&
    (reqReg.op === DRefillOp.inval || reqReg.op === DRefillOp.flush) && !exceptionReg
  io.invalidateSet.bits := reqReg.setIdx

  io.resp.valid := state === sDone
  io.resp.bits.line := lineBuf
  io.resp.bits.exception := exceptionReg
  io.resp.bits.exceptionCode := exceptionCodeReg

  when(state === sDone) {
    state := sIdle
  }
}
