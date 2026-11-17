"""One saved replay command for contextual text and native-source assessment branches.

Reuses existing abstract, recorded controls; no provider calls or semantic accuracy claims.
"""

import json
import subprocess
import sys
from pathlib import Path

from storymodel.analysis_workflow import (
    AnalysisAccountInput,
    AnalysisFile,
    AnalysisPlan,
    AnalysisStage,
    replay_analysis_file,
    verify_analysis,
)
from storymodel.artifacts import publish_json
from storymodel.detail_admission import detail_request_digest
from storymodel.detail_workflow import DetailRecording, import_details, read_detail_request
from storymodel.rich_construction import rich_construction_request_digest
from storymodel.rich_workflow import RichRecording, import_rich, read_rich_request


def context_plan(recall: str) -> AnalysisPlan:
    def build(name):
        return AnalysisAccountInput(
            kind="rich",
            file=AnalysisFile(path=f"inputs/{name}-construction-request.json"),
            recording=f"recordings/{name}-construction",
        )

    grade = "named-fidelity" if recall == "recall-a" else "unresolved-required-fidelity"
    return AnalysisPlan(
        source=build("source"),
        recall=build(recall),
        stages=(
            AnalysisStage(
                name="details",
                kind="details",
                request=AnalysisFile(path=f"inputs/{recall}-detail-request.json"),
                recording=f"recordings/{recall}-detail",
            ),
            AnalysisStage(
                name="mapping",
                kind="mapping",
                request=AnalysisFile(path=f"inputs/{recall}-mapping-request.json"),
                recording=f"recordings/{recall}-mapping",
            ),
            AnalysisStage(
                name="fidelity",
                kind="fidelity",
                request=AnalysisFile(path=f"inputs/{grade}-request.json"),
                recording=f"recordings/{grade}",
            ),
        ),
    )


def native_plan(number: int) -> AnalysisPlan:
    name = f"recall-{number}"
    return AnalysisPlan(
        source=AnalysisAccountInput(
            kind="playback",
            file=AnalysisFile(path="construction/request.json"),
            recording="construction/recording",
        ),
        recall=AnalysisAccountInput(
            kind="rich",
            file=AnalysisFile(path=f"construction/{name}/request.json"),
            recording=f"construction/{name}/recording",
        ),
        recall_details=(
            AnalysisStage(
                name="recall-details",
                kind="details",
                request=AnalysisFile(path=f"{name}/detail-request.json"),
                recording=f"{name}/details-recording",
            ),
        ),
        upgrade_native_assessment=True,
        stages=tuple(
            AnalysisStage(
                name=kind,
                kind=kind,
                request=AnalysisFile(path=f"{name}/{kind}-request.json"),
                recording=f"{name}/{kind}-recording",
            )
            for kind in ("mapping", "fidelity")
        ),
    )


def correspondence_plan(root: Path, recall: str) -> AnalysisPlan:
    """Reuse recorded proposal bytes from the existing nine-case reference controls."""
    from correspondence_workflow import CASES

    construction = read_rich_request(root / recall / "construction/request.json")
    import_rich(
        construction,
        RichRecording(
            request_sha256=rich_construction_request_digest(construction),
            origin="existing abstract correspondence construction",
            reply=(root / recall / "construction/reply.json").read_bytes().decode("utf-8"),
        ),
        root / recall / "construction/recording",
    )
    detail = read_detail_request(root / recall / "detail-request.json")
    import_details(
        detail,
        DetailRecording(
            request_sha256=detail_request_digest(detail),
            origin="existing abstract correspondence details",
            reply=(root / recall / "detail-reply.json").read_bytes().decode("utf-8"),
        ),
        root / recall / "detail-recording",
    )
    order = CASES if recall == "recall-a" else tuple(reversed(CASES))
    stages = [
        AnalysisStage(
            name="details",
            kind="details",
            request=AnalysisFile(path=f"{recall}/detail-request.json"),
            recording=f"{recall}/detail-recording",
        )
    ]
    for case in order:
        for kind in ("mapping", "fidelity"):
            stages.append(
                AnalysisStage(
                    name=f"{case[0]}-{kind}",
                    kind=kind,
                    request=AnalysisFile(path=f"{recall}/{case[0]}/{kind}-request.json"),
                    recording=f"{recall}/{case[0]}/{kind}-recording",
                )
            )
    return AnalysisPlan(
        source=AnalysisAccountInput(kind="account", file=AnalysisFile(path="source/account.json")),
        recall=AnalysisAccountInput(
            kind="rich",
            file=AnalysisFile(path=f"{recall}/construction/request.json"),
            recording=f"{recall}/construction/recording",
        ),
        stages=tuple(stages),
    )


def run(output: Path) -> None:
    output.mkdir(parents=True, exist_ok=False)
    examples = Path(__file__).resolve().parent
    for script, directory in (
        ("context_fidelity_workflow", "context"),
        ("native_assessment_workflow", "native"),
        ("correspondence_workflow", "correspondence"),
    ):
        subprocess.run(
            [sys.executable, str(examples / f"{script}.py"), str(output / directory)],
            check=True,
            capture_output=True,
        )
    summaries = []
    for directory, plans in (
        ("context", (context_plan("recall-a"), context_plan("recall-b"))),
        ("native", (native_plan(1), native_plan(2))),
    ):
        source_before = None
        for number, plan in enumerate(plans, 1):
            path = output / directory / f"analysis-{number}.json"
            publish_json(path, plan.model_dump(mode="json"))
            result = replay_analysis_file(path)
            assert verify_analysis(plan, path.parent, result) == result
            serialized_source = result.source.model_dump_json()
            if source_before is not None:
                assert serialized_source == source_before
            source_before = serialized_source
            assert result.stages[-1].result.decisions[0].status == (
                "admitted" if number == 1 else "rejected"
            )
            assert result.account.bindings[0].assessments[0].status == (
                "completed" if number == 1 else "pending"
            )
            publish_json(
                path.with_name(f"analysis-{number}-result.json"), result.model_dump(mode="json")
            )
            summaries.append(
                dict(
                    branch=directory,
                    recall=number,
                    stages=len(result.recall_details) + len(result.stages),
                    fidelity=result.account.bindings[0].assessments[0].status,
                    source_unchanged=True,
                )
            )
    root = output / "correspondence"
    source_before = None
    for number, recall in enumerate(("recall-a", "recall-b"), 1):
        plan = correspondence_plan(root, recall)
        path = root / f"analysis-{number}.json"
        publish_json(path, plan.model_dump(mode="json"))
        result = replay_analysis_file(path)
        assert result.account.model_dump(mode="json") == json.loads(
            (root / recall / "account.json").read_bytes()
        )
        assert result.views.model_dump(mode="json") == json.loads(
            (root / recall / "analysis.json").read_bytes()
        )
        if source_before is not None:
            assert result.source == source_before
        source_before = result.source
        publish_json(root / f"analysis-{number}-result.json", result.model_dump(mode="json"))
        summaries.append(
            dict(
                branch="correspondence",
                recall=number,
                stages=len(result.stages),
                bindings=len(result.account.bindings),
                source_unchanged=True,
            )
        )
    print(json.dumps(summaries))


if __name__ == "__main__":
    run(Path(sys.argv[1]))
