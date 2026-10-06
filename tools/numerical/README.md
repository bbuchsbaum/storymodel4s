# Portable alignment arithmetic reference

`fdlibm/*.gz` retain the exact Netlib sources and notices, without rewriting their whitespace.
`sources.json` binds their decompressed bytes. The Scala implementation is independently compared
with this C reference; the generator never executes the Scala implementation.

From the repository root, on a qualified little-endian machine with Python and Clang:

```sh
python3 tools/numerical/freeze_oracle.py --work .agent-work/numerical-reference --write
```

The command freezes 631 input/exp/log bit triples, including the three historical divergent inputs,
argument-reduction and polynomial branches, adjacent doubles, normal/subnormal boundaries, overflow,
signed zero, infinity, NaNs, and seeded samples. It writes a raw TSV and command/source receipt to
the chosen work directory. NaN payloads are canonicalized only in the test oracle; finite results
are never rounded or tolerated by the bit-identity tests. The independent 100-digit Decimal check
compares finite reference values with nearest binary64 values and refuses a difference above one
adjacent bit pattern. That sampled accuracy check does not establish correct rounding everywhere.

Reference compilation disables contraction and fast math and retains the original header's
little-endian and aliasing conventions explicitly. The legacy C shifts a negative signed exponent;
its observed outputs are corroborated against an exported copy with two unsigned shift expressions
and an undefined-behavior sanitizer. Both must match the frozen TSV digest exactly. This qualifies
the observed compiler execution, not arbitrary compilation of the legacy C. Qualified Scala targets must preserve binary64
operation order, separate multiply/add rounding and gradual underflow. No network access, input
dataset, provider, ONNX model or experimental calibration is needed.
