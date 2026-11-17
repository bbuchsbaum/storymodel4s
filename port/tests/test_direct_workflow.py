"""Offline direct-mapping recordings preserve raw replies and export boundaries."""

import json
from hashlib import sha256

import pytest

from storymodel.direct_mapping import mapping_prompt, prepare_mapping
from storymodel.direct_workflow import (
    MappingRecording,
    _profile,
    _prompt,
    import_mapping,
    read_mapping_request,
    replay_mapping,
)
from storymodel.execution import RecordedRun, TransportFailure
from storymodel.model import Account, Context, Detail, Document, Recipe, Span, Support, Unit
from storymodel.serialization import canonical, digest, strict_json


def account(*, export_permission="allowed"):
    source = Document.create("source-control", "source", "SS")
    recall = Document.create("recall-control", "recall", "RR")
    source_support = Support(document=source.id, spans=(Span(start=0, end=2),))
    recall_support = Support(document=recall.id, spans=(Span(start=0, end=2),))
    source_unit = Unit(kind="row", support=source_support)
    recall_unit = Unit(kind="recall", support=recall_support)
    return Account(
        schema_version="storymodel.account/v3",
        documents=(source, recall),
        units=(source_unit, recall_unit),
        contexts=(Context(side="source", kind="narrated"), Context(side="recall", kind="narrated")),
        details=(
            Detail(
                unit=recall_unit.id,
                kind="event",
                content="control",
                support=recall_support,
                address="unresolved",
            ),
        ),
        recipe=Recipe(
            name="direct-workflow-control/v1", input_sha256=(source.sha256, recall.sha256)
        ),
        export_permission=export_permission,
    )


def request(*, export_permission="allowed", interpretation="referent"):
    value = account(export_permission=export_permission)
    recall_unit = next(unit for unit in value.units if unit.kind == "recall")
    return prepare_mapping(value, recall_unit.id, interpretation=interpretation)


def reply(value):
    prompt = strict_json(mapping_prompt(value))
    source = next(document for document in value.account.documents if document.side == "source")
    target = next(unit for unit in value.account.units if unit.kind != "recall")
    return canonical(
        {
            "schema_version": "storymodel.mapping-proposal/v1",
            "packet_sha256": prompt["packet_sha256"],
            "details": [
                {
                    "detail": "d0",
                    "status": "attempted",
                    "outcome": "resolved",
                    "reason": "control conditional mapping",
                    "hypotheses": [{"key": "h0", "kind": "single", "targets": [target.id]}],
                    "assessments": [
                        {
                            "hypothesis": "h0",
                            "status": "completed",
                            "grade": "supported",
                            "reason": "control exact support",
                            "evidence": [
                                {
                                    "document": source.id,
                                    "span": {"start": 0, "end": 2},
                                    "text": "SS",
                                }
                            ],
                        }
                    ],
                }
            ],
        }
    )


def recording(value, raw=None):
    return MappingRecording(
        request_sha256=digest(value.model_dump(mode="json")),
        origin="abstract control fixture",
        reply=reply(value) if raw is None else raw,
    )


def test_import_replay_is_offline_and_preserves_conditional_supported_binding(
    tmp_path, monkeypatch
):
    value = request()
    monkeypatch.setattr("socket.socket", lambda *a, **k: pytest.fail("unexpected network"))
    root = tmp_path / "recording"
    import_mapping(value, recording(value), root)
    result = replay_mapping(value, root)
    assert result.decisions[0].status == "admitted"
    binding = result.account.bindings[0]
    assert binding.reference.outcome == "resolved"
    assert binding.assessments[0].grade == "supported"
    assert binding.assessments[0].evidence[0].spans[0] == Span(start=0, end=2)
    assert replay_mapping(value, root) == result


def test_malformed_raw_is_retained_before_parse_and_wrong_request_refuses_before_directory_mutation(
    tmp_path,
):
    value = request()
    root = tmp_path / "malformed"
    import_mapping(value, recording(value, "not JSON"), root)
    assert json.loads((root / "import.json").read_text())["reply"] == "not JSON"
    with pytest.raises(ValueError):
        replay_mapping(value, root)
    with pytest.raises(FileExistsError):
        import_mapping(value, recording(value), root)

    foreign = request(interpretation="narrative")
    untouched = tmp_path / "untouched"
    with pytest.raises(ValueError, match="another request"):
        import_mapping(foreign, recording(value), untouched)
    assert not untouched.exists()


def test_interrupted_cache_external_pins_and_import_cache_integrity(tmp_path):
    value = request()
    interrupted = tmp_path / "interrupted"
    with pytest.raises(TransportFailure):
        RecordedRun(interrupted, _profile(value)).run(
            (_prompt(value),),
            transport=lambda _: (_ for _ in ()).throw(RuntimeError("interrupted")),
            max_new_calls=1,
        )
    with pytest.raises(FileExistsError):
        import_mapping(value, recording(value), interrupted)

    request_path = tmp_path / "request.json"
    request_path.write_text(canonical(value.model_dump(mode="json")))
    assert (
        read_mapping_request(
            request_path, expected_sha256=sha256(request_path.read_bytes()).hexdigest()
        )
        == value
    )
    with pytest.raises(ValueError, match="external SHA"):
        read_mapping_request(request_path, expected_sha256="0" * 64)

    root = tmp_path / "recording"
    import_mapping(value, recording(value), root)
    imported = (root / "import.json").read_bytes()
    assert replay_mapping(value, root, expected_import_sha256=sha256(imported).hexdigest())
    with pytest.raises(ValueError, match="external SHA"):
        replay_mapping(value, root, expected_import_sha256="0" * 64)
    tampered = json.loads(imported)
    tampered["reply"] = "{}"
    (root / "import.json").write_text(canonical(tampered))
    with pytest.raises(ValueError, match="cached mapping differs"):
        replay_mapping(value, root)


def test_denied_request_refuses_prompt_import_and_replay_before_io(tmp_path):
    denied = request(export_permission="denied")
    root = tmp_path / "private-recording"
    with pytest.raises(PermissionError, match="denies direct mapping export"):
        _prompt(denied)
    with pytest.raises(PermissionError, match="denies direct mapping export"):
        import_mapping(denied, recording(denied, raw="{}"), root)
    assert not root.exists()
    with pytest.raises(PermissionError, match="denies direct mapping export"):
        replay_mapping(denied, root)
