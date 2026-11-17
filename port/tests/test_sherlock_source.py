"""Synthetic courts for retained Sherlock TSV and v2 repair intake."""

import json
from hashlib import sha256
from pathlib import Path

import pytest

from storymodel.sherlock_source import prepare_sherlock_source, validate_sherlock_source

HEADER = (
    "Segment Number\tStart Time (s) \tEnd Time (s) \tStart TR\tEnd TR\tScene Segments\t"
    "Scene Details - A Level \tSpace-In/Outdoor\tName - All\tName - Focus\tName - Speaking\t"
    "Location\tCamera Angle\tMusic Presence \tWords on Screen "
)
ROWS = (
    "1\t0\t10\t1\t7\t1. A\tAA BB\tOutdoor\tAA\tAA\t\tL\tLong\tNo\t",
    "2\t10\t20\t8\t14\t\tCC DD\tOutdoor\tBB\t\tBB\tL\tMedium\tNo\t",
    "3\t20\t20\t14\t14\t\tEE FF\tIndoor\t\t\t\t\tClose\tNo\t",
    "4\t20\t30\t\t\t\tGG HH\tIndoor\t\t\t\t\tLong\tNo\t",
    "5\t0\t5\t21\t24\t2. B\tII JJ\tIndoor\tAA / BB\tAA\tAA / BB\tL\tLong\tYes\t",
    "6\t5\t12\t25\t29\t\tKK LL\tIndoor\tBB\t\tBB\tL\tMedium\tYes\t",
)


def raw() -> bytes:
    return "\n".join((HEADER, *ROWS)).encode()


def repair() -> bytes:
    value = Path("examples/fixtures/sherlock-synthetic-repair.json").read_bytes()
    assert sha256(raw()).hexdigest().encode() in value
    return value


def repair_for(receipt: bytes) -> bytes:
    return repair().replace(
        sha256(raw()).hexdigest().encode(), sha256(receipt).hexdigest().encode()
    )


def alter(value: bytes, edit) -> bytes:
    decoded = json.loads(value)
    edit(decoded)
    return json.dumps(decoded, separators=(",", ":")).encode()


def test_retains_tsv_and_repair_and_derives_raw_part_local_playback():
    source = prepare_sherlock_source("sherlock", raw(), repair())
    assert source.raw_text.encode() == raw()
    assert source.repair_text.encode() == repair()
    assert source.raw_sha256 == sha256(raw()).hexdigest()
    assert source.rows[4].names_all_cell == "AA / BB"
    assert source.rows[4].names_all == ("AA", "BB")
    assert [(scene.first_row, scene.last_row) for scene in source.scenes] == [(1, 4), (5, 6)]
    assert source.loci[2].kind == "instant"
    assert source.loci[2].start_tick == source.loci[2].end_tick == 50000
    assert source.loci[4].part_id == "media-part-b"
    assert (source.loci[4].start_tick, source.loci[4].end_tick) == (0, 12500)
    assert [row.key for row in source.prepared.rows] == [
        f"sherlock:row:{n:04d}" for n in range(1, 7)
    ]
    assert source.prepared.document.text == "\n".join(row.description for row in source.rows)
    assert validate_sherlock_source(source) == source


@pytest.mark.parametrize(
    ("mutate", "message"),
    [
        (
            lambda value: value.replace(b"annotation-run-local-seconds (raw)", b"scanner-tr", 1),
            "unsupported",
        ),
        (
            lambda value: value.replace(b'"recordSeparator": "LF"', b'"recordSeparator": "CRLF"'),
            "inconsistent",
        ),
        (lambda value: value.replace(b'"maximumTr": 29', b'"maximumTr": 30'), "scanner"),
        (
            lambda value: value.replace(
                b'"valuesOffFrameBoundary": 0', b'"valuesOffFrameBoundary": 1'
            ),
            "inconsistent",
        ),
        (
            lambda value: value.replace(
                b'"durationSeconds": 30.4', b'"durationSeconds": 30.4000000000000001', 2
            ),
            "seconds and ticks",
        ),
    ],
)
def test_repair_declaration_guards_are_not_a_shortcut_manifest(mutate, message):
    with pytest.raises(ValueError, match=message):
        prepare_sherlock_source("sherlock", raw(), mutate(repair()))


def test_refuses_foreign_tsv_identity_and_revalidates_copied_models():
    foreign = raw().replace(b"AA BB", b"CC DD")
    with pytest.raises(ValueError, match="inputAnnotation"):
        prepare_sherlock_source("sherlock", foreign, repair())
    source = prepare_sherlock_source("sherlock", raw(), repair())
    with pytest.raises(ValueError, match="retained raw receipts"):
        validate_sherlock_source(source.model_copy(update={"rows": source.rows[:-1]}))


def test_header_is_checked_but_crlf_and_extra_published_columns_are_retained():
    with pytest.raises(ValueError, match="header"):
        prepare_sherlock_source("sherlock", raw().replace(b"Segment Number", b"row", 1), repair())
    crlf = raw().replace(b"\n", b"\r\n")
    source = prepare_sherlock_source("sherlock", crlf, repair_for(crlf))
    assert source.raw_text.encode() == crlf
    extended = raw().replace(HEADER.encode(), (HEADER + "\tAdditional published field").encode(), 1)
    source = prepare_sherlock_source("sherlock", extended, repair_for(extended))
    assert source.header[-1] == "Additional published field"


@pytest.mark.parametrize(
    ("edit", "message"),
    [
        (lambda value: value.pop("artifactId"), "artifactId"),
        (lambda value: value["inputAnnotation"].pop("artifactId"), "inputAnnotation/artifactId"),
        (lambda value: value["presentationEditionIdentity"].pop("declaration"), "declaration"),
        (
            lambda value: value["presentationEditionIdentity"]["parts"][0].__setitem__(
                "byteLength", 0
            ),
            "byte length",
        ),
        (
            lambda value: value["presentationEditionIdentity"]["parts"][0]["video"].__setitem__(
                "startPts", 1
            ),
            "startPts",
        ),
        (
            lambda value: value["presentationEditionIdentity"]["parts"][0]["video"].__setitem__(
                "constantFrameRate", 0
            ),
            "constantFrameRate",
        ),
        (
            lambda value: value["coordinateSystems"][2].__setitem__("secondsPerTr", -1),
            "secondsPerTr",
        ),
        (lambda value: value["sourceDerivation"].pop("commit"), "commit"),
        (lambda value: value.__setitem__("nonEquivalences", []), "nonEquivalences"),
    ],
)
def test_v2_metadata_guards_refuse_missing_or_weakened_declarations(edit, message):
    with pytest.raises(ValueError, match=message):
        prepare_sherlock_source("sherlock", raw(), alter(repair(), edit))


def test_playback_axis_metadata_is_checked_and_axis_names_are_not_hardcoded():
    def changed_axis(value):
        value["coordinateSystems"][6]["ticksPerFrame"] = 101

    with pytest.raises(ValueError, match="ticksPerFrame"):
        prepare_sherlock_source("sherlock", raw(), alter(repair(), changed_axis))
    renamed = (
        repair()
        .replace(b"media-part-a-playback-ticks", b"axis-alpha")
        .replace(b"media-part-b-playback-ticks", b"axis-beta")
    )
    source = prepare_sherlock_source("sherlock", raw(), renamed)
    assert source.loci[0].part_id == "media-part-a"


def test_arbitrarily_small_duration_difference_refuses_without_decimal_rounding():
    changed = repair().replace(
        b'"durationSeconds": 30.4',
        b'"durationSeconds": 30.40000000000000000000000000000001',
        2,
    )
    with pytest.raises(ValueError, match="seconds and ticks"):
        prepare_sherlock_source("sherlock", raw(), changed)
