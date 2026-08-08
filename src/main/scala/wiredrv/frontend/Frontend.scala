/*
 * Copyright (c) 2026 Institute of Computing Technology, Chinese Academy of Sciences
 * SPDX-License-Identifier: MulanPSL-2.0
 *
 * Dayan is licensed under the Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *
 *     http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details.
 */

package dayan.wiredrv.frontend

import chisel3._
import chisel3.util._
import dayan.wiredrv.CoreParams
import dayan.wiredrv.bus._
import dayan.wiredrv.bus.BusOp.ReadOnly
import xs.utils.mbist.MbistPipeline
import xs.utils.sram.SRAMTemplate

class FetchPacket(implicit p: CoreParams) extends Bundle {
  val pc = UInt(p.addrBits.W)
  val inst = UInt(32.W)
  val instWord = UInt(64.W)
  val jump = Bool()
  val target = UInt(p.addrBits.W)
}

class BranchUpdate(implicit p: CoreParams) extends Bundle {
  val pc = UInt(p.addrBits.W)
  val taken = Bool()
}

class FrontendIO(implicit p: CoreParams) extends Bundle {
  val inst = Decoupled(new FetchPacket)
  val redirects = Input(Vec(3, Valid(UInt(p.addrBits.W))))
  val branchUpdate = Input(Valid(new BranchUpdate))
  val bus = new BusBundle(p.outerBusWidth)
  val ifencei = Input(Bool())
  val perf = Output(new FrontendPerfCounters)
}

class FrontendPerfCounters extends Bundle {
  val cycles = UInt(64.W)
  val instFire = UInt(64.W)
  val backpressure = UInt(64.W)
  val frontStall = UInt(64.W)
  val fetchQueueFull = UInt(64.W)
  val icacheHit = UInt(64.W)
  val icacheRefill = UInt(64.W)
  val icacheRefillDone = UInt(64.W)
  val icacheRefillCycles = UInt(64.W)
  val icacheRefillReqStall = UInt(64.W)
  val icacheRefillRespWait = UInt(64.W)
  val icacheReplay = UInt(64.W)
  val respQueueFlush = UInt(64.W)
  val icacheMissSetHasValid = UInt(64.W)
  val icacheMissSetFull = UInt(64.W)
  val jalPred = UInt(64.W)
  val branchPred = UInt(64.W)
  val ifencei = UInt(64.W)
  val backendRedirect = UInt(64.W)
}

class Frontend(implicit p: CoreParams) extends Module {
  require(p.outerBusWidth == p.xlen, "current frontend refill path expects one xlen-wide bus beat")
  require(p.outerBusWidth >= 32 && isPow2(p.outerBusWidth / 8), "frontend bus beat bytes must be a power of two")

  val io = IO(new FrontendIO)
  val perf = RegInit(0.U.asTypeOf(new FrontendPerfCounters))
  io.perf := perf

  val frontMissReplay = Wire(Valid(UInt(p.addrBits.W)))
  frontMissReplay.valid := false.B
  frontMissReplay.bits := 0.U
  val frontPredRedirect = Wire(Valid(UInt(p.addrBits.W)))
  frontPredRedirect.valid := false.B
  frontPredRedirect.bits := 0.U

  val wRedirect = io.redirects(0)
  val eRedirect = io.redirects(1)
  val dEarlyRedirect = io.redirects(2)
  val backendRedirects = Seq(wRedirect, eRedirect, dEarlyRedirect)
  val flushRedirects = backendRedirects :+ frontMissReplay
  val allRedirects = flushRedirects :+ frontPredRedirect
  val anyFlushRedirect = VecInit(flushRedirects.map(_.valid)).asUInt.orR
  val backendRedirectValid = VecInit(backendRedirects.map(_.valid)).asUInt.orR

  val frontStall = WireDefault(false.B)
  val f0ReadFire = Wire(Bool())
  val respQueueFlush = Wire(Bool())

  val npc = Module(new NPC).io
  // Redirect priority is W, E, D, icache replay, then frontend prediction.
  npc.redirects := VecInit(allRedirects)
  val npcReady = Wire(Bool())
  npc.pc.ready := npcReady
  val pc = npc.pc.bits

  val f1RespValid = RegNext(f0ReadFire, false.B)
  val f1pc = RegEnable(pc, f0ReadFire)
  val f1Set = RegEnable(p.icacheGetSet(pc), f0ReadFire)
  val f1Tag = RegEnable(p.icacheGetTag(pc), f0ReadFire)

  class ICTag extends Bundle {
    val tag = UInt(p.icacheTagBits.W)
  }

  require(p.icacheWays == 2)
  private val fetchBeatBytes = p.outerBusWidth / 8
  private val fetchBeatBits = log2Ceil(fetchBeatBytes)
  require(p.icacheLineBytes % fetchBeatBytes == 0, "icache line size must be a multiple of fetch beat size")
  private val beatsPerLine = p.icacheLineBytes / fetchBeatBytes
  private def icacheGetBeat(addr: UInt): UInt =
    addr(p.icacheLineBits - 1, fetchBeatBits)
  val icValid = RegInit(0.U.asTypeOf(Vec(p.icacheWays, Vec(p.icacheSets, Bool()))))
  val icTags = Seq.fill(p.icacheWays)(
    Module(new SRAMTemplate(new ICTag, p.icacheSets, way = 1, singlePort = true, hasMbist = true))
  )
  val icDatas = Seq.fill(p.icacheWays)(
    Module(new SRAMTemplate(Vec(beatsPerLine, UInt(p.xlen.W)), p.icacheSets, way = 1, singlePort = true, hasMbist = true))
  )
  private val icMbistPipeline = MbistPipeline.PlaceMbistPipeline(1, "MbistPipelineWiredRVFrontend", place = true)

  class FetchResp extends Bundle {
    val pc = UInt(p.addrBits.W)
    val set = UInt(p.icacheSetBits.W)
    val tag = UInt(p.icacheTagBits.W)
    val hit = Bool()
    val inst = UInt(32.W)
  }
  private val respQueueDepth = 8
  val respQ = withReset(reset.asBool || respQueueFlush) {
    Module(new Queue(new FetchResp, respQueueDepth))
  }
  val respInFlight = RegInit(0.U(log2Ceil(respQueueDepth + 1).W))

  val replWay = RegInit(VecInit(Seq.fill(p.icacheSets)(false.B)))
  private val bhtEntries = 1024
  private val bhtIdxBits = log2Ceil(bhtEntries)
  val bhtValid = RegInit(VecInit(Seq.fill(bhtEntries)(false.B)))
  val bhtCtr = RegInit(VecInit(Seq.fill(bhtEntries)(1.U(2.W))))
  def bhtIndex(pc: UInt): UInt = pc(bhtIdxBits + 1, 2)

  val f2Valid = RegInit(false.B)
  val f2Refill = RegInit(false.B)
  val f2Inst = RegInit(0.U.asTypeOf(new FetchPacket))
  val tensor64Pending = RegInit(false.B)
  val tensor64Pc = RegInit(0.U(p.addrBits.W))
  val tensor64Low = RegInit(0.U(32.W))

  val rSelWay = RegInit(0.U(1.W))
  val rAValid = RegInit(false.B)
  val rRValid = RegInit(false.B) // 脉冲重定向信号
  val rDCnt = RegInit(0.U(log2Ceil(beatsPerLine).W))
  val refillLine = Reg(Vec(beatsPerLine, UInt(p.xlen.W)))

  when(io.ifencei) {
    icValid := 0.U.asTypeOf(icValid)
    replWay := VecInit(Seq.fill(p.icacheSets)(false.B))
  }

  val readTagSet = p.icacheGetSet(pc)
  val writeTagSet = p.icacheGetSet(f2Inst.pc)
  val readDataIdx = p.icacheGetSet(pc)
  val writeDataIdx = p.icacheGetSet(f2Inst.pc)
  val rDLast = rDCnt === (beatsPerLine - 1).U
  val f2RefillEnd = Wire(Bool())

  icTags.zipWithIndex.foreach { case (icTag, idx) =>
    icTag.io.r.req.valid := f0ReadFire
    icTag.io.r.req.bits.apply(readTagSet)
    icTag.io.w.req.valid := f2RefillEnd && (rSelWay === idx.U)
    icTag.io.w.req.bits.apply(WireInit({
      val w = Wire(new ICTag)
      w.tag := p.icacheGetTag(f2Inst.pc)
      w
    }), writeTagSet, 1.U)
  }

  val refillLineWrite = Wire(Vec(beatsPerLine, UInt(p.xlen.W)))
  refillLineWrite := refillLine
  when(io.bus.d.fire) {
    refillLineWrite(rDCnt) := io.bus.d.bits.data
  }

  icDatas.zipWithIndex.foreach { case (icData, idx) =>
    icData.io.r.req.valid := f0ReadFire
    icData.io.r.req.bits.apply(readDataIdx)
    icData.io.w.req.valid := f2RefillEnd && (rSelWay === idx.U)
    icData.io.w.req.bits.apply(refillLineWrite, writeDataIdx, 1.U)
  }

  val icSramReadReady = VecInit((icTags.map(_.io.r.req.ready) ++ icDatas.map(_.io.r.req.ready))).asUInt.andR
  val icTagRefillWriteReady = VecInit(icTags.map(_.io.w.req.ready))(rSelWay)
  val icDataRefillWriteReady = VecInit(icDatas.map(_.io.w.req.ready))(rSelWay)
  val icRefillWriteReady = icTagRefillWriteReady && icDataRefillWriteReady

  val rawHits = icValid.zip(icTags).map { case (v, t) =>
    RegEnable(!(io.ifencei) && v(p.icacheGetSet(pc)), f0ReadFire) &&
      t.io.r.resp.data(0).tag === f1Tag
  }
  val lines = VecInit(icDatas.map(_.io.r.resp.data(0)))
  val rawHit = VecInit(rawHits).asUInt.orR
  val rawLine = Mux(rawHits(0), lines(0), lines(1))
  val rawBeat = rawLine(icacheGetBeat(f1pc))
  val rawInst = Mux(f1pc(2), rawBeat(63, 32), rawBeat(31, 0))
  val respEnqValid = f1RespValid && !respQueueFlush
  respQ.io.enq.valid := respEnqValid
  respQ.io.enq.bits.pc := f1pc
  respQ.io.enq.bits.set := f1Set
  respQ.io.enq.bits.tag := f1Tag
  respQ.io.enq.bits.hit := rawHit
  respQ.io.enq.bits.inst := rawInst
  assert(!respEnqValid || respQ.io.enq.ready, "frontend fetch response queue overflow")

  // F2 holds the last accepted packet under backend backpressure.
  val f2Hold = f2Valid && !io.inst.ready && !backendRedirectValid
  val f1Valid = respQ.io.deq.valid
  val f1Hit = respQ.io.deq.bits.hit
  val f1Inst = respQ.io.deq.bits.inst
  val f1CurPc = respQ.io.deq.bits.pc
  val f1CurSet = respQ.io.deq.bits.set
  val f1CurTag = respQ.io.deq.bits.tag
  val f2RefillStart = !frontStall && f1Valid && !anyFlushRedirect && !f1Hit
  val f2HitAccept = !frontStall && f1Valid && !anyFlushRedirect && f1Hit
  respQ.io.deq.ready := f2HitAccept || f2RefillStart

  // Tensor64 packets consume two adjacent 32-bit fetch responses.
  val f1IsTensor64Low = f1Inst(6, 0) === "b0111111".U && !tensor64Pending
  val f1Tensor64Complete = f2HitAccept && tensor64Pending
  val f1EmitInst = f2HitAccept && !f1IsTensor64Low

  // Frontend prediction is lowest-priority redirect source.
  val f1IsJal = f1Inst(6, 0) === "b1101111".U
  val f1JalImm = Cat(Fill(p.addrBits - 21, f1Inst(31)), f1Inst(31), f1Inst(19, 12), f1Inst(20), f1Inst(30, 21), 0.U(1.W))
  val f1JalTarget = (f1CurPc + f1JalImm)(p.addrBits - 1, 0)
  val f1IsBranch = f1Inst(6, 0) === "b1100011".U
  val f1BranchImm = Cat(Fill(p.addrBits - 13, f1Inst(31)), f1Inst(31), f1Inst(7), f1Inst(30, 25), f1Inst(11, 8), 0.U(1.W))
  val f1BranchTarget = (f1CurPc + f1BranchImm)(p.addrBits - 1, 0)
  val f1BhtIdx = bhtIndex(f1CurPc)
  val f1BranchTakenPred = Mux(bhtValid(f1BhtIdx), bhtCtr(f1BhtIdx)(1), f1Inst(31))
  val f1CanPredict = f2HitAccept && !tensor64Pending && !f1IsTensor64Low
  val f1PredictJal = f1CanPredict && f1IsJal
  val f1PredictBranch = f1CanPredict && f1IsBranch && f1BranchTakenPred
  val f1PredictJump = f1PredictJal || f1PredictBranch
  val f1PredictTarget = Mux(f1PredictJal, f1JalTarget, f1BranchTarget)
  respQueueFlush := anyFlushRedirect || f1PredictJump || io.ifencei
  val missSetValidVec = VecInit(icValid.map(_(f1CurSet)))
  val missSetHasValid = missSetValidVec.asUInt.orR
  val missSetFull = missSetValidVec.asUInt.andR
  frontStall := f2Hold || f2Refill
  val respFree = respQueueDepth.U - respQ.io.count - respInFlight
  val canIssueFetch = respFree =/= 0.U

  // F0 issues at most one SRAM read when F2/refill and response queue space allow it.
  f0ReadFire := !frontStall && !f2Refill && !f2RefillStart && canIssueFetch && icSramReadReady && !io.ifencei
  npcReady := f0ReadFire
  frontPredRedirect.valid := f1PredictJump
  frontPredRedirect.bits := f1PredictTarget

  when(backendRedirectValid || f1PredictJump || io.ifencei) {
    tensor64Pending := false.B
  }.elsewhen(f2HitAccept && f1IsTensor64Low) {
    tensor64Pending := true.B
    tensor64Pc := f1CurPc
    tensor64Low := f1Inst
  }.elsewhen(f1Tensor64Complete) {
    tensor64Pending := false.B
  }

  when(!frontStall) {
    f2Valid := f1EmitInst
    when(f2RefillStart) {
      f2Refill := true.B
      val missSet = f1CurSet
      rSelWay := replWay(missSet).asUInt
      replWay(missSet) := !replWay(missSet)
      rAValid := true.B
      rRValid := true.B
      rDCnt := 0.U
    }
    f2Inst.pc := Mux(f1Tensor64Complete, tensor64Pc, f1CurPc)
    f2Inst.inst := Mux(f1Tensor64Complete, tensor64Low, f1Inst)
    f2Inst.instWord := Mux(f1Tensor64Complete, Cat(f1Inst, tensor64Low), Cat(0.U(32.W), f1Inst))
    f2Inst.jump := f1PredictJump
    f2Inst.target := Mux(
      f1Tensor64Complete,
      (tensor64Pc + 8.U)(p.addrBits - 1, 0),
      f1PredictTarget
    )
    io.inst.valid := f2Valid
    io.inst.bits := f2Inst
  }.otherwise {
    when(anyFlushRedirect) {
      f2Valid := false.B
    }
    io.inst.valid := f2Valid
    io.inst.bits := f2Inst
    f2Refill := f2Refill && !f2RefillEnd
  }

  when(respQueueFlush) {
    respInFlight := Mux(io.ifencei, 0.U, f0ReadFire.asUInt)
  }.otherwise {
    respInFlight := respInFlight + f0ReadFire.asUInt - respEnqValid.asUInt
  }

  when(f2Refill) {
    rAValid := rAValid && !io.bus.a.ready
    rRValid := false.B
    when(io.bus.d.fire) {
      refillLine(rDCnt) := io.bus.d.bits.data
      rDCnt := rDCnt + 1.U
    }
  }

  when(f2RefillEnd && !io.ifencei) {
    icValid(rSelWay)(p.icacheGetSet(f2Inst.pc)) := true.B
  }

  frontMissReplay.valid := rRValid
  frontMissReplay.bits := f2Inst.pc

  io.bus.a.valid := rAValid
  io.bus.a.bits.address := p.icacheGetTag(f2Inst.pc) ## p.icacheGetSet(f2Inst.pc) ## 0.U(p.icacheLineBits.W)
  io.bus.a.bits.data := 0.U
  io.bus.a.bits.compare := 0.U
  io.bus.a.bits.id := 0.U
  io.bus.a.bits.mask := Fill(fetchBeatBytes, 1.U(1.W))
  io.bus.a.bits.size := 6.U
  io.bus.a.bits.op := ReadOnly
  io.bus.d.ready := f2Refill && (!rDLast || icRefillWriteReady)
  f2RefillEnd := rDLast && io.bus.d.fire

  perf.cycles := perf.cycles + 1.U
  when(io.inst.fire) {
    perf.instFire := perf.instFire + 1.U
  }
  when(f2Hold) {
    perf.backpressure := perf.backpressure + 1.U
  }
  when(frontStall) {
    perf.frontStall := perf.frontStall + 1.U
  }
  when(!frontStall && !f2RefillStart && !canIssueFetch && !io.ifencei) {
    perf.fetchQueueFull := perf.fetchQueueFull + 1.U
  }
  when(f2HitAccept) {
    perf.icacheHit := perf.icacheHit + 1.U
  }
  when(f2RefillStart) {
    perf.icacheRefill := perf.icacheRefill + 1.U
    when(missSetHasValid) {
      perf.icacheMissSetHasValid := perf.icacheMissSetHasValid + 1.U
    }
    when(missSetFull) {
      perf.icacheMissSetFull := perf.icacheMissSetFull + 1.U
    }
  }
  when(f2RefillEnd) {
    perf.icacheRefillDone := perf.icacheRefillDone + 1.U
  }
  when(f2Refill) {
    perf.icacheRefillCycles := perf.icacheRefillCycles + 1.U
    when(rAValid && !io.bus.a.ready) {
      perf.icacheRefillReqStall := perf.icacheRefillReqStall + 1.U
    }
    when(!rAValid && !io.bus.d.fire) {
      perf.icacheRefillRespWait := perf.icacheRefillRespWait + 1.U
    }
  }
  when(frontMissReplay.valid) {
    perf.icacheReplay := perf.icacheReplay + 1.U
  }
  when(respQueueFlush) {
    perf.respQueueFlush := perf.respQueueFlush + 1.U
  }
  when(io.branchUpdate.valid) {
    val idx = bhtIndex(io.branchUpdate.bits.pc)
    val old = bhtCtr(idx)
    bhtValid(idx) := true.B
    bhtCtr(idx) := Mux(
      io.branchUpdate.bits.taken,
      Mux(old === 3.U, old, old + 1.U),
      Mux(old === 0.U, old, old - 1.U)
    )
  }
  when(f1PredictJal) {
    perf.jalPred := perf.jalPred + 1.U
  }
  when(f1PredictBranch) {
    perf.branchPred := perf.branchPred + 1.U
  }
  when(io.ifencei) {
    perf.ifencei := perf.ifencei + 1.U
  }
  when(backendRedirectValid) {
    perf.backendRedirect := perf.backendRedirect + 1.U
  }
}
