"""Small CLI over the same operations used from Python and notebooks."""

import argparse
import sys
from pathlib import Path
from typing import Any

from .analysis import Analysis, Policy, project
from .artifacts import publish_json, read_account
from .demo import example_account
from .execution import RecordedExecutionError
from .extraction import prepare_extraction
from .extraction_workflow import (
    ExtractionRecording,
    extraction_prompt,
    import_extraction,
    read_extraction_request,
    replay_extraction,
)
from .intake_workflow import prepare_time_study_files
from .model import Account, canonical
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
    args = parser.parse_args(argv)
    try:
        if args.command == "example":
            emit(example_account().model_dump(mode="json"), args.output)
        elif args.command == "schema":
            emit(
                {"account": Account.model_json_schema(), "analysis": Analysis.model_json_schema()},
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
        elif args.command in ("extraction-prompt", "import-extraction", "replay-extraction"):
            extraction = read_extraction_request(Path(args.request))
            if args.command == "extraction-prompt":
                emit(strict_json(extraction_prompt(extraction)), args.output)
            elif args.command == "import-extraction":
                recording = ExtractionRecording.model_validate_json(
                    canonical(strict_json(Path(args.reply).read_bytes()))
                )
                import_extraction(extraction, recording, Path(args.recording))
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
            account = read_account(Path(args.account), expected_sha256=args.expected_sha256)
            if args.command == "validate":
                emit(
                    {"valid": True, "details": len(account.details), "events": len(account.events)},
                    None,
                )
            else:
                if account.export_permission != "allowed":
                    raise PermissionError("producer denies analysis export")
                policy: Policy = args.policy
                emit(project(account, policy).model_dump(mode="json"), args.output)
    except (ValueError, OSError, RecordedExecutionError) as error:
        print(f"refused: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
