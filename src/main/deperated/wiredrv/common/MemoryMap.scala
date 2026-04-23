package wiredrv.common

import chisel3._

object MemoryMap {
  def hit(addr: UInt, regions: Seq[AddrRegion]): Bool = {
    if (regions.isEmpty) {
      false.B
    } else {
      VecInit(regions.map { region =>
        val base = region.base.U(addr.getWidth.W)
        val end = (region.base + region.size).U(addr.getWidth.W)
        addr >= base && addr < end
      }).asUInt.orR
    }
  }
}
