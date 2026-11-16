"""Connect recorded proposals to checked accounts using independently supplied inputs."""

from .execution import RecordedRun
from .model import Account, Document, validate_account


def replay_account(
    run: RecordedRun, prompt: str, *, expected_documents: tuple[Document, ...]
) -> Account:
    """Read one recorded account proposal without creating a model client.

    This bounded profile accepts a complete experimental account proposal. It does not
    implement sentence extraction, segmentation or the legacy Scala record writer.
    Expected documents come from the caller's intake, never from the model response.
    """
    documents = tuple(Document.model_validate_json(d.model_dump_json()) for d in expected_documents)
    if not documents or len({d.id for d in documents}) != len(documents):
        raise ValueError("expected documents must be nonempty and distinct")
    input_hashes = {d.sha256 for d in documents}
    if input_hashes != set(run.profile.input_sha256):
        raise ValueError("run profile does not bind the exact expected document inputs")
    raw = run.run((prompt,))[0]
    account = validate_account(raw)
    if {d.id: d for d in account.documents} != {d.id: d for d in documents}:
        raise ValueError("proposal documents differ from independent intake")
    if (
        account.recipe.name != run.profile.recipe
        or set(account.recipe.input_sha256) != input_hashes
    ):
        raise ValueError("proposal recipe differs from the recorded execution profile")
    return account
