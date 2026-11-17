# Native playback evidence

Rich account v6 admits source or recall graphs directly from supplied media observations,
without fabricating a text document. It uses the existing units, entities, events, states,
contexts and graph validator. A source account remains independent of any recall.

`MediaSource` binds the byte fingerprint, edition, stream, clock, side, extent and exact
seconds-per-tick scale. `PlaybackSupport` contains a canonical union of positive half-open
intervals and explicit points. Overlapping or touching intervals merge; gaps remain gaps.
Points survive even inside intervals and never supply duration. Integer strings preserve
large native coordinates through JSON; fractions retain exact conversion to seconds.
Only a declared edition-playback axis is accepted as claim evidence. An annotation timeline
cannot become primary media evidence through a numerical coincidence.

## Construction and consumption

```python
from storymodel import prepare_playback, compile_playback, situation_playback

request = prepare_playback(media_source, observations, export_permission="allowed")
result = compile_playback(request, recorded_proposals)
account = result.account
readout = situation_playback(account, account.events[0].id, media_source.id, bins)
```

Each observation records its origin, procedure, input hashes and physical envelope. Proposals
bind the complete request digest, use local graph keys, and supply their own native evidence.
The deterministic resolver checks evidence against the exact observation union. Context
scopes must be nested or disjoint; situations occupy their deepest applicable scope. Roles
must cite support within their situation. A rejected row contributes no graph claims; valid
neighbors survive. Omitted, failed, unsupported and search-limited observations remain explicit.
Recall detail inventories start unattempted; constructing a graph does not complete extraction.

`import_playback` records the exact reply before parsing through the existing recorded-run
engine. `replay_playback` checks request, profile, cache and imported reply binding before
rebuilding the graph. `verify_playback` recomputes the complete result. Export-denied inputs
refuse prompt/import/replay operations before accessing recording files.

`situation_table` inspects the actual native graph and role evidence. `situation_playback`
selects an explicit media source and measures the situation's support union, retaining its
account digest, context and event/state kind. Optional ordered, nonoverlapping bins return
exact exposure and explicit points separately. `verify_situation_playback` recomputes against
the account, selected source, situation and bins. Zero interval exposure is a geometric
measurement of supplied support, not proof that an event did not occur.

## Installed example

Run from the port directory, using an environment with the package installed:

```sh
python examples/playback_workflow.py data/playback-example
storymodel playback-prompt data/playback-example/request.json --output data/playback-prompt.json
storymodel replay-playback data/playback-example/request.json data/playback-example/recording --output data/playback-replayed.json
```

The example saves opaque byte controls, observation provenance, request/proposals, recording,
construction, source account, situation table and exact playback readouts. It constructs two
independent text recalls through recorded rich construction and pairs each with the unchanged
native source. Both paired artifacts retain the same source evidence and support measurement.
Abstract AA/BB/CC labels and opaque bytes establish engineering behavior, not audiovisual
recognition or semantic accuracy. The event has two disjoint intervals totaling `5/3` seconds;
the point-supported state has zero interval duration and retains its point at tick 22.

The CLI also exposes `prepare-playback`, `import-playback`, `situation-playback` and
`verify-situation-playback`; each command's help specifies its file arguments. Account-only
replay refuses incomplete admission so processing gaps cannot disappear with the receipt.

## Compatibility and remaining coverage

One `RichAccount` handles v4/v5/v6. V6 adds `media_sources` and native supports. Older tags
refuse that field even when explicitly empty, and refuse native supports anywhere in the
graph. Old serialized accounts omit it. Existing text IDs and recorded v1 construction remain
unchanged. `Entity.origin_source` exposes either actual source kind while the historical wire
field remains `source_document`; that field does not imply a fabricated document.

Historical schemas live in a narrow adapter, including legacy-only envelopes containing an
`Account`. `account_schema("v4" | "v5" | "v6")` selects a declared rich version. Combined
rich schemas broaden with the graph, while runtime validation checks version/field correlations.
Frozen schema export does not support a custom schema generator. The adapter preserves the
canonical historical layout rather than promising every alternate Pydantic schema presentation.

Text-dependent mapping, identity, assessment, alignment and analysis consumers currently refuse
accounts containing media sources explicitly. They do not strip native evidence. Media-aware
versions remain owed, along with clock maps/composition, shot/track anchors, acquisition,
automatic holder/identity proposals and complete graph/detail construction. No new media tool,
live provider call or participant data is needed for this supplied-observation path.
