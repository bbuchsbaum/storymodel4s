"""Small CLI over the same operations used from Python and notebooks."""

import argparse
import sys
from hashlib import sha256
from pathlib import Path
from typing import Any

from .alignment import align_account, validate_policy
from .analysis import Analysis, Policy, project
from .analysis_workflow import (
    AnalysisReplay,
    replay_analysis_file,
)
from .artifacts import publish_json, read_account, read_supported_account
from .attribution import (
    AttributionResult,
    attribution_table,
    resolve_context_holders,
    verify_attribution,
)
from .chronology import TemporalPolicy
from .chronology_workflow import (
    TemporalQuery,
    TemporalResult,
    admit_temporal_relations,
    query_temporal_relation,
    temporal_table,
    verify_temporal_query,
    verify_temporal_relations,
)
from .claim_assessment import (
    ClaimQuestion,
    assess_claims,
    prepare_claim_assessment,
    validate_claim_plan,
    verify_claim_assessment,
)
from .concentration import ConcentrationQuery, source_concentration
from .construction_links import (
    ConnectedConstructionResult,
    connect_rich_construction,
    construction_link_prompt,
    verify_connected_construction,
)
from .context_fidelity import ContextEvidence
from .demo import example_account
from .detail_admission import detail_prompt, prepare_details
from .detail_workflow import (
    DetailRecording,
    import_details,
    read_detail_request,
    replay_details,
)
from .direct_mapping import mapping_prompt, prepare_mapping
from .direct_workflow import (
    MappingRecording,
    import_mapping,
    read_mapping_request,
    replay_mapping,
)
from .embedding import EmbeddingCache, EmbeddingPair
from .execution import RecordedExecutionError
from .exposure import ExposureQuery, RecallExposure, recall_exposure
from .extraction import prepare_extraction
from .extraction_workflow import (
    ExtractionRecording,
    extraction_prompt,
    import_extraction,
    read_extraction_request,
    replay_extraction,
)
from .fidelity import fidelity_prompt, prepare_fidelity
from .fidelity_workflow import (
    FidelityRecording,
    import_fidelity,
    read_fidelity_request,
    replay_fidelity,
)
from .held_identity import (
    AttributedIdentityResult,
    attributed_identity_roles,
    resolve_attributed_identity,
    verify_attributed_identity,
)
from .hierarchy import (
    HierarchyResult,
    HierarchySpec,
    build_hierarchy,
    query_hierarchy,
    verify_hierarchy,
)
from .identity import IdentityResult, identity_role_table, resolve_text_identity
from .intake_workflow import prepare_time_study_files
from .local_evidence import (
    AccountInferenceResult,
    LocalEvidenceSpec,
    RefinedAccountInference,
    RefinedInferenceSpec,
    _resolve_account_inference,
    derive_local_evidence,
)
from .local_reference import LocalReferenceSpec, local_reference
from .model import Account, canonical
from .organization import (
    AccountOrganizationResult,
    OrganizationOrder,
    OrganizationResult,
    OrganizationSpec,
    RefinedAccountOrganizationResult,
    organization_readout,
    organize_accounts,
    verify_account_organization,
    verify_organization_readout,
)
from .playback import MediaSource, TickInterval
from .playback_construction import (
    PlaybackObservation,
    SituationPlaybackReadout,
    prepare_playback,
    situation_playback,
    verify_situation_playback,
)
from .playback_workflow import (
    PlaybackRecording,
    import_playback,
    playback_prompt,
    read_playback_request,
    replay_playback,
)
from .prepared_construction import (
    PreparedConstructionRecording,
    PreparedConstructionResult,
    import_prepared_construction,
    prepare_construction,
    prepare_sherlock_construction,
    prepared_construction_prompt,
    read_prepared_construction,
    replay_prepared_construction,
    verify_prepared_construction,
)
from .recall_clock import RecordingQuery, TimedRecall, timed_recall
from .reconstruction import (
    AccountReconstructionResult,
    ReconstructionSpec,
    RefinedAccountReconstructionResult,
    reconstruct_accounts,
    verify_account_reconstruction,
)
from .relations import admit_relations, relation_table
from .rich_account import (
    RichAccount,
    situation_table,
    upgrade_assessment_account,
    validate_rich_account,
)
from .rich_construction import prepare_rich_construction
from .rich_workflow import RichRecording, import_rich, read_rich_request, replay_rich, rich_prompt
from .scenes import SceneGroup, group_source_rows
from .semantic import (
    SemanticResult,
    prepare_semantic,
    resolve_semantic,
    semantic_prompt,
)
from .semantic_workflow import (
    SemanticRecording,
    import_semantic,
    read_semantic_request,
    replay_semantic,
)
from .sequence import infer_sequence, validate_sequence, verify_sequence
from .sherlock_composition import (
    SherlockComposition,
    SherlockCompositionReadout,
    compose_sherlock_source,
    project_sherlock_composition,
    verify_sherlock_composition_readout,
)
from .sherlock_recall import (
    SherlockRecall,
    prepare_sherlock_recall,
    sherlock_recall_timing,
)
from .sherlock_source import SherlockSource, prepare_sherlock_source
from .source import SourceRow, prepare_source
from .source_grid import (
    LocalScoreGridQuery,
    SourceGridQuery,
    TransportGridQuery,
    project_source_grid,
)
from .source_transitions import (
    SourceSequenceResult,
    SourceSequenceSpec,
    build_source_sequence,
    verify_source_sequence,
)
from .source_workflow import (
    SourceRecording,
    import_source,
    read_source_request,
    replay_source,
    source_prompt,
)
from .study_workflow import (
    ImportedReplies,
    import_time_replies,
    read_request,
    replay_time_study,
    strict_json,
)
from .transport import TransportSpec, align_transport, verify_transport


def emit(value: Any, output: str | None) -> None:
    text = canonical(value) + "\n"
    if output is None:
        sys.stdout.write(text)
    else:
        publish_json(Path(output), value)


def _read_inference_spec(path: str) -> LocalEvidenceSpec | RefinedInferenceSpec:
    payload = strict_json(Path(path).read_bytes())
    if (
        isinstance(payload, dict)
        and payload.get("schema_version") == "storymodel.refined-inference-spec/v1"
    ):
        return RefinedInferenceSpec.model_validate_json(canonical(payload))
    return LocalEvidenceSpec.model_validate_json(canonical(payload))


def _read_saved_inference(
    path: str, spec: LocalEvidenceSpec | RefinedInferenceSpec
) -> AccountInferenceResult | RefinedAccountInference:
    model = (
        RefinedAccountInference
        if isinstance(spec, RefinedInferenceSpec)
        else AccountInferenceResult
    )
    return model.model_validate_json(canonical(strict_json(Path(path).read_bytes())))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="storymodel")
    commands = parser.add_subparsers(dest="command", required=True)
    demo = commands.add_parser("example", help="write the admitted synthetic account")
    demo.add_argument("--output")
    validate = commands.add_parser("validate", help="validate a rich account")
    validate.add_argument("account")
    validate.add_argument("--expected-sha256")
    analyze = commands.add_parser("analyze", help="derive the three analysis views")
    analyze.add_argument("account")
    analyze.add_argument("--expected-sha256")
    analyze.add_argument(
        "--policy",
        choices=("target-episode-novel-content/v1", "target-event-only/v1"),
        default="target-episode-novel-content/v1",
    )
    analyze.add_argument("--output")
    schema = commands.add_parser("schema", help="generate the experimental exchange schema")
    schema.add_argument("--output")
    mapping_prepare = commands.add_parser(
        "prepare-mapping", help="prepare one recall unit's mapping"
    )
    mapping_prepare.add_argument("account")
    mapping_prepare.add_argument("unit")
    mapping_prepare.add_argument(
        "--interpretation", choices=("narrative", "referent"), default="referent"
    )
    mapping_prepare.add_argument("--expected-sha256")
    mapping_prepare.add_argument(
        "--native-source",
        action="store_true",
        help="explicitly upgrade to native observation assessment",
    )
    mapping_prepare.add_argument("--output")
    mapping_contract = commands.add_parser(
        "mapping-prompt", help="render the unit-only mapping input"
    )
    mapping_contract.add_argument("request")
    mapping_contract.add_argument("--output")
    mapping_import = commands.add_parser("import-mapping", help="record an exported mapping reply")
    mapping_import.add_argument("request")
    mapping_import.add_argument("reply")
    mapping_import.add_argument("recording")
    mapping_import.add_argument(
        "--recover", action="store_true", help="resume only matching saved import intent"
    )
    mapping_replay = commands.add_parser(
        "replay-mapping", help="resolve a recorded mapping offline"
    )
    mapping_replay.add_argument("request")
    mapping_replay.add_argument("recording")
    mapping_replay.add_argument("--expected-import-sha256")
    mapping_replay.add_argument("--account-only", action="store_true")
    mapping_replay.add_argument("--output")
    details_prepare = commands.add_parser(
        "prepare-details", help="prepare selected v4 recall inventories"
    )
    details_prepare.add_argument("account")
    details_prepare.add_argument("units", nargs="+")
    details_prepare.add_argument("--output")
    details_contract = commands.add_parser(
        "detail-prompt", help="render a snapshot-bound detail request"
    )
    details_contract.add_argument("request")
    details_contract.add_argument("--output")
    details_import = commands.add_parser(
        "import-details", help="record an exported detail inventory reply"
    )
    details_import.add_argument("request")
    details_import.add_argument("reply")
    details_import.add_argument("recording")
    details_import.add_argument(
        "--recover", action="store_true", help="resume only matching saved import intent"
    )
    details_replay = commands.add_parser(
        "replay-details", help="admit recorded v4 recall details offline"
    )
    details_replay.add_argument("request")
    details_replay.add_argument("recording")
    details_replay.add_argument("--expected-import-sha256")
    details_replay.add_argument("--account-only", action="store_true")
    details_replay.add_argument("--output")
    rich_prepare = commands.add_parser(
        "prepare-rich", help="prepare anchored rich graph construction"
    )
    rich_prepare.add_argument("text")
    rich_prepare.add_argument("rows")
    rich_prepare.add_argument("--document-key", required=True)
    rich_prepare.add_argument("--side", required=True, choices=("source", "recall"))
    rich_prepare.add_argument("--allow-export", action="store_true")
    rich_prepare.add_argument(
        "--full-contexts", action="store_true", help="include mental and holderless contexts"
    )
    rich_prepare.add_argument("--output")
    prepared_construction = commands.add_parser(
        "prepare-construction", help="prepare exact source or recall construction intake"
    )
    prepared_construction.add_argument("input")
    prepared_construction.add_argument("--key", required=True)
    prepared_construction.add_argument("--side", required=True, choices=("source", "recall"))
    prepared_construction.add_argument("--full-contexts", action="store_true")
    prepared_construction.add_argument("--allow-export", action="store_true")
    prepared_construction.add_argument("--expected-input-sha256")
    prepared_construction.add_argument("--output")
    sherlock_source_prepare = commands.add_parser(
        "prepare-sherlock-source",
        help="prepare retained Sherlock annotation and clock-repair intake",
    )
    sherlock_source_prepare.add_argument("input")
    sherlock_source_prepare.add_argument("repair")
    sherlock_source_prepare.add_argument("--key", required=True)
    sherlock_source_prepare.add_argument("--allow-export", action="store_true")
    sherlock_source_prepare.add_argument("--full-contexts", action="store_true")
    sherlock_source_prepare.add_argument("--expected-input-sha256")
    sherlock_source_prepare.add_argument("--expected-record-sha256")
    sherlock_source_prepare.add_argument("--output")
    sherlock_recall_prepare = commands.add_parser(
        "prepare-sherlock-recall", help="prepare retained Sherlock word-CSV and lineage intake"
    )
    sherlock_recall_prepare.add_argument("input")
    sherlock_recall_prepare.add_argument("lineage")
    sherlock_recall_prepare.add_argument("--key", required=True)
    sherlock_recall_prepare.add_argument("--source-id", required=True)
    sherlock_recall_prepare.add_argument("--allow-export", action="store_true")
    sherlock_recall_prepare.add_argument("--full-contexts", action="store_true")
    sherlock_recall_prepare.add_argument("--expected-input-sha256")
    sherlock_recall_prepare.add_argument("--expected-record-sha256")
    sherlock_recall_prepare.add_argument("--output")
    sherlock_timing = commands.add_parser(
        "sherlock-recall-timing",
        help="read exact recalled-word timing on a declared external clock",
    )
    sherlock_timing.add_argument("request")
    sherlock_timing.add_argument("--clock", required=True, choices=("princeton", "openneuro"))
    sherlock_timing.add_argument("--output")
    caption = commands.add_parser(
        "caption-alignment", help="score retrieved targets against supplied captions and decode"
    )
    caption.add_argument("plan")
    caption.add_argument("captions")
    caption.add_argument("--blend", type=float, default=0.5)
    caption.add_argument("--max-candidates", type=int, default=32)
    caption.add_argument("--max-postings", type=int, default=4096)
    caption.add_argument("--output")
    friends = commands.add_parser(
        "prepare-friends", help="prepare decoded Friends storyboard and recall rows"
    )
    friends.add_argument("study")
    friends.add_argument("--output")
    for name in ("feature-windows", "verify-feature-windows"):
        features = commands.add_parser(name, help="measure lexical features in recording windows")
        features.add_argument("plan")
        features.add_argument("query")
        features.add_argument("lexicon")
        if name.startswith("verify-"):
            features.add_argument("result")
        features.add_argument("--output")
    for command, help_text in (
        ("timed-recall", "measure mapped recall details on a declared recording clock"),
        ("verify-timed-recall", "recompute a saved timed recall result"),
        (
            "source-time-grid",
            "project an account measure into source-time bins",
        ),
        ("verify-source-time-grid", "recompute a saved source-time grid"),
        ("source-concentration", "locate a recall unit's measure on the source timeline"),
        ("verify-source-concentration", "recompute a saved concentration result"),
        ("recall-exposure", "measure unit durations from word intervals on a recording"),
        ("verify-recall-exposure", "recompute a saved recording-window exposure"),
    ):
        timed = commands.add_parser(command, help=help_text)
        timed.add_argument("plan")
        timed.add_argument("query")
        if command.startswith("verify-"):
            timed.add_argument("result")
        timed.add_argument("--output")
    sherlock_compose = commands.add_parser(
        "sherlock-compose", help="compose native source parts while retaining original anchors"
    )
    sherlock_compose.add_argument("request")
    sherlock_compose.add_argument("--expected-input-sha256")
    sherlock_compose.add_argument("--output")
    for command in ("sherlock-composition-readout", "verify-sherlock-composition-readout"):
        composition_command = commands.add_parser(
            command, help="measure composed row/scene support"
        )
        composition_command.add_argument("composition")
        composition_command.add_argument("--unit", action="append", default=[])
        composition_command.add_argument("--bins")
        composition_command.add_argument("--output")
        if command.startswith("verify-"):
            composition_command.add_argument("result")
    prepared_construction_prompt_command = commands.add_parser(
        "prepared-construction-prompt", help="render a raw-intake construction contract"
    )
    prepared_construction_prompt_command.add_argument("request")
    prepared_construction_prompt_command.add_argument("--output")
    prepared_construction_import = commands.add_parser(
        "import-prepared-construction", help="record an exported raw-intake construction reply"
    )
    prepared_construction_import.add_argument("request")
    prepared_construction_import.add_argument("reply")
    prepared_construction_import.add_argument("recording")
    prepared_construction_import.add_argument(
        "--recover", action="store_true", help="resume only matching saved import intent"
    )
    prepared_construction_replay = commands.add_parser(
        "replay-prepared-construction", help="construct a raw-intake graph offline"
    )
    prepared_construction_replay.add_argument("request")
    prepared_construction_replay.add_argument("recording")
    prepared_construction_replay.add_argument("--expected-import-sha256")
    prepared_construction_replay.add_argument("--output")
    prepared_construction_verify = commands.add_parser(
        "verify-prepared-construction", help="recompute a raw-intake construction result"
    )
    prepared_construction_verify.add_argument("request")
    prepared_construction_verify.add_argument("reply")
    prepared_construction_verify.add_argument("result")
    prepared_construction_verify.add_argument("--output")
    native_prepare = commands.add_parser(
        "prepare-playback", help="prepare supplied native observation envelopes"
    )
    native_prepare.add_argument("source")
    native_prepare.add_argument("observations")
    native_prepare.add_argument("--allow-export", action="store_true")
    native_prepare.add_argument("--output")
    native_prompt = commands.add_parser(
        "playback-prompt", help="render native observation construction contract"
    )
    native_prompt.add_argument("request")
    native_prompt.add_argument("--output")
    native_import = commands.add_parser(
        "import-playback", help="record supplied native graph proposals"
    )
    native_import.add_argument("request")
    native_import.add_argument("reply")
    native_import.add_argument("recording")
    native_import.add_argument(
        "--recover", action="store_true", help="resume only matching saved import intent"
    )
    native_replay = commands.add_parser("replay-playback", help="construct a native graph offline")
    native_replay.add_argument("request")
    native_replay.add_argument("recording")
    native_replay.add_argument("--expected-import-sha256")
    native_replay.add_argument("--account-only", action="store_true")
    native_replay.add_argument("--output")
    for command in ("situation-playback", "verify-situation-playback"):
        native_readout = commands.add_parser(
            command, help="measure or verify exact native situation support"
        )
        native_readout.add_argument("account")
        native_readout.add_argument("situation")
        native_readout.add_argument("source")
        native_readout.add_argument("--bins")
        if command == "verify-situation-playback":
            native_readout.add_argument("result")
        native_readout.add_argument("--output")
    rich_contract = commands.add_parser("rich-prompt", help="render the rich construction contract")
    rich_contract.add_argument("request")
    rich_contract.add_argument("--output")
    rich_import = commands.add_parser(
        "import-rich", help="record an exported v4 construction reply"
    )
    rich_import.add_argument("request")
    rich_import.add_argument("reply")
    rich_import.add_argument("recording")
    rich_import.add_argument(
        "--recover", action="store_true", help="resume only matching saved import intent"
    )
    rich_replay = commands.add_parser("replay-rich", help="construct a rich account offline")
    rich_replay.add_argument("request")
    rich_replay.add_argument("recording")
    rich_replay.add_argument("--expected-import-sha256")
    rich_replay.add_argument("--account-only", action="store_true")
    rich_replay.add_argument("--output")
    for command in ("construction-link-prompt", "connect-rich", "verify-connected-rich"):
        linked = commands.add_parser(command, help="connect verified row-local graph references")
        linked.add_argument("request")
        linked.add_argument("construction", help="exact original rich construction reply file")
        if command != "construction-link-prompt":
            linked.add_argument("links")
        if command == "connect-rich":
            linked.add_argument("--account-only", action="store_true")
        if command == "verify-connected-rich":
            linked.add_argument("result")
        linked.add_argument("--output")
    situations = commands.add_parser(
        "situation-table", help="inspect v4 events, states and circumstances"
    )
    situations.add_argument("account")
    situations.add_argument("--output")
    imported = commands.add_parser(
        "import-time-recording", help="import exported time-study replies offline"
    )
    imported.add_argument("request")
    imported.add_argument("replies")
    imported.add_argument("recording")
    imported.add_argument(
        "--recover", action="store_true", help="resume only matching saved import intent"
    )
    imported.add_argument("--expected-request-sha256")
    replay = commands.add_parser("replay-time-study", help="resolve a recorded time study offline")
    replay.add_argument("request")
    replay.add_argument("recording")
    replay.add_argument("--expected-request-sha256")
    replay.add_argument("--expected-import-sha256")
    replay.add_argument("--output")
    prepare = commands.add_parser(
        "prepare-time-study", help="prepare raw recall and native source clocks"
    )
    prepare.add_argument("source")
    prepare.add_argument("recall")
    prepare.add_argument("--recall-key", required=True)
    prepare.add_argument("--source-sha256")
    prepare.add_argument("--recall-sha256")
    prepare.add_argument("--chunk-size", type=int, default=50)
    prepare.add_argument("--max-attempts", type=int, default=1)
    prepare.add_argument("--allow-export", action="store_true")
    prepare.add_argument("--output")
    extract_prepare = commands.add_parser(
        "prepare-extraction", help="prepare raw recall extraction"
    )
    extract_prepare.add_argument("recall")
    extract_prepare.add_argument("--recall-key", required=True)
    extract_prepare.add_argument("--source-account")
    extract_prepare.add_argument("--allow-export", action="store_true")
    extract_prepare.add_argument("--output")
    extract_prompt = commands.add_parser(
        "extraction-prompt", help="write the recorded proposal contract"
    )
    extract_prompt.add_argument("request")
    extract_prompt.add_argument("--output")
    extract_import = commands.add_parser(
        "import-extraction", help="record an exported extraction reply"
    )
    extract_import.add_argument("request")
    extract_import.add_argument("reply")
    extract_import.add_argument("recording")
    extract_import.add_argument(
        "--recover", action="store_true", help="resume only matching saved import intent"
    )
    extract_replay = commands.add_parser(
        "replay-extraction", help="resolve recorded extraction offline"
    )
    extract_replay.add_argument("request")
    extract_replay.add_argument("recording")
    extract_replay.add_argument("--expected-import-sha256")
    extract_replay.add_argument("--account-only", action="store_true")
    extract_replay.add_argument("--output")
    source_prepare = commands.add_parser(
        "prepare-source", help="prepare independently anchored source rows"
    )
    source_prepare.add_argument("text")
    source_prepare.add_argument("rows")
    source_prepare.add_argument("--source-key", required=True)
    source_prepare.add_argument("--allow-export", action="store_true")
    source_prepare.add_argument("--output")
    source_contract = commands.add_parser(
        "source-prompt", help="write the recorded source proposal contract"
    )
    source_contract.add_argument("request")
    source_contract.add_argument("--output")
    source_import = commands.add_parser("import-source", help="record an exported source reply")
    source_import.add_argument("request")
    source_import.add_argument("reply")
    source_import.add_argument("recording")
    source_import.add_argument(
        "--recover", action="store_true", help="resume only matching saved import intent"
    )
    source_replay = commands.add_parser("replay-source", help="compile a recorded source offline")
    source_replay.add_argument("request")
    source_replay.add_argument("recording")
    source_replay.add_argument("--expected-import-sha256")
    source_replay.add_argument("--account-only", action="store_true")
    source_replay.add_argument("--output")
    semantic_prepare = commands.add_parser(
        "prepare-semantic", help="prepare bounded recorded surface-embedding inference"
    )
    semantic_prepare.add_argument("source")
    semantic_prepare.add_argument("recall")
    semantic_prepare.add_argument("spec")
    semantic_prepare.add_argument("pair")
    semantic_prepare.add_argument("--cache")
    semantic_prepare.add_argument("--max-comparisons", type=int, default=4096)
    semantic_prepare.add_argument("--max-semantic-candidates", type=int, default=32)
    semantic_prepare.add_argument("--output")
    semantic_contract = commands.add_parser(
        "semantic-prompt", help="render the recorded surface-embedding request"
    )
    semantic_contract.add_argument("request")
    semantic_contract.add_argument("--output")
    semantic_import = commands.add_parser(
        "import-semantic", help="record an exported surface-embedding reply"
    )
    semantic_import.add_argument("request")
    semantic_import.add_argument("reply")
    semantic_import.add_argument("recording")
    semantic_import.add_argument(
        "--recover", action="store_true", help="resume only matching saved import intent"
    )
    semantic_replay = commands.add_parser(
        "replay-semantic", help="resolve a recorded surface-embedding reply offline"
    )
    semantic_replay.add_argument("request")
    semantic_replay.add_argument("recording")
    semantic_replay.add_argument("--expected-import-sha256")
    semantic_replay.add_argument("--output")
    semantic_verify = commands.add_parser(
        "verify-semantic", help="recompute a semantic result from a request and exact reply"
    )
    semantic_verify.add_argument("request")
    semantic_verify.add_argument("reply")
    semantic_verify.add_argument("result")
    semantic_verify.add_argument("--output")
    analysis_replay = commands.add_parser(
        "replay-analysis", help="replay a saved account chain relative to its manifest"
    )
    analysis_replay.add_argument("plan")
    analysis_replay.add_argument("--output")
    analysis_verify = commands.add_parser(
        "verify-analysis", help="recompute and verify a saved analysis replay"
    )
    analysis_verify.add_argument("plan")
    analysis_verify.add_argument("result")
    analysis_verify.add_argument("--output")
    relation_admit = commands.add_parser(
        "admit-relations", help="admit an input-bound recorded event or state relation batch"
    )
    relation_admit.add_argument("account")
    relation_admit.add_argument("proposals")
    relation_admit.add_argument("--expected-sha256")
    relation_admit.add_argument("--account-only", action="store_true")
    relation_admit.add_argument("--output")
    relation_inspect = commands.add_parser(
        "relation-table", help="inspect existing relations with contexts and exact evidence"
    )
    relation_inspect.add_argument("account")
    relation_inspect.add_argument("--expected-sha256")
    relation_inspect.add_argument("--output")
    scene_group = commands.add_parser(
        "group-source-rows", help="group source rows with explicit identity remapping"
    )
    scene_group.add_argument("account")
    scene_group.add_argument("groups")
    scene_group.add_argument("--expected-sha256")
    scene_group.add_argument("--account-only", action="store_true")
    scene_group.add_argument("--output")
    fidelity_prepare = commands.add_parser(
        "prepare-fidelity", help="prepare a separate graded review of pending hypotheses"
    )
    fidelity_prepare.add_argument("account")
    fidelity_prepare.add_argument("unit")
    fidelity_prepare.add_argument(
        "--interpretation", choices=("narrative", "referent"), default="referent"
    )
    fidelity_prepare.add_argument("--expected-sha256")
    fidelity_prepare.add_argument(
        "--native-source",
        action="store_true",
        help="explicitly upgrade to native observation assessment",
    )
    fidelity_prepare.add_argument("--identity-annotations")
    fidelity_prepare.add_argument("--holder-proposals")
    fidelity_prepare.add_argument("--output")
    for command in ("fidelity-prompt", "import-fidelity", "replay-fidelity"):
        fidelity_command = commands.add_parser(
            command, help="record or replay conditional fidelity"
        )
        fidelity_command.add_argument("request")
        fidelity_command.add_argument("--expected-request-sha256")
        if command == "import-fidelity":
            fidelity_command.add_argument("reply")
            fidelity_command.add_argument("--recover", action="store_true")
        if command != "fidelity-prompt":
            fidelity_command.add_argument("recording")
        if command == "replay-fidelity":
            fidelity_command.add_argument("--expected-import-sha256")
            fidelity_command.add_argument("--account-only", action="store_true")
        if command != "import-fidelity":
            fidelity_command.add_argument("--output")
    claim_prepare = commands.add_parser(
        "prepare-claim-assessment",
        help="freeze a declared question population against an account snapshot",
    )
    claim_prepare.add_argument("account")
    claim_prepare.add_argument("questions")
    claim_prepare.add_argument("--expected-sha256")
    claim_prepare.add_argument("--output")
    for command in ("assess-claims", "verify-claims"):
        claim_command = commands.add_parser(
            command, help="check fixed-population attributed text assessments"
        )
        claim_command.add_argument("plan")
        claim_command.add_argument("reply")
        if command == "verify-claims":
            claim_command.add_argument("result")
        claim_command.add_argument("--output")
    identity_resolve = commands.add_parser(
        "resolve-identity", help="resolve document-local identity from recorded form annotations"
    )
    identity_resolve.add_argument("account")
    identity_resolve.add_argument("annotations")
    identity_resolve.add_argument("--expected-sha256")
    identity_resolve.add_argument("--output")
    identity_inspect = commands.add_parser(
        "identity-roles", help="inspect event roles through a verified identity sidecar"
    )
    identity_inspect.add_argument("account")
    identity_inspect.add_argument("annotations")
    identity_inspect.add_argument("result")
    identity_inspect.add_argument("--expected-sha256")
    identity_inspect.add_argument("--output")
    for name in (
        "resolve-holders",
        "verify-attribution",
        "attribution-table",
        "resolve-attributed-identity",
        "verify-attributed-identity",
        "attributed-identity-roles",
    ):
        attribution = commands.add_parser(name, help="checked context holder attribution")
        attribution.add_argument("account")
        attribution.add_argument("annotations")
        attribution.add_argument("proposals")
        if name not in ("resolve-holders", "resolve-attributed-identity"):
            attribution.add_argument("result")
        attribution.add_argument("--expected-sha256")
        if name not in ("verify-attribution", "verify-attributed-identity"):
            attribution.add_argument("--output")
    align = commands.add_parser(
        "align", help="generate sparse candidates and partial references offline"
    )
    align.add_argument("account")
    align.add_argument("--expected-sha256")
    align.add_argument("--policy-file")
    align.add_argument("--account-only", action="store_true")
    align.add_argument("--output")
    sequence = commands.add_parser("infer-sequence", help="infer from sparse numeric inputs")
    sequence.add_argument("request")
    sequence.add_argument("--output")
    sequence_check = commands.add_parser(
        "verify-sequence", help="recompute a saved result against its independent input"
    )
    sequence_check.add_argument("request")
    sequence_check.add_argument("result")
    for name in (
        "admit-temporal",
        "verify-temporal",
        "temporal-table",
        "query-temporal",
        "verify-temporal-query",
    ):
        temporal = commands.add_parser(name, help="construct or query story-world temporal claims")
        temporal.add_argument("account")
        temporal.add_argument("--expected-sha256")
        if name in ("admit-temporal", "verify-temporal"):
            temporal.add_argument("proposals")
            temporal.add_argument("--adjudicator")
        elif name in ("query-temporal", "verify-temporal-query"):
            temporal.add_argument("source")
            temporal.add_argument("target")
            temporal.add_argument("context")
            temporal.add_argument("--policy-file")
        else:
            temporal.add_argument("--context")
            temporal.add_argument("--policy-file")
        if name.startswith("verify-"):
            temporal.add_argument("result")
        else:
            temporal.add_argument("--output")
        if name == "admit-temporal":
            temporal.add_argument("--account-only", action="store_true")
    for name in ("build-hierarchy", "verify-hierarchy", "query-hierarchy"):
        hierarchy = commands.add_parser(name, help="construct or inspect checked primary hierarchy")
        hierarchy.add_argument("account")
        hierarchy.add_argument("--expected-sha256")
        if name == "query-hierarchy":
            hierarchy.add_argument("result")
            hierarchy.add_argument("anchor")
            hierarchy.add_argument("--level", type=int)
        else:
            hierarchy.add_argument("spec")
            if name == "verify-hierarchy":
                hierarchy.add_argument("result")
        if name != "verify-hierarchy":
            hierarchy.add_argument("--output")
    for name in ("build-source-sequence", "verify-source-sequence"):
        source_sequence = commands.add_parser(
            name, help="derive or verify source graph transitions"
        )
        source_sequence.add_argument("source")
        source_sequence.add_argument("recall")
        source_sequence.add_argument("spec")
        source_sequence.add_argument("--source-sha256")
        source_sequence.add_argument("--recall-sha256")
        if name == "build-source-sequence":
            source_sequence.add_argument("--request-only", action="store_true")
            source_sequence.add_argument("--output")
        else:
            source_sequence.add_argument("result")
    for name, summary in (
        ("derive-local-evidence", "derive account evidence and costs"),
        ("infer-accounts", "run sequence inference on account evidence"),
        ("verify-account-inference", "verify a saved sequence inference result"),
        ("align-transport", "fit the unbalanced semantic transport baseline"),
        ("verify-transport", "verify a saved account transport plan"),
        ("local-reference", "normalize independently measured costs on a declared source level"),
        ("verify-local-reference", "verify local score mass against both original accounts"),
    ):
        local = commands.add_parser(name, help=summary)
        local.add_argument("source")
        local.add_argument("recall")
        local.add_argument("spec")
        local.add_argument("--source-sha256")
        local.add_argument("--recall-sha256")
        if name.startswith("verify-"):
            local.add_argument("result")
        else:
            local.add_argument("--output")
        if name == "derive-local-evidence":
            local.add_argument("--request-only", action="store_true")
    for name in ("reconstruct-accounts", "verify-account-reconstruction"):
        reconstruction = commands.add_parser(
            name, help="explicit reconstruction over account inference"
        )
        reconstruction.add_argument("source")
        reconstruction.add_argument("recall")
        reconstruction.add_argument("evidence_spec")
        reconstruction.add_argument("spec")
        reconstruction.add_argument("--source-sha256")
        reconstruction.add_argument("--recall-sha256")
        if name == "reconstruct-accounts":
            reconstruction.add_argument("--inference")
            reconstruction.add_argument("--output")
        else:
            reconstruction.add_argument("result")
    for name in (
        "organization-readout",
        "verify-organization-readout",
        "organize-accounts",
        "verify-account-organization",
    ):
        organization = commands.add_parser(
            name, help="conditional organization with explicit dependence"
        )
        organization.add_argument("source")
        organization.add_argument("recall")
        if "account" in name:
            organization.add_argument("evidence_spec")
            organization.add_argument("order")
        else:
            organization.add_argument("spec")
        organization.add_argument("--source-sha256")
        organization.add_argument("--recall-sha256")
        if name.startswith("verify-"):
            organization.add_argument("result")
        else:
            organization.add_argument("--output")
        if name == "organize-accounts":
            organization.add_argument("--inference")
    args = parser.parse_args(argv)
    try:
        if args.command == "example":
            emit(example_account().model_dump(mode="json"), args.output)
        elif args.command in (
            "organization-readout",
            "verify-organization-readout",
            "organize-accounts",
            "verify-account-organization",
        ):
            org_source = read_supported_account(
                Path(args.source), expected_sha256=args.source_sha256
            )
            exporting = not args.command.startswith("verify-")
            if exporting and org_source.export_permission != "allowed":
                raise PermissionError("producer denies source organization export")
            org_recall = read_supported_account(
                Path(args.recall), expected_sha256=args.recall_sha256
            )
            if exporting and org_recall.export_permission != "allowed":
                raise PermissionError("producer denies recall organization export")
            if "account" in args.command:
                org_evidence = _read_inference_spec(args.evidence_spec)
                org_order = OrganizationOrder.model_validate_json(
                    canonical(strict_json(Path(args.order).read_bytes()))
                )
                if exporting:
                    org_inference = (
                        _read_saved_inference(args.inference, org_evidence)
                        if args.inference
                        else None
                    )
                    account_organization = organize_accounts(
                        org_source, org_recall, org_evidence, org_order, inference=org_inference
                    )
                    emit(account_organization.model_dump(mode="json"), args.output)
                else:
                    organization_model = (
                        RefinedAccountOrganizationResult
                        if isinstance(org_evidence, RefinedInferenceSpec)
                        else AccountOrganizationResult
                    )
                    verified_org_account = verify_account_organization(
                        org_source,
                        org_recall,
                        org_evidence,
                        org_order,
                        organization_model.model_validate_json(
                            canonical(strict_json(Path(args.result).read_bytes()))
                        ),
                    )
                    emit(
                        {
                            "valid": True,
                            "source_sha256": verified_org_account.organization.source_sha256,
                        },
                        None,
                    )
            else:
                org_spec = OrganizationSpec.model_validate_json(
                    canonical(strict_json(Path(args.spec).read_bytes()))
                )
                if exporting:
                    emit(
                        organization_readout(org_source, org_recall, org_spec).model_dump(
                            mode="json"
                        ),
                        args.output,
                    )
                else:
                    verified_org_rows = verify_organization_readout(
                        org_source,
                        org_recall,
                        org_spec,
                        OrganizationResult.model_validate_json(
                            canonical(strict_json(Path(args.result).read_bytes()))
                        ),
                    )
                    emit({"valid": True, "source_sha256": verified_org_rows.source_sha256}, None)
        elif args.command in ("reconstruct-accounts", "verify-account-reconstruction"):
            reconstruction_source = read_supported_account(
                Path(args.source), expected_sha256=args.source_sha256
            )
            exporting = args.command == "reconstruct-accounts"
            if exporting and reconstruction_source.export_permission != "allowed":
                raise PermissionError("producer denies source reconstruction export")
            reconstruction_recall = read_supported_account(
                Path(args.recall), expected_sha256=args.recall_sha256
            )
            if exporting and reconstruction_recall.export_permission != "allowed":
                raise PermissionError("producer denies recall reconstruction export")
            reconstruction_evidence_spec = _read_inference_spec(args.evidence_spec)
            reconstruction_spec = ReconstructionSpec.model_validate_json(
                canonical(strict_json(Path(args.spec).read_bytes()))
            )
            if exporting:
                prior_inference = (
                    _read_saved_inference(args.inference, reconstruction_evidence_spec)
                    if args.inference
                    else None
                )
                reconstruction_result = reconstruct_accounts(
                    reconstruction_source,
                    reconstruction_recall,
                    reconstruction_evidence_spec,
                    reconstruction_spec,
                    inference=prior_inference,
                )
                emit(reconstruction_result.model_dump(mode="json"), args.output)
            else:
                reconstruction_model = (
                    RefinedAccountReconstructionResult
                    if isinstance(reconstruction_evidence_spec, RefinedInferenceSpec)
                    else AccountReconstructionResult
                )
                verified_reconstruction = verify_account_reconstruction(
                    reconstruction_source,
                    reconstruction_recall,
                    reconstruction_evidence_spec,
                    reconstruction_spec,
                    reconstruction_model.model_validate_json(
                        canonical(strict_json(Path(args.result).read_bytes()))
                    ),
                )
                emit(
                    {
                        "valid": True,
                        "source_sha256": verified_reconstruction.reconstruction.source_sha256,
                    },
                    None,
                )
        elif args.command in (
            "derive-local-evidence",
            "infer-accounts",
            "verify-account-inference",
            "local-reference",
            "verify-local-reference",
            "align-transport",
            "verify-transport",
        ):
            local_source = read_supported_account(
                Path(args.source), expected_sha256=args.source_sha256
            )
            exporting = not args.command.startswith("verify-")
            if exporting and local_source.export_permission != "allowed":
                raise PermissionError("producer denies source inference export")
            local_recall = read_supported_account(
                Path(args.recall), expected_sha256=args.recall_sha256
            )
            if exporting and local_recall.export_permission != "allowed":
                raise PermissionError("producer denies recall inference export")
            if args.command in ("align-transport", "verify-transport"):
                transport_spec = TransportSpec.model_validate_json(
                    canonical(strict_json(Path(args.spec).read_bytes()))
                )
                if exporting:
                    fitted = align_transport(local_source, local_recall, transport_spec)
                    emit(fitted.model_dump(mode="json"), args.output)
                else:
                    checked_transport = verify_transport(
                        local_source, local_recall, transport_spec, Path(args.result).read_bytes()
                    )
                    emit({"valid": True, "source_sha256": checked_transport.source_sha256}, None)
            elif args.command in ("local-reference", "verify-local-reference"):
                ref_spec = LocalReferenceSpec.model_validate_json(
                    canonical(strict_json(Path(args.spec).read_bytes()))
                )
                reference_result = local_reference(local_source, local_recall, ref_spec)
                ref_payload = reference_result.model_dump(mode="json")
                if exporting:
                    emit(ref_payload, args.output)
                else:
                    if canonical(strict_json(Path(args.result).read_bytes())) != canonical(
                        ref_payload
                    ):
                        raise ValueError(
                            "local reference differs from original accounts or specification"
                        )
                    emit({"valid": True, "source_sha256": reference_result.source_sha256}, None)
            else:
                account_spec = _read_inference_spec(args.spec)
                if args.command == "derive-local-evidence":
                    local_spec = (
                        account_spec.local
                        if isinstance(account_spec, RefinedInferenceSpec)
                        else account_spec
                    )
                    local_result = derive_local_evidence(local_source, local_recall, local_spec)
                    emit(
                        (
                            local_result.sequence.request if args.request_only else local_result
                        ).model_dump(mode="json"),
                        args.output,
                    )
                else:
                    saved = (
                        _read_saved_inference(args.result, account_spec)
                        if args.command == "verify-account-inference"
                        else None
                    )
                    resolved = _resolve_account_inference(
                        local_source, local_recall, account_spec, saved
                    )
                    if saved is None:
                        emit(resolved.model_dump(mode="json"), args.output)
                    else:
                        spec_sha256 = (
                            resolved.spec_sha256
                            if isinstance(resolved, RefinedAccountInference)
                            else resolved.evidence.spec_sha256
                        )
                        emit({"valid": True, "spec_sha256": spec_sha256}, None)
        elif args.command in ("build-source-sequence", "verify-source-sequence"):
            graph_source = read_supported_account(
                Path(args.source), expected_sha256=args.source_sha256
            )
            building = args.command == "build-source-sequence"
            if building and graph_source.export_permission != "allowed":
                raise PermissionError("producer denies source sequence export")
            graph_recall = read_supported_account(
                Path(args.recall), expected_sha256=args.recall_sha256
            )
            if building and graph_recall.export_permission != "allowed":
                raise PermissionError("producer denies recall sequence export")
            graph_spec = SourceSequenceSpec.model_validate_json(
                canonical(strict_json(Path(args.spec).read_bytes()))
            )
            if building:
                graph_result = build_source_sequence(graph_source, graph_recall, graph_spec)
                emit(
                    (graph_result.request if args.request_only else graph_result).model_dump(
                        mode="json"
                    ),
                    args.output,
                )
            else:
                graph_result = verify_source_sequence(
                    graph_source,
                    graph_recall,
                    graph_spec,
                    SourceSequenceResult.model_validate_json(
                        canonical(strict_json(Path(args.result).read_bytes()))
                    ),
                )
                emit({"valid": True, "spec_sha256": graph_result.spec_sha256}, None)
        elif args.command in ("infer-sequence", "verify-sequence"):
            sequence_request = validate_sequence(Path(args.request).read_bytes())
            if args.command == "infer-sequence":
                if sequence_request.export_permission != "allowed":
                    raise PermissionError("producer denies sequence result export")
                emit(infer_sequence(sequence_request).model_dump(mode="json"), args.output)
            else:
                verified_sequence = verify_sequence(
                    sequence_request, Path(args.result).read_bytes()
                )
                emit({"valid": True, "request_sha256": verified_sequence.request_sha256}, None)
        elif args.command == "schema":
            emit(
                {"account": Account.model_json_schema(), "analysis": Analysis.model_json_schema()},
                args.output,
            )
        elif args.command in (
            "admit-temporal",
            "verify-temporal",
            "temporal-table",
            "query-temporal",
            "verify-temporal-query",
        ):
            temporal_account = read_supported_account(
                Path(args.account), expected_sha256=args.expected_sha256
            )
            if (
                not args.command.startswith("verify-")
                and temporal_account.export_permission != "allowed"
            ):
                raise PermissionError("producer denies temporal export")
            if args.command in ("admit-temporal", "verify-temporal"):
                if not isinstance(temporal_account, RichAccount):
                    raise ValueError(
                        "temporal admission requires a rich account; upgrade v3 explicitly"
                    )
                temporal_raw = Path(args.proposals).read_text(encoding="utf-8")
                if args.command == "admit-temporal":
                    temporal_result = admit_temporal_relations(
                        temporal_account, temporal_raw, adjudicator=args.adjudicator
                    )
                    if args.account_only and temporal_result.status != "admitted":
                        raise ValueError("account-only temporal export requires complete admission")
                    emit(
                        (
                            temporal_result.account if args.account_only else temporal_result
                        ).model_dump(mode="json"),
                        args.output,
                    )
                else:
                    temporal_receipt = TemporalResult.model_validate_json(
                        canonical(strict_json(Path(args.result).read_bytes()))
                    )
                    verify_temporal_relations(
                        temporal_account,
                        temporal_raw,
                        temporal_receipt,
                        adjudicator=args.adjudicator,
                    )
                    emit({"valid": True}, None)
            else:
                temporal_policy = (
                    TemporalPolicy.model_validate_json(
                        canonical(strict_json(Path(args.policy_file).read_bytes()))
                    )
                    if args.policy_file
                    else None
                )
                if args.command == "temporal-table":
                    emit(
                        [
                            r.model_dump(mode="json")
                            for r in temporal_table(
                                temporal_account, args.context, policy=temporal_policy
                            )
                        ],
                        args.output,
                    )
                elif args.command == "query-temporal":
                    emit(
                        query_temporal_relation(
                            temporal_account,
                            args.source,
                            args.target,
                            args.context,
                            policy=temporal_policy,
                        ).model_dump(mode="json"),
                        args.output,
                    )
                else:
                    temporal_query = TemporalQuery.model_validate_json(
                        canonical(strict_json(Path(args.result).read_bytes()))
                    )
                    verify_temporal_query(
                        temporal_account,
                        args.source,
                        args.target,
                        args.context,
                        temporal_query,
                        policy=temporal_policy,
                    )
                    emit({"valid": True}, None)
        elif args.command in ("build-hierarchy", "verify-hierarchy", "query-hierarchy"):
            hierarchy_account = read_supported_account(
                Path(args.account), expected_sha256=args.expected_sha256
            )
            if (
                args.command != "verify-hierarchy"
                and hierarchy_account.export_permission != "allowed"
            ):
                raise PermissionError("producer denies hierarchy export")
            if args.command == "query-hierarchy":
                saved_hierarchy = HierarchyResult.model_validate_json(
                    canonical(strict_json(Path(args.result).read_bytes()))
                )
                emit(
                    query_hierarchy(
                        hierarchy_account, saved_hierarchy, args.anchor, descendant_level=args.level
                    ).model_dump(mode="json"),
                    args.output,
                )
            else:
                hierarchy_spec = HierarchySpec.model_validate_json(
                    canonical(strict_json(Path(args.spec).read_bytes()))
                )
                if args.command == "build-hierarchy":
                    emit(
                        build_hierarchy(hierarchy_account, hierarchy_spec).model_dump(mode="json"),
                        args.output,
                    )
                else:
                    hierarchy_verified = verify_hierarchy(
                        hierarchy_account,
                        hierarchy_spec,
                        HierarchyResult.model_validate_json(
                            canonical(strict_json(Path(args.result).read_bytes()))
                        ),
                    )
                    emit({"valid": True, "account_sha256": hierarchy_verified.account_sha256}, None)
        elif args.command == "group-source-rows":
            scene_account = read_supported_account(
                Path(args.account), expected_sha256=args.expected_sha256
            )
            if scene_account.export_permission != "allowed":
                raise PermissionError("producer denies scene grouping export")
            scene_data = strict_json(Path(args.groups).read_bytes())
            if not isinstance(scene_data, list):
                raise ValueError("scene groups must be a JSON array")
            scene_groups = tuple(
                SceneGroup.model_validate_json(canonical(group)) for group in scene_data
            )
            grouped = group_source_rows(scene_account, scene_groups)
            emit(
                (grouped.account if args.account_only else grouped).model_dump(mode="json"),
                args.output,
            )
        elif args.command == "prepare-fidelity":
            fidelity_account = read_supported_account(
                Path(args.account), expected_sha256=args.expected_sha256
            )
            if fidelity_account.export_permission != "allowed":
                raise PermissionError("producer denies fidelity export")
            if args.native_source:
                if not isinstance(fidelity_account, RichAccount):
                    raise ValueError("native assessment requires a rich account")
                fidelity_account = upgrade_assessment_account(fidelity_account)
            if (args.identity_annotations is None) != (args.holder_proposals is None):
                raise ValueError("contextual fidelity requires both identity and holder recordings")
            context_evidence = (
                None
                if args.identity_annotations is None
                else ContextEvidence(
                    identity_annotations=Path(args.identity_annotations)
                    .read_bytes()
                    .decode("utf-8"),
                    holder_proposals=Path(args.holder_proposals).read_bytes().decode("utf-8"),
                )
            )
            emit(
                prepare_fidelity(
                    fidelity_account,
                    args.unit,
                    interpretation=args.interpretation,
                    context_evidence=context_evidence,
                ).model_dump(mode="json"),
                args.output,
            )
        elif args.command in ("fidelity-prompt", "import-fidelity", "replay-fidelity"):
            fidelity_request = read_fidelity_request(
                Path(args.request), expected_sha256=args.expected_request_sha256
            )
            if fidelity_request.account.export_permission != "allowed":
                raise PermissionError("producer denies fidelity export")
            if args.command == "fidelity-prompt":
                emit(strict_json(fidelity_prompt(fidelity_request)), args.output)
            elif args.command == "import-fidelity":
                fidelity_recording = FidelityRecording.model_validate_json(
                    canonical(strict_json(Path(args.reply).read_bytes()))
                )
                import_fidelity(
                    fidelity_request, fidelity_recording, Path(args.recording), recover=args.recover
                )
                emit({"recording": args.recording, "imported": 1}, None)
            else:
                fidelity_result = replay_fidelity(
                    fidelity_request,
                    Path(args.recording),
                    expected_import_sha256=args.expected_import_sha256,
                )
                if args.account_only and any(
                    d.status != "admitted" for d in fidelity_result.decisions
                ):
                    raise ValueError(
                        "fidelity account-only export would discard unsuccessful outcomes"
                    )
                emit(
                    (fidelity_result.account if args.account_only else fidelity_result).model_dump(
                        mode="json"
                    ),
                    args.output,
                )
        elif args.command == "prepare-claim-assessment":
            claim_account = read_supported_account(
                Path(args.account), expected_sha256=args.expected_sha256
            )
            if claim_account.export_permission != "allowed":
                raise PermissionError("producer denies claim assessment export")
            question_data = strict_json(Path(args.questions).read_bytes())
            if not isinstance(question_data, list):
                raise ValueError("claim questions must be a JSON array")
            questions = tuple(
                ClaimQuestion.model_validate_json(canonical(q)) for q in question_data
            )
            emit(
                prepare_claim_assessment(claim_account, questions).model_dump(mode="json"),
                args.output,
            )
        elif args.command in ("assess-claims", "verify-claims"):
            claim_plan = validate_claim_plan(Path(args.plan).read_bytes())
            if claim_plan.account.export_permission != "allowed":
                raise PermissionError("producer denies claim assessment export")
            claim_reply = Path(args.reply).read_bytes()
            if args.command == "assess-claims":
                emit(assess_claims(claim_plan, claim_reply).model_dump(mode="json"), args.output)
            else:
                verified_claims = verify_claim_assessment(
                    claim_plan, claim_reply, Path(args.result).read_bytes()
                )
                emit(
                    {"valid": True, "population_sha256": verified_claims.population_sha256},
                    args.output,
                )
        elif args.command == "align":
            account = read_supported_account(
                Path(args.account), expected_sha256=args.expected_sha256
            )
            if account.export_permission != "allowed":
                raise PermissionError("producer denies alignment export")
            alignment = align_account(
                account,
                validate_policy(Path(args.policy_file).read_bytes()) if args.policy_file else None,
            )
            emit(
                (alignment.account if args.account_only else alignment).model_dump(mode="json"),
                args.output,
            )
        elif args.command == "prepare-details":
            detail_account = validate_rich_account(Path(args.account).read_bytes())
            if detail_account.export_permission != "allowed":
                raise PermissionError("producer denies detail export")
            detail_request = prepare_details(detail_account, tuple(args.units))
            emit(detail_request.model_dump(mode="json"), args.output)
        elif args.command in ("detail-prompt", "import-details", "replay-details"):
            detail_request = read_detail_request(Path(args.request))
            if detail_request.account.export_permission != "allowed":
                raise PermissionError("producer denies detail export")
            if args.command == "detail-prompt":
                emit(strict_json(detail_prompt(detail_request)), args.output)
            elif args.command == "import-details":
                detail_recording = DetailRecording.model_validate_json(
                    canonical(strict_json(Path(args.reply).read_bytes()))
                )
                import_details(
                    detail_request, detail_recording, Path(args.recording), recover=args.recover
                )
            else:
                detail_result = replay_details(
                    detail_request,
                    Path(args.recording),
                    expected_import_sha256=args.expected_import_sha256,
                )
                if args.account_only and any(
                    r.status != "admitted" for r in detail_result.receipts
                ):
                    raise ValueError("account-only export would hide incomplete detail admission")
                emit(
                    (detail_result.account if args.account_only else detail_result).model_dump(
                        mode="json"
                    ),
                    args.output,
                )
        elif args.command == "prepare-playback":
            media_source = MediaSource.model_validate_json(
                canonical(strict_json(Path(args.source).read_bytes()))
            )
            observation_values = strict_json(Path(args.observations).read_bytes())
            if not isinstance(observation_values, list):
                raise ValueError("playback observations must be a JSON array")
            native_request = prepare_playback(
                media_source,
                tuple(
                    PlaybackObservation.model_validate_json(canonical(o))
                    for o in observation_values
                ),
                export_permission="allowed" if args.allow_export else "denied",
            )
            emit(native_request.model_dump(mode="json"), args.output)
        elif args.command in ("playback-prompt", "import-playback", "replay-playback"):
            native_request = read_playback_request(Path(args.request))
            if native_request.export_permission != "allowed":
                raise PermissionError("producer denies playback construction export")
            if args.command == "playback-prompt":
                emit(strict_json(playback_prompt(native_request)), args.output)
            elif args.command == "import-playback":
                native_recording = PlaybackRecording.model_validate_json(
                    canonical(strict_json(Path(args.reply).read_bytes()))
                )
                import_playback(
                    native_request, native_recording, Path(args.recording), recover=args.recover
                )
            else:
                native_result = replay_playback(
                    native_request,
                    Path(args.recording),
                    expected_import_sha256=args.expected_import_sha256,
                )
                if args.account_only and any(
                    r.status != "admitted" for r in native_result.resolutions
                ):
                    raise ValueError(
                        "account-only export would hide incomplete playback construction"
                    )
                emit(
                    (native_result.account if args.account_only else native_result).model_dump(
                        mode="json"
                    ),
                    args.output,
                )
        elif args.command in ("situation-playback", "verify-situation-playback"):
            native_account = validate_rich_account(Path(args.account).read_bytes())
            if (
                args.command == "situation-playback"
                and native_account.export_permission != "allowed"
            ):
                raise PermissionError("producer denies playback readout export")
            bin_values = strict_json(Path(args.bins).read_bytes()) if args.bins else []
            if not isinstance(bin_values, list):
                raise ValueError("playback bins must be a JSON array")
            native_bins = tuple(TickInterval.model_validate_json(canonical(b)) for b in bin_values)
            if args.command == "situation-playback":
                emit(
                    situation_playback(
                        native_account, args.situation, args.source, native_bins
                    ).model_dump(mode="json"),
                    args.output,
                )
            else:
                native_saved = SituationPlaybackReadout.model_validate_json(
                    canonical(strict_json(Path(args.result).read_bytes()))
                )
                native_checked = verify_situation_playback(
                    native_account, args.situation, args.source, native_bins, native_saved
                )
                emit(
                    {"verified": True, "account_sha256": native_checked.account_sha256}, args.output
                )
        elif args.command == "prepare-rich":
            row_values = strict_json(Path(args.rows).read_bytes())
            if not isinstance(row_values, list):
                raise ValueError("rich construction rows must be a JSON array")
            rich_request = prepare_rich_construction(
                args.document_key,
                args.side,
                Path(args.text).read_bytes().decode("utf-8"),
                tuple(SourceRow.model_validate_json(canonical(row)) for row in row_values),
                export_permission="allowed" if args.allow_export else "denied",
                full_contexts=args.full_contexts,
            )
            emit(rich_request.model_dump(mode="json"), args.output)
        elif args.command == "prepare-construction":
            raw_input = Path(args.input).read_bytes()
            if (
                args.expected_input_sha256 is not None
                and sha256(raw_input).hexdigest() != args.expected_input_sha256
            ):
                raise ValueError("construction input differs from external SHA-256")
            construction_prepared = prepare_construction(
                args.key,
                args.side,
                raw_input,
                full_contexts=args.full_contexts,
                export_permission="allowed" if args.allow_export else "denied",
            )
            emit(construction_prepared.model_dump(mode="json"), args.output)
        elif args.command in ("prepare-sherlock-source", "prepare-sherlock-recall"):
            sherlock_input = Path(args.input).read_bytes()
            record_path = args.repair if args.command == "prepare-sherlock-source" else args.lineage
            sherlock_record = Path(record_path).read_bytes()
            if (
                args.expected_input_sha256 is not None
                and sha256(sherlock_input).hexdigest() != args.expected_input_sha256
            ):
                raise ValueError("Sherlock input differs from external SHA-256")
            if (
                args.expected_record_sha256 is not None
                and sha256(sherlock_record).hexdigest() != args.expected_record_sha256
            ):
                raise ValueError("Sherlock record differs from external SHA-256")
            sherlock_intake: SherlockSource | SherlockRecall
            if args.command == "prepare-sherlock-source":
                sherlock_intake = prepare_sherlock_source(args.key, sherlock_input, sherlock_record)
            else:
                sherlock_intake = prepare_sherlock_recall(
                    args.key, sherlock_input, sherlock_record, source_id=args.source_id
                )
            sherlock_prepared = prepare_sherlock_construction(
                sherlock_intake,
                full_contexts=args.full_contexts,
                export_permission="allowed" if args.allow_export else "denied",
            )
            emit(sherlock_prepared.model_dump(mode="json"), args.output)
        elif args.command == "sherlock-compose":
            composition_prepared = read_prepared_construction(
                Path(args.request), expected_sha256=args.expected_input_sha256
            )
            if composition_prepared.export_permission != "allowed":
                raise PermissionError("producer denies Sherlock composition export")
            if not isinstance(composition_prepared.intake, SherlockSource):
                raise ValueError("Sherlock composition requires a Sherlock source intake")
            composed = compose_sherlock_source(
                composition_prepared.intake, export_permission="allowed"
            )
            emit(composed.model_dump(mode="json"), args.output)
        elif args.command in (
            "sherlock-composition-readout",
            "verify-sherlock-composition-readout",
        ):
            saved_composition = SherlockComposition.model_validate_json(
                canonical(strict_json(Path(args.composition).read_bytes()))
            )
            if saved_composition.export_permission != "allowed":
                raise PermissionError("producer denies Sherlock composition readout export")
            composition_bin_values = strict_json(Path(args.bins).read_bytes()) if args.bins else []
            if not isinstance(composition_bin_values, list):
                raise ValueError("composition bins must be a JSON array")
            composition_bins = tuple(
                TickInterval.model_validate_json(canonical(item)) for item in composition_bin_values
            )
            composition_units = tuple(args.unit)
            if args.command == "sherlock-composition-readout":
                composition_readout = project_sherlock_composition(
                    saved_composition, composition_units, composition_bins
                )
                emit(composition_readout.model_dump(mode="json"), args.output)
            else:
                prior_readout = SherlockCompositionReadout.model_validate_json(
                    canonical(strict_json(Path(args.result).read_bytes()))
                )
                checked_readout = verify_sherlock_composition_readout(
                    saved_composition, composition_units, composition_bins, prior_readout
                )
                emit({"verified": True, "input_sha256": checked_readout.input_sha256}, args.output)
        elif args.command == "sherlock-recall-timing":
            sherlock_prepared = read_prepared_construction(Path(args.request))
            if sherlock_prepared.export_permission != "allowed":
                raise PermissionError("producer denies Sherlock recall timing export")
            if not isinstance(sherlock_prepared.intake, SherlockRecall):
                raise ValueError("Sherlock recall timing requires a Sherlock recall intake")
            timing = sherlock_recall_timing(sherlock_prepared.intake, args.clock)
            emit(timing.model_dump(mode="json"), args.output)
        elif args.command == "caption-alignment":
            from .alignment_method import (
                CaptionEvidence,
                NativeCaptionEvidence,
                run_alignment_method,
            )

            declaration = strict_json(Path(args.captions).read_bytes())
            caption_input: CaptionEvidence | NativeCaptionEvidence
            if isinstance(declaration, dict) and "schema_version" in declaration:
                caption_input = NativeCaptionEvidence.model_validate_json(canonical(declaration))
            else:
                caption_input = CaptionEvidence.model_validate_json(canonical(declaration))
            aligned = run_alignment_method(
                Path(args.plan),
                caption_input,
                blend=args.blend,
                max_candidates=args.max_candidates,
                max_postings=args.max_postings,
            )
            emit(aligned.model_dump(mode="json"), args.output)
        elif args.command == "prepare-friends":
            from .friends import FriendsStudy, prepare_friends_study

            friends_study = FriendsStudy.model_validate_json(
                canonical(strict_json(Path(args.study).read_bytes()))
            )
            if friends_study.export_permission != "allowed":
                raise PermissionError("producer denies Friends intake export")
            emit(prepare_friends_study(friends_study).model_dump(mode="json"), args.output)
        elif args.command in ("feature-windows", "verify-feature-windows"):
            from .feature_windows import Lexicon, feature_windows

            feature_query = RecordingQuery.model_validate_json(
                canonical(strict_json(Path(args.query).read_bytes()))
            )
            lexicon = Lexicon.model_validate_json(
                canonical(strict_json(Path(args.lexicon).read_bytes()))
            )
            feature_result = feature_windows(Path(args.plan), feature_query, lexicon).model_dump(
                mode="json"
            )
            if args.command == "verify-feature-windows":
                if canonical(strict_json(Path(args.result).read_bytes())) != canonical(
                    feature_result
                ):
                    raise ValueError("feature windows differ from the replayed measurements")
                emit(
                    {"verified": True, "analysis_sha256": feature_result["analysis_sha256"]},
                    args.output,
                )
            else:
                emit(feature_result, args.output)
        elif args.command in ("source-concentration", "verify-source-concentration"):
            concentration_query = ConcentrationQuery.model_validate_json(
                canonical(strict_json(Path(args.query).read_bytes()))
            )
            concentration = source_concentration(Path(args.plan), concentration_query)
            concentration_payload = concentration.model_dump(mode="json")
            if args.command == "verify-source-concentration":
                if canonical(strict_json(Path(args.result).read_bytes())) != canonical(
                    concentration_payload
                ):
                    raise ValueError(
                        "source concentration differs from the replayed plan and query"
                    )
                emit(
                    {"verified": True, "analysis_sha256": concentration.analysis_sha256},
                    args.output,
                )
            else:
                emit(concentration_payload, args.output)
        elif args.command in ("source-time-grid", "verify-source-time-grid"):
            grid_input = strict_json(Path(args.query).read_bytes())
            if isinstance(grid_input, dict) and "transport" in grid_input:
                grid_schema: type[TransportGridQuery | LocalScoreGridQuery | SourceGridQuery] = (
                    TransportGridQuery
                )
            elif isinstance(grid_input, dict) and "reference" in grid_input:
                grid_schema = LocalScoreGridQuery
            else:
                grid_schema = SourceGridQuery
            grid_result = project_source_grid(
                Path(args.plan), grid_schema.model_validate_json(canonical(grid_input))
            )
            grid_payload = grid_result.model_dump(mode="json")
            if args.command.startswith("verify-"):
                if canonical(strict_json(Path(args.result).read_bytes())) != canonical(
                    grid_payload
                ):
                    raise ValueError("source grid differs from exact plan and query")
                emit(
                    {"verified": True, "analysis_sha256": grid_result.analysis_sha256}, args.output
                )
            else:
                emit(grid_payload, args.output)
        elif args.command in (
            "timed-recall",
            "verify-timed-recall",
            "recall-exposure",
            "verify-recall-exposure",
        ):
            query_json = canonical(strict_json(Path(args.query).read_bytes()))
            timed_result: RecallExposure | TimedRecall
            if args.command.endswith("recall-exposure"):
                timed_result = recall_exposure(
                    Path(args.plan), ExposureQuery.model_validate_json(query_json)
                )
                label = "recall exposure"
            else:
                timed_result = timed_recall(
                    Path(args.plan), RecordingQuery.model_validate_json(query_json)
                )
                label = "timed recall"
            if args.command.startswith("verify-"):
                saved_timing = strict_json(Path(args.result).read_bytes())
                if canonical(saved_timing) != canonical(timed_result.model_dump(mode="json")):
                    raise ValueError(f"{label} differs from exact plan and recording query")
                emit(
                    {"verified": True, "analysis_sha256": timed_result.analysis_sha256}, args.output
                )
            else:
                emit(timed_result.model_dump(mode="json"), args.output)
        elif args.command in (
            "prepared-construction-prompt",
            "import-prepared-construction",
            "replay-prepared-construction",
            "verify-prepared-construction",
        ):
            construction_prepared = read_prepared_construction(Path(args.request))
            if args.command == "prepared-construction-prompt":
                emit(strict_json(prepared_construction_prompt(construction_prepared)), args.output)
            elif args.command == "import-prepared-construction":
                prepared_construction_prompt(construction_prepared)
                prepared_recording = PreparedConstructionRecording.model_validate_json(
                    canonical(strict_json(Path(args.reply).read_bytes()))
                )
                import_prepared_construction(
                    construction_prepared,
                    prepared_recording,
                    Path(args.recording),
                    recover=args.recover,
                )
            elif args.command == "replay-prepared-construction":
                prepared_result = replay_prepared_construction(
                    construction_prepared,
                    Path(args.recording),
                    expected_import_sha256=args.expected_import_sha256,
                )
                emit(prepared_result.model_dump(mode="json"), args.output)
            else:
                prepared_construction_prompt(construction_prepared)
                prepared_reply = Path(args.reply).read_bytes().decode("utf-8")
                prepared_saved = PreparedConstructionResult.model_validate_json(
                    canonical(strict_json(Path(args.result).read_bytes()))
                )
                prepared_result = verify_prepared_construction(
                    construction_prepared, prepared_reply, prepared_saved
                )
                emit(
                    {"verified": True, "prepared_sha256": prepared_result.prepared_sha256},
                    args.output,
                )
        elif args.command == "situation-table":
            rich_account = validate_rich_account(Path(args.account).read_bytes())
            if rich_account.export_permission != "allowed":
                raise PermissionError("producer denies situation export")
            emit(
                [row.model_dump(mode="json") for row in situation_table(rich_account)], args.output
            )
        elif args.command in ("rich-prompt", "import-rich", "replay-rich"):
            rich_request = read_rich_request(Path(args.request))
            if rich_request.export_permission != "allowed":
                raise PermissionError("producer denies rich construction export")
            if args.command == "rich-prompt":
                emit(strict_json(rich_prompt(rich_request)), args.output)
            elif args.command == "import-rich":
                rich_recording = RichRecording.model_validate_json(
                    canonical(strict_json(Path(args.reply).read_bytes()))
                )
                import_rich(
                    rich_request, rich_recording, Path(args.recording), recover=args.recover
                )
            else:
                rich_result = replay_rich(
                    rich_request,
                    Path(args.recording),
                    expected_import_sha256=args.expected_import_sha256,
                )
                if args.account_only and any(
                    r.status != "admitted" for r in rich_result.resolutions
                ):
                    raise ValueError("account-only export would hide incomplete rich construction")
                emit(
                    (rich_result.account if args.account_only else rich_result).model_dump(
                        mode="json"
                    ),
                    args.output,
                )
        elif args.command in ("construction-link-prompt", "connect-rich", "verify-connected-rich"):
            linked_request = read_rich_request(Path(args.request))
            if linked_request.export_permission != "allowed":
                raise PermissionError("producer denies construction links export")
            construction_raw = Path(args.construction).read_bytes().decode("utf-8")
            if args.command == "construction-link-prompt":
                emit(
                    strict_json(construction_link_prompt(linked_request, construction_raw)),
                    args.output,
                )
            else:
                links_raw = Path(args.links).read_bytes().decode("utf-8")
                if args.command == "connect-rich":
                    connected = connect_rich_construction(
                        linked_request, construction_raw, links_raw
                    )
                    if args.account_only and (
                        connected.status != "admitted"
                        or any(r.status != "admitted" for r in connected.construction.resolutions)
                    ):
                        raise ValueError(
                            "account-only export would hide incomplete connected construction"
                        )
                    emit(
                        (connected.account if args.account_only else connected).model_dump(
                            mode="json"
                        ),
                        args.output,
                    )
                else:
                    connected_saved = ConnectedConstructionResult.model_validate_json(
                        canonical(strict_json(Path(args.result).read_bytes()))
                    )
                    verified_connected = verify_connected_construction(
                        linked_request, construction_raw, links_raw, connected_saved
                    )
                    emit(
                        {"verified": True, "links_sha256": verified_connected.links_sha256},
                        args.output,
                    )
        elif args.command == "prepare-mapping":
            mapping_account = read_supported_account(
                Path(args.account), expected_sha256=args.expected_sha256
            )
            if mapping_account.export_permission != "allowed":
                raise PermissionError("producer denies mapping export")
            if args.native_source:
                if not isinstance(mapping_account, RichAccount):
                    raise ValueError("native assessment requires a rich account")
                mapping_account = upgrade_assessment_account(mapping_account)
            mapping_request = prepare_mapping(
                mapping_account, args.unit, interpretation=args.interpretation
            )
            emit(mapping_request.model_dump(mode="json"), args.output)
        elif args.command in ("mapping-prompt", "import-mapping", "replay-mapping"):
            mapping_request = read_mapping_request(Path(args.request))
            if mapping_request.account.export_permission != "allowed":
                raise PermissionError("producer denies mapping export")
            if args.command == "mapping-prompt":
                emit(strict_json(mapping_prompt(mapping_request)), args.output)
            elif args.command == "import-mapping":
                mapping_recording = MappingRecording.model_validate_json(
                    canonical(strict_json(Path(args.reply).read_bytes()))
                )
                import_mapping(
                    mapping_request, mapping_recording, Path(args.recording), recover=args.recover
                )
            else:
                mapping_result = replay_mapping(
                    mapping_request,
                    Path(args.recording),
                    expected_import_sha256=args.expected_import_sha256,
                )
                if args.account_only and any(
                    d.status != "admitted" for d in mapping_result.decisions
                ):
                    raise ValueError(
                        "account-only export would hide unsuccessful mapping admission"
                    )
                emit(
                    (mapping_result.account if args.account_only else mapping_result).model_dump(
                        mode="json"
                    ),
                    args.output,
                )
        elif args.command in (
            "resolve-holders",
            "verify-attribution",
            "attribution-table",
            "resolve-attributed-identity",
            "verify-attributed-identity",
            "attributed-identity-roles",
        ):
            attribution_account = read_supported_account(
                Path(args.account), expected_sha256=args.expected_sha256
            )
            if (
                args.command not in ("verify-attribution", "verify-attributed-identity")
                and attribution_account.export_permission != "allowed"
            ):
                raise PermissionError("producer denies attribution export")
            form_annotations = Path(args.annotations).read_bytes().decode("utf-8")
            holder_proposals = Path(args.proposals).read_bytes().decode("utf-8")
            if args.command == "resolve-attributed-identity":
                emit(
                    resolve_attributed_identity(
                        attribution_account, form_annotations, holder_proposals
                    ).model_dump(mode="json"),
                    args.output,
                )
            elif args.command in ("verify-attributed-identity", "attributed-identity-roles"):
                saved_identity = AttributedIdentityResult.model_validate_json(
                    canonical(strict_json(Path(args.result).read_bytes()))
                )
                if args.command == "verify-attributed-identity":
                    verified_identity = verify_attributed_identity(
                        attribution_account, form_annotations, holder_proposals, saved_identity
                    )
                    emit(
                        {
                            "verified": True,
                            "account_sha256": verified_identity.attribution.account_sha256,
                        },
                        None,
                    )
                else:
                    emit(
                        [
                            row.model_dump(mode="json")
                            for row in attributed_identity_roles(
                                attribution_account,
                                form_annotations,
                                holder_proposals,
                                saved_identity,
                            )
                        ],
                        args.output,
                    )
            elif args.command == "resolve-holders":
                emit(
                    resolve_context_holders(
                        attribution_account, form_annotations, holder_proposals
                    ).model_dump(mode="json"),
                    args.output,
                )
            else:
                saved_attribution = AttributionResult.model_validate_json(
                    canonical(strict_json(Path(args.result).read_bytes()))
                )
                if args.command == "verify-attribution":
                    verified_attribution = verify_attribution(
                        attribution_account, form_annotations, holder_proposals, saved_attribution
                    )
                    emit(
                        {"verified": True, "account_sha256": verified_attribution.account_sha256},
                        None,
                    )
                else:
                    emit(
                        [
                            row.model_dump(mode="json")
                            for row in attribution_table(
                                attribution_account,
                                form_annotations,
                                holder_proposals,
                                saved_attribution,
                            )
                        ],
                        args.output,
                    )
        elif args.command in ("resolve-identity", "identity-roles"):
            identity_account = read_supported_account(
                Path(args.account), expected_sha256=args.expected_sha256
            )
            if identity_account.export_permission != "allowed":
                raise PermissionError("producer denies identity export")
            annotations = Path(args.annotations).read_bytes().decode("utf-8")
            if args.command == "resolve-identity":
                emit(
                    resolve_text_identity(identity_account, annotations).model_dump(mode="json"),
                    args.output,
                )
            else:
                identities = IdentityResult.model_validate_json(
                    canonical(strict_json(Path(args.result).read_bytes()))
                )
                emit(
                    [
                        row.model_dump(mode="json")
                        for row in identity_role_table(identity_account, annotations, identities)
                    ],
                    args.output,
                )
        elif args.command in ("admit-relations", "relation-table"):
            relation_account = read_supported_account(
                Path(args.account), expected_sha256=args.expected_sha256
            )
            if relation_account.export_permission != "allowed":
                raise PermissionError("producer denies relation export")
            if args.command == "relation-table":
                emit(
                    [row.model_dump(mode="json") for row in relation_table(relation_account)],
                    args.output,
                )
            else:
                relation_result = admit_relations(
                    relation_account, Path(args.proposals).read_bytes().decode("utf-8")
                )
                if args.account_only and relation_result.status in (
                    "rejected",
                    "not-attempted",
                    "failed",
                ):
                    raise ValueError(
                        "account-only export would hide unsuccessful relation admission"
                    )
                emit(
                    (relation_result.account if args.account_only else relation_result).model_dump(
                        mode="json"
                    ),
                    args.output,
                )
        elif args.command == "prepare-extraction":
            extraction = prepare_extraction(
                args.recall_key,
                Path(args.recall).read_bytes(),
                source=read_account(Path(args.source_account)) if args.source_account else None,
                export_permission="allowed" if args.allow_export else "denied",
            )
            emit(extraction.model_dump(mode="json"), args.output)
        elif args.command == "prepare-source":
            raw_rows = strict_json(Path(args.rows).read_bytes())
            if not isinstance(raw_rows, list):
                raise ValueError("source rows must be a JSON array")
            rows = tuple(SourceRow.model_validate(item) for item in raw_rows)
            source = prepare_source(
                args.source_key,
                Path(args.text).read_bytes().decode("utf-8"),
                rows,
                export_permission="allowed" if args.allow_export else "denied",
            )
            emit(source.model_dump(mode="json"), args.output)
        elif args.command in ("source-prompt", "import-source", "replay-source"):
            source = read_source_request(Path(args.request))
            if args.command == "source-prompt":
                emit(strict_json(source_prompt(source)), args.output)
            elif args.command == "import-source":
                source_recording = SourceRecording.model_validate_json(
                    canonical(strict_json(Path(args.reply).read_bytes()))
                )
                import_source(source, source_recording, Path(args.recording), recover=args.recover)
                emit({"recording": args.recording, "imported": 1}, None)
            else:
                if source.export_permission != "allowed":
                    raise PermissionError("producer denies source export")
                compiled = replay_source(
                    source,
                    Path(args.recording),
                    expected_import_sha256=args.expected_import_sha256,
                )
                emit(
                    (compiled.account if args.account_only else compiled).model_dump(mode="json"),
                    args.output,
                )
        elif args.command == "prepare-semantic":
            semantic_source = read_supported_account(Path(args.source))
            if semantic_source.export_permission != "allowed":
                raise PermissionError("producer denies semantic embedding export")
            semantic_recall = read_supported_account(Path(args.recall))
            if semantic_recall.export_permission != "allowed":
                raise PermissionError("producer denies semantic embedding export")
            semantic_spec = LocalEvidenceSpec.model_validate_json(
                canonical(strict_json(Path(args.spec).read_bytes()))
            )
            semantic_pair = EmbeddingPair.model_validate_json(
                canonical(strict_json(Path(args.pair).read_bytes()))
            )
            semantic_cache = (
                None
                if args.cache is None
                else EmbeddingCache.model_validate_json(
                    canonical(strict_json(Path(args.cache).read_bytes()))
                )
            )
            semantic_request = prepare_semantic(
                semantic_source,
                semantic_recall,
                semantic_spec,
                semantic_pair,
                cache=semantic_cache,
                max_comparisons=args.max_comparisons,
                max_semantic_candidates=args.max_semantic_candidates,
            )
            emit(semantic_request.model_dump(mode="json"), args.output)
        elif args.command == "replay-analysis":
            emit(replay_analysis_file(Path(args.plan)).model_dump(mode="json"), args.output)
        elif args.command == "verify-analysis":
            analysis_plan_path = Path(args.plan)
            # Replaying first makes both account grants fail closed before the independently
            # supplied receipt is read.
            analysis_expected = replay_analysis_file(analysis_plan_path)
            analysis_receipt = AnalysisReplay.model_validate_json(
                canonical(strict_json(Path(args.result).read_bytes()))
            )
            if analysis_receipt != analysis_expected:
                raise ValueError("analysis replay differs from original plan, inputs or recordings")
            emit({"verified": True}, args.output)
        elif args.command in (
            "semantic-prompt",
            "import-semantic",
            "replay-semantic",
            "verify-semantic",
        ):
            semantic_request = read_semantic_request(Path(args.request))
            if args.command in ("semantic-prompt", "import-semantic") and (
                semantic_request.source.export_permission != "allowed"
                or semantic_request.recall.export_permission != "allowed"
            ):
                raise PermissionError("producer denies semantic embedding export")
            if args.command == "semantic-prompt":
                emit(strict_json(semantic_prompt(semantic_request)), args.output)
            elif args.command == "import-semantic":
                semantic_recording = SemanticRecording.model_validate_json(
                    canonical(strict_json(Path(args.reply).read_bytes()))
                )
                import_semantic(
                    semantic_request, semantic_recording, Path(args.recording), recover=args.recover
                )
                emit({"recording": args.recording, "imported": 1}, None)
            elif args.command == "replay-semantic":
                semantic_result = replay_semantic(
                    semantic_request,
                    Path(args.recording),
                    expected_import_sha256=args.expected_import_sha256,
                )
                emit(semantic_result.model_dump(mode="json"), args.output)
            else:
                semantic_reply = Path(args.reply).read_bytes().decode("utf-8")
                semantic_saved = SemanticResult.model_validate_json(
                    canonical(strict_json(Path(args.result).read_bytes()))
                )
                semantic_result = resolve_semantic(semantic_request, semantic_reply)
                if semantic_result != semantic_saved:
                    raise ValueError("semantic result differs from recorded inputs")
                emit(
                    {"verified": True, "request_sha256": semantic_result.request_sha256},
                    args.output,
                )
        elif args.command in ("extraction-prompt", "import-extraction", "replay-extraction"):
            extraction = read_extraction_request(Path(args.request))
            if args.command == "extraction-prompt":
                emit(strict_json(extraction_prompt(extraction)), args.output)
            elif args.command == "import-extraction":
                extraction_recording = ExtractionRecording.model_validate_json(
                    canonical(strict_json(Path(args.reply).read_bytes()))
                )
                import_extraction(
                    extraction, extraction_recording, Path(args.recording), recover=args.recover
                )
                emit({"recording": args.recording, "imported": 1}, None)
            else:
                if extraction.export_permission != "allowed":
                    raise PermissionError("producer denies extraction export")
                extracted = replay_extraction(
                    extraction,
                    Path(args.recording),
                    expected_import_sha256=args.expected_import_sha256,
                )
                emit(
                    (extracted.account if args.account_only else extracted).model_dump(mode="json"),
                    args.output,
                )
        elif args.command == "prepare-time-study":
            prepared = prepare_time_study_files(
                Path(args.source),
                Path(args.recall),
                args.recall_key,
                expected_source_sha256=args.source_sha256,
                expected_recall_sha256=args.recall_sha256,
                chunk_size=args.chunk_size,
                max_attempts=args.max_attempts,
                export_permission="allowed" if args.allow_export else "denied",
            )
            emit(prepared.model_dump(mode="json"), args.output)
        elif args.command in ("import-time-recording", "replay-time-study"):
            request = read_request(Path(args.request), expected_sha256=args.expected_request_sha256)
            if args.command == "import-time-recording":
                replies = ImportedReplies.model_validate_json(
                    canonical(strict_json(Path(args.replies).read_bytes()))
                )
                import_time_replies(request, replies, Path(args.recording), recover=args.recover)
                emit({"imported": len(replies.replies), "recording": args.recording}, None)
            else:
                if request.export_permission != "allowed":
                    raise PermissionError("producer denies study result export")
                result = replay_time_study(
                    request,
                    Path(args.recording),
                    expected_import_sha256=args.expected_import_sha256,
                )
                emit(result.model_dump(mode="json"), args.output)
        else:
            supported_account = read_supported_account(
                Path(args.account), expected_sha256=args.expected_sha256
            )
            if args.command == "validate":
                counts = {
                    "valid": True,
                    "details": len(supported_account.details),
                    "events": len(supported_account.events),
                }
                if isinstance(supported_account, RichAccount):
                    counts["states"] = len(supported_account.states)
                emit(counts, None)
            else:
                if supported_account.export_permission != "allowed":
                    raise PermissionError("producer denies analysis export")
                policy: Policy = args.policy
                emit(project(supported_account, policy).model_dump(mode="json"), args.output)
    except (ValueError, OSError, RecordedExecutionError) as error:
        print(f"refused: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
