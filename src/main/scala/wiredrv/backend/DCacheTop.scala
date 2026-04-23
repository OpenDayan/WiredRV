package wiredrv.backend

import chisel3._
import chisel3.util._
import wiredrv.common._

class DCacheTopIO(implicit p: CoreParams) extends Bundle {
  val dReq = Input(Valid(new LSUReq))
  val dReady = Output(Bool())
  val eResp = Valid(new DCachePipeResp)
  val wReq = Flipped(Decoupled(new LSUReq))
  val wResp = Valid(new LSUResp)
  val busy = Output(Bool())
  val bus = new TLBundleUH
}

class DCacheTop(implicit p: CoreParams) extends Module {
  val io = IO(new DCacheTopIO)

  private val beatBytes = p.xlen / 8
  private val beatsPerLine = p.dcacheLineBytes / beatBytes

  val dcache = Module(new DCache)
  val drefill = Module(new DRefill)

  val sqValid = RegInit(VecInit(Seq.fill(p.storeQueueDepth)(false.B)))
  val sqData = Reg(Vec(p.storeQueueDepth, new StoreQueueEntry))
  val drainIdx = Reg(UInt(log2Ceil(p.storeQueueDepth).W))
  val pipeReq = Reg(new LSUReq)
  val pipePending = RegInit(false.B)
  val pipeMisaligned = RegInit(false.B)
  val refillReqReg = Reg(new LSUReq)
  val refillPending = RegInit(false.B)

  val sIdle :: sDrainReq :: sDrainResp :: Nil = Enum(3)
  val drainState = RegInit(sIdle)

  def accessBytes(funct3: UInt): UInt = {
    MuxLookup(funct3, 8.U)(Seq(
      "b000".U -> 1.U,
      "b001".U -> 2.U,
      "b010".U -> 4.U,
      "b011".U -> 8.U,
      "b100".U -> 1.U,
      "b101".U -> 2.U,
      "b110".U -> 4.U
    ))
  }

  def byteMask(addr: UInt, sizeBytes: UInt): UInt = {
    val base = Wire(UInt((p.xlen / 8).W))
    base := MuxLookup(sizeBytes, "hFF".U((p.xlen / 8).W))(Seq(
      1.U -> "h01".U((p.xlen / 8).W),
      2.U -> "h03".U((p.xlen / 8).W),
      4.U -> "h0F".U((p.xlen / 8).W),
      8.U -> "hFF".U((p.xlen / 8).W)
    ))
    (base << addr(2, 0))(p.xlen / 8 - 1, 0)
  }

  def misaligned(addr: UInt, sizeBytes: UInt): Bool = {
    MuxLookup(sizeBytes, false.B)(Seq(
      1.U -> false.B,
      2.U -> (addr(0) =/= 0.U),
      4.U -> (addr(1, 0) =/= 0.U),
      8.U -> (addr(2, 0) =/= 0.U)
    ))
  }

  def loadResult(data: UInt, addr: UInt, funct3: UInt): UInt = {
    val shifted = data >> (addr(2, 0) << 3)
    val b = shifted(7, 0)
    val h = shifted(15, 0)
    val w = shifted(31, 0)
    MuxLookup(funct3, shifted)(Seq(
      "b000".U -> Cat(Fill(p.xlen - 8, b(7)), b),
      "b001".U -> Cat(Fill(p.xlen - 16, h(15)), h),
      "b010".U -> Cat(Fill(p.xlen - 32, w(31)), w),
      "b011".U -> shifted,
      "b100".U -> Cat(0.U((p.xlen - 8).W), b),
      "b101".U -> Cat(0.U((p.xlen - 16).W), h),
      "b110".U -> Cat(0.U((p.xlen - 32).W), w)
    ))
  }

  def forwardFromQueue(addr: UInt, funct3: UInt): (UInt, UInt) = {
    val resultBytes = Wire(Vec(p.xlen / 8, UInt(8.W)))
    val coveredBits = Wire(Vec(p.xlen / 8, Bool()))
    resultBytes := VecInit(Seq.fill(p.xlen / 8)(0.U(8.W)))
    coveredBits := VecInit(Seq.fill(p.xlen / 8)(false.B))
    val reqBytes = accessBytes(funct3)
    val reqMask = byteMask(addr, reqBytes)
    for (idx <- 0 until p.storeQueueDepth) {
      val hit = sqValid(idx) && (sqData(idx).addr(p.addrBits - 1, 3) === addr(p.addrBits - 1, 3))
      when(hit) {
        for (b <- 0 until (p.xlen / 8)) {
          when(reqMask(b) && sqData(idx).mask(b)) {
            resultBytes(b) := sqData(idx).data(8 * b + 7, 8 * b)
            coveredBits(b) := true.B
          }
        }
      }
    }
    (resultBytes.asUInt, coveredBits.asUInt)
  }

  def combineLoad(baseData: UInt, forwarded: UInt, covered: UInt): UInt = {
    val mergedBytes = Wire(Vec(p.xlen / 8, UInt(8.W)))
    mergedBytes := baseData.asTypeOf(mergedBytes)
    for (b <- 0 until (p.xlen / 8)) {
      when(covered(b)) {
        mergedBytes(b) := forwarded(8 * b + 7, 8 * b)
      }
    }
    mergedBytes.asUInt
  }

  def wordFromLine(line: UInt, addr: UInt): UInt = {
    val words = line.asTypeOf(Vec(beatsPerLine, UInt(p.xlen.W)))
    words(addr(p.dcacheLineBits - 1, log2Ceil(beatBytes)))
  }

  val sqHasFree = !sqValid.asUInt.andR
  val unwrittenOH = PriorityEncoderOH(VecInit(sqValid.zip(sqData).map { case (v, e) => v && !e.cacheWritten }).asUInt)
  val hasUnwritten = unwrittenOH.orR
  val unwrittenIdx = OHToUInt(unwrittenOH)
  val drainPending = sqValid.asUInt.orR
  val currentDrain = sqData(drainIdx)

  val cacheWriteValid = hasUnwritten && !drefill.io.refill.valid && !drefill.io.invalidateSet.valid
  dcache.io.write.valid := cacheWriteValid
  dcache.io.write.bits.setIdx := sqData(unwrittenIdx).addr(p.dcacheLineBits + p.dcacheSetBits - 1, p.dcacheLineBits)
  dcache.io.write.bits.way := sqData(unwrittenIdx).way
  dcache.io.write.bits.wordOffset := sqData(unwrittenIdx).addr(p.dcacheLineBits - 1, log2Ceil(beatBytes))
  dcache.io.write.bits.data := sqData(unwrittenIdx).data
  dcache.io.write.bits.mask := sqData(unwrittenIdx).mask

  val cachePortBusy = cacheWriteValid || drefill.io.refill.valid || drefill.io.invalidateSet.valid
  val dReqSize = accessBytes(io.dReq.bits.funct3)
  val dReqMisaligned = (io.dReq.bits.isLoad || io.dReq.bits.isStore) && misaligned(io.dReq.bits.addr, dReqSize)
  io.dReady := !cachePortBusy

  val dLookupFire = io.dReq.valid && io.dReady && !dReqMisaligned
  dcache.io.req.valid := dLookupFire
  dcache.io.req.bits := io.dReq.bits.addr
  dcache.io.refill := drefill.io.refill
  dcache.io.invalidateSet := drefill.io.invalidateSet

  when(cacheWriteValid) {
    sqData(unwrittenIdx).cacheWritten := true.B
  }

  when(io.dReq.valid && io.dReady) {
    pipeReq := io.dReq.bits
    pipePending := true.B
    pipeMisaligned := dReqMisaligned
  }.elsewhen(io.eResp.valid) {
    pipePending := false.B
  }

  val (fwdData, fwdCovered) = forwardFromQueue(pipeReq.addr, pipeReq.funct3)
  io.eResp.valid := pipePending
  io.eResp.bits := 0.U.asTypeOf(new DCachePipeResp)
  io.eResp.bits.addr := pipeReq.addr
  io.eResp.bits.hit := !pipeMisaligned && dcache.io.resp.bits.hit
  io.eResp.bits.miss := !pipeMisaligned && !dcache.io.resp.bits.hit
  io.eResp.bits.waymask := Mux(pipeMisaligned, 0.U, dcache.io.resp.bits.waymask)
  io.eResp.bits.data := loadResult(
    combineLoad(wordFromLine(dcache.io.resp.bits.line, pipeReq.addr), fwdData, fwdCovered),
    pipeReq.addr,
    pipeReq.funct3
  )
  io.eResp.bits.exception := pipeMisaligned
  io.eResp.bits.exceptionCode := Mux(pipeReq.isLoad, 4.U, 6.U)

  io.wReq.ready := false.B
  io.wResp.valid := false.B
  io.wResp.bits := 0.U.asTypeOf(new LSUResp)

  drefill.io.req.valid := false.B
  drefill.io.req.bits := 0.U.asTypeOf(new DRefillReq)

  when(io.wReq.valid) {
    when(io.wReq.bits.isStore && !io.wReq.bits.miss && sqHasFree) {
      val freeOH = PriorityEncoderOH(~sqValid.asUInt)
      for (i <- 0 until p.storeQueueDepth) {
        when(freeOH(i)) {
          sqValid(i) := true.B
          sqData(i).addr := Cat(io.wReq.bits.addr(p.addrBits - 1, 3), 0.U(3.W))
          sqData(i).way := OHToUInt(io.wReq.bits.waymask)
          sqData(i).data := io.wReq.bits.data << (io.wReq.bits.addr(2, 0) << 3)
          sqData(i).mask := byteMask(io.wReq.bits.addr, accessBytes(io.wReq.bits.funct3))
          sqData(i).uncached := false.B
          sqData(i).cacheWritten := false.B
        }
      }
      io.wReq.ready := true.B
      io.wResp.valid := true.B
    }.elsewhen((io.wReq.bits.isLoad && io.wReq.bits.miss) || io.wReq.bits.isCacheMaintain) {
      drefill.io.req.valid := true.B
      drefill.io.req.bits.addr := io.wReq.bits.addr
      drefill.io.req.bits.setIdx := io.wReq.bits.addr(p.dcacheLineBits + p.dcacheSetBits - 1, p.dcacheLineBits)
      drefill.io.req.bits.tag := io.wReq.bits.addr(p.addrBits - 1, p.dcacheLineBits + p.dcacheSetBits)
      drefill.io.req.bits.replaceWay := 0.U
      drefill.io.req.bits.op := Mux(io.wReq.bits.isCacheMaintain, io.wReq.bits.cacheMaintainOp, DRefillOp.refill)
      io.wReq.ready := drefill.io.req.ready
      refillReqReg := io.wReq.bits
      refillPending := io.wReq.fire
    }
  }

  when(drefill.io.resp.valid && refillPending) {
    io.wResp.valid := true.B
    io.wResp.bits.exception := drefill.io.resp.bits.exception
    io.wResp.bits.exceptionCode := drefill.io.resp.bits.exceptionCode
    io.wResp.bits.data := loadResult(drefill.io.resp.bits.line, refillReqReg.addr, refillReqReg.funct3)
    refillPending := false.B
  }

  when(drainState === sIdle && drainPending && !drefill.io.busy) {
    drainIdx := PriorityEncoder(sqValid.asUInt)
    drainState := sDrainReq
  }

  io.bus.a.valid := false.B
  io.bus.a.bits := 0.U.asTypeOf(io.bus.a.bits)
  io.bus.d.ready := false.B
  drefill.io.bus.a.ready := false.B
  drefill.io.bus.d.valid := false.B
  drefill.io.bus.d.bits := io.bus.d.bits

  when(drefill.io.busy) {
    io.bus.a.valid := drefill.io.bus.a.valid
    io.bus.a.bits := drefill.io.bus.a.bits
    io.bus.d.ready := drefill.io.bus.d.ready
    drefill.io.bus.a.ready := io.bus.a.ready
    drefill.io.bus.d.valid := io.bus.d.valid
  }.elsewhen(drainState === sDrainReq) {
    io.bus.a.valid := true.B
    io.bus.a.bits.opcode := Mux(currentDrain.mask.andR, TLBundleUH.PutFullData, TLBundleUH.PutPartialData)
    io.bus.a.bits.param := 0.U
    io.bus.a.bits.size := log2Ceil(beatBytes).U
    io.bus.a.bits.source := 0.U
    io.bus.a.bits.address := currentDrain.addr
    io.bus.a.bits.mask := currentDrain.mask
    io.bus.a.bits.data := currentDrain.data
    io.bus.a.bits.corrupt := false.B
    io.bus.d.ready := drainState === sDrainResp
  }

  when(drainState === sDrainReq && io.bus.a.fire) {
    drainState := sDrainResp
  }

  when(drainState === sDrainResp && io.bus.d.fire) {
    sqValid(drainIdx) := false.B
    drainState := sIdle
  }

  io.busy := drainPending || drefill.io.busy || refillPending || cacheWriteValid || drainState =/= sIdle
}
