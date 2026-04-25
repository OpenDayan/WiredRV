package wiredrv.bus

import chisel3._
import chisel3.util._
import lmss.axi._
import wiredrv.CoreParams

class LmssAxiXbar(mstParams: Seq[AxiParams])(implicit p: CoreParams)
    extends Module {
  require(mstParams.nonEmpty, "AXI crossbar needs at least one upstream port")
  require(mstParams.forall(_.dataBits == mstParams.head.dataBits), "all upstream AXI ports must have the same data width")

  private val axiP = AxiSlvParamsCalc(mstParams)
  private val portCount = mstParams.length
  private val selBits = log2Ceil(portCount max 2)

  val io = IO(new Bundle {
    val upstream = Vec(portCount, Flipped(new AxiBundle(axiP)))
    val downstream = Vec(1, new AxiBundle(axiP))
  })

  val down = io.downstream.head

  for (up <- io.upstream) {
    up.aw.ready := false.B
    up.w.ready := false.B
    up.b.valid := false.B
    up.b.bits := 0.U.asTypeOf(new BFlit(axiP))
    up.ar.ready := false.B
    up.r.valid := false.B
    up.r.bits := 0.U.asTypeOf(new RFlit(axiP))
  }

  val wBusy = RegInit(false.B)
  val wSel = RegInit(0.U(selBits.W))
  val writeReqs = VecInit(io.upstream.map(up => up.aw.valid || up.w.valid))
  val writeHasReq = writeReqs.asUInt.orR
  val writePick = PriorityEncoder(writeReqs)
  val writeActive = wBusy || writeHasReq
  val writeSel = Mux(wBusy, wSel, writePick)

  down.aw.valid := writeActive && io.upstream(writeSel).aw.valid
  down.aw.bits := io.upstream(writeSel).aw.bits
  down.w.valid := writeActive && io.upstream(writeSel).w.valid
  down.w.bits := io.upstream(writeSel).w.bits
  for (i <- 0 until portCount) {
    io.upstream(i).aw.ready := writeActive && (writeSel === i.U) && down.aw.ready
    io.upstream(i).w.ready := writeActive && (writeSel === i.U) && down.w.ready
  }

  down.b.ready := wBusy && io.upstream(wSel).b.ready
  for (i <- 0 until portCount) {
    io.upstream(i).b.valid := wBusy && (wSel === i.U) && down.b.valid
    io.upstream(i).b.bits := down.b.bits
  }

  when(!wBusy && (down.aw.fire || down.w.fire)) {
    wBusy := true.B
    wSel := writeSel
  }
  when(down.b.fire) {
    wBusy := false.B
  }

  val rBusy = RegInit(false.B)
  val rSel = RegInit(0.U(selBits.W))
  val readReqs = VecInit(io.upstream.map(_.ar.valid))
  val readHasReq = readReqs.asUInt.orR
  val readPick = PriorityEncoder(readReqs)

  down.ar.valid := !rBusy && readHasReq
  down.ar.bits := io.upstream(readPick).ar.bits
  for (i <- 0 until portCount) {
    io.upstream(i).ar.ready := !rBusy && (readPick === i.U) && down.ar.ready
  }

  down.r.ready := rBusy && io.upstream(rSel).r.ready
  for (i <- 0 until portCount) {
    io.upstream(i).r.valid := rBusy && (rSel === i.U) && down.r.valid
    io.upstream(i).r.bits := down.r.bits
  }

  when(down.ar.fire) {
    rBusy := true.B
    rSel := readPick
  }
  when(down.r.fire && down.r.bits.last.asBool) {
    rBusy := false.B
  }
}
