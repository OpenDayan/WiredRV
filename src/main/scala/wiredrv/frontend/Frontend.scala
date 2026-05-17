package wiredrv.frontend

import chisel3._
import chisel3.util._
import wiredrv.CoreParams
import wiredrv.bus._
import wiredrv.bus.BusOp.ReadOnly
import xs.utils.sram.SRAMTemplate

class FetchPacket(implicit p: CoreParams) extends Bundle {
  val pc = UInt(p.addrBits.W)
  val inst = UInt(32.W)
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
  val bus = new BusBundle(32)
  val ifencei = Input(Bool())
  val perf = Output(new FrontendPerfCounters)
}

class FrontendPerfCounters extends Bundle {
  val cycles = UInt(64.W)
  val instFire = UInt(64.W)
  val backpressure = UInt(64.W)
  val icacheHit = UInt(64.W)
  val icacheRefill = UInt(64.W)
  val icacheRefillDone = UInt(64.W)
  val icacheRefillCycles = UInt(64.W)
  val icacheReplay = UInt(64.W)
  val icacheMissSetHasValid = UInt(64.W)
  val icacheMissSetFull = UInt(64.W)
  val jalPred = UInt(64.W)
  val branchPred = UInt(64.W)
  val ifencei = UInt(64.W)
  val backendRedirect = UInt(64.W)
}

class Frontend(implicit p: CoreParams) extends Module {
  require(p.innerBusWidth == 32, "current frontend only supports 32-bit internal bus")

  val io = IO(new FrontendIO)
  val perf = RegInit(0.U.asTypeOf(new FrontendPerfCounters))
  io.perf := perf

  val frontMissReplay = Wire(Valid(UInt(p.addrBits.W)))
  frontMissReplay.valid := false.B
  frontMissReplay.bits := 0.U
  val frontPredRedirect = Wire(Valid(UInt(p.addrBits.W)))
  frontPredRedirect.valid := false.B
  frontPredRedirect.bits := 0.U

  val flushRedir = io.redirects ++ Seq(frontMissReplay)
  val allRedir = flushRedir ++ Seq(frontPredRedirect)
  val anyFlushRedir = VecInit(flushRedir.map(_.valid)).asUInt.orR
  val backRedir = VecInit(io.redirects.map(_.valid)).asUInt.orR

  val frontStall = WireDefault(false.B)

  val npc = Module(new NPC).io
  npc.redirects := allRedir
  npc.pc.ready := !frontStall
  val pc = npc.pc.bits

  val f1Valid = RegInit(0.B)
  val f1pc = RegEnable(pc, !frontStall)

  class ICTag extends Bundle {
    val tag = UInt(p.icacheTagBits.W)
  }

  require(p.icacheWays == 2)
  private val beatsPerLine = p.icacheLineBytes / 4
  val icValid = RegInit(0.U.asTypeOf(Vec(p.icacheWays, Vec(p.icacheSets, Bool()))))
  val icTags = Seq.fill(p.icacheWays)(Module(new SRAMTemplate(new ICTag, p.icacheSets, singlePort = true)))
  val icDatas = Seq.fill(p.icacheWays)(Module(new SRAMTemplate(UInt(p.xlen.W), p.icacheSets * beatsPerLine, singlePort = true)))
  val f1Hit = WireDefault(false.B)
  val f1Inst = WireDefault(0.U(32.W))

  val replWay = RegInit(VecInit(Seq.fill(p.icacheSets)(false.B)))
  private val bhtEntries = 1024
  private val bhtIdxBits = log2Ceil(bhtEntries)
  val bhtValid = RegInit(VecInit(Seq.fill(bhtEntries)(false.B)))
  val bhtCtr = RegInit(VecInit(Seq.fill(bhtEntries)(1.U(2.W))))
  def bhtIndex(pc: UInt): UInt = pc(bhtIdxBits + 1, 2)

  val f2Valid = RegInit(false.B)
  val f2Refill = RegInit(false.B)
  val f2Inst = RegInit(0.U.asTypeOf(new FetchPacket))
  val f2SkidV = RegInit(false.B)
  val f2Skid = RegInit(0.U.asTypeOf(new FetchPacket))

  val rSelWay = RegInit(0.U(1.W))
  val rAValid = RegInit(false.B)
  val rRValid = RegInit(false.B) // 脉冲重定向信号
  val rDCnt = RegInit(0.U(log2Ceil(beatsPerLine).W))

  when(io.ifencei) {
    icValid := 0.U.asTypeOf(icValid)
    replWay := VecInit(Seq.fill(p.icacheSets)(false.B))
  }

  val readTagSet = p.icacheGetSet(pc)
  val writeTagSet = p.icacheGetSet(f2Inst.pc)
  val readDataIdx = p.icacheGetSet(pc) ## p.icacheGetWord(pc)
  val writeDataIdx = p.icacheGetSet(f2Inst.pc) ## rDCnt
  val rDLast = rDCnt === (beatsPerLine - 1).U
  val f2RefillEnd = rDLast && io.bus.d.fire

  icTags.zipWithIndex.foreach { case (icTag, idx) =>
    icTag.io.r.req.valid := !f2Refill && !frontStall
    icTag.io.r.req.bits.apply(readTagSet)
    icTag.io.w.req.valid := f2RefillEnd && (rSelWay === idx.U)
    icTag.io.w.req.bits.apply(WireInit({
      val w = Wire(new ICTag)
      w.tag := p.icacheGetTag(f2Inst.pc)
      w
    }), writeTagSet, 1.U)
  }

  icDatas.zipWithIndex.foreach { case (icData, idx) =>
    icData.io.r.req.valid := !f2Refill && !frontStall
    icData.io.r.req.bits.apply(readDataIdx)
    icData.io.w.req.valid := io.bus.d.fire && (rSelWay === idx.U)
    icData.io.w.req.bits.apply(io.bus.d.bits.data, writeDataIdx, 1.U)
  }

  when(!f2Refill) {
    val hits = icValid.zip(icTags).map { case (v, t) =>
      val set = p.icacheGetSet(pc)
      RegEnable(!(io.ifencei) && v(set), !frontStall) && t.io.r.resp.data(0).tag === p.icacheGetTag(f1pc)
    }
    val insts = VecInit(icDatas.map(_.io.r.resp.data(0)))
    f1Hit := VecInit(hits).asUInt.orR
    f1Inst := Mux(hits(0), insts(0), insts(1))
    f1Valid := true.B
  }.otherwise {
    f1Valid := false.B
  }

  val f2Hold = f2Valid && !io.inst.ready && !backRedir
  val f2RefillStart = !frontStall && f1Valid && !anyFlushRedir && !f1Hit
  val f2HitAccept = !frontStall && f1Valid && !anyFlushRedir && f1Hit
  val f1IsJal = f1Inst(6, 0) === "b1101111".U
  val f1JalImm = Cat(Fill(p.addrBits - 21, f1Inst(31)), f1Inst(31), f1Inst(19, 12), f1Inst(20), f1Inst(30, 21), 0.U(1.W))
  val f1JalTarget = (f1pc + f1JalImm)(p.addrBits - 1, 0)
  val f1IsBranch = f1Inst(6, 0) === "b1100011".U
  val f1BranchImm = Cat(Fill(p.addrBits - 13, f1Inst(31)), f1Inst(31), f1Inst(7), f1Inst(30, 25), f1Inst(11, 8), 0.U(1.W))
  val f1BranchTarget = (f1pc + f1BranchImm)(p.addrBits - 1, 0)
  val f1BhtIdx = bhtIndex(f1pc)
  val f1BranchTakenPred = Mux(bhtValid(f1BhtIdx), bhtCtr(f1BhtIdx)(1), f1Inst(31))
  val f1PredictJal = f2HitAccept && f1IsJal
  val f1PredictBranch = f2HitAccept && f1IsBranch && f1BranchTakenPred
  val f1PredictJump = f1PredictJal || f1PredictBranch
  val f1PredictTarget = Mux(f1PredictJal, f1JalTarget, f1BranchTarget)
  val missSetValidVec = VecInit(icValid.map(_(p.icacheGetSet(f1pc))))
  val missSetHasValid = missSetValidVec.asUInt.orR
  val missSetFull = missSetValidVec.asUInt.andR
  frontStall := f2Hold || f2Refill
  frontPredRedirect.valid := f1PredictJump
  frontPredRedirect.bits := f1PredictTarget

  when(!frontStall) {
    f2Valid := f1Valid && !anyFlushRedir && f1Hit
    when(f2RefillStart) {
      f2Refill := true.B
      val missSet = p.icacheGetSet(f1pc)
      rSelWay := replWay(missSet).asUInt
      replWay(missSet) := !replWay(missSet)
      rAValid := true.B
      rRValid := true.B
      rDCnt := 0.U
    }
    f2Inst.pc := f1pc
    f2Inst.inst := f1Inst
    f2Inst.jump := f1PredictJump
    f2Inst.target := f1PredictTarget
    when(f2Valid) {
      f2SkidV := !io.inst.ready && !backRedir
      f2Skid := f2Inst
    }
    io.inst.valid := f2Valid
    io.inst.bits := f2Inst
  }.otherwise {
    f2SkidV := false.B
    when(backRedir) {
      f2Valid := false.B
    }
    io.inst.valid := f2Valid
    io.inst.bits := f2Inst
    f2Refill := f2Refill && !f2RefillEnd
  }

  when(f2Refill) {
    rAValid := rAValid && !io.bus.a.ready
    rRValid := false.B
    when(io.bus.d.fire) {
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
  io.bus.a.bits.id := 0.U
  io.bus.a.bits.mask := "hf".U((32 / 8).W)
  io.bus.a.bits.size := 6.U
  io.bus.a.bits.op := ReadOnly
  io.bus.d.ready := f2Refill

  perf.cycles := perf.cycles + 1.U
  when(io.inst.fire) {
    perf.instFire := perf.instFire + 1.U
  }
  when(f2Hold) {
    perf.backpressure := perf.backpressure + 1.U
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
  }
  when(frontMissReplay.valid) {
    perf.icacheReplay := perf.icacheReplay + 1.U
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
  when(backRedir) {
    perf.backendRedirect := perf.backendRedirect + 1.U
  }
}
