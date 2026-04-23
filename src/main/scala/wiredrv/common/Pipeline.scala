package wiredrv.common

import chisel3._

object PipeCtrl {
  def fire(valid: Bool, ready: Bool): Bool = valid && ready
  def hold(valid: Bool, ready: Bool): Bool = valid && !ready
}
