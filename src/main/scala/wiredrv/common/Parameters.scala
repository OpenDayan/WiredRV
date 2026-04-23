package wiredrv.common

import chisel3.util.log2Ceil

case class AddrRegion(base: BigInt, size: BigInt) {
  require(size > 0, "region size must be positive")
  require((size & (size - 1)) == 0, "region size must be power-of-two")
  def contains(addr: BigInt): Boolean = addr >= base && addr < (base + size)
}

case class CoreParams(
  xlen: Int = 64,
  addrBits: Int = 40,
  resetPc: BigInt = 0x80000000L,
  icacheSets: Int = 64,
  icacheWays: Int = 2,
  icacheLineBytes: Int = 64,
  dcacheSets: Int = 64,
  dcacheWays: Int = 2,
  dcacheLineBytes: Int = 64,
  storeQueueDepth: Int = 4,
  longLatencySlots: Int = 3,
  cachedRegions: Seq[AddrRegion] = Seq(AddrRegion(0x80000000L, 0x10000000L)),
  uncachedRegions: Seq[AddrRegion] = Seq(AddrRegion(0x00000000L, 0x80000000L))
) {
  require(icacheWays == 2, "current frontend skeleton assumes 2-way icache")
  require(dcacheWays == 2, "current backend skeleton assumes 2-way dcache")
  require(icacheLineBytes >= 4 && (icacheLineBytes & (icacheLineBytes - 1)) == 0)
  require(dcacheLineBytes >= 8 && (dcacheLineBytes & (dcacheLineBytes - 1)) == 0)

  val icacheSetBits: Int = log2Ceil(icacheSets)
  val icacheLineBits: Int = log2Ceil(icacheLineBytes)
  val icacheTagBits: Int = addrBits - icacheSetBits - icacheLineBits
  val dcacheSetBits: Int = log2Ceil(dcacheSets)
  val dcacheLineBits: Int = log2Ceil(dcacheLineBytes)
  val dcacheTagBits: Int = addrBits - dcacheSetBits - dcacheLineBits
}
