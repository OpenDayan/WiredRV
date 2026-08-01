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

class BackendCsrWriteCalc(implicit p: CoreParams) extends Bundle {
  val addr = Input(UInt(12.W))
  val op = Input(UInt(CsrOp.width.W))
  val old = Input(UInt(p.xlen.W))
  val src = Input(UInt(p.xlen.W))
}

class BackendCsrTrapCommit(implicit p: CoreParams) extends Bundle {
  val valid = Input(Bool())
  val isInterrupt = Input(Bool())
  val code = Input(UInt(p.xlen.W))
  val pc = Input(UInt(p.addrBits.W))
  val mtval = Input(UInt(p.xlen.W))
}

class BackendCsrWriteCommit(implicit p: CoreParams) extends Bundle {
  val valid = Input(Bool())
  val addr = Input(UInt(12.W))
  val data = Input(UInt(p.xlen.W))
}

class BackendCsr(implicit p: CoreParams) extends Module {
  private val misaValue = ((BigInt(2) << (p.xlen - 2)) | (BigInt(1) << 8) | (BigInt(1) << 12)).U(p.xlen.W)
  private val mstatusMieBit = 3
  private val mstatusMpieBit = 7
  private val mstatusMieMask = (BigInt(1) << mstatusMieBit).U(p.xlen.W)
  private val mstatusMpieMask = (BigInt(1) << mstatusMpieBit).U(p.xlen.W)
  private val mstatusMppMask = (BigInt(3) << 11).U(p.xlen.W)
  private val mstatusMppM = (BigInt(3) << 11).U(p.xlen.W)
  private val msipMask = (BigInt(1) << 3).U(p.xlen.W)
  private val mtipMask = (BigInt(1) << 7).U(p.xlen.W)
  private val meipMask = (BigInt(1) << 11).U(p.xlen.W)

  val io = IO(new Bundle {
    val hartId = Input(UInt(p.maxHartIdBits.W))
    val time = Input(Valid(UInt(64.W)))
    val cycle = Input(UInt(p.xlen.W))
    val msip = Input(Bool())
    val mtip = Input(Bool())
    val meip = Input(Bool())

    val readAddr = Input(UInt(12.W))
    val readData = Output(UInt(p.xlen.W))

    val writeCalc = new BackendCsrWriteCalc
    val writeData = Output(UInt(p.xlen.W))
    val writeEnable = Output(Bool())
    val writeModeledWritable = Output(Bool())

    val trapCommit = new BackendCsrTrapCommit
    val mretCommit = Input(Bool())
    val writeCommit = new BackendCsrWriteCommit

    val interruptEnabled = Output(Bool())
    val interruptCause = Output(UInt(4.W))
    val privilegeMode = Output(UInt(2.W))
    val trapVector = Output(UInt(p.addrBits.W))
    val mretTarget = Output(UInt(p.addrBits.W))
    val diff = Output(new CoreCsrState)
  })

  private val mstatus = RegInit(mstatusMppM)
  private val mtvec = RegInit(0.U(p.xlen.W))
  private val mepc = RegInit(0.U(p.xlen.W))
  private val mcause = RegInit(0.U(p.xlen.W))
  private val mtval = RegInit(0.U(p.xlen.W))
  private val mie = RegInit(0.U(p.xlen.W))
  private val mip = RegInit(0.U(p.xlen.W))
  private val mscratch = RegInit(0.U(p.xlen.W))
  private val pmpcfg0 = RegInit(0.U(p.xlen.W))
  private val pmpaddr0 = RegInit(0.U(p.xlen.W))
  private val privilegeMode = RegInit(3.U(2.W))

  private def csrWriteData(op: UInt, old: UInt, src: UInt): UInt =
    MuxLookup(op, old)(Seq(
      CsrOp.csrrw -> src,
      CsrOp.csrrs -> (old | src),
      CsrOp.csrrc -> (old & ~src)
    ))

  private def legalizeMstatus(data: UInt): UInt =
    data

  private def legalizeCsrWrite(addr: UInt, data: UInt): UInt =
    Mux(addr === "h300".U, legalizeMstatus(data), data)

  private def csrModeledWritable(addr: UInt): Bool =
    addr === "h300".U || addr === "h304".U || addr === "h305".U ||
      addr === "h340".U || addr === "h341".U || addr === "h342".U ||
      addr === "h343".U || addr === "h344".U || addr === "h3a0".U ||
      addr === "h3b0".U

  private def csrWrites(op: UInt, src: UInt): Bool =
    op === CsrOp.csrrw || ((op === CsrOp.csrrs || op === CsrOp.csrrc) && src =/= 0.U)

  private val linePendingMask =
    Mux(io.msip, msipMask, 0.U(p.xlen.W)) |
      Mux(io.mtip, mtipMask, 0.U(p.xlen.W)) |
      Mux(io.meip, meipMask, 0.U(p.xlen.W))
  private val mipRead = mip | linePendingMask
  private val enabledInterrupts = mie & mipRead
  private val csrTime = Mux(io.time.valid, io.time.bits, io.cycle)

  io.readData := MuxLookup(io.readAddr, 0.U(p.xlen.W))(Seq(
    "h300".U(12.W) -> mstatus,
    "h304".U(12.W) -> mie,
    "h305".U(12.W) -> mtvec,
    "h301".U(12.W) -> misaValue,
    "h340".U(12.W) -> mscratch,
    "h341".U(12.W) -> mepc,
    "h342".U(12.W) -> mcause,
    "h343".U(12.W) -> mtval,
    "h344".U(12.W) -> mipRead,
    "h3a0".U(12.W) -> pmpcfg0,
    "h3b0".U(12.W) -> pmpaddr0,
    "hc01".U(12.W) -> csrTime,
    "hc81".U(12.W) -> Cat(0.U(32.W), csrTime(63, 32)),
    "hb00".U(12.W) -> io.cycle,
    "hb80".U(12.W) -> Cat(0.U(32.W), io.cycle(63, 32)),
    "hf11".U(12.W) -> 0.U,
    "hf12".U(12.W) -> 0.U,
    "hf13".U(12.W) -> 0.U,
    "hf14".U(12.W) -> Cat(0.U((p.xlen - p.maxHartIdBits).W), io.hartId)
  ))

  io.writeData := legalizeCsrWrite(io.writeCalc.addr,
    csrWriteData(io.writeCalc.op, io.writeCalc.old, io.writeCalc.src))
  io.writeEnable := csrWrites(io.writeCalc.op, io.writeCalc.src)
  io.writeModeledWritable := csrModeledWritable(io.writeCalc.addr)

  io.interruptEnabled := mstatus(mstatusMieBit) && (enabledInterrupts =/= 0.U)
  io.interruptCause := Mux(
    enabledInterrupts(11),
    11.U(4.W),
    Mux(enabledInterrupts(3), 3.U(4.W), Mux(enabledInterrupts(7), 7.U(4.W), 0.U(4.W)))
  )
  io.privilegeMode := privilegeMode

  private val mtvecBase = Cat(mtvec(p.addrBits - 1, 2), 0.U(2.W))
  private val trapVectorOffset = (io.trapCommit.code(p.addrBits - 1, 0) << 2)(p.addrBits - 1, 0)
  io.trapVector := Mux(
    io.trapCommit.isInterrupt && mtvec(1, 0) === 1.U,
    mtvecBase + trapVectorOffset,
    mtvecBase
  )
  io.mretTarget := mepc(p.addrBits - 1, 0)

  private val privilegeModeXlen = Cat(0.U((p.xlen - 2).W), privilegeMode)
  private val trapMpp = (privilegeModeXlen << 11)(p.xlen - 1, 0)
  private val trapMstatusNext = legalizeMstatus(
    (mstatus & ~mstatusMieMask & ~mstatusMpieMask & ~mstatusMppMask) |
      trapMpp |
      Mux(mstatus(mstatusMieBit), mstatusMpieMask, 0.U(p.xlen.W))
  )
  private val mretPrivilegeMode = mstatus(12, 11)
  private val mretMstatusNext = legalizeMstatus(
    (mstatus & ~mstatusMieMask & ~mstatusMpieMask & ~mstatusMppMask) |
      mstatusMpieMask |
      Mux(mstatus(mstatusMpieBit), mstatusMieMask, 0.U(p.xlen.W))
  )
  private val interruptMcauseFlag = Cat(1.B, 0.U((p.xlen - 1).W))
  private val trapMcause = Mux(io.trapCommit.isInterrupt,
    interruptMcauseFlag | io.trapCommit.code,
    io.trapCommit.code
  )

  val diff = WireDefault(0.U.asTypeOf(new CoreCsrState))
  diff.privilegeMode := Cat(0.U((p.xlen - 2).W), privilegeMode)
  diff.mstatus := mstatus
  diff.mepc := mepc
  diff.mtval := mtval
  diff.mtvec := mtvec
  diff.mcause := mcause
  diff.mip := mipRead
  diff.mie := mie
  diff.mscratch := mscratch
  when(io.trapCommit.valid) {
    diff.privilegeMode := 3.U
    diff.mstatus := trapMstatusNext
    diff.mepc := io.trapCommit.pc
    diff.mtval := io.trapCommit.mtval
    diff.mcause := trapMcause
  }.elsewhen(io.mretCommit) {
    diff.privilegeMode := Cat(0.U((p.xlen - 2).W), mretPrivilegeMode)
    diff.mstatus := mretMstatusNext
  }.elsewhen(io.writeCommit.valid) {
    switch(io.writeCommit.addr) {
      is("h300".U) { diff.mstatus := io.writeCommit.data }
      is("h304".U) { diff.mie := io.writeCommit.data }
      is("h305".U) { diff.mtvec := io.writeCommit.data }
      is("h340".U) { diff.mscratch := io.writeCommit.data }
      is("h341".U) { diff.mepc := io.writeCommit.data }
      is("h342".U) { diff.mcause := io.writeCommit.data }
      is("h343".U) { diff.mtval := io.writeCommit.data }
      is("h344".U) { diff.mip := io.writeCommit.data | linePendingMask }
    }
  }
  io.diff := diff

  when(io.trapCommit.valid) {
    privilegeMode := 3.U
    mstatus := trapMstatusNext
    mepc := io.trapCommit.pc
    mcause := trapMcause
    mtval := io.trapCommit.mtval
  }.elsewhen(io.mretCommit) {
    privilegeMode := mretPrivilegeMode
    mstatus := mretMstatusNext
  }.elsewhen(io.writeCommit.valid) {
    switch(io.writeCommit.addr) {
      is("h300".U) { mstatus := io.writeCommit.data }
      is("h304".U) { mie := io.writeCommit.data }
      is("h305".U) { mtvec := io.writeCommit.data }
      is("h340".U) { mscratch := io.writeCommit.data }
      is("h341".U) { mepc := io.writeCommit.data }
      is("h342".U) { mcause := io.writeCommit.data }
      is("h343".U) { mtval := io.writeCommit.data }
      is("h344".U) { mip := io.writeCommit.data }
      is("h3a0".U) { pmpcfg0 := io.writeCommit.data }
      is("h3b0".U) { pmpaddr0 := io.writeCommit.data }
    }
  }
}
