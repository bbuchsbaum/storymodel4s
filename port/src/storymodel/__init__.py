"""Evidence-backed accounts; importing the package performs no I/O."""

from .accounts import make_account, pair_accounts
from .alignment import AlignmentPolicy, align_account, verify_alignment
from .analysis import Analysis, project
from .artifacts import export_account, read_account
from .extraction import prepare_extraction, resolve_extraction
from .extraction_workflow import ExtractionRecording, import_extraction, replay_extraction
from .model import Account, account_digest, validate_account
from .source import SourceRow, compile_source, prepare_source, verify_source
from .source_workflow import SourceRecording, import_source, replay_source

__version__ = "0.1.0"

__all__ = [
    "Account",
    "AlignmentPolicy",
    "Analysis",
    "ExtractionRecording",
    "SourceRow",
    "SourceRecording",
    "account_digest",
    "align_account",
    "compile_source",
    "export_account",
    "project",
    "make_account",
    "pair_accounts",
    "prepare_extraction",
    "prepare_source",
    "resolve_extraction",
    "import_extraction",
    "import_source",
    "replay_extraction",
    "replay_source",
    "read_account",
    "validate_account",
    "verify_alignment",
    "verify_source",
]
