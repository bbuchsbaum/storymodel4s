# Exact playback composition

Composition joins declared native segments into one presentation timeline without changing
their playback speed. It is a separate, content-addressed artifact: a derived timeline is not
a physical media file and has no invented media checksum. Existing `MediaSource` identities
and physical-account evidence admission remain unchanged.

`concatenate_playback(key, sources, parts)` takes a registry of `MediaSource` declarations
and an ordered tuple of `CompositionPart(source=media.id, native=interval)`. Registry order is
irrelevant; part order defines presentation order. Each selected native interval must lie in
its physical source. Repeating the same source creates distinct occurrence identities. Mixed
source/recall sides and annotation-only timelines refuse admission.

For reduced timebases `nᵢ/dᵢ`, the common tick quantum is `gcd(nᵢ) / lcm(dᵢ)`. Native-to-target
scales are integral, and target offsets accumulate complete selected durations. Python integers
and rational arithmetic keep large coordinates exact. No rounding, interpolation or speed
change is implicit.

```python
composition = concatenate_playback("presentation", (a, b), parts)
anchor = compose_support(composition, composition.segments[0].occurrence, native_support)
readout = project_composition(composition, anchor.composed, bins)
original = inverse_support(composition, anchor.composed)
```

Each returned anchor retains the occurrence, original native support and projected support.
Forward mapping checks the chosen segment, not merely the physical file's full extent. Inverse
mapping splits at occurrence boundaries and retains repetitions. A point at a join belongs to
the following occurrence; the final endpoint is outside the half-open extent. Inverse mapping
refuses a target endpoint or point between native ticks instead of rounding it. Readout bins
may still use finer target ticks: measuring overlap does not invent a native evidence anchor.

`project_composition` shares the existing physical playback measurement kernel. It measures
interval unions rather than their hull or the sum of overlapping selections. Explicit points
remain visible, including points inside selected intervals, and contribute zero duration.
The saved readout includes the source registry, ordered parts, derived segments, timebase and
selection. Verification recomputes from the actual composition and query.

## Sherlock rows and scenes

`compose_sherlock_source` consumes the checked TSV/repair intake. It orders parts by declared
presentation ordinal, preserves their declared media fingerprints and primary clocks, and binds
every row and scene to both native and composed evidence. It concatenates complete media
extents: an unannotated tail is retained, not trimmed or filled with annotation support.
Notebook offsets and scanner TRs are not media coordinates.

```sh
storymodel sherlock-compose source-prepared.json --output composed.json
storymodel sherlock-composition-readout composed.json --bins bins.json --output readout.json
storymodel verify-sherlock-composition-readout composed.json readout.json --bins bins.json
```

`--unit` selects a row or scene key and can be repeated. With no selection, all rows are used.
Overlapping row/scene selections form a union. A bin file is an array of integer-string
`{"start": "0", "end": "75000"}` intervals in the composed clock. The producer must grant
export; source preparation, composition and readout keep that permission explicit.

From `port/`, `python examples/composed_playback.py data/composed-example` saves a repeated-media
control and the retained synthetic Sherlock workflow. Its full source duration is 42.4 seconds,
but annotation support covers 42 seconds, with a 0.4-second gap. This is declared coordinate
coverage, not proof of audiovisual content, recall fidelity, media reachability or research
accuracy.

Arbitrary warps, target gaps in an edit plan, nested compositions, cross-edition correspondence,
composed evidence admitted directly into accounts, recording/scanner linkage and downstream
recall-time measurements remain owed. This slice provides exact ordered concatenation and
support/readout consumers; it does not claim the entire Scala mapping surface.
