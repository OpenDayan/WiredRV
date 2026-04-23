package wiredrv.frontend

import chisel3._
import chisel3.util._
import wiredrv.common._

class FrontendIO(implicit p: CoreParams) extends Bundle {
  val inst = Decoupled(new FetchPacket)
  val redirects = Input(Vec(5, Valid(new RedirectReq)))
  val invalidate = Input(Bool())
  val bus = new TLBundleUH
}

class Frontend(implicit p: CoreParams) extends Module {
  val io = IO(new FrontendIO)

  val nextPc = Module(new NextPC)
  val icache = Module(new ICache)
  val refill = Module(new IRefill)
  val instQueue = Module(new Queue(new FetchPacket, 1, flow = true, pipe = false))

  val fetchPc = RegInit(p.resetPc.U(p.addrBits.W))
  val reqOutstanding = RegInit(false.B)
  val dropResp = RegInit(false.B)
  val rrWay = RegInit(0.U(log2Ceil(p.icacheWays).W))

  nextPc.io.currentPc := fetchPc

  val frontendRedirects = Wire(Vec(5, Valid(new RedirectReq)))
  frontendRedirects := io.redirects
  frontendRedirects(RedirectCause.frontendMissReplay).valid := refill.io.replay.valid
  frontendRedirects(RedirectCause.frontendMissReplay).bits.target := refill.io.replay.bits
  frontendRedirects(RedirectCause.frontendMissReplay).bits.cause := RedirectCause.frontendMissReplay
  nextPc.io.redirects := frontendRedirects

  val redirectFire = nextPc.io.selected.valid
  val canIssueReq = !refill.io.busy && instQueue.io.enq.ready
  val skidFlush = redirectFire

  icache.io.req.valid := canIssueReq
  icache.io.req.bits := fetchPc
  icache.io.refill := refill.io.refill
  icache.io.invalidate := io.invalidate

  when(icache.io.req.valid) {
    reqOutstanding := true.B
  }
  when(icache.io.resp.valid) {
    reqOutstanding := false.B
  }
  when(redirectFire && (reqOutstanding || icache.io.req.valid)) {
    dropResp := true.B
  }.elsewhen(icache.io.resp.valid) {
    dropResp := false.B
  }

  val instSel = icache.io.resp.bits.pc(3, 2)
  val instVec = icache.io.resp.bits.line.asTypeOf(Vec(p.icacheLineBytes / 4, UInt(32.W)))
  val predictedTarget = icache.io.resp.bits.pc + 4.U
  val respUsable = icache.io.resp.valid && !dropResp

  when(redirectFire || canIssueReq) {
    fetchPc := nextPc.io.nextPc
  }

  val missReq = Wire(Decoupled(new IRefillReq))
  missReq.valid := respUsable && !icache.io.resp.bits.hit && !refill.io.busy
  missReq.bits.missPc := icache.io.resp.bits.pc
  missReq.bits.setIdx := icache.io.resp.bits.pc(p.icacheLineBits + p.icacheSetBits - 1, p.icacheLineBits)
  missReq.bits.tag := icache.io.resp.bits.pc(p.addrBits - 1, p.icacheLineBits + p.icacheSetBits)
  missReq.bits.replaceWay := rrWay
  refill.io.req <> missReq

  when(missReq.fire) {
    rrWay := ~rrWay
  }

  val enqPacket = Wire(new FetchPacket)
  enqPacket.pc := icache.io.resp.bits.pc
  enqPacket.inst := instVec(instSel)
  enqPacket.jump := false.B
  enqPacket.target := predictedTarget

  instQueue.io.enq.valid := respUsable && icache.io.resp.bits.hit
  instQueue.io.enq.bits := enqPacket
  instQueue.io.deq.ready := io.inst.ready || skidFlush

  io.inst.valid := instQueue.io.deq.valid
  io.inst.bits := instQueue.io.deq.bits
  io.bus <> refill.io.bus
}
