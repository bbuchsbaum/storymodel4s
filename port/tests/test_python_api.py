import subprocess
import sys
from pathlib import Path

import pytest

from storymodel import align_account, project
from storymodel.extraction import resolve_extraction
from storymodel.extraction_demo import example_proposal, example_request
from storymodel.serialization import strict_json


def test_notebook_style_example_outside_checkout(tmp_path):
    example = Path(__file__).parents[1] / "examples/python_workflow.py"
    run = subprocess.run(
        [sys.executable, str(example)], cwd=tmp_path, capture_output=True, text=True
    )
    assert run.returncode == 0, run.stderr
    assert "3 details" in run.stdout


@pytest.mark.parametrize("raw", ['{"x":1,"x":2}', '{"x":NaN}', '{"x":1e999}'])
def test_common_json_boundary_refuses_ambiguous_or_nonfinite_values(raw):
    with pytest.raises(ValueError):
        strict_json(raw)


def test_algorithms_validate_original_input_once_before_hashing(monkeypatch):
    import storymodel.alignment as alignment
    import storymodel.analysis as analysis
    import storymodel.model as model

    account = resolve_extraction(example_request(), example_proposal()).account
    for module, operation in ((analysis, project), (alignment, align_account)):
        original = module.validate_account
        inputs = []

        def counted(value):
            inputs.append(value)
            return original(value)

        with monkeypatch.context() as patch:
            patch.setattr(module, "validate_account", counted)
            patch.setattr(model, "validate_account", counted)
            operation(account)
        assert sum(value is account for value in inputs) == 1


def test_direct_source_extraction_matches_independent_pair_graph():
    from storymodel import make_account, pair_accounts, prepare_extraction

    legacy = example_request().source
    source = make_account(
        legacy.documents,
        units=legacy.units,
        contexts=tuple(c for c in legacy.contexts if c.side == "source"),
        entities=legacy.entities,
        events=legacy.events,
        export_permission="allowed",
    )
    raw = example_request().recall.raw_text.encode()
    direct = resolve_extraction(
        prepare_extraction("same-session", raw, source=source), example_proposal()
    ).account
    recall = resolve_extraction(prepare_extraction("same-session", raw), example_proposal()).account
    paired = pair_accounts(source, recall)
    for field in (
        "documents",
        "units",
        "contexts",
        "entities",
        "events",
        "relations",
        "details",
        "inventories",
        "bindings",
    ):
        assert getattr(direct, field) == getattr(paired, field)
    assert direct.export_permission == paired.export_permission == "denied"
