#include <stdarg.h>

#include "relic_label.h"

void LABEL_util_printf(const char *format, ...) {
    (void)format;
    va_list args;
    va_start(args, format);
    va_end(args);
}
