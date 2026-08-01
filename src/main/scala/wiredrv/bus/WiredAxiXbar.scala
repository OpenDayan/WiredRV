/*
 * Copyright (c) 2026 Institute of Computing Technology, Chinese Academy of Sciences
 * SPDX-License-Identifier: MulanPSL-2.0
 *
 * WiredRV is licensed under the Mulan PSL v2.
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

package wiredrv.bus

import chisel3._
import chisel3.util._
import xs.infra.axi._
import wiredrv.CoreParams

class WiredAxiXbar(mstParams: Seq[AxiParams])(implicit p: CoreParams)
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

  val rBusy = RegInit(false.B)
  val rSel = RegInit(0.U(selBits.W))
  val wBusy = RegInit(false.B)
  val wSel = RegInit(0.U(selBits.W))
  val wAwSeen = RegInit(false.B)
  val wNeedsR = RegInit(false.B)
  val wGotB = RegInit(false.B)
  val wGotR = RegInit(false.B)
  val writeReqs = VecInit(io.upstream.map(up => up.aw.valid || up.w.valid))
  val writeHasReq = writeReqs.asUInt.orR
  val writePick = PriorityEncoder(writeReqs)
  val writeSel = Mux(wBusy, wSel, writePick)
  val writeSelectedHasAw = io.upstream(writeSel).aw.valid
  val writeSelectedKnownAtomic = writeSelectedHasAw && io.upstream(writeSel).aw.bits.atop =/= 0.U
  val writeSelectedUnknown = !writeSelectedHasAw && io.upstream(writeSel).w.valid
  val writeBlockedByRead = !wBusy && rBusy && (writeSelectedKnownAtomic || writeSelectedUnknown)
  val writeActive = (wBusy || writeHasReq) && !writeBlockedByRead

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
    wAwSeen := down.aw.fire
    wNeedsR := down.aw.fire && down.aw.bits.atop =/= 0.U
    wGotB := false.B
    wGotR := false.B
  }
  when(wBusy && down.aw.fire) {
    wAwSeen := true.B
    when(down.aw.bits.atop =/= 0.U) {
      wNeedsR := true.B
    }
  }
  when(down.b.fire && (!wNeedsR || wGotR)) {
    wBusy := false.B
    wAwSeen := false.B
    wNeedsR := false.B
    wGotB := false.B
    wGotR := false.B
  }.elsewhen(down.b.fire) {
    wGotB := true.B
  }
  when(wNeedsR && down.r.fire && down.r.bits.last.asBool && wGotB) {
    wBusy := false.B
    wAwSeen := false.B
    wNeedsR := false.B
    wGotB := false.B
    wGotR := false.B
  }.elsewhen(wNeedsR && down.r.fire && down.r.bits.last.asBool) {
    wGotR := true.B
  }

  val readReqs = VecInit(io.upstream.map(_.ar.valid))
  val readHasReq = readReqs.asUInt.orR
  val readPick = PriorityEncoder(readReqs)
  val atomicRActive = wBusy && wNeedsR
  val writeMayReturnR = (wBusy && !wAwSeen) || atomicRActive ||
    (!wBusy && writeHasReq && (writeSelectedKnownAtomic || writeSelectedUnknown))

  down.ar.valid := !rBusy && !writeMayReturnR && readHasReq
  down.ar.bits := io.upstream(readPick).ar.bits
  for (i <- 0 until portCount) {
    io.upstream(i).ar.ready := !rBusy && !writeMayReturnR && (readPick === i.U) && down.ar.ready
  }

  down.r.ready := Mux(atomicRActive, io.upstream(wSel).r.ready, rBusy && io.upstream(rSel).r.ready)
  for (i <- 0 until portCount) {
    io.upstream(i).r.valid :=
      (atomicRActive && (wSel === i.U) && down.r.valid) ||
        (!atomicRActive && rBusy && (rSel === i.U) && down.r.valid)
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
