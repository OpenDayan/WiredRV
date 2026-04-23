package wiredrv.frontend

import chisel3._
import chisel3.util._
import wiredrv.common._

class ICacheIO(implicit p: CoreParams) extends Bundle {
  val req = Flipped(Valid(UInt(p.addrBits.W)))
  val resp = Valid(new ICacheResp)
  val tagWrite = Flipped(Valid(new ICacheTagWrite))
  val dataWrite = Flipped(Valid(new ICacheDataWrite))
  val invalidate = Input(Bool())
}

class ICache(implicit p: CoreParams) extends Module {
  val io = IO(new ICacheIO)

  private val beatBytes = p.xlen / 8
  private val beatsPerLine = p.icacheLineBytes / beatBytes
  private val validArray = RegInit(VecInit(Seq.fill(p.icacheSets)(VecInit(Seq.fill(p.icacheWays)(false.B)))))
  private val tagArray = Seq.fill(p.icacheWays)(SyncReadMem(p.icacheSets, UInt(p.icacheTagBits.W)))
  private val dataArray = Seq.fill(p.icacheWays, beatsPerLine)(SyncReadMem(p.icacheSets, UInt(p.xlen.W)))

  val reqReg = RegInit(false.B)
  val pcReg = Reg(UInt(p.addrBits.W))
  val setIdxReg = Reg(UInt(p.icacheSetBits.W))
  val tagReg = Reg(UInt(p.icacheTagBits.W))
  val beatIdxReg = Reg(UInt((p.icacheLineBits - log2Ceil(beatBytes)).W))
  val readTags = Wire(Vec(p.icacheWays, UInt(p.icacheTagBits.W)))
  val readBeats = Wire(Vec(p.icacheWays, Vec(beatsPerLine, UInt(p.xlen.W))))
  val wayData = Wire(Vec(p.icacheWays, UInt(p.xlen.W)))

  readTags := 0.U.asTypeOf(readTags)
  readBeats := 0.U.asTypeOf(readBeats)
  wayData := 0.U.asTypeOf(wayData)

  when(io.invalidate) {
    for (set <- 0 until p.icacheSets) {
      for (way <- 0 until p.icacheWays) {
        validArray(set)(way) := false.B
      }
    }
  }

  val reqSetIdx = io.req.bits(p.icacheLineBits + p.icacheSetBits - 1, p.icacheLineBits)
  val reqTag = io.req.bits(p.addrBits - 1, p.icacheLineBits + p.icacheSetBits)
  val reqBeatIdx = io.req.bits(p.icacheLineBits - 1, log2Ceil(beatBytes))
  val refillBusy = io.tagWrite.valid || io.dataWrite.valid

  when(refillBusy) {
    for (way <- 0 until p.icacheWays) {
      when(io.tagWrite.valid && io.tagWrite.bits.way === way.U) {
        tagArray(way).write(io.tagWrite.bits.setIdx, io.tagWrite.bits.tag)
        validArray(io.tagWrite.bits.setIdx)(way) := true.B
      }
      when(io.dataWrite.valid && io.dataWrite.bits.way === way.U) {
        for (beat <- 0 until beatsPerLine) {
          when(io.dataWrite.bits.beatIdx === beat.U) {
            dataArray(way)(beat).write(io.dataWrite.bits.setIdx, io.dataWrite.bits.data)
          }
        }
      }
    }
  }.otherwise {
    for (way <- 0 until p.icacheWays) {
      readTags(way) := tagArray(way).read(reqSetIdx, io.req.valid)
      for (beat <- 0 until beatsPerLine) {
        readBeats(way)(beat) := dataArray(way)(beat).read(reqSetIdx, io.req.valid)
      }
    }
  }

  when(io.req.valid) {
    reqReg := true.B
    pcReg := io.req.bits
    setIdxReg := reqSetIdx
    tagReg := reqTag
    beatIdxReg := reqBeatIdx
  }.otherwise {
    reqReg := false.B
  }

  val wayHits = Wire(Vec(p.icacheWays, Bool()))
  for (way <- 0 until p.icacheWays) {
    wayHits(way) := validArray(setIdxReg)(way) && readTags(way) === tagReg
    wayData(way) := readBeats(way)(beatIdxReg)
  }

  val hitMask = wayHits.asUInt
  val hitData = Mux1H(wayHits, wayData)

  io.resp.valid := reqReg
  io.resp.bits.pc := pcReg
  io.resp.bits.hit := hitMask.orR
  io.resp.bits.waymask := hitMask
  io.resp.bits.data := hitData
}
