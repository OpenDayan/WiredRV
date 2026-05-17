#include "coremark.h"
#include "core_portme.h"

#if VALIDATION_RUN
volatile ee_s32 seed1_volatile = 0x3415;
volatile ee_s32 seed2_volatile = 0x3415;
volatile ee_s32 seed3_volatile = 0x66;
#endif
#if PERFORMANCE_RUN
volatile ee_s32 seed1_volatile = 0x0;
volatile ee_s32 seed2_volatile = 0x0;
volatile ee_s32 seed3_volatile = 0x66;
#endif
#if PROFILE_RUN
volatile ee_s32 seed1_volatile = 0x8;
volatile ee_s32 seed2_volatile = 0x8;
volatile ee_s32 seed3_volatile = 0x8;
#endif

volatile ee_s32 seed4_volatile = ITERATIONS;
volatile ee_s32 seed5_volatile = 0;

static CORE_TICKS start_time_val;
static CORE_TICKS stop_time_val;

ee_u32 default_num_contexts = 1;

static CORE_TICKS read_mcycle(void)
{
    CORE_TICKS value;
    __asm__ volatile("csrr %0, mcycle" : "=r"(value));
    return value;
}

void start_time(void)
{
    start_time_val = read_mcycle();
}

void stop_time(void)
{
    stop_time_val = read_mcycle();
}

CORE_TICKS get_time(void)
{
    return stop_time_val - start_time_val;
}

secs_ret time_in_secs(CORE_TICKS ticks)
{
    return ((secs_ret)ticks) / (secs_ret)COREMARK_CPU_HZ;
}

void portable_init(core_portable *p, int *argc, char *argv[])
{
    (void)argc;
    (void)argv;

    if (sizeof(ee_ptr_int) != sizeof(ee_u8 *))
    {
        ee_printf("ERROR! ee_ptr_int must hold a pointer.\n");
    }
    if (sizeof(ee_u32) != 4)
    {
        ee_printf("ERROR! ee_u32 must be 32 bits.\n");
    }
    p->portable_id = 1;
}

void portable_fini(core_portable *p)
{
    p->portable_id = 0;
}
