#include "riscv_test.h"
#include "test_macros.h"

RVTEST_RV64U
RVTEST_CODE_BEGIN

  li TESTNUM, 1

  li t2, 0

  // tx_mat_tcsr_read<0>(t2): read-class tensor issues write back through
  // tensorResp.
  .word 0x0000438b

  li t4, 0x1111222233334444
  bne t2, t4, fail

  li TESTNUM, 2
  addi t3, t2, 1
  li t4, 0x1111222233334445
  bne t3, t4, fail

  li TESTNUM, 3

  li t2, 0x1020304050607080

  // tx_mat_sb_bar(t2): barrier completion writes back the original gpr[rd] value.
  .word 0x0800538b

  li t4, 0x1020304050607080
  bne t2, t4, fail

  li TESTNUM, 4
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
