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

class FrontendIO(implicit p: CoreParams) extends Bundle {
  val inst = Decoupled(new FetchPacket)
  val redirects = Input(Vec(3, Valid(UInt(p.addrBits.W))))
  val bus = new BusBundle(32)
  val ifencei = Input(Bool())
}

class Frontend(implicit p: CoreParams) extends Module {
  require(p.innerBusWidth == 32, "current frontend only supports 32-bit internal bus")

  val io = IO(new FrontendIO)

  val frontMissReplay = Wire(Valid(UInt(p.addrBits.W)))
  frontMissReplay.valid := false.B
  frontMissReplay.bits := 0.U

  val allRedir = io.redirects ++ Seq(frontMissReplay)
  val anyRedir = VecInit(allRedir.map(_.valid)).asUInt.orR
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

  val rWay = RegInit(0.U(1.W))
  rWay := rWay + 1.U

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
  }

  val readTagSet = p.icacheGetSet(pc)
  val writeTagSet = p.icacheGetSet(f2Inst.pc)
  val readDataIdx = p.icacheGetSet(pc) ## p.icacheGetWord(pc)
  val writeDataIdx = p.icacheGetSet(f2Inst.pc) ## rDCnt

  icTags.zipWithIndex.foreach { case (icTag, idx) =>
    icTag.io.r.req.valid := !f2Refill && !frontStall
    icTag.io.r.req.bits.apply(readTagSet)
    icTag.io.w.req.valid := f2Refill && (rSelWay === idx.U)
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

  val rDLast = rDCnt === (beatsPerLine - 1).U

  val f2RefillEnd = rDLast && io.bus.d.valid
  frontStall := f2SkidV || f2Refill

  when(!frontStall) {
    f2Valid := f1Valid && !anyRedir && f1Hit
    when(f1Valid && !anyRedir && !f1Hit) {
      f2Refill := true.B
      rSelWay := rWay
      rAValid := true.B
      rRValid := true.B
      rDCnt := 0.U
    }
    f2Inst.pc := f1pc
    f2Inst.inst := f1Inst
    f2Inst.jump := false.B
    f2Inst.target := 0.U
    when(f2Valid) {
      f2SkidV := !io.inst.ready && !backRedir
      f2Skid := f2Inst
    }
    io.inst.valid := f2Valid
    io.inst.bits := f2Inst
  }.otherwise {
    f2SkidV := f2SkidV && !io.inst.ready && !backRedir
    io.inst.valid := f2SkidV
    io.inst.bits := f2Skid
    f2Refill := f2Refill && !f2RefillEnd
  }

  when(f2Refill) {
    rAValid := rAValid && !io.bus.a.ready
    rRValid := false.B
    icValid(rSelWay)(p.icacheGetSet(f2Inst.pc)) := true.B
    when(io.bus.d.fire) {
      rDCnt := rDCnt + 1.U
    }
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
}
