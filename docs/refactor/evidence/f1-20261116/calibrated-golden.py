#!/usr/bin/env python3
"""Independent hashlib/IEEE754 encoding of the minimal calibrated chart fixture."""
import hashlib
import struct

def sha(text):
    return hashlib.sha256(text.encode()).hexdigest()

def tag(kind, fields):
    return kind + '(' + ','.join(map(sha, fields)) + ')'

def bits(number):
    return '0x' + struct.pack('>d', number).hex()

credence = tag('credence', ['raw:f1-scorer=' + bits(.25),
                          tag('calibrated', [bits(.1), 'f1-calibration'])])
provenance = tag('chart-provenance', ['hand', '',
                 tag('alternative', [sha('alternative'), credence])])
fields = ['film-chart-payload/v1', 'none', '', '', '', '', '', provenance, 'none', provenance]
result = sha('\x1f'.join(map(sha, fields)))
assert result == '399403c26574ed94f568145af09f620ce09615fa599b4932282b29e7efd933d4'
print(result)
