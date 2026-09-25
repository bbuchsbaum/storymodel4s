# S2a-4 content scorer court

Mote: `bd-01M379NZZ67GZJMWVBT5XNA0ZD`. Base: `48457bdbfd2ca012bdc9144b3395d4d8e5d4ec10`.

This slice tests the landed content projection and scorer seam. It adds no scoring behavior
or public API. `ContentUnforgeableSuite` is compiled outside `storymodel4s`, and checks five
construction doors on each of ParticipantContent, UnitContent, TargetContent, Members and
ContentGrain. Positive controls are required to compile alongside refusals.

The JVM `ContentSurfaceSuite` pins declared public method names and overload counts, including
companions, and scans payload return/argument signatures for forbidden coordinate-bearing types.
Scala package-private methods become JVM-public methods, so known internal reads are explicitly
pinned. Static forwarders to projection factories are excluded from the payload signature scan;
their companion method names are pinned. This is a Scala boundary court, not a JVM sandbox.

`ContentBehaviorLawsSuite` checks the charted strict reduction: an exact partial-match distance,
an exact-member minimum, separate span-shift, identifier-renaming and storage-permutation laws,
and missing versus present-empty charts. Empty charts produce the current observed distance
0.85; missing unit charts produce ProviderAbstained. The historical-moves control remains in
`ContentScoringSuite` and the participant-order control in `ContentProjectionSuite`.

## Mutations

Run `tools/content-scorer-mutation-check.py` only in an isolated clone. It records HEAD, source
hashes, exact commands, exits, test totals and the named failing witness. It forces fresh test
compilation, rejects compilation errors as evidence, and restores every mutated source in a
`finally` block. Each run must also have passing sibling tests.

| Mutant | Deliberate defect | Named court |
| --- | --- | --- |
| M1 | Add a unit ordinal accessor | UnitContent public-method pin |
| M2 | Add a nested original-chart accessor | UnitContent forbidden-type scan |
| M3 | Expose member iteration | Members public-method pin |
| M4 | Open ContentGrain construction | ContentGrain new refusal |
| M5 | Open ParticipantContent construction | ParticipantContent new refusal |
| M6 | Collapse Some(empty chart) into None | Missing/empty behavioural distinction |
| M7 | Preserve participant storage order under Canonical | Participant permutation law |
| M8 | Preserve inherited contradiction storage order | Strict mode-gate order law |

## Remaining acceptance

The unavailable-channel versus provider-abstention criterion depends on S2a-3
(`bd-01M379N8HK5ZCV9W252X9MQ4VV`), which owns the new ChannelUnavailable case and strict channel
wiring. It is not implemented by substituting a generic missing value in this court. S2a-4 must
remain open until that witness exists and the reviewed slice lands. No strict-end-to-end or
hosted-CI qualification is claimed by these local tests.

## Repairs found during independent review

The older SemanticGraph court supplied eight constructor arguments where the graph now takes
nine, and seven where ContentCompatibilityReport now takes five. Both probes now use the actual
shape, with public same-field test controls. Additional M11/M12 visibility mutations must kill
these corrected probes; the old arity failures alone were not boundary evidence.

M9 and M10 open the `sourceUnit` and `sourceNode` historical shortcuts respectively. They are
known JVM methods but must remain inaccessible to external Scala scorers. The JVM inventory also
pins constructor counts to detect an added auxiliary constructor.

The reflection pin covers names and overload counts, not every possible JVM signature rewrite.
The forbidden-type scan and compiler courts add separate checks. Members callbacks may capture
state: absence of named iteration accessors is not proof that arbitrary callbacks cannot observe
order. The behavioural claims here concern the library's controlled scorers.
