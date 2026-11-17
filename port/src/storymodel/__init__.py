"""Evidence-backed accounts; importing the package performs no I/O."""

from .accounts import make_account, pair_accounts
from .alignment import AlignmentPolicy, align_account, verify_alignment
from .analysis import Analysis, project
from .artifacts import export_account, read_account
from .direct_mapping import mapping_prompt, prepare_mapping, resolve_mapping, verify_mapping
from .direct_workflow import MappingRecording, import_mapping, replay_mapping
from .extraction import prepare_extraction, resolve_extraction
from .extraction_workflow import ExtractionRecording, import_extraction, replay_extraction
from .identity import identity_role_table, resolve_text_identity, verify_identity
from .model import Account, account_digest, validate_account
from .relations import admit_relations, relation_table, verify_relations
from .source import SourceRow, compile_source, prepare_source, verify_source
from .source_workflow import SourceRecording, import_source, replay_source

__version__ = "0.1.0"

__all__ = [
    "Account",
    "AlignmentPolicy",
    "Analysis",
    "ExtractionRecording",
    "MappingRecording",
    "SourceRow",
    "SourceRecording",
    "account_digest",
    "admit_relations",
    "align_account",
    "compile_source",
    "export_account",
    "project",
    "make_account",
    "pair_accounts",
    "prepare_extraction",
    "prepare_mapping",
    "mapping_prompt",
    "prepare_source",
    "resolve_extraction",
    "resolve_mapping",
    "import_extraction",
    "import_mapping",
    "import_source",
    "identity_role_table",
    "replay_extraction",
    "replay_mapping",
    "replay_source",
    "resolve_text_identity",
    "read_account",
    "relation_table",
    "validate_account",
    "verify_alignment",
    "verify_source",
    "verify_relations",
    "verify_identity",
    "verify_mapping",
]
