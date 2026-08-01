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
  maxHartIdBits: Int = 1,
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
  require(maxHartIdBits > 0 && maxHartIdBits <= xlen, "maxHartIdBits must fit in xlen")
  require(icacheWays == 2, "current frontend skeleton assumes 2-way icache")
  require(dcacheWays == 2, "current backend skeleton assumes 2-way dcache")
  require(icacheLineBytes >= 4 && (icacheLineBytes & (icacheLineBytes - 1)) == 0)
  require(dcacheLineBytes >= 8 && (dcacheLineBytes & (dcacheLineBytes - 1)) == 0)

  val icacheSetBits: Int = log2Ceil(icacheSets)
  val icacheLineBits: Int = log2Ceil(icacheLineBytes)
  val icacheTagBits: Int = addrBits - icacheSetBits - icacheLineBits

  def icacheGetTag(addr: UInt):  UInt = addr(addrBits - 1, icacheSetBits + icacheLineBits)
  def icacheGetSet(addr: UInt):  UInt = addr(icacheSetBits + icacheLineBits - 1, icacheLineBits)
  def icacheGetWord(addr: UInt): UInt = addr(icacheLineBits - 1, 2)

  val dcacheSetBits: Int = log2Ceil(dcacheSets)
  val dcacheLineBits: Int = log2Ceil(dcacheLineBytes)
  val dcacheTagBits: Int = addrBits - dcacheSetBits - dcacheLineBits

  def dcacheGetTag(addr: UInt):  UInt = addr(addrBits - 1, dcacheSetBits + dcacheLineBits)
  def dcacheGetSet(addr: UInt):  UInt = addr(dcacheSetBits + dcacheLineBits - 1, dcacheLineBits)
  def dcacheGetWord(addr: UInt): UInt = addr(dcacheLineBits - 1, log2Ceil(xlen / 8))

  val longLatencySlotsBits = log2Ceil(longLatencySlots)
}
