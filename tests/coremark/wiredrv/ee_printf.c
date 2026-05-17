#include <stdarg.h>
#include "coremark.h"

#define WIREDRV_SIM_UART ((volatile unsigned char *)0x80001ff0UL)

static void uart_putchar(char c)
{
    *WIREDRV_SIM_UART = (unsigned char)c;
}

static void puts_raw(const char *s, int *count)
{
    while (*s)
    {
        uart_putchar(*s++);
        (*count)++;
    }
}

static void put_padding(char ch, int width, int len, int *count)
{
    while (width > len)
    {
        uart_putchar(ch);
        (*count)++;
        width--;
    }
}

static int format_unsigned(char *buf, unsigned long value, unsigned base, int uppercase)
{
    const char *digits = uppercase ? "0123456789ABCDEF" : "0123456789abcdef";
    char tmp[32];
    int len = 0;

    if (value == 0)
    {
        buf[0] = '0';
        return 1;
    }

    while (value != 0)
    {
        tmp[len++] = digits[value % base];
        value /= base;
    }

    for (int i = 0; i < len; i++)
    {
        buf[i] = tmp[len - i - 1];
    }
    return len;
}

static void print_unsigned(unsigned long value,
                           unsigned base,
                           int width,
                           char pad,
                           int uppercase,
                           int *count)
{
    char buf[32];
    int len = format_unsigned(buf, value, base, uppercase);

    put_padding(pad, width, len, count);
    for (int i = 0; i < len; i++)
    {
        uart_putchar(buf[i]);
        (*count)++;
    }
}

static void print_signed(long value, int width, char pad, int *count)
{
    unsigned long magnitude;

    if (value < 0)
    {
        uart_putchar('-');
        (*count)++;
        magnitude = (unsigned long)(-value);
        if (width > 0)
        {
            width--;
        }
    }
    else
    {
        magnitude = (unsigned long)value;
    }

    print_unsigned(magnitude, 10, width, pad, 0, count);
}

static void print_fixed(double value, int *count)
{
    unsigned long whole;
    unsigned long frac;

    if (value < 0.0)
    {
        uart_putchar('-');
        (*count)++;
        value = -value;
    }

    whole = (unsigned long)value;
    frac = (unsigned long)((value - (double)whole) * 1000000.0 + 0.5);
    if (frac >= 1000000UL)
    {
        whole++;
        frac -= 1000000UL;
    }

    print_unsigned(whole, 10, 0, ' ', 0, count);
    uart_putchar('.');
    (*count)++;
    print_unsigned(frac, 10, 6, '0', 0, count);
}

int ee_printf(const char *fmt, ...)
{
    va_list args;
    int count = 0;

    va_start(args, fmt);
    while (*fmt)
    {
        int width = 0;
        char pad = ' ';
        int long_arg = 0;

        if (*fmt != '%')
        {
            uart_putchar(*fmt++);
            count++;
            continue;
        }

        fmt++;
        if (*fmt == '%')
        {
            uart_putchar(*fmt++);
            count++;
            continue;
        }

        if (*fmt == '0')
        {
            pad = '0';
            fmt++;
        }

        while (*fmt >= '0' && *fmt <= '9')
        {
            width = width * 10 + (*fmt - '0');
            fmt++;
        }

        if (*fmt == 'l')
        {
            long_arg = 1;
            fmt++;
        }

        switch (*fmt)
        {
            case 'd':
            case 'i':
                print_signed(long_arg ? va_arg(args, long) : va_arg(args, int),
                             width,
                             pad,
                             &count);
                break;
            case 'u':
                print_unsigned(long_arg ? va_arg(args, unsigned long)
                                        : va_arg(args, unsigned int),
                               10,
                               width,
                               pad,
                               0,
                               &count);
                break;
            case 'x':
            case 'X':
                print_unsigned(long_arg ? va_arg(args, unsigned long)
                                        : va_arg(args, unsigned int),
                               16,
                               width,
                               pad,
                               *fmt == 'X',
                               &count);
                break;
            case 's':
                puts_raw(va_arg(args, const char *), &count);
                break;
            case 'c':
                uart_putchar((char)va_arg(args, int));
                count++;
                break;
            case 'f':
                print_fixed(va_arg(args, double), &count);
                break;
            default:
                uart_putchar('%');
                uart_putchar(*fmt);
                count += 2;
                break;
        }

        if (*fmt)
        {
            fmt++;
        }
    }
    va_end(args);

    return count;
}
