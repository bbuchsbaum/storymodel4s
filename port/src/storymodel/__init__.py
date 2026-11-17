"""Evidence-backed accounts; importing the package performs no I/O."""

from .accounts import make_account, pair_accounts
from .alignment import AlignmentPolicy, align_account, verify_alignment
from .analysis import Analysis, project
from .artifacts import export_account, read_account
from .extraction import prepare_extraction, resolve_extraction
from .extraction_workflow import ExtractionRecording, import_extraction, replay_extraction
from .model import Account, account_digest, validate_account

__version__ = "0.1.0"

__all__ = [
    "Account",
    "AlignmentPolicy",
    "Analysis",
    "ExtractionRecording",
    "account_digest",
    "align_account",
    "export_account",
    "project",
    "make_account",
    "pair_accounts",
    "prepare_extraction",
    "resolve_extraction",
    "import_extraction",
    "replay_extraction",
    "read_account",
    "validate_account",
    "verify_alignment",
]
