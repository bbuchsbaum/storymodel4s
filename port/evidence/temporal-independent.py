"""Independent chronology oracle and compatibility probes; run with the port environment."""

import itertools
import json
import random
import subprocess
import sys
from pathlib import Path

from storymodel.chronology import RELATIONS, TemporalPolicy, interval_consistent
from storymodel.chronology_workflow import query_temporal_relation, temporal_table
from storymodel.rich_account import account_schema, validate_rich_account
from storymodel.source_transitions import SourceProjectionPolicy, project_source


def numeric_relation(left, right):
    # Ordinary endpoint comparisons, independent of the production rule table.
    a, b = left
    c, d = right
    if a == c and b == d:
        return "equal"
    if b < c:
        return "before"
    if b == c:
        return "meets"
    if a > d:
        return "after"
    if a == d:
        return "met-by"
    if a < c < b < d:
        return "overlaps"
    if c < a < d < b:
        return "overlapped-by"
    if c < a and b < d:
        return "during"
    if a < c and d < b:
        return "contains"
    if a == c and b < d:
        return "starts"
    if a == c and b > d:
        return "started-by"
    if c < a and b == d:
        return "finishes"
    if a < c and b == d:
        return "finished-by"
    raise AssertionError((left, right))


base = Path(__file__).resolve().parents[1]
reviewed = "f2610fcb1ac3b9344c2bae60128567f7f1342cb9"
executed = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=base.parent).decode().strip()


def controls():
    sys.path.insert(0, str(base / "tests"))
    from test_chronology import _account, _claim

    return _account, _claim


_account, _claim = controls()
intervals = list(itertools.combinations(range(8), 2))
pairs = list(itertools.combinations(range(4), 2))
feasible = {
    tuple(numeric_relation(values[a], values[b]) for a, b in pairs)
    for values in itertools.product(intervals, repeat=4)
}
rng = random.Random(20261117)
positive = rng.sample(sorted(feasible), 2000)
negative = []
while len(negative) < 2000:
    signature = tuple(rng.choice(RELATIONS) for _ in pairs)
    if signature not in feasible:
        negative.append(signature)
for expected, signatures in ((True, positive), (False, negative)):
    for signature in signatures:
        observed = interval_consistent(
            tuple((str(a), str(b), r) for (a, b), r in zip(pairs, signature))
        )
        assert observed is expected, (expected, signature)

# Historical schema artifact is a bundle. Reproduce the historical account_schema
# version-field specialization after selecting its account member.
old = json.loads(
    subprocess.check_output(
        ["git", "show", "ec1e91f45:port/schemas/rich-account.json"], cwd=base.parent
    )
)["account"]
old["properties"]["schema_version"] = {
    "const": "storymodel.account/v6",
    "default": "storymodel.account/v6",
    "title": "Schema Version",
    "type": "string",
}
assert account_schema("v6") == old

account = _account(root_events=True)
x, y, z = (s.id for s in account._situations())
root = next(c.id for c in account.contexts if c.kind == "narrated")
child = next(c.id for c in account.contexts if c.kind != "narrated")
p = _claim(account, x, y, "before", context=root)
q = _claim(
    account,
    y,
    z,
    "before",
    context=child,
    status="linguistically-entailed",
    premises=(p.id,),
)
account = validate_rich_account(account.model_copy(update={"temporal_relations": (p, q)}))
assert len(temporal_table(account, child)) == 1
assert (
    temporal_table(account, child, policy=TemporalPolicy(statuses=("linguistically-entailed",)))
    == ()
)
assert query_temporal_relation(account, y, z, child).possible == ("before",)
assert len(query_temporal_relation(account, y, z, root).possible) == 13
for bad in (False, 0, "", []):
    try:
        temporal_table(account, policy=bad)
    except ValueError:
        pass
    else:
        raise AssertionError(("invalid policy accepted", bad))
legacy = _account(root_events=True)
p1 = project_source(legacy)
p3 = project_source(legacy, SourceProjectionPolicy(recipe="account-source-layers/v3"))
assert p1.nodes == p3.nodes and p1.edges == p3.edges
result = {
    "reviewed_sha": reviewed,
    "executed_sha": executed,
    "numeric_assignments": len(intervals) ** 4,
    "feasible_signatures": len(feasible),
    "positive_networks": len(positive),
    "negative_networks": len(negative),
    "mismatches": 0,
    "v6_schema_equal_to_ec1e91f45": True,
    "cross_context_policy_exclusion": True,
    "root_child_query_isolation": True,
    "falsy_policy_rejections": 4,
    "empty_temporal_projection_legacy_graph_equal": True,
}
(base / "data/temporal-independent.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result))
