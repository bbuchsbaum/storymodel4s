# Recurring-cast correspondence through the public Python workflow

This executable control connects construction, scene grouping, detail admission, sparse
structural candidates, recorded direct mapping, separate graded fidelity, fixed-question
claim accounting and the three analysis views. One source is reused unchanged for two recalls
whose unit orders are reversed. All text consists of abstract tokens; no participant data or
provider call is needed.

```sh
uv run python examples/correspondence_workflow.py data/correspondence
uv run storymodel analyze data/correspondence/recall-a/account.json
uv run storymodel replay-mapping \
  data/correspondence/recall-a/ambiguous/mapping-request.json \
  data/correspondence/recall-a/ambiguous/mapping-recording
uv run storymodel replay-fidelity \
  data/correspondence/recall-a/ambiguous/fidelity-request.json \
  data/correspondence/recall-a/ambiguous/fidelity-recording
```

The output directory must be new. Every stage retains its input and reply plus a checked result;
mapping and grading use the existing raw-before-parse recording/cache path. `summary.json`
locates the source rows, scenes and recall units; the complete source, recall and analysis
artifacts remain independently inspectable.

## The control and what it demonstrates

The source contains four rows: `AA PP BB XX`, `AA QQ BB YY`, `AA PP BB ZZ`, and `CC RR DD WW`.
AA/BB are repeated cast labels, PP/QQ are distinct action labels, and XX/YY/ZZ are manner labels.
The first two rows belong to one declared scene; the last two belong to another. Mentions keep
occurrence identities; matching labels are lexical evidence, not a newly inferred coreference.

| Recall control | Computed structural result | Scripted direct reference / conditional grade |
|---|---|---|
| `AA QQ BB YY` | Unique QQ row | Single / supported |
| `AA PP BB XX` | Alternative PP rows; manner does not select reference | First PP row / supported |
| `BB QQ AA YY` | Unresolved; conflicting agent and patient labels | QQ row / contradicted |
| `AA QQ BB ZZ` | QQ row; YY versus ZZ visible as manner evidence | QQ row / gist-detail-error, minor |
| Combined first two rows | Not attempted: no single linked situation in this control | Joint / supported |
| `AA PP QQ BB` summary | Not attempted: no linked situation | Broad first scene / supported |
| `AA PP BB` | Alternative PP rows | Alternatives, each conditionally supported |
| `EE TT FF` | Not attempted: no linked situation | Explicit personal destination / external |
| Revisited `AA QQ BB YY` | Same QQ target, separate recall occurrence | Single / supported; repetition remains explicit |

Structural results are produced by the actual sparse predicate/role engine. Mapping and
fidelity replies are scripted controls: their admission, preservation, replay and consequences
are tested, not a provider's ability to choose them. The example therefore does not demonstrate
that direct mapping is more accurate than structural matching. Missing graph linkage does not
show that unit-only localization is impossible; the two routes have different declared inputs.

Local mapping and grading prompt bytes remain equal for the same control after recall order
changes, while private request digests and recall occurrence IDs change. Revisited identical
text has a separate detail/unit identity. This audits prompt inputs and recording isolation;
it does not prove that a real extractor or provider was unaffected by context.

## Coverage, missingness and accounting

The final supported-only projection reports the first two source rows **present**, the other
PP row **uncertain**, and the unrelated RR row **absent**. Here absent means no qualifying
recall evidence under the declared policy, not independently established omission.

A test removes supported singleton/joint credit while preserving references and inventories.
The first three rows become uncertain; broad and alternative grades cannot create definite
presence. Making an inventory incomplete instead yields not-assessable results. Detail address
and recall classification remain unchanged when fidelity changes.

Recall intake covers every raw character: separators belong to the preceding unit. Detail and
event quotes retain their exact token spans. Unassigned whitespace is still unreviewed support
under the existing conservative completeness policy; the example does not silently discard it.
Source row gaps, in contrast, remain gaps in grouped scene evidence.

Three separately declared claim questions cover a supported claim, a contradicted claim and
an unresolved reference: three expressed, two resolved. Their receipt is not converted into
the fidelity grades above or used to inflate the coverage denominator.

## Checked scene grouping

The new `group_source_rows(source, groups)` operation removes the need to hand-assemble scene
containers. Each `SceneGroup(key, rows)` names existing unparented source rows. A group must
stay in one source document; rows cannot occur twice or in multiple groups. The input must be
source-only, before pairing/mapping. Existing scenes and unselected rows remain untouched.

Scene support is the exact union of member supports, merging overlaps/adjacency without
filling gaps. A group label is an artifact alias, not an asserted semantic identity. Parent
membership contributes to existing `Unit` identity, so grouped rows receive new IDs. The
`SceneGroupingResult.rows` map exposes old-to-new row IDs, and `scenes` maps labels to new scene
IDs. Non-unit graph collections and source text stay unchanged. Old construction receipts
continue to describe their original snapshot; the grouping receipt binds that snapshot and
its declared groups. No existing external artifact is silently migrated.

```python
from storymodel import SceneGroup, group_source_rows, verify_scene_grouping

groups = (SceneGroup(key="scene-a", rows=(first_row_id, second_row_id)),)
result = group_source_rows(source_account, groups)
verify_scene_grouping(source_account, groups, result)
source_account = result.account
new_first_row_id = result.rows[first_row_id]
```

The CLI is `group-source-rows ACCOUNT GROUPS_JSON --output RESULT_JSON`; `--account-only` emits
the derived account when the caller deliberately needs only that snapshot. JSON groups are an
array of `{key, rows}` objects. Producer grants are checked before reading groups or writing
output. Public grouping and verification revalidate typed inputs and recompute identity maps.

This is one-level grouping of source rows, not the complete narrative hierarchy. Event/episode
targets, richer hierarchy and graph families, semantic channels, neighbor-aware/cross-target
assessment and account-derived sequence inference remain owed. These controls close a connected
engineering demonstration; they do not close P2–P6 or establish scientific accuracy.
