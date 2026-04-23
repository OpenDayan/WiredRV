package wiredrv.backend

import chisel3._
import chisel3.util._
import difftest._
import wiredrv.common._

class CSRFile(implicit p: CoreParams) extends Module {
  val io = IO(new Bundle {
    val readAddr = Input(UInt(12.W))
    val readData = Output(UInt(p.xlen.W))
    val writeValid = Input(Bool())
    val writeAddr = Input(UInt(12.W))
    val writeData = Input(UInt(p.xlen.W))
    val trapValid = Input(Bool())
    val trapPc = Input(UInt(p.addrBits.W))
    val trapCode = Input(UInt(p.xlen.W))
    val trapValue = Input(UInt(p.xlen.W))
    val trapVector = Output(UInt(p.addrBits.W))
    val state = Output(new CsrStateBundle)
  })

  val mstatus = RegInit(0.U(64.W))
  val mtvec = RegInit(0.U(64.W))
  val mepc = RegInit(0.U(64.W))
  val mcause = RegInit(0.U(64.W))
  val mtval = RegInit(0.U(64.W))
  val mie = RegInit(0.U(64.W))
  val mip = RegInit(0.U(64.W))
  val mscratch = RegInit(0.U(64.W))
  val diffCsr = DifftestModule(new DiffCSRState, delay = 1, dontCare = true)

  io.readData := 0.U
  switch(io.readAddr) {
    is("h300".U) { io.readData := mstatus }
    is("h305".U) { io.readData := mtvec }
    is("h341".U) { io.readData := mepc }
    is("h342".U) { io.readData := mcause }
    is("h343".U) { io.readData := mtval }
    is("h304".U) { io.readData := mie }
    is("h344".U) { io.readData := mip }
    is("h340".U) { io.readData := mscratch }
  }

  when(io.writeValid) {
    switch(io.writeAddr) {
      is("h300".U) { mstatus := io.writeData }
      is("h305".U) { mtvec := io.writeData }
      is("h341".U) { mepc := io.writeData }
      is("h342".U) { mcause := io.writeData }
      is("h343".U) { mtval := io.writeData }
      is("h304".U) { mie := io.writeData }
      is("h344".U) { mip := io.writeData }
      is("h340".U) { mscratch := io.writeData }
    }
  }

  when(io.trapValid) {
    mepc := io.trapPc
    mcause := io.trapCode
    mtval := io.trapValue
  }

  io.trapVector := mtvec(p.addrBits - 1, 0)
  io.state := 0.U.asTypeOf(new CsrStateBundle)
  io.state.privilegeMode := 3.U
  io.state.mstatus := mstatus
  io.state.mepc := mepc
  io.state.mtval := mtval
  io.state.mtvec := mtvec
  io.state.mcause := mcause
  io.state.mip := mip
  io.state.mie := mie
  io.state.mscratch := mscratch

  diffCsr.coreid := 0.U
  diffCsr.privilegeMode := io.state.privilegeMode
  diffCsr.mstatus := io.state.mstatus
  diffCsr.sstatus := io.state.sstatus
  diffCsr.mepc := io.state.mepc
  diffCsr.sepc := io.state.sepc
  diffCsr.mtval := io.state.mtval
  diffCsr.stval := io.state.stval
  diffCsr.mtvec := io.state.mtvec
  diffCsr.stvec := io.state.stvec
  diffCsr.mcause := io.state.mcause
  diffCsr.scause := io.state.scause
  diffCsr.satp := io.state.satp
  diffCsr.mip := io.state.mip
  diffCsr.mie := io.state.mie
  diffCsr.mscratch := io.state.mscratch
  diffCsr.sscratch := io.state.sscratch
  diffCsr.mideleg := io.state.mideleg
  diffCsr.medeleg := io.state.medeleg
}
