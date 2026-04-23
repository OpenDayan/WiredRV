package wiredrv.backend

import chisel3._
import chisel3.util._
import wiredrv.common._

class DCacheIO(implicit p: CoreParams) extends Bundle {
  val req = Flipped(Valid(UInt(p.addrBits.W)))
  val resp = Valid(new DCacheResp)
  val write = Flipped(Valid(new DCacheWriteReq))
  val refill = Flipped(Valid(new DCacheRefillWrite))
  val invalidateSet = Flipped(Valid(UInt(p.dcacheSetBits.W)))
}

class DCache(implicit p: CoreParams) extends Module {
  val io = IO(new DCacheIO)

  private val lineBytes = p.dcacheLineBytes
  private val lineBits = lineBytes * 8
  private val beatBytes = p.xlen / 8
  private val beatWords = p.dcacheLineBytes / beatBytes
  private val validArray = RegInit(VecInit(Seq.fill(p.dcacheSets)(VecInit(Seq.fill(p.dcacheWays)(false.B)))))
  private val tagArray = Seq.fill(p.dcacheWays)(SyncReadMem(p.dcacheSets, UInt(p.dcacheTagBits.W)))
  private val dataArray = Seq.fill(p.dcacheWays)(SyncReadMem(p.dcacheSets, Vec(lineBytes, UInt(8.W))))

  val reqReg = RegInit(false.B)
  val addrReg = Reg(UInt(p.addrBits.W))
  val setIdxReg = Reg(UInt(p.dcacheSetBits.W))
  val tagReg = Reg(UInt(p.dcacheTagBits.W))
  val readTags = Wire(Vec(p.dcacheWays, UInt(p.dcacheTagBits.W)))
  val readLines = Wire(Vec(p.dcacheWays, UInt(lineBits.W)))

  readTags := 0.U.asTypeOf(readTags)
  readLines := 0.U.asTypeOf(readLines)

  val reqSetIdx = io.req.bits(p.dcacheLineBits + p.dcacheSetBits - 1, p.dcacheLineBits)
  val reqTag = io.req.bits(p.addrBits - 1, p.dcacheLineBits + p.dcacheSetBits)

  when(io.refill.valid) {
    for (way <- 0 until p.dcacheWays) {
      when(io.refill.bits.way === way.U) {
        tagArray(way).write(io.refill.bits.setIdx, io.refill.bits.tag)
        dataArray(way).write(io.refill.bits.setIdx, io.refill.bits.line.asTypeOf(Vec(lineBytes, UInt(8.W))))
        validArray(io.refill.bits.setIdx)(way) := true.B
      }
    }
  }.elsewhen(io.write.valid) {
    val writeMaskVec = Wire(Vec(lineBytes, Bool()))
    val writeDataVec = Wire(Vec(lineBytes, UInt(8.W)))
    writeMaskVec := VecInit(Seq.fill(lineBytes)(false.B))
    writeDataVec := VecInit(Seq.fill(lineBytes)(0.U(8.W)))
    for (b <- 0 until beatBytes) {
      for (i <- 0 until lineBytes) {
        when(io.write.bits.wordOffset === (i / beatBytes).U && (i % beatBytes).U === b.U) {
          writeMaskVec(i) := io.write.bits.mask(b)
          writeDataVec(i) := io.write.bits.data(8 * b + 7, 8 * b)
        }
      }
    }
    for (way <- 0 until p.dcacheWays) {
      when(io.write.bits.way === way.U) {
        dataArray(way).write(io.write.bits.setIdx, writeDataVec, writeMaskVec)
      }
    }
  }.elsewhen(io.invalidateSet.valid) {
    for (way <- 0 until p.dcacheWays) {
      validArray(io.invalidateSet.bits)(way) := false.B
    }
  }.otherwise {
    for (way <- 0 until p.dcacheWays) {
      readTags(way) := tagArray(way).read(reqSetIdx, io.req.valid)
      readLines(way) := dataArray(way).read(reqSetIdx, io.req.valid).asUInt
    }
  }

  when(io.req.valid) {
    reqReg := true.B
    addrReg := io.req.bits
    setIdxReg := reqSetIdx
    tagReg := reqTag
  }.otherwise {
    reqReg := false.B
  }

  val wayHits = Wire(Vec(p.dcacheWays, Bool()))
  for (way <- 0 until p.dcacheWays) {
    wayHits(way) := validArray(setIdxReg)(way) && readTags(way) === tagReg
  }

  val hitMask = wayHits.asUInt
  val hitLine = Mux1H(wayHits, readLines)

  io.resp.valid := reqReg
  io.resp.bits.addr := addrReg
  io.resp.bits.hit := hitMask.orR
  io.resp.bits.waymask := hitMask
  io.resp.bits.line := hitLine
}
