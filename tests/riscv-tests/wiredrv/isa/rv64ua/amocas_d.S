#include "riscv_test.h"
#include "test_macros.h"

#define AMOCAS_D(rd, rs1, rs2) \
  .word ((0x05 << 27) | ((rs2) << 20) | ((rs1) << 15) | (0x3 << 12) | ((rd) << 7) | 0x2f)

RVTEST_RV64U
RVTEST_CODE_BEGIN

  li t0, 0x80001010

  TEST_CASE(2, a4, 0x0123456789abcdef, \
    li a0, 0x0123456789abcdef; \
    li a1, 0xfedcba9876543210; \
    sd a0, 0(t0); \
    li a4, 0x0123456789abcdef; \
    AMOCAS_D(14, 5, 11); \
  )

  TEST_CASE(3, a5, 0xfedcba9876543210, ld a5, 0(t0))

  TEST_CASE(4, a4, 0xfedcba9876543210, \
    li a1, 0x1111222233334444; \
    li a4, 0x5555666677778888; \
    AMOCAS_D(14, 5, 11); \
  )

  TEST_CASE(5, a5, 0xfedcba9876543210, ld a5, 0(t0))

  TEST_PASSFAIL

RVTEST_CODE_END

.data
RVTEST_DATA_BEGIN
  TEST_DATA
RVTEST_DATA_END
