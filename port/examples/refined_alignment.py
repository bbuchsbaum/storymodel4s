"""Refine a lexical tie using a declared causal chain in two synthetic accounts."""

import sys
from pathlib import Path
from typing import Literal

from account_inference import control

from storymodel.artifacts import export_supported_account, publish_json
from storymodel.local_evidence import (
    LocalEvidenceSpec,
    RecallSelection,
    RefinedInferenceSpec,
    infer_refined_accounts,
)
from storymodel.model import Relation
from storymodel.refinement import RefinementPolicy
from storymodel.rich_account import RichAccount, validate_rich_account
from storymodel.source_transitions import TransitionWeights


def chain(side: Literal["source", "recall"], length: int) -> RichAccount:
    account = control(side, "\n".join(["AA PP BB"] * length))
    relations = tuple(
        Relation(
            kind="causes",
            source=left.id,
            target=right.id,
            context=left.context,
            evidence=(left.evidence[0], right.evidence[0]),
        )
        for left, right in zip(account.states, account.states[1:])
    )
    return validate_rich_account(account.model_copy(update={"relations": relations}))


def example() -> tuple[RichAccount, RichAccount, RefinedInferenceSpec]:
    source, recall = chain("source", 3), chain("recall", 2)
    # Both targets are lexically identical. Only the causal chain connects first to last.
    candidates = (source.states[0].id, source.states[2].id)
    local = LocalEvidenceSpec(
        units=tuple(RecallSelection(unit=u.id, targets=candidates) for u in recall.units),
        transitions=TransitionWeights(theta={}),
    )
    return (
        source,
        recall,
        RefinedInferenceSpec(local=local, policy=RefinementPolicy(passes=2, weight=2.0)),
    )


def run(output: Path) -> None:
    source, recall, spec = example()
    result = infer_refined_accounts(source, recall, spec)
    output.mkdir(parents=True, exist_ok=False)
    export_supported_account(output / "source.json", source)
    export_supported_account(output / "recall.json", recall)
    publish_json(output / "spec.json", spec.model_dump(mode="json"))
    publish_json(output / "result.json", result.model_dump(mode="json"))


if __name__ == "__main__":
    run(Path(sys.argv[1]))
