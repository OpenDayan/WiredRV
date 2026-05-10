package lmss.axi

import chisel3._
import chisel3.util._

case class AxiParams(
  addrBits: Int,
  idBits:   Int,
  dataBits: Int,
  lenBits:  Int = 8,
  sizeBits: Int = 3,
  userBits: Int = 1)

object AxiSlvParamsCalc {
  def apply(params: Seq[AxiParams]): AxiParams = {
    require(params.nonEmpty, "AXI parameter list must be non-empty")
    AxiParams(
      addrBits = params.map(_.addrBits).max,
      idBits = params.map(_.idBits).max,
      dataBits = params.map(_.dataBits).max,
      lenBits = params.map(_.lenBits).max,
      sizeBits = params.map(_.sizeBits).max,
      userBits = params.map(_.userBits).max)
  }
}

object AxiComputeFunction {
  val FIXED = 0.U(2.W)
  val INCR = 1.U(2.W)
  val WRAP = 2.U(2.W)
}

class AWFlit(val p: AxiParams) extends Bundle {
  val id = UInt(p.idBits.W)
  val addr = UInt(p.addrBits.W)
  val len = UInt(p.lenBits.W)
  val size = UInt(p.sizeBits.W)
  val burst = UInt(2.W)
  val lock = UInt(1.W)
  val cache = UInt(4.W)
  val prot = UInt(3.W)
  val qos = UInt(4.W)
  val region = UInt(4.W)
  val user = UInt(p.userBits.W)
}

class ARFlit(val p: AxiParams) extends Bundle {
  val id = UInt(p.idBits.W)
  val addr = UInt(p.addrBits.W)
  val len = UInt(p.lenBits.W)
  val size = UInt(p.sizeBits.W)
  val burst = UInt(2.W)
  val lock = UInt(1.W)
  val cache = UInt(4.W)
  val prot = UInt(3.W)
  val qos = UInt(4.W)
  val region = UInt(4.W)
  val user = UInt(p.userBits.W)
}

class WFlit(val p: AxiParams) extends Bundle {
  val data = UInt(p.dataBits.W)
  val strb = UInt((p.dataBits / 8).W)
  val last = UInt(1.W)
  val user = UInt(p.userBits.W)
}

class BFlit(val p: AxiParams) extends Bundle {
  val id = UInt(p.idBits.W)
  val resp = UInt(2.W)
  val user = UInt(p.userBits.W)
}

class RFlit(val p: AxiParams) extends Bundle {
  val id = UInt(p.idBits.W)
  val data = UInt(p.dataBits.W)
  val resp = UInt(2.W)
  val last = UInt(1.W)
  val user = UInt(p.userBits.W)
}

class AxiBundle(val p: AxiParams) extends Bundle {
  val aw = Decoupled(new AWFlit(p))
  val w = Decoupled(new WFlit(p))
  val b = Flipped(Decoupled(new BFlit(p)))
  val ar = Decoupled(new ARFlit(p))
  val r = Flipped(Decoupled(new RFlit(p)))
}

class AxiNarrowToWide(narrow: AxiParams, wide: AxiParams, buffer: Int = 0) extends Module {
  require(wide.addrBits == narrow.addrBits, "address widths must match")
  require(wide.idBits == narrow.idBits, "id widths must match")
  require(wide.dataBits > narrow.dataBits, "AxiNarrowToWide expects a wider downstream bus")
  require(wide.dataBits % narrow.dataBits == 0, "wide data width must be a multiple of narrow data width")
  require(isPow2(wide.dataBits / narrow.dataBits), "narrow-to-wide ratio must be a power of two")

  private val ratio = wide.dataBits / narrow.dataBits
  private val ratioBits = log2Ceil(ratio)
  private val narrowBytes = narrow.dataBits / 8
  private val narrowBeatBits = log2Ceil(narrowBytes)
  private val wideBytes = wide.dataBits / 8
  private val wideBeatBits = log2Ceil(wideBytes)

  val io = IO(new Bundle {
    val mst = Flipped(new AxiBundle(narrow))
    val slv = new AxiBundle(wide)
  })

  val sIdle :: sRead :: Nil = Enum(2)
  val state = RegInit(sIdle)
  val haveWideBeat = RegInit(false.B)
  val slice = RegInit(0.U(ratioBits.W))
  val rId = Reg(UInt(narrow.idBits.W))
  val rResp = Reg(UInt(2.W))
  val rUser = Reg(UInt(narrow.userBits.W))
  val rData = Reg(UInt(wide.dataBits.W))
  val rWideLast = Reg(Bool())

  val narrowBeats = io.mst.ar.bits.len +& 1.U
  val wideBeats = (narrowBeats + (ratio - 1).U) >> ratioBits

  io.mst.ar.ready := state === sIdle && io.slv.ar.ready
  io.slv.ar.valid := state === sIdle && io.mst.ar.valid
  io.slv.ar.bits := 0.U.asTypeOf(new ARFlit(wide))
  io.slv.ar.bits.id := io.mst.ar.bits.id
  io.slv.ar.bits.addr := Cat(io.mst.ar.bits.addr(wide.addrBits - 1, wideBeatBits), 0.U(wideBeatBits.W))
  io.slv.ar.bits.len := (wideBeats - 1.U)(wide.lenBits - 1, 0)
  io.slv.ar.bits.size := log2Ceil(wideBytes).U
  io.slv.ar.bits.burst := AxiComputeFunction.INCR
  io.slv.ar.bits.lock := io.mst.ar.bits.lock
  io.slv.ar.bits.cache := io.mst.ar.bits.cache
  io.slv.ar.bits.prot := io.mst.ar.bits.prot
  io.slv.ar.bits.qos := io.mst.ar.bits.qos
  io.slv.ar.bits.region := io.mst.ar.bits.region
  io.slv.ar.bits.user := io.mst.ar.bits.user

  io.slv.r.ready := state === sRead && !haveWideBeat
  io.mst.r.valid := haveWideBeat
  io.mst.r.bits := 0.U.asTypeOf(new RFlit(narrow))
  io.mst.r.bits.id := rId
  io.mst.r.bits.resp := rResp
  io.mst.r.bits.user := rUser
  io.mst.r.bits.last := (rWideLast && (slice === (ratio - 1).U)).asUInt
  io.mst.r.bits.data := VecInit((0 until ratio).map { i =>
    rData((i + 1) * narrow.dataBits - 1, i * narrow.dataBits)
  })(slice)

  val wIdle :: wSend :: wResp :: Nil = Enum(3)
  val wState = RegInit(wIdle)
  val wAw = Reg(new AWFlit(narrow))
  val wData = Reg(new WFlit(narrow))
  val wAwValid = RegInit(false.B)
  val wDataValid = RegInit(false.B)
  val wAwSent = RegInit(false.B)
  val wDataSent = RegInit(false.B)

  val wInputAwValid = wAwValid || io.mst.aw.fire
  val wInputDataValid = wDataValid || io.mst.w.fire
  val wInputAw = Mux(wAwValid, wAw, io.mst.aw.bits)
  val wInputData = Mux(wDataValid, wData, io.mst.w.bits)
  val wSlice = wAw.addr(wideBeatBits - 1, narrowBeatBits)
  val wShiftBits = wSlice << log2Ceil(narrow.dataBits).U
  val wShiftBytes = wSlice << narrowBeatBits.U

  io.mst.aw.ready := wState === wIdle && !wAwValid
  io.mst.w.ready := wState === wIdle && !wDataValid
  io.mst.b.valid := wState === wResp && io.slv.b.valid
  io.mst.b.bits := 0.U.asTypeOf(new BFlit(narrow))
  io.mst.b.bits.id := io.slv.b.bits.id
  io.mst.b.bits.resp := io.slv.b.bits.resp
  io.mst.b.bits.user := io.slv.b.bits.user

  io.slv.aw.valid := wState === wSend && !wAwSent
  io.slv.aw.bits := 0.U.asTypeOf(new AWFlit(wide))
  io.slv.aw.bits.id := wAw.id
  io.slv.aw.bits.addr := Cat(wAw.addr(wide.addrBits - 1, wideBeatBits), 0.U(wideBeatBits.W))
  io.slv.aw.bits.len := 0.U
  io.slv.aw.bits.size := log2Ceil(wideBytes).U
  io.slv.aw.bits.burst := AxiComputeFunction.INCR
  io.slv.aw.bits.lock := wAw.lock
  io.slv.aw.bits.cache := wAw.cache
  io.slv.aw.bits.prot := wAw.prot
  io.slv.aw.bits.qos := wAw.qos
  io.slv.aw.bits.region := wAw.region
  io.slv.aw.bits.user := wAw.user

  io.slv.w.valid := wState === wSend && !wDataSent
  io.slv.w.bits := 0.U.asTypeOf(new WFlit(wide))
  io.slv.w.bits.data := wData.data << wShiftBits
  io.slv.w.bits.strb := wData.strb << wShiftBytes
  io.slv.w.bits.last := 1.U
  io.slv.w.bits.user := wData.user
  io.slv.b.ready := wState === wResp && io.mst.b.ready

  when(io.mst.ar.fire) {
    state := sRead
  }

  when(io.slv.r.fire) {
    haveWideBeat := true.B
    slice := 0.U
    rId := io.slv.r.bits.id
    rResp := io.slv.r.bits.resp
    rUser := io.slv.r.bits.user
    rData := io.slv.r.bits.data
    rWideLast := io.slv.r.bits.last.asBool
  }

  when(io.mst.r.fire) {
    when(rWideLast && (slice === (ratio - 1).U)) {
      haveWideBeat := false.B
      state := sIdle
    }.elsewhen(slice === (ratio - 1).U) {
      haveWideBeat := false.B
    }.otherwise {
      slice := slice + 1.U
    }
  }

  when(io.mst.aw.fire) {
    wAwValid := true.B
    wAw := io.mst.aw.bits
  }
  when(io.mst.w.fire) {
    wDataValid := true.B
    wData := io.mst.w.bits
  }
  when(wState === wIdle && wInputAwValid && wInputDataValid) {
    wAw := wInputAw
    wData := wInputData
    wAwValid := false.B
    wDataValid := false.B
    wAwSent := false.B
    wDataSent := false.B
    wState := wSend
  }
  when(io.slv.aw.fire) {
    wAwSent := true.B
  }
  when(io.slv.w.fire) {
    wDataSent := true.B
  }
  when(wState === wSend && (wAwSent || io.slv.aw.fire) && (wDataSent || io.slv.w.fire)) {
    wState := wResp
  }
  when(io.slv.b.fire) {
    wState := wIdle
  }
}
