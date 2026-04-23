package wiredrv.common

import chisel3._
import chisel3.util._

class FetchPacket(implicit p: CoreParams) extends Bundle {
  val pc = UInt(p.addrBits.W)
  val inst = UInt(32.W)
  val jump = Bool()
  val target = UInt(p.addrBits.W)
}

object RedirectCause {
  val frontendMissReplay :: jumpEarlyDecode :: exuFlush :: backendReplayOrException :: nlp :: Nil = Enum(5)
}

class RedirectReq(implicit p: CoreParams) extends Bundle {
  val target = UInt(p.addrBits.W)
  val cause = UInt(3.W)
}

class TLUHChannelA(implicit p: CoreParams) extends Bundle {
  val opcode = UInt(3.W)
  val param = UInt(3.W)
  val size = UInt(4.W)
  val source = UInt(4.W)
  val address = UInt(p.addrBits.W)
  val mask = UInt((p.xlen / 8).W)
  val data = UInt(p.xlen.W)
  val corrupt = Bool()
}

class TLUHChannelD(implicit p: CoreParams) extends Bundle {
  val opcode = UInt(3.W)
  val param = UInt(2.W)
  val size = UInt(4.W)
  val source = UInt(4.W)
  val sink = UInt(2.W)
  val denied = Bool()
  val data = UInt(p.xlen.W)
  val corrupt = Bool()
}

class TLBundleUH(implicit p: CoreParams) extends Bundle {
  val a = Decoupled(new TLUHChannelA)
  val d = Flipped(Decoupled(new TLUHChannelD))
}

object TLBundleUH {
  val PutFullData: UInt = 0.U(3.W)
  val PutPartialData: UInt = 1.U(3.W)
  val Get: UInt = 4.U(3.W)

  def tieoffMaster(bus: TLBundleUH): Unit = {
    bus.a.valid := false.B
    bus.a.bits := 0.U.asTypeOf(bus.a.bits)
    bus.d.ready := true.B
  }
}

class ICacheResp(implicit p: CoreParams) extends Bundle {
  val pc = UInt(p.addrBits.W)
  val hit = Bool()
  val waymask = UInt(p.icacheWays.W)
  val line = UInt((p.icacheLineBytes * 8).W)
}

class ICacheRefillWrite(implicit p: CoreParams) extends Bundle {
  val setIdx = UInt(p.icacheSetBits.W)
  val tag = UInt(p.icacheTagBits.W)
  val way = UInt(log2Ceil(p.icacheWays).W)
  val line = UInt((p.icacheLineBytes * 8).W)
}

class IRefillReq(implicit p: CoreParams) extends Bundle {
  val missPc = UInt(p.addrBits.W)
  val setIdx = UInt(p.icacheSetBits.W)
  val tag = UInt(p.icacheTagBits.W)
  val replaceWay = UInt(log2Ceil(p.icacheWays).W)
}

class DCacheResp(implicit p: CoreParams) extends Bundle {
  val addr = UInt(p.addrBits.W)
  val hit = Bool()
  val waymask = UInt(p.dcacheWays.W)
  val line = UInt((p.dcacheLineBytes * 8).W)
}

class DCacheRefillWrite(implicit p: CoreParams) extends Bundle {
  val setIdx = UInt(p.dcacheSetBits.W)
  val tag = UInt(p.dcacheTagBits.W)
  val way = UInt(log2Ceil(p.dcacheWays).W)
  val line = UInt((p.dcacheLineBytes * 8).W)
}

class DCacheWriteReq(implicit p: CoreParams) extends Bundle {
  val setIdx = UInt(p.dcacheSetBits.W)
  val way = UInt(log2Ceil(p.dcacheWays).W)
  val wordOffset = UInt((p.dcacheLineBits - log2Ceil(p.xlen / 8)).W)
  val data = UInt(p.xlen.W)
  val mask = UInt((p.xlen / 8).W)
}

class DRefillReq(implicit p: CoreParams) extends Bundle {
  val addr = UInt(p.addrBits.W)
  val setIdx = UInt(p.dcacheSetBits.W)
  val tag = UInt(p.dcacheTagBits.W)
  val replaceWay = UInt(log2Ceil(p.dcacheWays).W)
  val op = UInt(2.W)
}

class DRefillResp(implicit p: CoreParams) extends Bundle {
  val line = UInt((p.dcacheLineBytes * 8).W)
  val exception = Bool()
  val exceptionCode = UInt(16.W)
}

class DCachePipeResp(implicit p: CoreParams) extends Bundle {
  val addr = UInt(p.addrBits.W)
  val data = UInt(p.xlen.W)
  val hit = Bool()
  val miss = Bool()
  val waymask = UInt(p.dcacheWays.W)
  val exception = Bool()
  val exceptionCode = UInt(16.W)
}

class DecodeInfo extends Bundle {
  val src1Type = UInt(3.W)
  val src2Type = UInt(3.W)
  val fuType = UInt(4.W)
  val fuOpType = UInt(8.W)
  val rfWen = Bool()
  val readRd = Bool()
  val isRVT64 = Bool()
  val isRVT32 = Bool()
  val selImm = UInt(4.W)
  val isLoad = Bool()
  val isStore = Bool()
  val isCacheMaintain = Bool()
  val cacheMaintainOp = UInt(2.W)
  val isBranch = Bool()
  val isJump = Bool()
  val isCsr = Bool()
  val isMul = Bool()
  val isDiv = Bool()
  val isAtomic = Bool()
  val isFence = Bool()
  val flushPipe = Bool()
  val illegal = Bool()
}

class DecodedInst(implicit p: CoreParams) extends Bundle {
  val pc = UInt(p.addrBits.W)
  val inst = UInt(32.W)
  val info = new DecodeInfo
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val rd = UInt(5.W)
  val imm = UInt(p.xlen.W)
  val csrAddr = UInt(12.W)
  val exceptionCode = UInt(16.W)
  val isUncached = Bool()
}

class ExecuteIn(implicit p: CoreParams) extends Bundle {
  val pc = UInt(p.addrBits.W)
  val inst = UInt(32.W)
  val info = new DecodeInfo
  val src1 = UInt(p.xlen.W)
  val src2 = UInt(p.xlen.W)
  val rdData = UInt(p.xlen.W)
  val csrReadData = UInt(p.xlen.W)
  val imm = UInt(p.xlen.W)
  val rd = UInt(5.W)
  val isUncached = Bool()
}

class ExecuteOut(implicit p: CoreParams) extends Bundle {
  val pc = UInt(p.addrBits.W)
  val inst = UInt(32.W)
  val rd = UInt(5.W)
  val result = UInt(p.xlen.W)
  val exception = Bool()
  val exceptionCode = UInt(16.W)
  val invalidateIcache = Bool()
  val redirect = Valid(new RedirectReq)
}

class StageD(implicit p: CoreParams) extends Bundle {
  val fetch = new FetchPacket
  val decoded = new DecodedInst
  val src1 = UInt(p.xlen.W)
  val src2 = UInt(p.xlen.W)
  val rdData = UInt(p.xlen.W)
}

class StageE(implicit p: CoreParams) extends Bundle {
  val decoded = new DecodedInst
  val src1 = UInt(p.xlen.W)
  val src2 = UInt(p.xlen.W)
  val rdData = UInt(p.xlen.W)
  val dcache = new DCachePipeResp
  val exec = new ExecuteOut
}

class StageW(implicit p: CoreParams) extends Bundle {
  val decoded = new DecodedInst
  val src1 = UInt(p.xlen.W)
  val src2 = UInt(p.xlen.W)
  val rdData = UInt(p.xlen.W)
  val dcache = new DCachePipeResp
  val exec = new ExecuteOut
}

class CommitInfo(implicit p: CoreParams) extends Bundle {
  val valid = Bool()
  val pc = UInt(p.addrBits.W)
  val inst = UInt(32.W)
  val rfWen = Bool()
  val rd = UInt(5.W)
  val data = UInt(p.xlen.W)
  val isLoad = Bool()
  val isStore = Bool()
  val skip = Bool()
}

class TrapInfo(implicit p: CoreParams) extends Bundle {
  val valid = Bool()
  val code = UInt(p.xlen.W)
  val pc = UInt(p.addrBits.W)
}

class CsrStateBundle extends Bundle {
  val privilegeMode = UInt(64.W)
  val mstatus = UInt(64.W)
  val sstatus = UInt(64.W)
  val mepc = UInt(64.W)
  val sepc = UInt(64.W)
  val mtval = UInt(64.W)
  val stval = UInt(64.W)
  val mtvec = UInt(64.W)
  val stvec = UInt(64.W)
  val mcause = UInt(64.W)
  val scause = UInt(64.W)
  val satp = UInt(64.W)
  val mip = UInt(64.W)
  val mie = UInt(64.W)
  val mscratch = UInt(64.W)
  val sscratch = UInt(64.W)
  val mideleg = UInt(64.W)
  val medeleg = UInt(64.W)
}

class StoreQueueEntry(implicit p: CoreParams) extends Bundle {
  val addr = UInt(p.addrBits.W)
  val way = UInt(log2Ceil(p.dcacheWays).W)
  val data = UInt(p.xlen.W)
  val mask = UInt((p.xlen / 8).W)
  val uncached = Bool()
  val cacheWritten = Bool()
}

class LSUReq(implicit p: CoreParams) extends Bundle {
  val addr = UInt(p.addrBits.W)
  val funct3 = UInt(3.W)
  val isLoad = Bool()
  val isStore = Bool()
  val isCacheMaintain = Bool()
  val cacheMaintainOp = UInt(2.W)
  val miss = Bool()
  val waymask = UInt(p.dcacheWays.W)
  val data = UInt(p.xlen.W)
  val uncached = Bool()
}

class LSUResp(implicit p: CoreParams) extends Bundle {
  val data = UInt(p.xlen.W)
  val exception = Bool()
  val exceptionCode = UInt(16.W)
}

class StoreQueueStatus(implicit p: CoreParams) extends Bundle {
  val valid = Bool()
  val entry = new StoreQueueEntry
}

class FixedSlot extends Bundle {
  val valid = Bool()
  val rd = UInt(5.W)
  val isLoad = Bool()
}

class LongSlot extends Bundle {
  val valid = Bool()
  val rd = UInt(5.W)
  val token = UInt(3.W)
}
