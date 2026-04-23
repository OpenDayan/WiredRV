package wiredrv.bus

import chisel3._
import lmss.axi._
import lmss.param.{AddressParams, PortParams}
import wiredrv.CoreParams

class LmssAxiXbar(mstParams: Seq[AxiParams])(implicit p: CoreParams)
  extends BaseAxiXbar(
    mstParams = mstParams,
    memParams = Seq(
      PortParams(
        addr = AddressParams(base = 0L, size = 1L << p.addrBits)
      )
    )
  ) {
  override def slvMatchersSeq: Seq[UInt => Bool] = Seq(_ => true.B)
  initialize()
}
