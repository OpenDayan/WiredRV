#include "riscv_test.h"
#include "test_macros.h"

#define AMOCAS_W(rd, rs1, rs2) \
  .word ((0x05 << 27) | ((rs2) << 20) | ((rs1) << 15) | (0x2 << 12) | ((rd) << 7) | 0x2f)

RVTEST_RV64U
RVTEST_CODE_BEGIN

  li t0, 0x80001000

  TEST_CASE(2, a4, 0x0000000012345678, \
    li a0, 0x12345678; \
    li a1, 0x89abcdef; \
    sw a0, 0(t0); \
    li a4, 0x12345678; \
    AMOCAS_W(14, 5, 11); \
  )

  TEST_CASE(3, a5, 0xffffffff89abcdef, lw a5, 0(t0))

  TEST_CASE(4, a4, 0xffffffff89abcdef, \
    li a1, 0x22222222; \
    li a4, 0x11111111; \
    AMOCAS_W(14, 5, 11); \
  )

  TEST_CASE(5, a5, 0xffffffff89abcdef, lw a5, 0(t0))

  TEST_CASE(6, a4, 0x000000007fffffff, \
    li a0, 0x7fffffff; \
    li a1, 0x80000000; \
    sw a0, 4(t0); \
    li a4, 0x7fffffff; \
    addi t1, t0, 4; \
    AMOCAS_W(14, 6, 11); \
  )

  TEST_CASE(7, a5, 0xffffffff80000000, lw a5, 4(t0))

  TEST_PASSFAIL

RVTEST_CODE_END

.data
RVTEST_DATA_BEGIN
  TEST_DATA
RVTEST_DATA_END
