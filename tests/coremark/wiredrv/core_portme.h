#ifndef WIREDRV_CORE_PORTME_H
#define WIREDRV_CORE_PORTME_H

#include <stddef.h>
#include <stdint.h>

#ifndef HAS_FLOAT
#define HAS_FLOAT 1
#endif

#ifndef HAS_TIME_H
#define HAS_TIME_H 0
#endif

#ifndef USE_CLOCK
#define USE_CLOCK 0
#endif

#ifndef HAS_STDIO
#define HAS_STDIO 0
#endif

#ifndef HAS_PRINTF
#define HAS_PRINTF 0
#endif

#ifndef TOTAL_DATA_SIZE
#define TOTAL_DATA_SIZE 2000
#endif

#ifndef COMPILER_VERSION
#ifdef __GNUC__
#define COMPILER_VERSION "GCC " __VERSION__
#else
#define COMPILER_VERSION "unknown compiler"
#endif
#endif

#ifndef COMPILER_FLAGS
#define COMPILER_FLAGS "WiredRV baremetal CoreMark flags"
#endif

#ifndef MEM_LOCATION
#define MEM_LOCATION "Static data in simulated RAM"
#endif

typedef int16_t   ee_s16;
typedef uint16_t  ee_u16;
typedef int32_t   ee_s32;
typedef double    ee_f32;
typedef uint8_t   ee_u8;
typedef uint32_t  ee_u32;
typedef uintptr_t ee_ptr_int;
typedef size_t    ee_size_t;

#define align_mem(x) (void *)(4 + (((ee_ptr_int)(x)-1) & ~3))

#define CORETIMETYPE uint64_t
typedef uint64_t CORE_TICKS;

#ifndef COREMARK_CPU_HZ
#define COREMARK_CPU_HZ 1000000ULL
#endif

#ifndef SEED_METHOD
#define SEED_METHOD SEED_VOLATILE
#endif

#ifndef MEM_METHOD
#define MEM_METHOD MEM_STATIC
#endif

#ifndef MULTITHREAD
#define MULTITHREAD 1
#define USE_PTHREAD 0
#define USE_FORK    0
#define USE_SOCKET  0
#endif

#ifndef MAIN_HAS_NOARGC
#define MAIN_HAS_NOARGC 1
#endif

#ifndef MAIN_HAS_NORETURN
#define MAIN_HAS_NORETURN 0
#endif

extern ee_u32 default_num_contexts;

typedef struct CORE_PORTABLE_S
{
    ee_u8 portable_id;
} core_portable;

void portable_init(core_portable *p, int *argc, char *argv[]);
void portable_fini(core_portable *p);
int  ee_printf(const char *fmt, ...);

#if !defined(PROFILE_RUN) && !defined(PERFORMANCE_RUN) \
    && !defined(VALIDATION_RUN)
#if (TOTAL_DATA_SIZE == 1200)
#define PROFILE_RUN 1
#elif (TOTAL_DATA_SIZE == 2000)
#define PERFORMANCE_RUN 1
#else
#define VALIDATION_RUN 1
#endif
#endif

#endif
