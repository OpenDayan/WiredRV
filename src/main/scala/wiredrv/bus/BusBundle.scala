/*
 * Copyright (c) 2026 Institute of Computing Technology, Chinese Academy of Sciences
 * SPDX-License-Identifier: MulanPSL-2.0
 *
 * Dayan is licensed under the Mulan PSL v2.
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

package dayan.wiredrv.bus

import chisel3._
import chisel3.util._
import dayan.wiredrv.CoreParams

object BusOp extends ChiselEnum {
  val ReadOnly = Value
  val StoreOnly = Value
  val Add = Value
  val Clr = Value
  val Eor = Value
  val Set = Value
  val SMax = Value
  val SMin = Value
  val UMax = Value
  val UMin = Value
  val Swap = Value
  val CAS = Value
  val Flush = Value
}

class BusA(width: Int)(implicit p: CoreParams) extends Bundle {
  val op = BusOp()
  val id = UInt(4.W)
  val size = UInt(4.W) // log2 Bytes
  val address = UInt(p.addrBits.W)
  val mask = UInt((width / 8).W)
  val data = UInt(width.W)
  val compare = UInt(width.W)
}

class BusD(width: Int)(implicit p: CoreParams) extends Bundle {
  val id = UInt(4.W)
  val size = UInt(4.W)
  val data = UInt(width.W)
}

class BusBundle(width: Int)(implicit p: CoreParams) extends Bundle {
  val a = Decoupled(new BusA(width))
  val d = Flipped(Decoupled(new BusD(width)))
}
