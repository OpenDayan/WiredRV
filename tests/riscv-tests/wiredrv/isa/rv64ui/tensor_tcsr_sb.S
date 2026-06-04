#include "riscv_test.h"
#include "test_macros.h"

RVTEST_RV64U
RVTEST_CODE_BEGIN

  li TESTNUM, 1

  li t2, 0x0feedface1234567
  // tx_mat_tcsr_write<0>(t2): rd is a source data register, not writeback rd.
  .word 0x0000038b

  li t4, 0x0feedface1234567
  bne t2, t4, fail

  li TESTNUM, 2

  li t2, 0x0feedface1234567
  // tx_vec_tcsr_write<0>(t2)
  .word 0x0200038b

  li t4, 0x0feedface1234567
  bne t2, t4, fail

  li TESTNUM, 3

  li t2, 0x0feedface1234567
  // tx_lsu_tcsr_write<0>(t2)
  .word 0x0400038b

  li t4, 0x0feedface1234567
  bne t2, t4, fail

  li TESTNUM, 4

  li t2, 0
  // tx_mat_tcsr_read<0>(t2): true generated encoding uses funct3=100.
  .word 0x0000438b

  li t4, 0x1111222233334444
  bne t2, t4, fail
  addi t3, t2, 1
  li t4, 0x1111222233334445
  bne t3, t4, fail

  li TESTNUM, 5

  li t2, 0
  // tx_vec_tcsr_read<0>(t2)
  .word 0x0200438b

  li t4, 0x5555666677778888
  bne t2, t4, fail
  addi t3, t2, 1
  li t4, 0x5555666677778889
  bne t3, t4, fail

  li TESTNUM, 6

  li t2, 0
  // tx_lsu_tcsr_read<0>(t2)
  .word 0x0400438b

  li t4, 0x9999aaaabbbbcccc
  bne t2, t4, fail
  addi t3, t2, 1
  li t4, 0x9999aaaabbbbcccd
  bne t3, t4, fail

  li TESTNUM, 7

  li t2, 0x0feedface1234567
  // tx_mat_sb_produce(t2)
  .word 0x0800038b

  li t4, 0x0feedface1234567
  bne t2, t4, fail

  li TESTNUM, 8

  li t2, 0x0feedface1234567
  // tx_vec_sb_produce(t2)
  .word 0x0a00038b

  li t4, 0x0feedface1234567
  bne t2, t4, fail

  li TESTNUM, 9

  li t2, 0x0feedface1234567
  // tx_lsu_sb_produce(t2)
  .word 0x0c00038b

  li t4, 0x0feedface1234567
  bne t2, t4, fail

  li TESTNUM, 10

  li t2, 0
  // tx_mat_sb_bar(t2)
  .word 0x0800538b

  li t4, 0x1020304050607080
  bne t2, t4, fail
  addi t3, t2, 1
  li t4, 0x1020304050607081
  bne t3, t4, fail

  li TESTNUM, 11

  li t2, 0
  // tx_vec_sb_bar(t2)
  .word 0x0a00538b

  li t4, 0x1122334455667788
  bne t2, t4, fail
  addi t3, t2, 1
  li t4, 0x1122334455667789
  bne t3, t4, fail

  li TESTNUM, 12

  li t2, 0
  // tx_lsu_sb_bar(t2)
  .word 0x0c00538b

  li t4, 0x8877665544332211
  bne t2, t4, fail
  addi t3, t2, 1
  li t4, 0x8877665544332212
  bne t3, t4, fail

  li TESTNUM, 13

  li t2, 0
  // tx_sb_get_any(t2)
  .word 0x0e00438b

  li t4, 0x0f0e0d0c0b0a0908
  bne t2, t4, fail
  addi t3, t2, 1
  li t4, 0x0f0e0d0c0b0a0909
  bne t3, t4, fail

pass:
  RVTEST_PASS

fail:
  RVTEST_FAIL

RVTEST_CODE_END

.data
RVTEST_DATA_BEGIN
RVTEST_DATA_END
