# Rich recall details, mapping and analysis

The v4 computational path now connects recorded graph construction to recall details, direct
reference/conditional fidelity, and the three Python analysis views. Every update retains the full
rich account, including states, circumstances, contexts and existing bindings.

```sh
uv run python examples/rich_detail_workflow.py data/rich-detail-example
uv run storymodel analyze data/rich-detail-example/recall-a/account.json
uv run storymodel situation-table data/rich-detail-example/recall-b/account.json
```

The example independently constructs one source and two recall accounts from recorded abstract
token proposals. Each recall is paired with the unchanged source, receives an explicit detail
inventory, and is mapped and assessed from another recorded proposal. All requests, raw replies,
receipts, accounts and views are saved. No model call or linguistic accuracy claim is involved.

## Detail admission

`prepare_details(rich_account, unit_ids)` selects an explicit, nonempty set of recall units.
Selected units must have no existing details and no completed inventory. This prevents an
enrichment pass from replacing accepted content or treating a completed zero-detail review as
unprocessed. Units not selected stay unchanged.

`admit_details(request, raw)` accepts an input-bound proposal. Every proposed detail declares its
kind, address, discourse function and exact UTF-16 quotes. It can name an existing recall event
or state when its support fits that situation. A source situation cannot become a recall detail's
owner. No source reference or fidelity is inferred from the detail's address or situation link.

Quote order is checked. The display content concatenates adjacent spans and inserts ` … ` across
gaps; it does not fabricate intervening words. The original supports remain separate and exact.
Classification and address are recorded proposals whose evidence consistency is checked, not
proven semantic truth.

Units admit atomically. One bad claim rejects that unit, without dropping valid siblings or
altering existing graph records. Completed-zero requires an explicit completion reason. Missing
work is not-attempted; failed work remains failed; a limited search may retain valid details with
a partial receipt and a search-limited inventory. Those outcomes remain visible to analysis.
There is no automatic replacement/revision of existing details in this profile.

`DetailRecording`, `import_details` and `replay_details` use the existing recorded executor, retaining
raw text before parse and checking the complete request, cache and optional external pins.
`verify_details` recomputes from original inputs. CLI equivalents are `prepare-details ACCOUNT UNIT...`,
`detail-prompt`, `import-details` and `replay-details`; account-only replay refuses incomplete receipts.
Export permission is checked before additional artifact reads or writes.

The detail prompt deliberately exposes the selected request's **full account snapshot**. It is
not an order-independent or unit-only acquisition condition. The later direct mapping prompt
retains its separately tested unit-only condition; that alone does not make the entire pipeline
independent of recall order.

## Rich mapping and views

`prepare_mapping` chooses a v2 request/result envelope for v4 accounts and retains v1 for legacy
accounts. Both use the same unit-only resolver and existing recording engine. The rich envelope
preserves all graph collections through nested serialization and verification. Existing v1 schemas,
request bytes, prompt bytes, example results and analysis output have frozen comparison checks.

`project` accepts validated legacy or rich accounts. Its declared detail/reference policies are
unchanged: pending fidelity earns no supported coverage; unprocessed recall withholds absence;
supported evidence can establish presence. This is a detail-policy readout, not an event/state
search-completeness or source-construction-completeness claim.

Use `read_supported_account` and `export_supported_account` for complete version-dispatched
snapshots. The original legacy-only readers remain available. `prepare-mapping`, `validate` and
`analyze` now dispatch by the explicit account version. Structural alignment and older identity/
relation construction still refuse v4 until their situation coverage is explicitly extended.

## Scope still owed

This connects a working path; it does not finish P2 or P3. Rich recall grouping/summaries,
event qualifiers, playback, full attribution/identity/relation families, semantic channels,
event/episode reference targets, fixed-population claim assessment and a recurring-scene
demonstration remain required. P4 numerical inference/measurement, P5 studies and P6 inspection
remain part of the full port commitment.
