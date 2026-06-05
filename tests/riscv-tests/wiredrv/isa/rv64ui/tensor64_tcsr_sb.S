#include "riscv_test.h"
#include "test_macros.h"

RVTEST_RV64U
RVTEST_CODE_BEGIN

  li TESTNUM, 1

  li t0, 0x13579bdf2468ace0
  // __tx_tcsr_write<0>(t0): func=0x80, rd=x0, rs1=t0, rs3=x0.
  .align 3
  .word 0x0002803f
  .word 0x80000000

  li t4, 0x13579bdf2468ace0
  bne t0, t4, fail

  li TESTNUM, 2

  li t0, 0x13579bdf2468ace0
  li t2, 0
  // __tx_tcsr_xchg<0>(t0, t2): func=0x80, rd=t2, rs1=t0, rs3=x0.
  .align 3
  .word 0x000283bf
  .word 0x80000000

  li t4, 0x2468ace013579bdf
  bne t2, t4, fail
  addi t3, t2, 1
  li t4, 0x2468ace013579be0
  bne t3, t4, fail

  li TESTNUM, 3

  li t0, 0x13579bdf2468ace0
  li t1, 0x0f1e2d3c4b5a6978
  li t2, 0
  // __tx_tcsr_xchg_mask<0>(t0, t1, t2): func=0x81, rd=t2, rs1=t0, rs3=t1.
  .align 3
  .word 0x300283bf
  .word 0x81000000

  li t4, 0x0a0b0c0d0e0f1011
  bne t2, t4, fail
  addi t3, t2, 1
  li t4, 0x0a0b0c0d0e0f1012
  bne t3, t4, fail

  li TESTNUM, 4

  li t2, 0x0feedface1234567
  // __tx_sb_produce(t2): func=0xff, sub=0, rs3=t2.
  .align 3
  .word 0x3800003f
  .word 0xff000000

  li t4, 0x0feedface1234567
  bne t2, t4, fail

  li TESTNUM, 5

  li t0, 0x13579bdf2468ace0
  // __tx_sb_bar(): func=0xff, sub=1. The old builder follows it with
  // andi t0, t0, 0 to clear the temporary register it encoded as rd/rs3.
  .align 3
  .word 0x280002bf
  .word 0xff100000
  andi t0, t0, 0

  bne t0, zero, fail

pass:
  RVTEST_PASS

fail:
  RVTEST_FAIL

RVTEST_CODE_END

.data
RVTEST_DATA_BEGIN
RVTEST_DATA_END
