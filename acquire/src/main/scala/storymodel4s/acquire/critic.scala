package storymodel4s.acquire

import cats.Order

/** Independent critic specialisations. Each critic receives the candidate and exact evidence, not
  * another agent's reasoning.
  */
enum CriticFamily:
  case SyntaxGraphLaw
  case FrameRole
  case SourceEntailment
  case PolarityModalityContext
  case DocumentIdentity
  case TemporalConsistency
  case CausalOverreach
  case HierarchyCoherence

/** How much a finding weighs on resolution. `Blocking` findings prevent acceptance. */
enum Severity:
  case Info, Warning, Blocking

object Severity:
  given Order[Severity] = Order.by(_.ordinal)
  given Ordering[Severity] = Order[Severity].toOrdering

/** Closed vocabulary of typed evidence codes a critic may emit (design record §86, §95).
  *
  * Why closed: findings must be aggregable, benchmarkable, and translatable into review cards
  * without parsing prose.
  */
enum FindingCode(val severity: Severity):
  // syntax / graph laws
  case MalformedStructure extends FindingCode(Severity.Blocking)
  case DanglingReference extends FindingCode(Severity.Blocking)
  case DuplicateTriple extends FindingCode(Severity.Warning)
  // frame / role
  case UnknownFrame extends FindingCode(Severity.Warning)
  case ArgumentNotLicensed extends FindingCode(Severity.Warning)
  case WrongParticipant extends FindingCode(Severity.Blocking)
  case RoleReversalSuspected extends FindingCode(Severity.Blocking)
  // source entailment
  case MissingConcept extends FindingCode(Severity.Warning)
  case HallucinatedConcept extends FindingCode(Severity.Blocking)
  case SpanInadequate extends FindingCode(Severity.Warning)
  case SpanMismatch extends FindingCode(Severity.Blocking)
  // polarity / modality / context
  case ReportTreatedAsFact extends FindingCode(Severity.Blocking)
  case PolarityMisattached extends FindingCode(Severity.Blocking)
  case EmbeddingMisplaced extends FindingCode(Severity.Blocking)
  case ModalityMisattached extends FindingCode(Severity.Warning)
  case IntendedTreatedAsRealized extends FindingCode(Severity.Blocking)
  // document identity
  case DuplicateOccurrence extends FindingCode(Severity.Blocking)
  case OverMerge extends FindingCode(Severity.Blocking)
  case UnderMerge extends FindingCode(Severity.Warning)
  // temporal
  case TemporalCycle extends FindingCode(Severity.Blocking)
  case OrderContradiction extends FindingCode(Severity.Warning)
  // causal
  case UnsupportedCause extends FindingCode(Severity.Blocking)
  case PrecedenceAsCause extends FindingCode(Severity.Blocking)
  // hierarchy
  case EmptySegment extends FindingCode(Severity.Warning)
  case IncoherentBoundary extends FindingCode(Severity.Info)
  // generic
  case AlternativeAnalysis extends FindingCode(Severity.Info)
  case PrefersSourceSupportedCandidate extends FindingCode(Severity.Info)
  case PrefersFoil extends FindingCode(Severity.Blocking)

  def isBlocking: Boolean = severity == Severity.Blocking

/** What a finding is about. */
enum TargetKind:
  case Node, Edge, Claim, Sentence, Token, Chart

final case class TargetRef(kind: TargetKind, id: String)

/** A critic's typed observation. Findings are data: they never mutate a candidate, and the resolver
  * decides what they mean.
  */
final case class CriticFinding(
    taskId: TaskId,
    family: CriticFamily,
    code: FindingCode,
    targets: Vector[TargetRef],
    evidence: Vector[EvidenceRef],
    rawScore: Option[RawScore],
    note: Option[String]
):
  def severity: Severity = code.severity
  def isBlocking: Boolean = code.isBlocking

object CriticFinding:
  def blocking(findings: Iterable[CriticFinding]): Vector[CriticFinding] =
    findings.toVector.filter(_.isBlocking)
  def maxSeverity(findings: Iterable[CriticFinding]): Option[Severity] =
    findings.map(_.severity).maxOption(using Severity.given_Ordering_Severity)
