package wiredrv

import chisel3._
import chisel3.util._

case class AddrRegion(base: BigInt, size: BigInt) {
  require(size > 0, "region size must be positive")
  require((size & (size - 1)) == 0, "region size must be power-of-two")
  def contains(addr: BigInt): Boolean = addr >= base && addr < (base + size)
}

case class CoreParams(
  xlen: Int = 64,
  addrBits: Int = 39,
  resetPc: BigInt = 0x80000000L,
  innerBusWidth: Int = 32,
  outerBusWidth: Int = 64,
  icacheSets: Int = 256,
  icacheWays: Int = 2,
  icacheLineBytes: Int = 64,
  dcacheSets: Int = 256,
  dcacheWays: Int = 2,
  dcacheLineBytes: Int = 64,
  longLatencySlots: Int = 4,
  cachedRegions: Seq[AddrRegion] = Seq(AddrRegion(0x80000000L, 0x10000000L)),
  uncachedRegions: Seq[AddrRegion] = Seq(AddrRegion(0x00000000L, 0x80000000L))
) {
  require(isPow2(innerBusWidth / 8), "innerBusWidth must be power-of-two bytes")
  require(isPow2(outerBusWidth / 8), "outerBusWidth must be power-of-two bytes")
  require(icacheWays == 2, "current frontend skeleton assumes 2-way icache")
  require(dcacheWays == 2, "current backend skeleton assumes 2-way dcache")
  require(icacheLineBytes >= 4 && (icacheLineBytes & (icacheLineBytes - 1)) == 0)
  require(dcacheLineBytes >= 8 && (dcacheLineBytes & (dcacheLineBytes - 1)) == 0)

  val icacheSetBits: Int = log2Ceil(icacheSets)
  val icacheLineBits: Int = log2Ceil(icacheLineBytes)
  val icacheTagBits: Int = addrBits - icacheSetBits - icacheLineBits

  def icacheGetTag(addr: UInt):  UInt = addr(addrBits - 1, icacheSetBits + icacheLineBits)
  def icacheGetSet(addr: UInt):  UInt = addr(icacheSetBits + icacheLineBits - 1, icacheLineBits)
  def icacheGetWord(addr: UInt): UInt = addr(icacheSetBits + icacheLineBits - 1, 2)

  val dcacheSetBits: Int = log2Ceil(dcacheSets)
  val dcacheLineBits: Int = log2Ceil(dcacheLineBytes)
  val dcacheTagBits: Int = addrBits - dcacheSetBits - dcacheLineBits

  val longLatencySlotsBits = log2Ceil(p.longLatencySlots)
}
