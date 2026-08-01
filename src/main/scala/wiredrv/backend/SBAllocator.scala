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

package wiredrv.backend

import chisel3._
import chisel3.util._
import wiredrv.CoreParams

/*
 * 本模块用于分配空闲 VarLatency Scoreboard 表项，并及时回收
 * 模块有一个分配接口和两个释放接口。
 * 分配顺序为 FIFO，且 allocate 接口只反映寄存器中的当前队头状态。
 */
class SBAllocator(implicit p: CoreParams) extends Module {
  require(p.longLatencySlots > 0, "SBAllocator requires at least one slot")

  val io = IO(new Bundle {
    val allocate = Decoupled(UInt(p.longLatencySlotsBits.W))
    val release  = Flipped(Vec(2, Decoupled(UInt(p.longLatencySlotsBits.W))))
  })

  private val slotCount = p.longLatencySlots
  private val ptrWidth = p.longLatencySlotsBits
  private val countWidth = (ptrWidth + 1).max(2)

  def wrapInc(ptr: UInt): UInt = Mux(ptr === (slotCount - 1).U, 0.U(ptrWidth.W), ptr +% 1.U)

  val fifo = RegInit(VecInit.tabulate(slotCount)(i => i.U(ptrWidth.W)))
  val rptr = RegInit(0.U(ptrWidth.W))
  val wptr = RegInit(0.U(ptrWidth.W))
  val freeCount = RegInit(slotCount.U(countWidth.W))
  val allocateValidReg = RegInit(true.B)
  val allocateBitsReg = RegInit(0.U(ptrWidth.W))

  io.allocate.valid := allocateValidReg
  io.allocate.bits := allocateBitsReg

  val usedCount = slotCount.U(countWidth.W) - freeCount
  io.release(0).ready := usedCount =/= 0.U
  io.release(1).ready := usedCount > io.release(0).valid.asUInt

  val doAllocate = allocateValidReg && io.allocate.ready
  val doRelease0 = io.release(0).valid && io.release(0).ready
  val doRelease1 = io.release(1).valid && io.release(1).ready
  val releaseCount = PopCount(Seq(doRelease0, doRelease1))
  val releaseCountExt = Wire(UInt(countWidth.W))
  releaseCountExt := releaseCount
  val allocateCount = Mux(doAllocate, 1.U(countWidth.W), 0.U(countWidth.W))
  val nextRptr = wrapInc(rptr)
  val nextWptr = wrapInc(wptr)
  val nextWptr2 = wrapInc(nextWptr)

  val nextFreeCount = freeCount + releaseCountExt - allocateCount
  val firstReleaseBits = Mux(doRelease0, io.release(0).bits, io.release(1).bits)

  val nextAllocateBits = WireDefault(allocateBitsReg)
  when(doAllocate) {
    when(freeCount === 1.U && releaseCountExt =/= 0.U) {
      nextAllocateBits := firstReleaseBits
    }.elsewhen(freeCount > 1.U) {
      nextAllocateBits := fifo(nextRptr)
    }
  }.elsewhen(freeCount === 0.U && releaseCountExt =/= 0.U) {
    nextAllocateBits := firstReleaseBits
  }

  freeCount := nextFreeCount
  allocateValidReg := nextFreeCount =/= 0.U
  allocateBitsReg := nextAllocateBits

  when(doRelease0) {
    fifo(wptr) := io.release(0).bits
  }

  when(doRelease1) {
    fifo(Mux(doRelease0, nextWptr, wptr)) := io.release(1).bits
  }

  when(releaseCountExt =/= 0.U) {
    wptr := Mux(releaseCountExt === 2.U, nextWptr2, nextWptr)
  }

  when(doAllocate) {
    rptr := nextRptr
  }
}
