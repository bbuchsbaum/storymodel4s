#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

double __ieee754_exp(double);
double __ieee754_log(double);

static uint64_t canonical_bits(double x) {
  uint64_t b;
  memcpy(&b, &x, sizeof b);
  if ((b & UINT64_C(0x7ff0000000000000)) == UINT64_C(0x7ff0000000000000)
      && (b & UINT64_C(0x000fffffffffffff)))
    return UINT64_C(0x7ff8000000000000);
  return b;
}

int main(void) {
  uint64_t b;
  while (scanf("%" SCNx64, &b) == 1) {
    double x;
    memcpy(&x, &b, sizeof x);
    printf("%016" PRIx64 " %016" PRIx64 " %016" PRIx64 "\n",
           b, canonical_bits(__ieee754_exp(x)), canonical_bits(__ieee754_log(x)));
  }
  return ferror(stdin) || ferror(stdout);
}
