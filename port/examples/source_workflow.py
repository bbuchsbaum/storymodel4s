"""Recorded source construction and reuse by two recalls, using already admitted text.

Writes a new output directory; never overwrites an existing run. No provider calls.
"""

import sys
from pathlib import Path

from storymodel import account_digest, export_account, pair_accounts, prepare_extraction, project
from storymodel.artifacts import publish_json, read_account
from storymodel.demo import RECALL_TEXT
from storymodel.extraction import resolve_extraction
from storymodel.extraction_demo import example_proposal
from storymodel.source import source_request_digest, verify_source
from storymodel.source_demo import example_source_proposal, example_source_request
from storymodel.source_workflow import SourceRecording, import_source, replay_source

destination = Path(sys.argv[1])
destination.mkdir(parents=True, exist_ok=False)
request = example_source_request()
reply = example_source_proposal()
recording = SourceRecording(
    request_sha256=source_request_digest(request),
    origin="admitted synthetic source proposal diagnostic; not a model run",
    reply=reply,
)
publish_json(destination / "request.json", request.model_dump(mode="json"))
import_source(request, recording, destination / "recording")
result = replay_source(request, destination / "recording")
verify_source(request, reply, result)
publish_json(destination / "source-result.json", result.model_dump(mode="json"))
export_account(destination / "source-account.json", result.account)
source = read_account(destination / "source-account.json")
source_sha = account_digest(source)

for session in ("source-compiler-reuse-A", "source-compiler-reuse-B"):
    recall = resolve_extraction(
        prepare_extraction(session, RECALL_TEXT.encode(), export_permission="allowed"),
        example_proposal(),
    ).account
    paired = pair_accounts(source, recall)
    export_account(destination / f"{session}.json", paired)
    publish_json(destination / f"{session}.analysis.json", project(paired).model_dump(mode="json"))
    assert paired.recipe.input_sha256[-2] == source_sha

assert account_digest(read_account(destination / "source-account.json")) == source_sha
print(f"Compiled {len(source.events)} source events; reused unchanged by 2 independent recalls.")
