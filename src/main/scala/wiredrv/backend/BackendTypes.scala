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

class CoreCsrState(implicit p: CoreParams) extends Bundle {
  val privilegeMode = UInt(p.xlen.W)
  val mstatus = UInt(p.xlen.W)
  val sstatus = UInt(p.xlen.W)
  val mepc = UInt(p.xlen.W)
  val sepc = UInt(p.xlen.W)
  val mtval = UInt(p.xlen.W)
  val stval = UInt(p.xlen.W)
  val mtvec = UInt(p.xlen.W)
  val stvec = UInt(p.xlen.W)
  val mcause = UInt(p.xlen.W)
  val scause = UInt(p.xlen.W)
  val satp = UInt(p.xlen.W)
  val mip = UInt(p.xlen.W)
  val mie = UInt(p.xlen.W)
  val mscratch = UInt(p.xlen.W)
  val sscratch = UInt(p.xlen.W)
  val mideleg = UInt(p.xlen.W)
  val medeleg = UInt(p.xlen.W)
}

class BackendUop(implicit p: CoreParams) extends Bundle {
  val pc = UInt(p.addrBits.W)
  val inst = UInt(32.W)
  val instWord = UInt(64.W)
  val predictedJump = Bool()
  val predictedTarget = UInt(p.addrBits.W)
  val info = new DecodeInfo

  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val rd = UInt(5.W)
  val imm = UInt(p.xlen.W)
  val csrAddr = UInt(12.W)
  val exceptionValid = Bool()
  val exceptionCode = UInt(16.W)
  val isInterrupt = Bool()

  val src1 = UInt(p.xlen.W)
  val src2 = UInt(p.xlen.W)
  val store_data = UInt(p.xlen.W)
  val rdData = UInt(p.xlen.W)
  val src1_forward_w = Bool()
  val src2_forward_w = Bool()
  val store_forward_w = Bool()

  val flushed = Bool()
  val vslot_valid = Bool()
  val vslot = UInt(p.longLatencySlotsBits.W)
}

class DCTag(implicit p: CoreParams) extends Bundle {
  val tag = UInt(p.dcacheTagBits.W)
  val dirty = Bool()
}

class StoreQueueEntry(implicit p: CoreParams) extends Bundle {
  val valid = Bool()
  val way = UInt(log2Ceil(p.dcacheWays).W)
  val idx = UInt((p.dcacheSetBits + log2Ceil(p.dcacheLineBytes / (p.xlen / 8))).W)
  val addr = UInt(p.addrBits.W)
  val mask = UInt((p.xlen / 8).W)
  val data = UInt(p.xlen.W)
  val set = UInt(p.dcacheSetBits.W)
}

class BackendPerfCounters extends Bundle {
  val cycles = UInt(64.W)
  val commits = UInt(64.W)
  val dStall = UInt(64.W)
  val dRs1VarHaz = UInt(64.W)
  val dRs2VarHaz = UInt(64.W)
  val dRdVarHaz = UInt(64.W)
  val dERawHaz = UInt(64.W)
  val dWRawHaz = UInt(64.W)
  val dLsuBaseNotReady = UInt(64.W)
  val dJrNotReady = UInt(64.W)
  val dNoVslot = UInt(64.W)
  val dDivResourceBusy = UInt(64.W)
  val dCacheBusy = UInt(64.W)
  val dUncachedBusy = UInt(64.W)
  val dStoreQueueWait = UInt(64.W)
  val dInflightStoreWait = UInt(64.W)
  val dFenceWait = UInt(64.W)
  val dMisalignedWait = UInt(64.W)
  val dNemuTrapWait = UInt(64.W)
  val loadCommit = UInt(64.W)
  val storeCommit = UInt(64.W)
  val jumpCommit = UInt(64.W)
  val branchCommit = UInt(64.W)
  val branchRedirect = UInt(64.W)
  val dcacheLoadHit = UInt(64.W)
  val dcacheStoreHit = UInt(64.W)
  val dcacheMiss = UInt(64.W)
  val dcacheMissLoad = UInt(64.W)
  val dcacheMissStore = UInt(64.W)
  val dcacheRefillCycles = UInt(64.W)
  val dcacheRefillRespWait = UInt(64.W)
  val dcacheVictimWriteback = UInt(64.W)
  val dcacheVictimWritebackCycles = UInt(64.W)
  val cmoStart = UInt(64.W)
  val cmoCycles = UInt(64.W)
  val storeQueueEnq = UInt(64.W)
  val storeQueueDrain = UInt(64.W)
  val storeQueueFullCycles = UInt(64.W)
  val storeQueueOneEntryCycles = UInt(64.W)
  val uncachedAccess = UInt(64.W)
  val uncachedCycles = UInt(64.W)
  val dBusAWait = UInt(64.W)
  val dBusDWait = UInt(64.W)
  val dBusDWaitRefill = UInt(64.W)
  val dBusDWaitUncached = UInt(64.W)
  val mulStart = UInt(64.W)
  val divStart = UInt(64.W)
  val divBusyCycles = UInt(64.W)
}

object DCacheRefillState {
  val idle :: sendA :: recvD :: writeMeta :: cmoRead :: cmoWriteback :: cmoUpdate :: Nil = Enum(7)
}

object UncachedState {
  val idle :: sendA :: recvD :: Nil = Enum(3)
}

object MisalignedState {
  val idle :: readSecondReq :: readSecondResp :: busCheck :: busSend :: busRecv :: Nil = Enum(6)
}
