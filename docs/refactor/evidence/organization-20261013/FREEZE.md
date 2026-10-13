# Synthetic organization recovery freeze

The abstract packets in `tools/recall-study/fixtures/organization-recovery/manifest.json`
are committed before comparison with the new organization implementation. They contain no
story or participant text. Seed 271828 is reserved; the initial sweep is deterministic.

The estimands are additive backward, forward and same-target counts on original adjacent
units, and the ratio of expected backward count to expected comparable count. Missing units
remain in their original positions. Bounds range over one coherent assignment per unit in
the declared Cartesian product; they are conditional ambiguity bounds, not confidence intervals.

Primary recovery diagnostics are count bias and attenuation of the forward-versus-reverse
effect as assignment ambiguity and omissions increase. All cases and adverse results remain.
No threshold for human accuracy or calibration is declared. The arithmetic tolerance is 1e-10;
an observed scientific bias does not become an arithmetic failure or authorize loosening it.

The public language-path control reuses the project's admitted Anna/Bob synthetic sentences.
Reverse order is coherent recall of those same events. It is separate from packet-level
metadata/storage permutations, which hold the already prepared unit packets fixed.
