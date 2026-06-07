#include "riscv_test.h"
#include "test_macros.h"

RVTEST_RV64U
RVTEST_CODE_BEGIN

  li TESTNUM, 1

  // tx_mat_sb_bar(x0): writes original x0 back, with no effective GPR writeback.
  .word 0x0800500b

  li t0, 1
  addi t0, t0, 1
  li t1, 2
  bne t0, t1, fail

  li TESTNUM, 2

  li t2, 0x1020304050607080
  // tx_mat_sb_bar(t2): writes back the original gpr[rd] value after completion.
  .word 0x0800538b

  li t4, 0x1020304050607080
  bne t2, t4, fail
  addi t3, t2, 1
  li t4, 0x1020304050607081
  bne t3, t4, fail

pass:
  RVTEST_PASS

fail:
  RVTEST_FAIL

RVTEST_CODE_END

.data
RVTEST_DATA_BEGIN
RVTEST_DATA_END
