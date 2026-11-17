"""Evidence-backed accounts; importing the package performs no I/O."""

from .accounts import make_account, pair_accounts
from .alignment import AlignmentPolicy, align_account, verify_alignment
from .analysis import Analysis, project
from .artifacts import (
    export_account,
    export_supported_account,
    read_account,
    read_supported_account,
)
from .attribution import (
    AttributionResult,
    ContextHolder,
    HolderProposal,
    HolderProposals,
    attribution_table,
    resolve_context_holders,
    verify_attribution,
)
from .claim_assessment import (
    ClaimQuestion,
    assess_claims,
    claim_population_digest,
    prepare_claim_assessment,
    verify_claim_assessment,
)
from .detail_admission import admit_details, detail_prompt, prepare_details, verify_details
from .detail_workflow import DetailRecording, import_details, replay_details
from .direct_mapping import mapping_prompt, prepare_mapping, resolve_mapping, verify_mapping
from .direct_workflow import MappingRecording, import_mapping, replay_mapping
from .extraction import prepare_extraction, resolve_extraction
from .extraction_workflow import ExtractionRecording, import_extraction, replay_extraction
from .fidelity import (
    fidelity_prompt,
    prepare_fidelity,
    resolve_fidelity,
    verify_fidelity,
)
from .fidelity_workflow import FidelityRecording, import_fidelity, replay_fidelity
from .held_identity import (
    attributed_identity_roles,
    resolve_attributed_identity,
    verify_attributed_identity,
)
from .identity import identity_role_table, resolve_text_identity, verify_identity
from .local_evidence import (
    LocalEvidenceSpec,
    RecallSelection,
    derive_local_evidence,
    infer_accounts,
    verify_account_inference,
    verify_local_evidence,
)
from .model import Account, account_digest, validate_account
from .organization import (
    DestinationMass,
    JointMass,
    OrganizationDestination,
    OrganizationJoint,
    OrganizationOrder,
    OrganizationRow,
    OrganizationSpec,
    organization_readout,
    organize_accounts,
    verify_account_organization,
    verify_organization_readout,
)
from .reconstruction import (
    AccountReconstructionResult,
    FillTarget,
    ReconstructionMass,
    ReconstructionPolicy,
    ReconstructionRow,
    ReconstructionSpec,
    prepare_reconstruction_fill,
    reconstruct_accounts,
    reconstruct_rows,
    verify_account_reconstruction,
    verify_reconstruction,
)
from .relations import admit_relations, relation_table, verify_relations
from .rich_account import RichAccount, pair_rich_accounts, situation_table, upgrade_account
from .rich_construction import compile_rich_construction, prepare_rich_construction
from .rich_workflow import RichRecording, import_rich, replay_rich
from .scenes import SceneGroup, group_source_rows, verify_scene_grouping
from .sequence import SequenceRequest, SequenceResult, infer_sequence, verify_sequence
from .source import SourceRow, compile_source, prepare_source, verify_source
from .source_transitions import (
    SourceProjectionPolicy,
    SourceSequenceSpec,
    TransitionWeights,
    build_source_sequence,
    project_source,
    verify_source_projection,
    verify_source_sequence,
)
from .source_workflow import SourceRecording, import_source, replay_source

__version__ = "0.1.0"

__all__ = [
    "attributed_identity_roles",
    "resolve_attributed_identity",
    "verify_attributed_identity",
    "AttributionResult",
    "ContextHolder",
    "HolderProposal",
    "HolderProposals",
    "attribution_table",
    "resolve_context_holders",
    "verify_attribution",
    "OrganizationDestination",
    "DestinationMass",
    "OrganizationRow",
    "OrganizationOrder",
    "JointMass",
    "OrganizationJoint",
    "OrganizationSpec",
    "organization_readout",
    "verify_organization_readout",
    "organize_accounts",
    "verify_account_organization",
    "AccountReconstructionResult",
    "FillTarget",
    "ReconstructionPolicy",
    "ReconstructionRow",
    "ReconstructionMass",
    "ReconstructionSpec",
    "prepare_reconstruction_fill",
    "reconstruct_accounts",
    "reconstruct_rows",
    "verify_account_reconstruction",
    "verify_reconstruction",
    "Account",
    "AlignmentPolicy",
    "Analysis",
    "ClaimQuestion",
    "FidelityRecording",
    "fidelity_prompt",
    "prepare_fidelity",
    "resolve_fidelity",
    "verify_fidelity",
    "import_fidelity",
    "replay_fidelity",
    "DetailRecording",
    "ExtractionRecording",
    "MappingRecording",
    "LocalEvidenceSpec",
    "RecallSelection",
    "derive_local_evidence",
    "infer_accounts",
    "verify_account_inference",
    "verify_local_evidence",
    "RichAccount",
    "RichRecording",
    "SequenceRequest",
    "SequenceResult",
    "SourceRow",
    "SourceProjectionPolicy",
    "SourceSequenceSpec",
    "TransitionWeights",
    "build_source_sequence",
    "project_source",
    "verify_source_projection",
    "verify_source_sequence",
    "SceneGroup",
    "group_source_rows",
    "verify_scene_grouping",
    "SourceRecording",
    "account_digest",
    "assess_claims",
    "claim_population_digest",
    "prepare_claim_assessment",
    "verify_claim_assessment",
    "admit_relations",
    "admit_details",
    "detail_prompt",
    "align_account",
    "compile_source",
    "compile_rich_construction",
    "export_account",
    "export_supported_account",
    "project",
    "make_account",
    "pair_accounts",
    "pair_rich_accounts",
    "prepare_extraction",
    "prepare_details",
    "prepare_mapping",
    "mapping_prompt",
    "prepare_source",
    "prepare_rich_construction",
    "resolve_extraction",
    "resolve_mapping",
    "import_extraction",
    "import_details",
    "import_mapping",
    "import_source",
    "import_rich",
    "infer_sequence",
    "identity_role_table",
    "replay_extraction",
    "replay_details",
    "replay_mapping",
    "replay_source",
    "replay_rich",
    "resolve_text_identity",
    "read_account",
    "read_supported_account",
    "relation_table",
    "situation_table",
    "upgrade_account",
    "validate_account",
    "verify_alignment",
    "verify_source",
    "verify_relations",
    "verify_identity",
    "verify_mapping",
    "verify_details",
    "verify_sequence",
]
