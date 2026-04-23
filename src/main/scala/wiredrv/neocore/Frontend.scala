package wiredrv.neocore

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import difftest._
import difftest.common.DifftestMem
import scala.tools.nsc.doc.html.HtmlTags.P
import wiredrv.neocore.BusOp.ReadOnly

class FetchPacket(implicit p: CoreParams) extends Bundle {
  val pc = UInt(p.addrBits.W)
  val inst = UInt(32.W)
  val jump = Bool() // 预测是否要跳转
  val target = UInt(p.addrBits.W)
}

/*
* 重定向表：
* 0 Exception / Backend DCache Miss Replay
* 1 Branch / CSR / Uncached Flush
* 2 Jump Early Decode
* 3 Frontend Miss Replay
*/

class FrontendIO(implicit p: CoreParams) extends Bundle {
  val inst = Decoupled(new FetchPacket)
  val redirects = Input(Vec(3, Valid(UInt(p.addrBits.W)))) // 后端重定向
  val invalidate = Input(Bool())
  val bus = new BusBundle(32)

  // ifencei
  val ifencei = Input(Bool())
}

class Frontend(implicit p: CoreParams) extends Module {
    val io = IO(new FrontendIO)

    // 前端重定向请求
    val frontMissReplay = Wire(Valid(UInt(p.addrBits.W)))
    frontMissReplay.valid := 0.B

    // 所有的重定向请求
    val allRedir = io.redirects ++ Seq(frontMissReplay)
    val anyRedir = VecInit(allRedir.map(_.valid)).asUInt.orR
    val backRedir = VecInit(io.redirects.map(_.valid)).asUInt.orR

    // 前端阻塞
    val frontStall = Wire(Bool())

    // NPC Parts
    val f1pc = RegInit((p.resetPc - 4L).U(p.addrBits.W))
    val pc = Mux(anyRedir, PriorityMux(allRedir.map(_.valid), allRedir.map(_.bits)), f1pc + 4.U)
    when(!frontStall) {
        f1pc := pc
    }

    // ICache Tag
    class icTag extends Bundle {
        val tag = UInt(p.icacheTagBits.W)
    }

    // ICache
    require(p.icacheWays == 2)
    private val beatsPerLine = p.icacheLineBytes / 4
    val icValid = RegInit(0.U.asTypeOf(Vec(p.icacheWays, Vec(p.icacheSets, Bool()))))
    val icTags  = Seq.fill(p.icacheWays)(SyncReadMem(p.icacheSets, new icTag))
    val icDatas = Seq.fill(p.icacheWays)(SyncReadMem(p.icacheSets * beatsPerLine, UInt(p.xlen.W)))

    // f1 data
    val f1Valid = RegInit(0.B)
    val f1Hit = Wire(Bool())
    f1Hit := DontCare
    val f1Inst = Wire(UInt(32.W))
    f1Inst := DontCare

    // Flush valid
    when(io.ifencei) {
        icValid := 0.U.asTypeOf(icValid)
    }

    // Hit Fast Path
    when(!frontStall) {
        val hits = icValid.zip(icTags).map{case(v, t) => {
            val set = p.icacheGetSet(pc)
            RegNext(!io.ifencei && v(set)) && t(set).tag === p.icacheGetTag(f1pc)
        }}
        val insts = VecInit(icDatas.map(_(p.icacheGetWord(pc))))
        f1Hit := VecInit(hits).asUInt.orR
        // Only Work in 2-ways
        f1Inst := Mux(hits(0), insts(0), insts(1))
        f1Valid := 1.B
    }.otherwise {
        when(anyRedir) {
            f1Valid := 0.B
        }
    }

    // TODO: LFSR 随机选路
    val rWay = RegInit(0.U(1.W))
    rWay := rWay + 1.U

    // F2 输出 Skid logic
    val f2Valid = RegInit(0.B)
    val f2Refil = RegInit(0.B)
    val f2Inst  = Reg(new FetchPacket)
    val f2SkidV = RegInit(0.B)
    val f2Skid  = Reg(new FetchPacket)

    // icache 重填状态
    val rSelWay = Reg(UInt(1.W))
    val rAValid = RegInit(0.B) // A 请求待发送
    val rDCnt = Reg(UInt(log2Ceil(beatsPerLine).W))
    val rDLast = rDCnt === (beatsPerLine - 1).U

    val f2RefilEnd = rDLast && io.bus.d.valid
    frontStall := f2SkidV || f2Refil

    when(!frontStall) {
        f2Valid := f1Valid && !anyRedir && f1Hit
        when(f1Valid && !anyRedir && !f1Hit) {
            f2Refil := 1.B
            rSelWay := rWay
            rAValid := 1.B
            rDCnt := 0.U
        }
        f2Inst.pc := f1pc
        f2Inst.inst := f1Inst
        f2Inst.jump := 0.B
        f2Inst.target := 0.U
        when(f2Valid) {
            f2SkidV := !io.inst.ready && !backRedir
            f2Skid := f2Inst
        }
        io.inst.valid := f2Valid
        io.inst.bits := f2Inst
    }.otherwise {
        // 此时阻塞
        f2SkidV := f2SkidV && !io.inst.ready && !backRedir
        io.inst.valid := f2SkidV
        io.inst.bits := f2Skid
        f2Refil := f2Refil && !f2RefilEnd
    }

    when(f2Refil) {
        rAValid := rAValid && !io.bus.a.ready
        icValid(rSelWay)(p.icacheGetSet(f2Inst.pc)) := 1.B
        icTags.zipWithIndex.foreach{case(icTag, idx) => {
            when(rSelWay === idx.U) {
                icTag(p.icacheGetSet(f2Inst.pc)).tag := p.icacheGetTag(f2Inst.pc)
            }
        }}
        when(io.bus.d.fire) {
            rDCnt := rDCnt + 1.U
            icDatas.zipWithIndex.foreach{case(icData, idx) => {
                when(rSelWay === idx.U) {
                    icData(p.icacheGetSet(f2Inst.pc) ## rDCnt) := io.bus.d.bits.data
                }
            }}
        }
    }
    io.bus.a.valid := rAValid
    io.bus.a.bits.address := p.icacheGetTag(f2Inst.pc) ## p.icacheGetSet(f2Inst.pc) ## 0.U(p.icacheLineBits.W)
    io.bus.a.bits.data := DontCare
    io.bus.a.bits.id := 0.U
    io.bus.a.bits.mask := 0xffffffff.U
    io.bus.a.bits.size := 63.U
    io.bus.a.bits.op := ReadOnly
    io.bus.d.ready := f2Refil
}