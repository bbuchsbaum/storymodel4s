"""Copied public objects must undergo the same strict admission as JSON inputs."""

from __future__ import annotations

from pathlib import Path

import pytest

from storymodel.alignment import AlignmentPolicy, align_account, validate_policy, verify_alignment
from storymodel.demo import example_account
from storymodel.detail_admission import (
    admit_details,
    prepare_details,
    validate_detail_request,
    verify_details,
)
from storymodel.detail_workflow import DetailRecording, import_details
from storymodel.direct_mapping import (
    mapping_prompt,
    prepare_mapping,
    resolve_mapping,
    validate_mapping_request,
    verify_mapping,
)
from storymodel.direct_workflow import MappingRecording, import_mapping
from storymodel.execution import RecordedRun, RunProfile
from storymodel.extraction import resolve_extraction
from storymodel.extraction import validate_request as validate_extraction_request
from storymodel.extraction_demo import example_proposal, example_request
from storymodel.extraction_workflow import ExtractionRecording, import_extraction
from storymodel.identity import resolve_text_identity, verify_identity
from storymodel.intake_workflow import prepare_time_study, validate_prepared_study
from storymodel.model import Span, account_digest, validate_account
from storymodel.pipeline import replay_account
from storymodel.recall_intake import prepare_recall, validate_prepared_recall
from storymodel.relations import admit_relations, verify_relations
from storymodel.rich_account import (
    upgrade_account,
    validate_rich_account,
    validate_supported_account,
)
from storymodel.rich_construction import (
    compile_rich_construction,
    prepare_rich_construction,
    validate_rich_construction_request,
    verify_rich_construction,
)
from storymodel.rich_workflow import RichRecording, import_rich
from storymodel.serialization import canonical, digest, strict_json
from storymodel.source import (
    SourceRow,
    compile_source,
    source_request_digest,
    validate_source_request,
    verify_source,
)
from storymodel.source_demo import example_source_proposal, example_source_request
from storymodel.source_intake import NativeSource, validate_native_source
from storymodel.source_workflow import SourceRecording, import_source
from storymodel.study_time import resolve_time_item
from storymodel.study_workflow import (
    ImportedReplies,
    RawReply,
    checked_request,
    import_time_replies,
)
from storymodel.temporal import Interval, allocate


def rejects(check, forged) -> None:
    with pytest.raises(ValueError):
        check(forged)


def rich_mapping_request():
    """Use an admitted v3 fixture so v2 dispatch has a real unbound recall detail."""
    legacy = resolve_extraction(example_request(), example_proposal()).account
    legacy = legacy.model_copy(update={"schema_version": "storymodel.account/v3"})
    detail = legacy.details[0].model_copy(update={"address": "target"})
    rich = upgrade_account(legacy.model_copy(update={"details": (detail,)}))
    return prepare_mapping(rich, detail.unit)


def legacy_mapping_request():
    account = resolve_extraction(example_request(), example_proposal()).account
    return prepare_mapping(account, account.details[0].unit)


@pytest.mark.parametrize(
    "family",
    (
        "account",
        "rich-account",
        "supported-account",
        "source-request",
        "extraction-request",
        "mapping-v1",
        "mapping-v2",
        "rich-construction-request",
        "prepared-recall",
        "native-source",
    ),
)
def test_public_validators_rebuild_valid_objects_and_raw_snapshots_identically(family):
    account = example_account()
    source = example_source_request()
    rich_request = prepare_rich_construction(
        "control", "source", "AA BB", (SourceRow(key="r", span=Span(start=0, end=5)),)
    )
    prepared = prepare_recall("control", "AA BB.")
    native = NativeSource.model_validate_json(
        (Path(__file__).parent / "fixtures" / "native-time-source.json").read_bytes()
    )
    validators = {
        "account": (validate_account, account, True),
        "rich-account": (
            validate_rich_account,
            upgrade_account(account.model_copy(update={"schema_version": "storymodel.account/v3"})),
            True,
        ),
        "supported-account": (validate_supported_account, account, True),
        "source-request": (validate_source_request, source, True),
        "extraction-request": (validate_extraction_request, example_request(), True),
        "mapping-v1": (validate_mapping_request, legacy_mapping_request(), True),
        "mapping-v2": (validate_mapping_request, rich_mapping_request(), True),
        "rich-construction-request": (validate_rich_construction_request, rich_request, True),
        "prepared-recall": (validate_prepared_recall, prepared, False),
        "native-source": (validate_native_source, native, False),
    }
    validate, value, accepts_raw = validators[family]
    checked = validate(value)
    if accepts_raw:
        raw_checked = validate(value.model_dump_json())
        assert canonical(checked.model_dump(mode="json")) == canonical(
            raw_checked.model_dump(mode="json")
        )
    assert account_digest(account) == account_digest(validate_account(account))
    rejects(validate, value.model_copy(update={"unexpected": 1}))


def test_copied_boolean_numbers_and_unknown_fields_refuse_at_public_boundaries():
    policy = AlignmentPolicy()
    rejects(validate_policy, policy.model_construct(max_candidates=True))
    rejects(validate_policy, policy.model_copy(update={"max_candidates": True}))
    rejects(validate_policy, policy.model_copy(update={"unexpected": 1}))

    account = example_account()
    rejects(validate_account, account.model_copy(update={"unexpected": 1}))
    request = example_source_request()
    valid_span = request.rows[0].span
    rejects(
        validate_source_request,
        request.model_copy(
            update={
                "rows": (
                    request.rows[0].model_copy(
                        update={"span": valid_span.model_copy(update={"start": False})}
                    ),
                    *request.rows[1:],
                )
            }
        ),
    )
    rejects(validate_source_request, request.model_copy(update={"unexpected": 1}))

    legacy = legacy_mapping_request()
    rejects(validate_mapping_request, legacy.model_copy(update={"unexpected": 1}))
    rich = rich_mapping_request()
    rejects(validate_mapping_request, rich.model_copy(update={"unexpected": 1}))

    prepared = prepare_recall("control", "AA BB.")
    rejects(validate_prepared_recall, prepared.model_copy(update={"unexpected": 1}))
    native = NativeSource.model_validate_json(
        (Path(__file__).parent / "fixtures" / "native-time-source.json").read_bytes()
    )
    rejects(validate_native_source, native.model_copy(update={"unexpected": 1}))


def test_verifiers_temporal_and_recording_import_revalidate_before_effects(tmp_path):
    account = example_account()
    result = align_account(account)
    assert verify_alignment(account, result) == result
    rejects(
        lambda value: verify_alignment(account, value),
        result.model_copy(update={"unexpected": 1}),
    )

    source = example_source_request()
    compiled = verify_source(
        source, example_source_proposal(), compile_source(source, example_source_proposal())
    )
    assert compiled.account.export_permission == "allowed"
    recording = SourceRecording(
        request_sha256=source_request_digest(source),
        origin="control",
        reply=example_source_proposal(),
    )
    written = tmp_path / "valid-import"
    import_source(source, recording, written)
    assert (written / "import.json").exists()
    forged_source = source.model_copy(update={"unexpected": 1})
    untouched_request = tmp_path / "forged-request"
    rejects(lambda value: import_source(value, recording, untouched_request), forged_source)
    assert not untouched_request.exists()
    untouched_recording = tmp_path / "forged-recording"
    rejects(
        lambda value: import_source(source, value, untouched_recording),
        recording.model_copy(update={"unexpected": 1}),
    )
    assert not untouched_recording.exists()

    interval = Interval(clock="recall", start="0", end="1")
    assert allocate("1", interval, (interval,), "held-level")[0].value == "1"
    rejects(
        lambda value: allocate("1", value, (interval,), "held-level"),
        interval.model_copy(update={"start": True}),
    )

    native = NativeSource.model_validate_json(
        (Path(__file__).parent / "fixtures" / "native-time-source.json").read_bytes()
    )
    study = prepare_time_study(native, "control", b"AA BB.")
    assert validate_prepared_study(study) == study
    rejects(validate_prepared_study, study.model_copy(update={"chunk_size": True}))


def rich_control():
    request = prepare_rich_construction(
        "boundary-control",
        "recall",
        "AA BB",
        (SourceRow(key="r", span=Span(start=0, end=5)),),
        export_permission="allowed",
    )
    raw = canonical({"schema_version": "storymodel.rich-construction-proposal/v1", "rows": []})
    return request, raw


def detail_control():
    request, raw = rich_control()
    account = compile_rich_construction(request, raw).account
    return prepare_details(account, (account.units[0].id,))


@pytest.mark.parametrize(
    "family", ["source", "extraction", "rich", "detail", "mapping-v1", "mapping-v2"]
)
def test_each_recording_import_refuses_forged_objects_before_creating_files(tmp_path, family):
    request, recording_class, importer = {
        "source": (example_source_request(), SourceRecording, import_source),
        "extraction": (example_request(), ExtractionRecording, import_extraction),
        "rich": (rich_control()[0], RichRecording, import_rich),
        "detail": (detail_control(), DetailRecording, import_details),
        "mapping-v1": (legacy_mapping_request(), MappingRecording, import_mapping),
        "mapping-v2": (rich_mapping_request(), MappingRecording, import_mapping),
    }[family]
    recording = recording_class(
        request_sha256=digest(request.model_dump(mode="json")), origin="control", reply="{}"
    )
    valid = tmp_path / "valid"
    importer(request, recording, valid)
    assert (valid / "import.json").is_file()
    for label, supplied_request, supplied_recording in (
        ("request", request.model_copy(update={"unexpected": 1}), recording),
        ("recording", request, recording.model_copy(update={"unexpected": 1})),
    ):
        destination = tmp_path / label
        with pytest.raises(ValueError):
            importer(supplied_request, supplied_recording, destination)
        assert not destination.exists()


@pytest.mark.parametrize(
    "family", ["source", "rich", "detail", "mapping-v1", "mapping-v2", "identity", "relations"]
)
def test_each_result_verifier_rejects_extras_in_an_otherwise_matching_receipt(family):
    if family == "source":
        request, raw = example_source_request(), example_source_proposal()
        result, verify = compile_source(request, raw), verify_source
    elif family == "rich":
        request, raw = rich_control()
        result, verify = compile_rich_construction(request, raw), verify_rich_construction
    elif family == "detail":
        request = detail_control()
        assert validate_detail_request(request) == validate_detail_request(
            request.model_dump_json()
        )
        raw = canonical(
            {
                "schema_version": "storymodel.detail-proposal/v1",
                "request_sha256": digest(request.model_dump(mode="json")),
                "units": [],
            }
        )
        result, verify = admit_details(request, raw), verify_details
    elif family.startswith("mapping"):
        request = legacy_mapping_request() if family == "mapping-v1" else rich_mapping_request()
        raw = canonical(
            {
                "schema_version": "storymodel.mapping-proposal/v1",
                "packet_sha256": strict_json(mapping_prompt(request))["packet_sha256"],
                "details": [],
            }
        )
        result, verify = resolve_mapping(request, raw), verify_mapping
    else:
        request = example_account()
        common = {"account_sha256": account_digest(request)}
        if family == "identity":
            raw = canonical(
                {
                    **common,
                    "schema_version": "storymodel.identity-annotations/v1",
                    "origin": "control",
                    "mentions": [],
                }
            )
            result, verify = resolve_text_identity(request, raw), verify_identity
        else:
            raw = canonical(
                {
                    **common,
                    "schema_version": "storymodel.relation-proposals/v1",
                    "status": "completed",
                    "relations": [],
                }
            )
            result, verify = admit_relations(request, raw), verify_relations
    assert verify(request, raw, result) == result
    with pytest.raises(ValueError, match="Extra inputs"):
        verify(request, raw, result.model_copy(update={"unexpected": 1}))


def test_study_inputs_and_replies_validate_types_before_dispatch_or_disk(tmp_path):
    native = NativeSource.model_validate_json(
        (Path(__file__).parent / "fixtures/native-time-source.json").read_bytes()
    )
    prepared = prepare_time_study(native, "control", b"AA BB.")
    request = prepared.request
    assert checked_request(request) == request
    rejects(checked_request, request.model_copy(update={"chunk_size": True}))
    assert resolve_time_item(request.source, None)
    rejects(
        lambda value: resolve_time_item(value, None),
        request.source.model_copy(update={"unexpected": 1}),
    )
    replies = ImportedReplies(
        origin="control",
        request_sha256=digest(request.model_dump(mode="json")),
        replies=(RawReply(chunk=0, attempt=0, text="{}"),),
    )
    import_time_replies(request, replies, tmp_path / "valid")
    assert (tmp_path / "valid/import.json").is_file()
    for i, forged in enumerate(
        (
            replies.model_copy(update={"unexpected": 1}),
            replies.model_copy(
                update={"replies": (replies.replies[0].model_copy(update={"chunk": False}),)}
            ),
        )
    ):
        destination = tmp_path / f"forged-{i}"
        rejects(lambda value: import_time_replies(request, value, destination), forged)
        assert not destination.exists()


def test_temporal_and_document_consumers_cannot_drop_unknown_fields(tmp_path, monkeypatch):
    interval = Interval(clock="recall", start="0", end="1")
    assert allocate("1", interval, (interval,), "held-level")[0].value == "1"
    forged = interval.model_copy(update={"unexpected": 1})
    for support, bins in ((forged, (interval,)), (interval, (forged,))):
        with pytest.raises(ValueError, match="Extra inputs"):
            allocate("1", support, bins, "held-level")

    account = example_account()
    profile = RunProfile(
        provider="control",
        model="control",
        endpoint="offline:control",
        max_tokens=1,
        recipe=account.recipe.name,
        input_sha256=tuple(document.sha256 for document in account.documents),
    )
    run = RecordedRun(tmp_path / "recording", profile)
    run.run(("control",), transport=lambda _: account.model_dump_json(), max_new_calls=1)
    assert replay_account(run, "control", expected_documents=account.documents) == account

    def unexpected_read(*args, **kwargs):
        pytest.fail("invalid expected documents reached recording consumption")

    monkeypatch.setattr(run, "run", unexpected_read)
    documents = (account.documents[0].model_copy(update={"unexpected": 1}), *account.documents[1:])
    with pytest.raises(ValueError, match="Extra inputs"):
        replay_account(run, "control", expected_documents=documents)
