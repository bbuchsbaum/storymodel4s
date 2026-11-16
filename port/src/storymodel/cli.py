"""Small CLI over the same operations used from Python and notebooks."""

import argparse
import sys
from pathlib import Path
from typing import Any

from .analysis import Analysis, Policy, project
from .artifacts import publish_json, read_account
from .demo import example_account
from .execution import RecordedExecutionError
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
    args = parser.parse_args(argv)
    try:
        if args.command == "example":
            emit(example_account().model_dump(mode="json"), args.output)
        elif args.command == "schema":
            emit(
                {"account": Account.model_json_schema(), "analysis": Analysis.model_json_schema()},
                args.output,
            )
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
