package wiredrv.neocore

import chisel3._
import chisel3.util._

// 指定总线请求类型
object BusOp extends ChiselEnum {
  // 1. 非原子操作（用于普通 Load / Store）
  val ReadOnly  = Value
  val StoreOnly = Value

  // 2. 原子操作（对应于 RISC-V AMO 指令和 AXI5 原子事务）
  // --- RISC-V 原子内存操作映射到 AXI5 AtomicStore 的部分 ---
  val Add  = Value          // ADD  对应 AMOADD 指令
  val Clr  = Value          // CLR  对应 AMOAND 指令 (已在 CPU 中取过反)
  val Eor  = Value          // EOR  对应 AMOXOR 指令
  val Set  = Value          // SET  对应 AMOOR  指令
  val SMax = Value          // SMax 对应 AMOMAX 指令
  val SMin = Value          // SMin 对应 AMOMIN 指令
  val UMax = Value          // UMax 对应 AMOMAXU 指令
  val UMin = Value          // UMin 对应 AMOMINU 指令
  // --- RISC-V 原子内存操作映射到 AXI5 AtomicSwap 的部分 ---
  val Swap = Value          // 对应 AMOSWAP 指令
  // --- RISC-V 原子内存操作映射到 AXI5 AtomicCompare 的部分 ---
  val CAS  = Value          // 对应 AMOCAS 指令 (Zacas 扩展)
  // 3. CMO 操作
  val Flush     = Value     // 刷新缓存行，不写回，注意是刷新所有路的 Tag
  // val Writeback = Value     // 刷新缓存行，有脏数据则写回，注意指定路刷新(Note: Broken, 建议不要使用)
}


class BusA(width: Int)(implicit p: CoreParams) extends Bundle {
  val op = BusOp()
  val id = UInt(4.W) // Source (0: InstCache, 1: DataCache, 4,5,6,7: Uncached)
  val size = UInt(4.W) // Bytes - 1
  val address = UInt(p.addrBits.W)
  val mask = UInt((width / 8).W)
  val data = UInt(width.W)
}

class BusD(width: Int)(implicit p: CoreParams) extends Bundle {
  val id = UInt(4.W) // Source (0: InstCache, 1: DataCache, 4,5,6,7: Uncached)
  val size = UInt(4.W) // Bytes
  val data = UInt(width.W)
}

class BusBundle(width: Int)(implicit p: CoreParams) extends Bundle {
  val a = Decoupled(new BusA(width))
  val d = Flipped(Decoupled(new BusD(width)))
}