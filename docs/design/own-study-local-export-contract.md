# Own-study local export admission: proposed contract

Mote U1: `bd-01M50K95JAQB34R876FWMV85J0`; tag `reality-check-20261015`.
Assessed implementation: `286b523432bac3f7ab7f0026c363ef23198cd637`.
Status: proposal for independent review and the owner's authority decision. This document
does not amend the accepted ADR or grant admission. U2 waits for that decision.

## Outcome and decision

A researcher can take unchanged, authorized study files through the installed offline CLI,
paired and weighted exchange, independent readers, and the pinned Atlas local workspace.
Admission records the researcher's actual authority claim and its scope. It does not assert
that the material is public, detector-certified, consented, or covered by an REB decision.

Choose the trust model before implementation:

1. **Researcher declaration (recommended for an offline single-researcher tool).** The local
   operator explicitly declares responsibility for the named study and exact local exports.
   The trusted fact is that declaration by this operator; software does not verify their
   institutional entitlement. A separate explicit CLI action/API argument supplies the
   declaration; the study JSON and imported receipts cannot silently supply it.
2. **Approved issuer.** A locally installed policy identifies the issuers allowed to authorize
   a study. A grant is accepted only after issuer authentication against that independent
   policy. Neither the study file nor the grant chooses its own trusted issuer or key.
   Issuer provisioning and revocation become necessary product requirements under this choice.

Both choices require the bindings and refusal behavior below. Do not describe choice 1 as
verified consent or silently implement choice 2 with a caller-supplied issuer string.

## Existing route and rejected alternative

[`RecallDisclosure.certified`](../../pipeline/src/main/scala/storymodel4s/pipeline/RecallDisclosure.scala)
retains a genuine `PseudonymizedText`, derived from its payload. The checked factory in
[`privacy.scala`](../../embed-core/src/main/scala/storymodel4s/embed/privacy.scala)
requires at least one independently detected source span, a matching transformation map,
and no detected destination spans. Exposing that route helps transformed inputs but cannot
admit an unchanged text with no detector findings. Dummy replacements, empty witnesses and
renaming researcher authority as pseudonymization are rejected alternatives.

`LocalOnly` already permits working analysis. The current publication predicate compares
exact text and accepts any basis other than `LocalOnly`; it lacks study, observation,
destination and operation context. Merely adding a new non-local basis would therefore
grant context-free publication and is rejected. Contextual authorization must be checked at
the actual publication entry point; an old context-free overload must refuse the new basis.

## Binding and lifetime

The proposed checked admission associates a declaration/grant with:

- the study's explicit identifier and the prepared source edition/representation digests;
- the complete allowed observation set: participant, session, observation identity and exact
  prepared recall-text digest, with input-byte digests retained separately where decoding
  transforms bytes into text;
- explicit canonical local destination roots, including a separate source-cache root when
  requested; destination resolution must reject symlink escapes and existing-path substitution;
- a closed set of operations: paired exchange, weighted exchange, temporal exchange,
  organization exchange, workspace packets and source-cache writes, only as requested;
- the declaration/issuer-policy version, authorization identifier and revocation/lifetime
  semantics appropriate to the selected trust model.

Current study configuration has no study identifier. Supply that identity in a separate
explicit authorization context so ordinary input files can remain unchanged. Authorize the
complete requested participant/session inventory, including failure metadata. If preparation
fails before a recall digest exists, only the explicitly covered attempt metadata may be
exported; it must not inherit permission to emit unknown text. Such rows stay in the batch
manifest with their failure and unavailable digest, rather than disappearing from the study.

No implicit permission propagates to a sibling study, new recall, new destination, upload,
remote embedding/parser call, repository admission, or public-corpus classification. Source
content exported to the workspace/cache must also be covered by the declaration; recall
authority alone does not establish source redistribution rights.

Operation names describe complete payloads: weighted and temporal exports include their base
paired artifacts, so their explicit grants must cover that composition. A paired grant alone
cannot authorize weighted or temporal export. Workspace publication always includes its source
cache write and therefore requires both named destination roots; it refuses before either write
when either authorization is missing. Do not grant unlisted operations by invoking an internal
base helper.

A live checked value is created only after these relations are verified against the actual
prepared batch and publication request. Stable relational validity uses a final non-case
class with a private checked factory, per rule 8. The identity is derived from the bound
content and policy, not accepted from a caller's label. A receipt serializes the verified
facts and digests for audit, never a reusable authorization capability. Decoding an artifact
requires a fresh supplied authorization for the current request. Resume revalidates authority
and destination before checking existing files; a matching old manifest alone is insufficient.

## Publication seams

| Entry point | Required change in U2 |
| --- | --- |
| `StudyPreparation.publishRecall` | Receive actual study/observation/operation/destination context; retain exact text check. |
| `MappingPipeline.exchangeArtifacts`, `publishExchange`, `exportResults` | Validate the whole prepared batch before returning exchange contents or writing any output. Keep failed attempts in the manifest. |
| `WeightedMapping.artifacts` / `publish` | Propagate the checked context to base exchange and bind requested weighted export; no implicit grant through the base overload. |
| `MappingPipeline.exportTemporalResults` | Apply the same admission to the temporal exchange path. |
| `OrganizationPipeline.publish` | Check the same operation and observations before reference/reconstruction text can be emitted. |
| `StudyWorkspace.artifacts` / `publish` | Check packets and source-cache destinations before either can be created; bind both roots explicitly. |
| Standalone `MappingPipeline.publishExchange(run)` and `OrganizationPipeline.publish(value)` | These values have no retained study/disclosure context. Classify the actual payload explicitly as ordinary analysis or admitted exchange and test that classification. Any text-bearing export must require checked context or refuse. |
| `StudyArtifacts.publish` and workspace cache writes | Enforce the verified root at actual file creation/reuse, including intermediate directories and completion markers. A higher-level pathname check alone cannot establish the binding if paths can change before use. |
| CLI configuration/preparation and cache decode | Treat stored admission/receipt fields as requests/data; require fresh authority from the selected trust boundary. |

The existing `StudyCli` prepared working artifacts and ordinary mapping analysis keep their
current local-analysis semantics. U2 must trace and document those writes so the new exchange
admission is not misrepresented as a filesystem sandbox. Direct API exports of a checked run
must be reviewed for text-bearing contents as well as the batch path; an unreviewed overload
cannot become an authorization bypass.
Current standalone mapping exchange serializes coordinates, identities and numeric evidence,
including transcript digests/spans; this review has not demonstrated a raw-transcript leak there.
Batch exchange additionally serializes prepared recall JSON containing graph text. Preserve that
distinction instead of assuming every standalone run export has the batch's disclosure behavior.

## Acceptance examples fixed before implementation

| Witness | Required observable result |
| --- | --- |
| Unfamiliar unchanged synthetic study, explicit valid local declaration/grant | Paired and three-weight exports pass independent readers; the pinned Atlas consumer opens every eligible observation. No pseudonymization label appears. |
| Same request without authority | Working local analysis remains available; admitted exchange/workspace export refuses before creating output/cache paths. |
| Change study/source/participant/session/recall bytes after authorization | Each changed coordinate independently refuses, including reuse of a cached prepared recall. |
| Change destination root or replace an intermediate path with a symlink | Refuse before any text-bearing write outside the authorized root. |
| Missing/wrong issuer or altered grant under issuer mode | Refuse against independently provisioned policy. Under declaration mode, require the actual explicit operator action instead of pretending to authenticate an issuer. |
| Import a genuine receipt, edit its scope, or place it in study JSON | No authority is reconstructed; attempts to gain hosted/public/certified scope refuse. |
| Resume valid output after authority removal/revocation/change | Refuse even if existing output bytes and manifest match. |
| Resume with the same still-valid authority and exact bytes | Succeed without rewriting; retain the same observations and receipt. |
| Request an extra operation/cache destination | Refuse; permission for paired export does not grant workspace/cache writes. |
| Mixed successful and failed preparation attempts | Preserve every authorized attempt, including metadata-only failures without a text digest; refuse unauthorized attempt metadata and never emit unavailable text. |
| Genuine detector-certified payload, admitted Sherlock replay, existing project control | Preserve their exact existing lineage and allowed local routes; retain their refusal controls. |

Use admitted synthetic fixtures for all courts. No participant text enters Git. Compile-refusal
probes for `apply`, `copy`, `fromProduct` and `Mirror.ProductOf` run outside the defining privacy
scope with separate positive controls. Compiling mutations must kill the named study, text,
destination, operation and resume checks while unrelated controls pass. The cold review must
trace every text-bearing export, including prepared-cache reuse, before U2 is accepted.

## Approval and handoff

The owner chooses the trust model and approves the resulting ADR 0022 amendment under SD5.
Independent U1 review checks this contract against that choice before U2 begins. U2 binds its
implementation and producer/consumer gates to exact commits. U3 is a separate independent
ordinary-file journey, with no hidden setup or reclassification of familiar project controls.
