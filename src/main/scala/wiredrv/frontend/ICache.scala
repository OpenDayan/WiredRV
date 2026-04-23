package wiredrv.frontend

import chisel3._
import chisel3.util._
import wiredrv.common._

class ICacheIO(implicit p: CoreParams) extends Bundle {
  val req = Flipped(Valid(UInt(p.addrBits.W)))
  val resp = Valid(new ICacheResp)
  val refill = Flipped(Valid(new ICacheRefillWrite))
  val invalidate = Input(Bool())
}

class ICache(implicit p: CoreParams) extends Module {
  val io = IO(new ICacheIO)

  private val lineBits = p.icacheLineBytes * 8
  private val validArray = RegInit(VecInit(Seq.fill(p.icacheSets)(VecInit(Seq.fill(p.icacheWays)(false.B)))))
  private val tagArray = Seq.fill(p.icacheWays)(SyncReadMem(p.icacheSets, UInt(p.icacheTagBits.W)))
  private val dataArray = Seq.fill(p.icacheWays)(SyncReadMem(p.icacheSets, UInt(lineBits.W)))

  val reqReg = RegInit(false.B)
  val pcReg = Reg(UInt(p.addrBits.W))
  val setIdxReg = Reg(UInt(p.icacheSetBits.W))
  val tagReg = Reg(UInt(p.icacheTagBits.W))
  val readTags = Wire(Vec(p.icacheWays, UInt(p.icacheTagBits.W)))
  val readLines = Wire(Vec(p.icacheWays, UInt(lineBits.W)))

  readTags := 0.U.asTypeOf(readTags)
  readLines := 0.U.asTypeOf(readLines)

  when(io.invalidate) {
    for (set <- 0 until p.icacheSets) {
      for (way <- 0 until p.icacheWays) {
        validArray(set)(way) := false.B
      }
    }
  }

  val reqSetIdx = io.req.bits(p.icacheLineBits + p.icacheSetBits - 1, p.icacheLineBits)
  val reqTag = io.req.bits(p.addrBits - 1, p.icacheLineBits + p.icacheSetBits)

  when(io.refill.valid) {
    for (way <- 0 until p.icacheWays) {
      when(io.refill.bits.way === way.U) {
        tagArray(way).write(io.refill.bits.setIdx, io.refill.bits.tag)
        dataArray(way).write(io.refill.bits.setIdx, io.refill.bits.line)
        validArray(io.refill.bits.setIdx)(way) := true.B
      }
    }
  }.otherwise {
    for (way <- 0 until p.icacheWays) {
      readTags(way) := tagArray(way).read(reqSetIdx, io.req.valid)
      readLines(way) := dataArray(way).read(reqSetIdx, io.req.valid)
    }
  }

  when(io.req.valid) {
    reqReg := true.B
    pcReg := io.req.bits
    setIdxReg := reqSetIdx
    tagReg := reqTag
  }.otherwise {
    reqReg := false.B
  }

  val wayHits = Wire(Vec(p.icacheWays, Bool()))
  for (way <- 0 until p.icacheWays) {
    wayHits(way) := validArray(setIdxReg)(way) && readTags(way) === tagReg
  }

  val hitMask = wayHits.asUInt
  val hitLine = Mux1H(wayHits, readLines)

  io.resp.valid := reqReg
  io.resp.bits.pc := pcReg
  io.resp.bits.hit := hitMask.orR
  io.resp.bits.waymask := hitMask
  io.resp.bits.line := hitLine
}
