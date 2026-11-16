import hashlib
import json

import pytest

from storymodel import export_account, read_account, validate_account
from storymodel.artifacts import publish_json
from storymodel.cli import main
from storymodel.demo import example_account


def test_externally_pinned_read_and_atomic_publication(tmp_path):
    path = tmp_path / "account.json"
    account = example_account()
    export_account(path, account)
    pin = hashlib.sha256(path.read_bytes()).hexdigest()
    assert read_account(path, expected_sha256=pin) == account
    changed = json.loads(path.read_text())
    changed["export_permission"] = "denied"
    path.write_text(json.dumps(changed))
    with pytest.raises(ValueError, match="external SHA"):
        read_account(path, expected_sha256=pin)


def test_denied_export_creates_no_artifact(tmp_path):
    path = tmp_path / "denied.json"
    denied = example_account().model_copy(update={"export_permission": "denied"})
    with pytest.raises(PermissionError):
        export_account(path, denied)
    assert list(tmp_path.iterdir()) == []


def test_cli_cannot_bypass_denied_export(tmp_path):
    input_path = tmp_path / "input.json"
    output = tmp_path / "out.json"
    denied = example_account().model_copy(update={"export_permission": "denied"})
    input_path.write_text(denied.model_dump_json())
    assert main(["analyze", str(input_path), "--output", str(output)]) == 2
    assert not output.exists()


def test_existing_file_and_dangling_symlink_are_never_overwritten(tmp_path):
    path = tmp_path / "existing"
    path.write_text("original")
    with pytest.raises(FileExistsError):
        publish_json(path, {"value": 1})
    assert path.read_text() == "original"
    link = tmp_path / "link"
    link.symlink_to(tmp_path / "absent")
    with pytest.raises(FileExistsError):
        publish_json(link, {"value": 1})
    assert not (tmp_path / "absent").exists()
    assert sorted(p.name for p in tmp_path.iterdir()) == ["existing", "link"]


def test_json_duplicate_fields_are_refused_before_validation():
    raw = example_account().model_dump_json()
    duplicate = raw[:-1] + ',"export_permission":"denied"}'
    with pytest.raises(ValueError, match="duplicate JSON field"):
        validate_account(duplicate)


def test_failed_serialization_leaves_no_output(tmp_path):
    with pytest.raises(ValueError):
        publish_json(tmp_path / "bad", {"value": float("nan")})
    assert list(tmp_path.iterdir()) == []
