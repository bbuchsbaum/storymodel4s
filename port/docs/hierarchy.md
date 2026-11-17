# Primary narrative hierarchy without changing occurrences

`prepare_hierarchy` binds explicit grouping declarations to an independent source or recall
account. `build_hierarchy` resolves them into a checked primary tree or an explicitly partial
forest. The account's units, situations, details, bindings, contexts and identities stay unchanged.
A grouping is a derived interpretation of that account, not a new source of narrative facts.

```python
from storymodel import (
    HierarchyGroup, HierarchyRef, SituationMembership,
    prepare_hierarchy, build_hierarchy, query_hierarchy,
    SourceProjectionPolicy, project_source,
)

spec = prepare_hierarchy(
    source,
    (HierarchyGroup(
        key="story", kind="story",
        members=tuple(HierarchyRef(kind="anchor", key=u.id) for u in source.units),
    ),),
    memberships=declared_situation_memberships,
)
hierarchy = build_hierarchy(source, spec)
query = query_hierarchy(source, hierarchy, hierarchy.root)
policy = SourceProjectionPolicy(recipe="account-source-layers/v2", hierarchy=spec)
projection = project_source(source, policy)
```

Here `declared_situation_memberships` is a tuple of
`SituationMembership(situation=situation.id, unit=unit.id)` declarations. The builder checks
that all situation evidence lies within the unit's exact support. It does not infer membership
from labels, text order or generic `part_of` relations. Groups can contain observed units,
situations directly, or other groups referenced by proposal-local keys. Scene, episode, story
and arc kinds are available. Keys resolve declarations; they do not enter segment identity.

Each generated segment derives its ID from its kind, sorted resolved members and normalized
exact support union. Relabeling a group, reordering declarations or placing an existing group
under a new parent does not change that group's identity. Changing its membership can change
its identity; the member occurrences remain intact. Duplicate members/parents, conflicting
existing unit parents, dangling references and cycles refuse. Existing `Unit.parent` edges are
retained. The historical `group_source_rows` operation still has its documented reidentification
semantics; this new operation does not rewrite those old artifacts.

Generated supports preserve text gaps, native interval gaps and explicit playback points.
Members on different documents or media axes retain separate support entries. No common clock
or continuous hull is fabricated. An observed unit's envelope may exceed its extracted children;
that represents supplied observation support, not fabricated extracted content. Native hierarchy
construction and queries work, but the existing text numerical consumers still explicitly refuse
native accounts. This slice does not establish media-aware alignment or assessment.

## Completeness, levels and queries

A complete nonempty hierarchy has one segment root and includes every account unit and situation
beneath it. Generated groups cannot be empty; existing empty scene containers cannot make a
complete tree. An account with no anchors can have an empty complete hierarchy. An independent
source/recall registry is still required. The root is derived; `expected_root` can assert the
expected anchor or group key without constructing a content-addressed ID.

For a partial forest, set `completeness="partial"` and supply a reason. Invalid edges still
refuse. The receipt lists all roots and empty existing scenes. When there is no unique segment
root, `unrooted` lists every anchor rather than arbitrarily selecting a component as the story.
With a unique valid root, `unrooted` is empty even if the caller conservatively declares partial
work. These are **structural** completeness statements: they do not certify that every narrative
fact was extracted or that upstream construction rows succeeded. Keep those construction receipts.

Situations have level zero. Observed rows/recall units start at level one and historical scene
units at two, preserving existing projection conventions; parents rise above their children.
Generated segments have minimum level one. Heights are derived, not asserted by callers. This
improves on a caller-declared level but does not claim identical numbering to every Scala model.

`query_hierarchy` returns children, parent, nearest-first ancestors, stable-ID depth-first
strict descendants and situations under an anchor. Its optional `descendant_level` filters the
descendant list; the situations list still describes all strict descendant situations. Child
order is deterministic identity order, never a statement about discourse or story chronology.
Traversal is iterative and does not allocate a dense reachability matrix.

Every public builder, query and verifier revalidates typed inputs against the actual account.
`verify_hierarchy(account, spec, result)` recomputes from an independently supplied specification.
A query uses the embedded specification and checks it against the account; this proves consistency,
not the external authority or scientific truth of a caller's grouping. Account digest binding
invalidates the receipt after any account change, even when occurrence identities survive.

## Computational integration and compatibility

The opt-in `account-source-layers/v2` policy embeds the hierarchy specification and forbids
legacy situation-membership overrides. Source projection and local evidence share the resolved
anchor/support catalog: generated segments receive content, candidate nominations, local costs
and sequence transitions. The existing inference/reconstruction entry points consume the same
policy. A new hierarchy is not just an inspection attachment.

V1 policy serialization and behavior remain unchanged. A `hierarchy` field, even null, refuses
under the v1 tag. V2 requires a hierarchy; its projection artifact is tagged v2. Combined schemas
now describe both versions, with runtime validation enforcing the version/field relationships.
Old numerical spec/result envelopes continue to bind their nested versioned policy and projection.
No account version or occurrence identity namespace changes.

Run the connected example from `port/`:

```sh
uv run python examples/hierarchy_workflow.py data/hierarchy-example-new
storymodel build-hierarchy data/hierarchy-example-new/source/account.json data/hierarchy-example-new/source/hierarchy-spec.json --output data/hierarchy-result.json
storymodel verify-hierarchy data/hierarchy-example-new/source/account.json data/hierarchy-example-new/source/hierarchy-spec.json data/hierarchy-result.json
```

`query-hierarchy ACCOUNT RESULT ANCHOR [--level N]` exposes the same query as Python.
Construction/query CLI exports check the account grant before reading supplementary files.
The example extends recorded connected construction, reuses one source across two recalls,
executes hierarchy-aware inference, and constructs/queries a native-media hierarchy. Installed
qualification copies its two sibling example helpers too; it requires no provider or browser.

## Preservation and remaining work

Scala references: `story/hierarchy.scala`, `ContainmentEdge`/`NarrativeMember` in
`story/relations.scala`, and the hierarchy checks/tests in `StoryValidator` and `ValidatorSuite`.
Python preserves primary membership, root reachability, single parents, cycle refusal and
queries. It deliberately checks exact text unions instead of the Scala validator's text hull.
Working auxiliary memberships, boundary beliefs, summaries and their evidence/credence policies
remain owed, alongside richer relation families and native numerical consumers. This primary
hierarchy slice is not full P2–P6 parity or a scientific segmentation-accuracy claim.
