"""Small CLI over the same operations used from Python and notebooks."""

import argparse
import sys
from pathlib import Path
from typing import Any

from .alignment import align_account, validate_policy
from .analysis import Analysis, Policy, project
from .artifacts import publish_json, read_account, read_supported_account
from .claim_assessment import (
    ClaimQuestion,
    assess_claims,
    prepare_claim_assessment,
    validate_claim_plan,
    verify_claim_assessment,
)
from .demo import example_account
from .detail_admission import detail_prompt, prepare_details
from .detail_workflow import (
    DetailRecording,
    import_details,
    read_detail_request,
    replay_details,
)
from .direct_mapping import mapping_prompt, prepare_mapping
from .direct_workflow import (
    MappingRecording,
    import_mapping,
    read_mapping_request,
    replay_mapping,
)
from .execution import RecordedExecutionError
from .extraction import prepare_extraction
from .extraction_workflow import (
    ExtractionRecording,
    extraction_prompt,
    import_extraction,
    read_extraction_request,
    replay_extraction,
)
from .fidelity import fidelity_prompt, prepare_fidelity
from .fidelity_workflow import (
    FidelityRecording,
    import_fidelity,
    read_fidelity_request,
    replay_fidelity,
)
from .identity import IdentityResult, identity_role_table, resolve_text_identity
from .intake_workflow import prepare_time_study_files
from .model import Account, canonical
from .relations import admit_relations, relation_table
from .rich_account import RichAccount, situation_table, validate_rich_account
from .rich_construction import prepare_rich_construction
from .rich_workflow import RichRecording, import_rich, read_rich_request, replay_rich, rich_prompt
from .scenes import SceneGroup, group_source_rows
from .sequence import infer_sequence, validate_sequence, verify_sequence
from .source import SourceRow, prepare_source
from .source_transitions import (
    SourceSequenceResult,
    SourceSequenceSpec,
    build_source_sequence,
    verify_source_sequence,
)
from .source_workflow import (
    SourceRecording,
    import_source,
    read_source_request,
    replay_source,
    source_prompt,
)
from .study_workflow import (
    ImportedReplies,
    import_time_replies,
    read_request,
    replay_time_study,
    strict_json,
)


def emit(value: Any, output: str | None) -> None:
    text = canonical(value) + "\n"
    if output is None:
        sys.stdout.write(text)
    else:
        publish_json(Path(output), value)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="storymodel")
    commands = parser.add_subparsers(dest="command", required=True)
    demo = commands.add_parser("example", help="write the admitted synthetic account")
    demo.add_argument("--output")
    validate = commands.add_parser("validate", help="validate a rich account")
    validate.add_argument("account")
    validate.add_argument("--expected-sha256")
    analyze = commands.add_parser("analyze", help="derive the three analysis views")
    analyze.add_argument("account")
    analyze.add_argument("--expected-sha256")
    analyze.add_argument(
        "--policy",
        choices=("target-episode-novel-content/v1", "target-event-only/v1"),
        default="target-episode-novel-content/v1",
    )
    analyze.add_argument("--output")
    schema = commands.add_parser("schema", help="generate the experimental exchange schema")
    schema.add_argument("--output")
    mapping_prepare = commands.add_parser(
        "prepare-mapping", help="prepare one recall unit's mapping"
    )
    mapping_prepare.add_argument("account")
    mapping_prepare.add_argument("unit")
    mapping_prepare.add_argument(
        "--interpretation", choices=("narrative", "referent"), default="referent"
    )
    mapping_prepare.add_argument("--expected-sha256")
    mapping_prepare.add_argument("--output")
    mapping_contract = commands.add_parser(
        "mapping-prompt", help="render the unit-only mapping input"
    )
    mapping_contract.add_argument("request")
    mapping_contract.add_argument("--output")
    mapping_import = commands.add_parser("import-mapping", help="record an exported mapping reply")
    mapping_import.add_argument("request")
    mapping_import.add_argument("reply")
    mapping_import.add_argument("recording")
    mapping_replay = commands.add_parser(
        "replay-mapping", help="resolve a recorded mapping offline"
    )
    mapping_replay.add_argument("request")
    mapping_replay.add_argument("recording")
    mapping_replay.add_argument("--expected-import-sha256")
    mapping_replay.add_argument("--account-only", action="store_true")
    mapping_replay.add_argument("--output")
    details_prepare = commands.add_parser(
        "prepare-details", help="prepare selected v4 recall inventories"
    )
    details_prepare.add_argument("account")
    details_prepare.add_argument("units", nargs="+")
    details_prepare.add_argument("--output")
    details_contract = commands.add_parser(
        "detail-prompt", help="render a snapshot-bound detail request"
    )
    details_contract.add_argument("request")
    details_contract.add_argument("--output")
    details_import = commands.add_parser(
        "import-details", help="record an exported detail inventory reply"
    )
    details_import.add_argument("request")
    details_import.add_argument("reply")
    details_import.add_argument("recording")
    details_replay = commands.add_parser(
        "replay-details", help="admit recorded v4 recall details offline"
    )
    details_replay.add_argument("request")
    details_replay.add_argument("recording")
    details_replay.add_argument("--expected-import-sha256")
    details_replay.add_argument("--account-only", action="store_true")
    details_replay.add_argument("--output")
    rich_prepare = commands.add_parser(
        "prepare-rich", help="prepare anchored v4 graph construction"
    )
    rich_prepare.add_argument("text")
    rich_prepare.add_argument("rows")
    rich_prepare.add_argument("--document-key", required=True)
    rich_prepare.add_argument("--side", required=True, choices=("source", "recall"))
    rich_prepare.add_argument("--allow-export", action="store_true")
    rich_prepare.add_argument("--output")
    rich_contract = commands.add_parser("rich-prompt", help="render the v4 construction contract")
    rich_contract.add_argument("request")
    rich_contract.add_argument("--output")
    rich_import = commands.add_parser(
        "import-rich", help="record an exported v4 construction reply"
    )
    rich_import.add_argument("request")
    rich_import.add_argument("reply")
    rich_import.add_argument("recording")
    rich_replay = commands.add_parser("replay-rich", help="construct a v4 account offline")
    rich_replay.add_argument("request")
    rich_replay.add_argument("recording")
    rich_replay.add_argument("--expected-import-sha256")
    rich_replay.add_argument("--account-only", action="store_true")
    rich_replay.add_argument("--output")
    situations = commands.add_parser(
        "situation-table", help="inspect v4 events, states and circumstances"
    )
    situations.add_argument("account")
    situations.add_argument("--output")
    imported = commands.add_parser(
        "import-time-recording", help="import exported time-study replies offline"
    )
    imported.add_argument("request")
    imported.add_argument("replies")
    imported.add_argument("recording")
    imported.add_argument("--expected-request-sha256")
    replay = commands.add_parser("replay-time-study", help="resolve a recorded time study offline")
    replay.add_argument("request")
    replay.add_argument("recording")
    replay.add_argument("--expected-request-sha256")
    replay.add_argument("--expected-import-sha256")
    replay.add_argument("--output")
    prepare = commands.add_parser(
        "prepare-time-study", help="prepare raw recall and native source clocks"
    )
    prepare.add_argument("source")
    prepare.add_argument("recall")
    prepare.add_argument("--recall-key", required=True)
    prepare.add_argument("--source-sha256")
    prepare.add_argument("--recall-sha256")
    prepare.add_argument("--chunk-size", type=int, default=50)
    prepare.add_argument("--max-attempts", type=int, default=1)
    prepare.add_argument("--allow-export", action="store_true")
    prepare.add_argument("--output")
    extract_prepare = commands.add_parser(
        "prepare-extraction", help="prepare raw recall extraction"
    )
    extract_prepare.add_argument("recall")
    extract_prepare.add_argument("--recall-key", required=True)
    extract_prepare.add_argument("--source-account")
    extract_prepare.add_argument("--allow-export", action="store_true")
    extract_prepare.add_argument("--output")
    extract_prompt = commands.add_parser(
        "extraction-prompt", help="write the recorded proposal contract"
    )
    extract_prompt.add_argument("request")
    extract_prompt.add_argument("--output")
    extract_import = commands.add_parser(
        "import-extraction", help="record an exported extraction reply"
    )
    extract_import.add_argument("request")
    extract_import.add_argument("reply")
    extract_import.add_argument("recording")
    extract_replay = commands.add_parser(
        "replay-extraction", help="resolve recorded extraction offline"
    )
    extract_replay.add_argument("request")
    extract_replay.add_argument("recording")
    extract_replay.add_argument("--expected-import-sha256")
    extract_replay.add_argument("--account-only", action="store_true")
    extract_replay.add_argument("--output")
    source_prepare = commands.add_parser(
        "prepare-source", help="prepare independently anchored source rows"
    )
    source_prepare.add_argument("text")
    source_prepare.add_argument("rows")
    source_prepare.add_argument("--source-key", required=True)
    source_prepare.add_argument("--allow-export", action="store_true")
    source_prepare.add_argument("--output")
    source_contract = commands.add_parser(
        "source-prompt", help="write the recorded source proposal contract"
    )
    source_contract.add_argument("request")
    source_contract.add_argument("--output")
    source_import = commands.add_parser("import-source", help="record an exported source reply")
    source_import.add_argument("request")
    source_import.add_argument("reply")
    source_import.add_argument("recording")
    source_replay = commands.add_parser("replay-source", help="compile a recorded source offline")
    source_replay.add_argument("request")
    source_replay.add_argument("recording")
    source_replay.add_argument("--expected-import-sha256")
    source_replay.add_argument("--account-only", action="store_true")
    source_replay.add_argument("--output")
    relation_admit = commands.add_parser(
        "admit-relations", help="admit an input-bound recorded event or state relation batch"
    )
    relation_admit.add_argument("account")
    relation_admit.add_argument("proposals")
    relation_admit.add_argument("--expected-sha256")
    relation_admit.add_argument("--account-only", action="store_true")
    relation_admit.add_argument("--output")
    relation_inspect = commands.add_parser(
        "relation-table", help="inspect existing relations with contexts and exact evidence"
    )
    relation_inspect.add_argument("account")
    relation_inspect.add_argument("--expected-sha256")
    relation_inspect.add_argument("--output")
    scene_group = commands.add_parser(
        "group-source-rows", help="group source rows with explicit identity remapping"
    )
    scene_group.add_argument("account")
    scene_group.add_argument("groups")
    scene_group.add_argument("--expected-sha256")
    scene_group.add_argument("--account-only", action="store_true")
    scene_group.add_argument("--output")
    fidelity_prepare = commands.add_parser(
        "prepare-fidelity", help="prepare a separate graded review of pending hypotheses"
    )
    fidelity_prepare.add_argument("account")
    fidelity_prepare.add_argument("unit")
    fidelity_prepare.add_argument(
        "--interpretation", choices=("narrative", "referent"), default="referent"
    )
    fidelity_prepare.add_argument("--expected-sha256")
    fidelity_prepare.add_argument("--output")
    for command in ("fidelity-prompt", "import-fidelity", "replay-fidelity"):
        fidelity_command = commands.add_parser(
            command, help="record or replay conditional fidelity"
        )
        fidelity_command.add_argument("request")
        fidelity_command.add_argument("--expected-request-sha256")
        if command == "import-fidelity":
            fidelity_command.add_argument("reply")
        if command != "fidelity-prompt":
            fidelity_command.add_argument("recording")
        if command == "replay-fidelity":
            fidelity_command.add_argument("--expected-import-sha256")
            fidelity_command.add_argument("--account-only", action="store_true")
        if command != "import-fidelity":
            fidelity_command.add_argument("--output")
    claim_prepare = commands.add_parser(
        "prepare-claim-assessment",
        help="freeze a declared question population against an account snapshot",
    )
    claim_prepare.add_argument("account")
    claim_prepare.add_argument("questions")
    claim_prepare.add_argument("--expected-sha256")
    claim_prepare.add_argument("--output")
    for command in ("assess-claims", "verify-claims"):
        claim_command = commands.add_parser(
            command, help="check fixed-population attributed text assessments"
        )
        claim_command.add_argument("plan")
        claim_command.add_argument("reply")
        if command == "verify-claims":
            claim_command.add_argument("result")
        claim_command.add_argument("--output")
    identity_resolve = commands.add_parser(
        "resolve-identity", help="resolve document-local identity from recorded form annotations"
    )
    identity_resolve.add_argument("account")
    identity_resolve.add_argument("annotations")
    identity_resolve.add_argument("--expected-sha256")
    identity_resolve.add_argument("--output")
    identity_inspect = commands.add_parser(
        "identity-roles", help="inspect event roles through a verified identity sidecar"
    )
    identity_inspect.add_argument("account")
    identity_inspect.add_argument("annotations")
    identity_inspect.add_argument("result")
    identity_inspect.add_argument("--expected-sha256")
    identity_inspect.add_argument("--output")
    align = commands.add_parser(
        "align", help="generate sparse candidates and partial references offline"
    )
    align.add_argument("account")
    align.add_argument("--expected-sha256")
    align.add_argument("--policy-file")
    align.add_argument("--account-only", action="store_true")
    align.add_argument("--output")
    sequence = commands.add_parser("infer-sequence", help="infer from sparse numeric inputs")
    sequence.add_argument("request")
    sequence.add_argument("--output")
    sequence_check = commands.add_parser(
        "verify-sequence", help="recompute a saved result against its independent input"
    )
    sequence_check.add_argument("request")
    sequence_check.add_argument("result")
    for name in ("build-source-sequence", "verify-source-sequence"):
        source_sequence = commands.add_parser(
            name, help="derive or verify source graph transitions"
        )
        source_sequence.add_argument("source")
        source_sequence.add_argument("recall")
        source_sequence.add_argument("spec")
        source_sequence.add_argument("--source-sha256")
        source_sequence.add_argument("--recall-sha256")
        if name == "build-source-sequence":
            source_sequence.add_argument("--request-only", action="store_true")
            source_sequence.add_argument("--output")
        else:
            source_sequence.add_argument("result")
    args = parser.parse_args(argv)
    try:
        if args.command == "example":
            emit(example_account().model_dump(mode="json"), args.output)
        elif args.command in ("build-source-sequence", "verify-source-sequence"):
            graph_source = read_supported_account(
                Path(args.source), expected_sha256=args.source_sha256
            )
            building = args.command == "build-source-sequence"
            if building and graph_source.export_permission != "allowed":
                raise PermissionError("producer denies source sequence export")
            graph_recall = read_supported_account(
                Path(args.recall), expected_sha256=args.recall_sha256
            )
            if building and graph_recall.export_permission != "allowed":
                raise PermissionError("producer denies recall sequence export")
            graph_spec = SourceSequenceSpec.model_validate_json(
                canonical(strict_json(Path(args.spec).read_bytes()))
            )
            if building:
                graph_result = build_source_sequence(graph_source, graph_recall, graph_spec)
                emit(
                    (graph_result.request if args.request_only else graph_result).model_dump(
                        mode="json"
                    ),
                    args.output,
                )
            else:
                graph_result = verify_source_sequence(
                    graph_source,
                    graph_recall,
                    graph_spec,
                    SourceSequenceResult.model_validate_json(
                        canonical(strict_json(Path(args.result).read_bytes()))
                    ),
                )
                emit({"valid": True, "spec_sha256": graph_result.spec_sha256}, None)
        elif args.command in ("infer-sequence", "verify-sequence"):
            sequence_request = validate_sequence(Path(args.request).read_bytes())
            if args.command == "infer-sequence":
                if sequence_request.export_permission != "allowed":
                    raise PermissionError("producer denies sequence result export")
                emit(infer_sequence(sequence_request).model_dump(mode="json"), args.output)
            else:
                verified_sequence = verify_sequence(
                    sequence_request, Path(args.result).read_bytes()
                )
                emit({"valid": True, "request_sha256": verified_sequence.request_sha256}, None)
        elif args.command == "schema":
            emit(
                {"account": Account.model_json_schema(), "analysis": Analysis.model_json_schema()},
                args.output,
            )
        elif args.command == "group-source-rows":
            scene_account = read_supported_account(
                Path(args.account), expected_sha256=args.expected_sha256
            )
            if scene_account.export_permission != "allowed":
                raise PermissionError("producer denies scene grouping export")
            scene_data = strict_json(Path(args.groups).read_bytes())
            if not isinstance(scene_data, list):
                raise ValueError("scene groups must be a JSON array")
            scene_groups = tuple(
                SceneGroup.model_validate_json(canonical(group)) for group in scene_data
            )
            grouped = group_source_rows(scene_account, scene_groups)
            emit(
                (grouped.account if args.account_only else grouped).model_dump(mode="json"),
                args.output,
            )
        elif args.command == "prepare-fidelity":
            fidelity_account = read_supported_account(
                Path(args.account), expected_sha256=args.expected_sha256
            )
            if fidelity_account.export_permission != "allowed":
                raise PermissionError("producer denies fidelity export")
            emit(
                prepare_fidelity(
                    fidelity_account, args.unit, interpretation=args.interpretation
                ).model_dump(mode="json"),
                args.output,
            )
        elif args.command in ("fidelity-prompt", "import-fidelity", "replay-fidelity"):
            fidelity_request = read_fidelity_request(
                Path(args.request), expected_sha256=args.expected_request_sha256
            )
            if fidelity_request.account.export_permission != "allowed":
                raise PermissionError("producer denies fidelity export")
            if args.command == "fidelity-prompt":
                emit(strict_json(fidelity_prompt(fidelity_request)), args.output)
            elif args.command == "import-fidelity":
                fidelity_recording = FidelityRecording.model_validate_json(
                    canonical(strict_json(Path(args.reply).read_bytes()))
                )
                import_fidelity(fidelity_request, fidelity_recording, Path(args.recording))
                emit({"recording": args.recording, "imported": 1}, None)
            else:
                fidelity_result = replay_fidelity(
                    fidelity_request,
                    Path(args.recording),
                    expected_import_sha256=args.expected_import_sha256,
                )
                if args.account_only and any(
                    d.status != "admitted" for d in fidelity_result.decisions
                ):
                    raise ValueError(
                        "fidelity account-only export would discard unsuccessful outcomes"
                    )
                emit(
                    (fidelity_result.account if args.account_only else fidelity_result).model_dump(
                        mode="json"
                    ),
                    args.output,
                )
        elif args.command == "prepare-claim-assessment":
            claim_account = read_supported_account(
                Path(args.account), expected_sha256=args.expected_sha256
            )
            if claim_account.export_permission != "allowed":
                raise PermissionError("producer denies claim assessment export")
            question_data = strict_json(Path(args.questions).read_bytes())
            if not isinstance(question_data, list):
                raise ValueError("claim questions must be a JSON array")
            questions = tuple(
                ClaimQuestion.model_validate_json(canonical(q)) for q in question_data
            )
            emit(
                prepare_claim_assessment(claim_account, questions).model_dump(mode="json"),
                args.output,
            )
        elif args.command in ("assess-claims", "verify-claims"):
            claim_plan = validate_claim_plan(Path(args.plan).read_bytes())
            if claim_plan.account.export_permission != "allowed":
                raise PermissionError("producer denies claim assessment export")
            claim_reply = Path(args.reply).read_bytes()
            if args.command == "assess-claims":
                emit(assess_claims(claim_plan, claim_reply).model_dump(mode="json"), args.output)
            else:
                verified_claims = verify_claim_assessment(
                    claim_plan, claim_reply, Path(args.result).read_bytes()
                )
                emit(
                    {"valid": True, "population_sha256": verified_claims.population_sha256},
                    args.output,
                )
        elif args.command == "align":
            account = read_supported_account(
                Path(args.account), expected_sha256=args.expected_sha256
            )
            if account.export_permission != "allowed":
                raise PermissionError("producer denies alignment export")
            alignment = align_account(
                account,
                validate_policy(Path(args.policy_file).read_bytes()) if args.policy_file else None,
            )
            emit(
                (alignment.account if args.account_only else alignment).model_dump(mode="json"),
                args.output,
            )
        elif args.command == "prepare-details":
            detail_account = validate_rich_account(Path(args.account).read_bytes())
            if detail_account.export_permission != "allowed":
                raise PermissionError("producer denies detail export")
            detail_request = prepare_details(detail_account, tuple(args.units))
            emit(detail_request.model_dump(mode="json"), args.output)
        elif args.command in ("detail-prompt", "import-details", "replay-details"):
            detail_request = read_detail_request(Path(args.request))
            if detail_request.account.export_permission != "allowed":
                raise PermissionError("producer denies detail export")
            if args.command == "detail-prompt":
                emit(strict_json(detail_prompt(detail_request)), args.output)
            elif args.command == "import-details":
                detail_recording = DetailRecording.model_validate_json(
                    canonical(strict_json(Path(args.reply).read_bytes()))
                )
                import_details(detail_request, detail_recording, Path(args.recording))
            else:
                detail_result = replay_details(
                    detail_request,
                    Path(args.recording),
                    expected_import_sha256=args.expected_import_sha256,
                )
                if args.account_only and any(
                    r.status != "admitted" for r in detail_result.receipts
                ):
                    raise ValueError("account-only export would hide incomplete detail admission")
                emit(
                    (detail_result.account if args.account_only else detail_result).model_dump(
                        mode="json"
                    ),
                    args.output,
                )
        elif args.command == "prepare-rich":
            row_values = strict_json(Path(args.rows).read_bytes())
            if not isinstance(row_values, list):
                raise ValueError("rich construction rows must be a JSON array")
            rich_request = prepare_rich_construction(
                args.document_key,
                args.side,
                Path(args.text).read_bytes().decode("utf-8"),
                tuple(SourceRow.model_validate_json(canonical(row)) for row in row_values),
                export_permission="allowed" if args.allow_export else "denied",
            )
            emit(rich_request.model_dump(mode="json"), args.output)
        elif args.command == "situation-table":
            rich_account = validate_rich_account(Path(args.account).read_bytes())
            if rich_account.export_permission != "allowed":
                raise PermissionError("producer denies situation export")
            emit(
                [row.model_dump(mode="json") for row in situation_table(rich_account)], args.output
            )
        elif args.command in ("rich-prompt", "import-rich", "replay-rich"):
            rich_request = read_rich_request(Path(args.request))
            if rich_request.export_permission != "allowed":
                raise PermissionError("producer denies rich construction export")
            if args.command == "rich-prompt":
                emit(strict_json(rich_prompt(rich_request)), args.output)
            elif args.command == "import-rich":
                rich_recording = RichRecording.model_validate_json(
                    canonical(strict_json(Path(args.reply).read_bytes()))
                )
                import_rich(rich_request, rich_recording, Path(args.recording))
            else:
                rich_result = replay_rich(
                    rich_request,
                    Path(args.recording),
                    expected_import_sha256=args.expected_import_sha256,
                )
                if args.account_only and any(
                    r.status != "admitted" for r in rich_result.resolutions
                ):
                    raise ValueError("account-only export would hide incomplete rich construction")
                emit(
                    (rich_result.account if args.account_only else rich_result).model_dump(
                        mode="json"
                    ),
                    args.output,
                )
        elif args.command == "prepare-mapping":
            mapping_account = read_supported_account(
                Path(args.account), expected_sha256=args.expected_sha256
            )
            if mapping_account.export_permission != "allowed":
                raise PermissionError("producer denies mapping export")
            mapping_request = prepare_mapping(
                mapping_account, args.unit, interpretation=args.interpretation
            )
            emit(mapping_request.model_dump(mode="json"), args.output)
        elif args.command in ("mapping-prompt", "import-mapping", "replay-mapping"):
            mapping_request = read_mapping_request(Path(args.request))
            if mapping_request.account.export_permission != "allowed":
                raise PermissionError("producer denies mapping export")
            if args.command == "mapping-prompt":
                emit(strict_json(mapping_prompt(mapping_request)), args.output)
            elif args.command == "import-mapping":
                mapping_recording = MappingRecording.model_validate_json(
                    canonical(strict_json(Path(args.reply).read_bytes()))
                )
                import_mapping(mapping_request, mapping_recording, Path(args.recording))
            else:
                mapping_result = replay_mapping(
                    mapping_request,
                    Path(args.recording),
                    expected_import_sha256=args.expected_import_sha256,
                )
                if args.account_only and any(
                    d.status != "admitted" for d in mapping_result.decisions
                ):
                    raise ValueError(
                        "account-only export would hide unsuccessful mapping admission"
                    )
                emit(
                    (mapping_result.account if args.account_only else mapping_result).model_dump(
                        mode="json"
                    ),
                    args.output,
                )
        elif args.command in ("resolve-identity", "identity-roles"):
            identity_account = read_supported_account(
                Path(args.account), expected_sha256=args.expected_sha256
            )
            if identity_account.export_permission != "allowed":
                raise PermissionError("producer denies identity export")
            annotations = Path(args.annotations).read_bytes().decode("utf-8")
            if args.command == "resolve-identity":
                emit(
                    resolve_text_identity(identity_account, annotations).model_dump(mode="json"),
                    args.output,
                )
            else:
                identities = IdentityResult.model_validate_json(
                    canonical(strict_json(Path(args.result).read_bytes()))
                )
                emit(
                    [
                        row.model_dump(mode="json")
                        for row in identity_role_table(identity_account, annotations, identities)
                    ],
                    args.output,
                )
        elif args.command in ("admit-relations", "relation-table"):
            relation_account = read_supported_account(
                Path(args.account), expected_sha256=args.expected_sha256
            )
            if relation_account.export_permission != "allowed":
                raise PermissionError("producer denies relation export")
            if args.command == "relation-table":
                emit(
                    [row.model_dump(mode="json") for row in relation_table(relation_account)],
                    args.output,
                )
            else:
                relation_result = admit_relations(
                    relation_account, Path(args.proposals).read_bytes().decode("utf-8")
                )
                if args.account_only and relation_result.status in (
                    "rejected",
                    "not-attempted",
                    "failed",
                ):
                    raise ValueError(
                        "account-only export would hide unsuccessful relation admission"
                    )
                emit(
                    (relation_result.account if args.account_only else relation_result).model_dump(
                        mode="json"
                    ),
                    args.output,
                )
        elif args.command == "prepare-extraction":
            extraction = prepare_extraction(
                args.recall_key,
                Path(args.recall).read_bytes(),
                source=read_account(Path(args.source_account)) if args.source_account else None,
                export_permission="allowed" if args.allow_export else "denied",
            )
            emit(extraction.model_dump(mode="json"), args.output)
        elif args.command == "prepare-source":
            raw_rows = strict_json(Path(args.rows).read_bytes())
            if not isinstance(raw_rows, list):
                raise ValueError("source rows must be a JSON array")
            rows = tuple(SourceRow.model_validate(item) for item in raw_rows)
            source = prepare_source(
                args.source_key,
                Path(args.text).read_bytes().decode("utf-8"),
                rows,
                export_permission="allowed" if args.allow_export else "denied",
            )
            emit(source.model_dump(mode="json"), args.output)
        elif args.command in ("source-prompt", "import-source", "replay-source"):
            source = read_source_request(Path(args.request))
            if args.command == "source-prompt":
                emit(strict_json(source_prompt(source)), args.output)
            elif args.command == "import-source":
                source_recording = SourceRecording.model_validate_json(
                    canonical(strict_json(Path(args.reply).read_bytes()))
                )
                import_source(source, source_recording, Path(args.recording))
                emit({"recording": args.recording, "imported": 1}, None)
            else:
                if source.export_permission != "allowed":
                    raise PermissionError("producer denies source export")
                compiled = replay_source(
                    source,
                    Path(args.recording),
                    expected_import_sha256=args.expected_import_sha256,
                )
                emit(
                    (compiled.account if args.account_only else compiled).model_dump(mode="json"),
                    args.output,
                )
        elif args.command in ("extraction-prompt", "import-extraction", "replay-extraction"):
            extraction = read_extraction_request(Path(args.request))
            if args.command == "extraction-prompt":
                emit(strict_json(extraction_prompt(extraction)), args.output)
            elif args.command == "import-extraction":
                extraction_recording = ExtractionRecording.model_validate_json(
                    canonical(strict_json(Path(args.reply).read_bytes()))
                )
                import_extraction(extraction, extraction_recording, Path(args.recording))
                emit({"recording": args.recording, "imported": 1}, None)
            else:
                if extraction.export_permission != "allowed":
                    raise PermissionError("producer denies extraction export")
                extracted = replay_extraction(
                    extraction,
                    Path(args.recording),
                    expected_import_sha256=args.expected_import_sha256,
                )
                emit(
                    (extracted.account if args.account_only else extracted).model_dump(mode="json"),
                    args.output,
                )
        elif args.command == "prepare-time-study":
            prepared = prepare_time_study_files(
                Path(args.source),
                Path(args.recall),
                args.recall_key,
                expected_source_sha256=args.source_sha256,
                expected_recall_sha256=args.recall_sha256,
                chunk_size=args.chunk_size,
                max_attempts=args.max_attempts,
                export_permission="allowed" if args.allow_export else "denied",
            )
            emit(prepared.model_dump(mode="json"), args.output)
        elif args.command in ("import-time-recording", "replay-time-study"):
            request = read_request(Path(args.request), expected_sha256=args.expected_request_sha256)
            if args.command == "import-time-recording":
                replies = ImportedReplies.model_validate_json(
                    canonical(strict_json(Path(args.replies).read_bytes()))
                )
                import_time_replies(request, replies, Path(args.recording))
                emit({"imported": len(replies.replies), "recording": args.recording}, None)
            else:
                if request.export_permission != "allowed":
                    raise PermissionError("producer denies study result export")
                result = replay_time_study(
                    request,
                    Path(args.recording),
                    expected_import_sha256=args.expected_import_sha256,
                )
                emit(result.model_dump(mode="json"), args.output)
        else:
            supported_account = read_supported_account(
                Path(args.account), expected_sha256=args.expected_sha256
            )
            if args.command == "validate":
                counts = {
                    "valid": True,
                    "details": len(supported_account.details),
                    "events": len(supported_account.events),
                }
                if isinstance(supported_account, RichAccount):
                    counts["states"] = len(supported_account.states)
                emit(counts, None)
            else:
                if supported_account.export_permission != "allowed":
                    raise PermissionError("producer denies analysis export")
                policy: Policy = args.policy
                emit(project(supported_account, policy).model_dump(mode="json"), args.output)
    except (ValueError, OSError, RecordedExecutionError) as error:
        print(f"refused: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
