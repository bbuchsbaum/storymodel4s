#!/usr/bin/env python3
"""Freeze reference bits independently of Scala and audit accuracy with Decimal (100 digits)."""
import argparse
import decimal
import gzip
import hashlib
import json
import math
from pathlib import Path
import random
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).resolve().parent / 'fdlibm'
MASK = (1 << 64) - 1
FROZEN_TSV_SHA256 = 'd020d76b77746ab45519b3662200a3926aab4628190075a7d7cf294cd240457f'

def bits(x):
    return struct.unpack('>Q', struct.pack('>d', x))[0]

def value(b):
    return struct.unpack('>d', struct.pack('>Q', b))[0]

def inputs():
    result = [0, 1 << 63, 0x7ff0000000000000, 0xfff0000000000000,
              0x7ff8000000000001, 0xfff8000000000001,
              0xc01764ec3fc51648, 0xc017cc3a9d9858b8, 0xc02254b1cb8d139a]
    # Argument-reduction branches, polynomial branches, exponent boundaries and neighbors.
    for b in (1, 0xfffffffffffff, 0x10000000000000, 0x7fefffffffffffff,
              0x3e30000000000000, 0x3fd62e4200000000, 0x3fd62e4300000000,
              0x3ff0a2b200000000, 0x3ff0000000000000, 0x3ff0000000000002,
              0x3ff6147a00000000, 0x3ff6b85100000000, 0x3ff6a09c00000000,
              0x40862e42fefa39ef, 0x40874910d52d3051, bits(1.5), bits(0.85),
              bits(-708.3964185322641), bits(-709.0895657128241), bits(-744.0)):
        for offset in (-1, 0, 1):
            result.extend([(b + offset) & MASK, ((b + offset) ^ (1 << 63)) & MASK])
    rng = random.Random(20261006)
    result.extend(bits(rng.uniform(-750, 715)) for _ in range(256))
    result.extend(rng.randrange(1, 0x7ff0000000000000) for _ in range(256))
    return list(dict.fromkeys(result))

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--work', type=Path, required=True)
    p.add_argument('--write', action='store_true', help='write the committed Scala oracle')
    args = p.parse_args()
    args.work.mkdir(parents=True, exist_ok=True)
    for item in json.loads((SOURCE / 'sources.json').read_text()):
        data = gzip.decompress((SOURCE / (item['file'] + '.gz')).read_bytes())
        assert hashlib.sha256(data).hexdigest() == item['sha256']
        (args.work / item['file']).write_bytes(data)
    assert sys.byteorder == 'little', 'reference header requires qualified little-endian target'
    executable = args.work / 'fdlibm-oracle'
    command = ['clang', '-std=c99', '-O2', '-D__LITTLE_ENDIAN', '-fno-strict-aliasing',
               '-ffp-contract=off', '-fno-fast-math', str(args.work/'e_exp.c'), str(args.work/'e_log.c'),
               str(SOURCE/'oracle.c'), '-o', str(executable)]
    subprocess.run(command, check=True)
    roster = inputs()
    output = subprocess.check_output([str(executable)], input=''.join(f'{b:016x}\n' for b in roster).encode())
    assert hashlib.sha256(output).hexdigest() == FROZEN_TSV_SHA256, 'reference differs from frozen bits'
    # The legacy C shifts negative signed k. Corroborate with unsigned, defined shifts, and UBSan.
    controlled = (args.work/'e_exp.c').read_text()
    replacements = {
        '__HI(y) += (k<<20);': '__HI(y) = (int)((unsigned)__HI(y) + ((unsigned)k << 20));',
        '__HI(y) += ((k+1000)<<20);': '__HI(y) = (int)((unsigned)__HI(y) + ((unsigned)(k+1000) << 20));'
    }
    for old, new in replacements.items():
        assert controlled.count(old) == 1
        controlled = controlled.replace(old, new)
    controlled_path = args.work/'e_exp_defined_shift.c'
    controlled_path.write_text(controlled)
    second_executable = args.work/'fdlibm-defined-shift-oracle'
    second_command = command[:-2] + ['-fsanitize=undefined', '-fno-sanitize-recover=all', '-o', str(second_executable)]
    second_command[second_command.index(str(args.work/'e_exp.c'))] = str(controlled_path)
    subprocess.run(second_command, check=True)
    corroboration = subprocess.check_output([str(second_executable)], input=''.join(f'{b:016x}\n' for b in roster).encode())
    assert corroboration == output, 'defined-shift reference differs from retained legacy C'
    rows = [tuple(int(v, 16) for v in line.split()) for line in output.decode().splitlines()]
    assert [r[0] for r in rows] == roster
    context = decimal.Context(prec=100, Emax=999999999, Emin=-999999999)
    counts = {'exp': 0, 'log': 0}; maximum = {'exp': 0, 'log': 0}
    for inp, exp, log in rows:
        x = value(inp)
        if not math.isfinite(x):
            continue
        for operation, got in [('exp', exp), ('log', log)]:
            if operation == 'log' and x <= 0: continue
            if operation == 'exp' and abs(x) > 1000: continue
            exact = getattr(context, operation if operation == 'exp' else 'ln')(decimal.Decimal.from_float(x))
            rounded = bits(float(exact))
            # Accuracy check, not the bit-identity court: compare the nearest high-precision value.
            if math.isfinite(value(got)) and math.isfinite(value(rounded)):
                distance = abs(got - rounded)
                assert distance <= 1, (operation, f'{inp:016x}', distance)
                counts[operation] += 1
                maximum[operation] = max(maximum[operation], distance)
            else:
                assert got == rounded
    source = ('package storymodel4s.align\n\n'
              '/** Frozen from the original C reference, not generated by AlignmentMath. */\n'
              'private[align] object AlignmentMathOracle:\n'
              '  val rows: Vector[(Long, Long, Long)] = Vector(\n' +
              ',\n'.join(f'    (0x{i:016x}L, 0x{e:016x}L, 0x{l:016x}L)' for i,e,l in rows) + '\n  )\n')
    target = ROOT/'align/src/test/scala/storymodel4s/align/AlignmentMathOracle.scala'
    if args.write: target.write_text(source)
    (args.work/'oracle.tsv').write_bytes(output)
    receipt = {'rows': len(rows), 'sha256': hashlib.sha256(output).hexdigest(),
               'compiler': subprocess.check_output(['clang','--version']).decode().splitlines()[0],
               'compile_command': command, 'reference_sources': json.loads((SOURCE/'sources.json').read_text()),
               'defined_shift_corroboration': {'compile_command': second_command, 'ubsan_exit': 0,
                                                'exact_frozen_bits_match': True},
               'accuracy': {'independent_reference': 'Python Decimal 100-digit exp/ln',
                            'checked': counts, 'max_bit_distance_from_rounded_reference': maximum},
               'scala_port_executed_by_generator': False}
    (args.work/'receipt.json').write_text(json.dumps(receipt, indent=2)+'\n')
    print(json.dumps(receipt, indent=2))

if __name__ == '__main__': main()
