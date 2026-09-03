package storymodel4s.view

import storymodel4s.acquire.ClaimFamily
import storymodel4s.core.*
import storymodel4s.document.{
  AbstentionReason,
  ChartNodeRef,
  CoordinatedBranch,
  DerivationGap,
  DerivationGapReason,
  DocRef,
  NarrativeCandidateAddress,
  SentenceCoverage
}
import storymodel4s.story.{ModelStatus, Severity, StoryModel, ValidationOutcome, Violation}

/** One promotion law a draft did not satisfy, with how many times it failed.
  *
  * Why a count rather than the violations themselves: this travels inside every scene's
  * reproducibility receipt, and a receipt carrying a hundred and thirty-five sentences of prose
  * stops being read. The violations stay on their own marks, where a reader can click through to
  * the words. `law` is the model's own identifier (`story.Violation.law`), copied and never
  * re-coined here.
  */
final case class UnsatisfiedLaw(law: String, severity: Severity, count: PositiveInt)

/** The exact promotion state of a draft model, derived from its own validation outcome.
  *
  * Why unforgeable rather than a product: `promoted`, `unsatisfiedLaws` and `gapCount` stand in a
  * derived relation to one [[ValidationOutcome]] and one gap vector, so most combinations of
  * individually lawful field values are false. A caller who could state them independently could
  * mint a receipt reading "promoted, no unsatisfied laws" over a model with a hundred and
  * thirty-five of them, and that receipt would survive every audit downstream. An identity is
  * derived from what it describes, never asserted by the caller.
  */
final class DraftPromotion private (
    val promoted: Boolean,
    val unsatisfiedLaws: Vector[UnsatisfiedLaw],
    val gapCount: Int
):
  /** Total violations behind [[unsatisfiedLaws]], counting each occurrence once. */
  def violationCount: Int = unsatisfiedLaws.map(_.count.value).sum

  /** One line naming the promotion state, the laws left unsatisfied and the derivation gaps. */
  def label: String =
    val promotion = if promoted then "promotable" else "not promotable"
    s"$promotion; ${unsatisfiedLaws.size} unsatisfied laws " +
      s"($violationCount violations); $gapCount derivation gaps"

  override def equals(other: Any): Boolean = other match
    case that: DraftPromotion =>
      promoted == that.promoted && unsatisfiedLaws == that.unsatisfiedLaws &&
      gapCount == that.gapCount
    case _ => false

  override def hashCode(): Int = (promoted, unsatisfiedLaws, gapCount).hashCode()

  override def toString: String = s"DraftPromotion(${label})"

object DraftPromotion:
  /** Derive the promotion state from the validator's own outcome and the compiler's own gaps. */
  def from(outcome: ValidationOutcome, gaps: Vector[DerivationGap]): DraftPromotion =
    val laws = outcome.report.violations
      .groupBy(violation => (violation.law, violation.severity))
      .toVector
      .flatMap { case ((law, severity), violations) =>
        PositiveInt.from(violations.size).toOption.map(UnsatisfiedLaw(law, severity, _))
      }
      .sortBy(entry => (entry.law, entry.severity.toString))
    new DraftPromotion(outcome.validated.isDefined, laws, gaps.size)

/** A draft story model bound to the exact evidence of its own incompleteness.
  *
  * Why the three inputs travel together: a draft is legible as partial only when the reader can see
  * what the compiler could not derive (`gaps`), what promotion still requires (`violations`), and
  * which sentences produced nothing at all (`coverage`). Any one of them alone reports a different,
  * smaller absence than the model actually has. Construction sorts every vector so that a compiled
  * scene is a pure function of its inputs (ADR 0002 V-D1) whatever order a caller assembled them
  * in, and derives [[promotion]] rather than accepting it.
  */
final class DraftModel private (
    val model: StoryModel[ModelStatus.Draft],
    val promotion: DraftPromotion,
    val gaps: Vector[DerivationGap],
    val violations: Vector[Violation],
    val coverage: Vector[SentenceCoverage]
):
  /** Every coverage row that admitted no situation root, with the row's own typed reason. */
  def abstentions: Vector[(SurfaceUnitId, SentenceAbstention)] =
    coverage.flatMap(row => SentenceAbstention.from(row).map(row.sentence -> _))

  override def equals(other: Any): Boolean = other match
    case that: DraftModel =>
      model == that.model && promotion == that.promotion && gaps == that.gaps &&
      violations == that.violations && coverage == that.coverage
    case _ => false

  override def hashCode(): Int =
    (model, promotion, gaps, violations, coverage).hashCode()

  override def toString: String =
    s"DraftModel(${model.source.id.value}, ${promotion.label}, " +
      s"coverage=${coverage.size} rows)"

object DraftModel:
  /** Bind a draft to its validation outcome, its derivation gaps and its provider coverage ledger.
    */
  def of(
      model: StoryModel[ModelStatus.Draft],
      outcome: ValidationOutcome,
      gaps: Vector[DerivationGap],
      coverage: Vector[SentenceCoverage]
  ): DraftModel =
    new DraftModel(
      model,
      DraftPromotion.from(outcome, gaps),
      gaps.sortBy(gap => (gap.family.toString, gap.target.render, gap.reason.render)),
      outcome.report.violations.sortBy(v => (v.law, v.severity.toString, v.path, v.reason)),
      coverage.sortBy(row => row.sentence.value)
    )

/** Why a sentence yielded no situation root, derived from the provider's own coverage row.
  *
  * Why four cases and not one "abstained": a focus the provider refused, a coordinating focus whose
  * every branch was refused, a chart with no concepts, and a sentence with no chart at all are four
  * different states of the world, and folding them together would hide the ones a caller can act on
  * behind the ones they cannot.
  */
enum SentenceAbstention:
  /** The chart had a focus and the provider refused it for a stated reason. */
  case ProviderAbstained(reason: AbstentionReason)

  /** A coordinating focus admitted no branch; each branch's own refusal is kept. */
  case CoordinationAdmittedNothing(reasons: Vector[AbstentionReason])

  /** The parser produced a chart with no concepts in it. */
  case EmptyChart

  /** The sentence produced no chart at all. */
  case NoChart

  def render: String = this match
    case ProviderAbstained(reason)       => s"provider-abstained:${reason.render}"
    case CoordinationAdmittedNothing(rs) =>
      s"coordination-admitted-nothing:${rs.map(_.render).sorted.mkString(",")}"
    case EmptyChart => "empty-chart"
    case NoChart    => "no-chart"

object SentenceAbstention:
  /** `None` when the row admitted at least one root; the sentence is then not an abstention. */
  def from(row: SentenceCoverage): Option[SentenceAbstention] =
    if row.admittedRoots.nonEmpty then None
    else
      row match
        case SentenceCoverage.Proposed(_, _)           => None
        case SentenceCoverage.Abstained(_, reason)     => Some(ProviderAbstained(reason))
        case SentenceCoverage.Coordinated(_, branches) =>
          Some(
            CoordinationAdmittedNothing(
              branches.collect { case CoordinatedBranch.Abstained(_, _, reason) => reason }
            )
          )
        case SentenceCoverage.EmptyChart(_) => Some(EmptyChart)
        case SentenceCoverage.NoChart(_)    => Some(NoChart)

/** The uncertainty states of ADR 0002 D9 that a compiled mark can carry today.
  *
  * D9 names five. Three are reachable from a draft compilation and are implemented here. The other
  * two — a raw [[storymodel4s.core.Credence]] and a calibrated probability — are properties of a
  * mark that carries a *value*, and no mark carries one until the D11 feature layer materializes
  * numeric sidecars. Minting their vocabulary now would repeat the defect D14a records: an axis
  * that is receipted but semantically inert looks exactly like a shipped feature. They arrive with
  * the marks that can bear them.
  */
enum UncertaintyState:
  /** D9 (1): there is no value here, and the mark carries why. */
  case Missing

  /** D9 (4): several readings survived and none was selected. */
  case Alternatives

  /** D9 (5): resolution was attempted and reached no reading at all. */
  case Unresolved

  /** The non-colour channel this state is drawn in; injective, so no two states share one. */
  def channel: EpistemicChannel = this match
    case Missing      => EpistemicChannel.OpenHatch
    case Alternatives => EpistemicChannel.Fan
    case Unresolved   => EpistemicChannel.Placeholder

object UncertaintyState:
  /** Classify one derivation gap by its own reason; the reason itself stays on the mark. */
  def of(reason: DerivationGapReason): UncertaintyState = reason match
    case DerivationGapReason.Alternatives             => Alternatives
    case DerivationGapReason.Unresolved(_)            => Unresolved
    case DerivationGapReason.Rejected(_)              => Missing
    case DerivationGapReason.MissingRawScore          => Missing
    case DerivationGapReason.MissingSpanEvidence      => Missing
    case DerivationGapReason.MissingUpstream(_)       => Missing
    case DerivationGapReason.UnscopableRelation(_, _) => Missing
    case DerivationGapReason.InvalidAccepted(_)       => Missing

/** A non-colour visual channel carrying an epistemic distinction (ADR 0002 D9, V-U5).
  *
  * Why the channel is a type and not a renderer's choice: D9 requires each epistemic state to be
  * distinguishable without colour, and a requirement a renderer may satisfy differently in each
  * backend is a requirement nothing can fail. Here the assignment is total and injective and a
  * court kills any collision.
  */
enum EpistemicChannel:
  /** An open hatch: material is absent and the absence is the mark. */
  case OpenHatch

  /** A fan: several readings, drawn as a split rather than one averaged reading. */
  case Fan

  /** An explicit placeholder: resolution ran and reached nothing. */
  case Placeholder

  /** A bracket: a promotion law this model does not satisfy. Not a D9 state — a structural defect
    * of the build rather than uncertainty about a value — so it has its own channel.
    */
  case Bracket

/** Why an epistemic mark has no position on the discourse axis.
  *
  * Why four reasons: an absence about the work as a whole, a candidate naming a surface unit the
  * atlas does not contain, a law whose subject cites no words, and material the reader horizon has
  * not reached are four different facts. A single "unplaced" would make a defect in the atlas
  * indistinguishable from an honest global claim.
  */
enum NoPositionReason:
  /** The absence is about the whole work: a story summary, a global structural law. */
  case WholeWork

  /** The candidate names a surface unit the model's atlas does not contain. */
  case UnitAbsentFromAtlas(unit: SurfaceUnitId)

  /** The law's subject is a narrative object whose claim cites no spans. */
  case SubjectCitesNoSpans(subject: Address)

  /** Every span the mark would carry ends after the active reader horizon. */
  case BeyondHorizon

  def render: String = this match
    case WholeWork                  => "whole-work"
    case UnitAbsentFromAtlas(unit)  => s"unit-absent-from-atlas:${unit.value}"
    case SubjectCitesNoSpans(where) => s"subject-cites-no-spans:${where.render}"
    case BeyondHorizon              => "beyond-horizon"

/** Where an epistemic mark sits on the discourse axis.
  *
  * Why no lane: the vertical axis of the Discourse Atlas is the context lane, and an unresolved
  * context assignment is precisely a candidate whose context is not established. Placing it in lane
  * zero would draw it in the narrated world, which is the claim the gap says the model could not
  * make.
  */
enum EpistemicPlacement:
  /** Exact spans of the surface material the absence concerns; never a hull, never a lane. */
  case AtSpans(spans: SpanSet)

  /** No honest discourse position exists, with the reason recorded. */
  case NoDiscoursePosition(reason: NoPositionReason)

  /** Exact spans when the mark has any; empty otherwise. */
  def spanSet: Option[SpanSet] = this match
    case AtSpans(spans)         => Some(spans)
    case NoDiscoursePosition(_) => None

  def render: String = this match
    case AtSpans(spans) =>
      spans.refs.toVector
        .map(ref => s"[${ref.span.start},${ref.span.endExclusive})")
        .mkString(",")
    case NoDiscoursePosition(reason) => s"unplaced:${reason.render}"

/** How a derivation gap's typed target is resolved to an address and to exact surface material.
  *
  * Why a named object and not a method on the mark: a gap's candidate address belongs to
  * `document`, which has no view of the Atlas, while the address a mark carries must come from the
  * closed view seam ([[ViewRef]]). This is the one place that translation happens, so a court can
  * pin it and a reader can find it.
  */
private[view] object GapTarget:
  /** Every chart node the candidate names, in the order the candidate names them. */
  def chartNodes(target: NarrativeCandidateAddress): Vector[ChartNodeRef] = target match
    case NarrativeCandidateAddress.Situation(source)               => Vector(source)
    case NarrativeCandidateAddress.ContextAssignment(source)       => Vector(source)
    case NarrativeCandidateAddress.StorySummary(_)                 => Vector.empty
    case NarrativeCandidateAddress.SegmentMembership(_, member)    => Vector(member)
    case NarrativeCandidateAddress.Causal(from, to)                => Vector(from, to)
    case NarrativeCandidateAddress.TrajectoryStep(from, to)        => Vector(from, to)
    case NarrativeCandidateAddress.EntityMention(mention)          => Vector(mention)
    case NarrativeCandidateAddress.Participant(situation, filler)  => Vector(situation, filler)
    case NarrativeCandidateAddress.ParticipantCoverage(situation)  => Vector(situation)
    case NarrativeCandidateAddress.Circumstance(situation, filler) => Vector(situation, filler)
    case NarrativeCandidateAddress.Temporal(from, to)              => Vector(from, to)

  /** The address the gap is about: its anchoring chart node, or the work when it names none.
    *
    * The anchor is the first node the candidate names — the node the derivation was attempted at.
    * Nothing is lost by the choice: the mark carries the whole typed target beside the address.
    */
  def address(target: NarrativeCandidateAddress, story: StoryId): Address =
    chartNodes(target).headOption match
      case Some(node) => Addressable[DocRef].address(DocRef.ChartNode(node))
      case None       => Addressable[CoreRef].address(CoreRef.Story(story))

  /** A content key that separates two gaps sharing one anchor, so mark identity stays injective. */
  def markKey(gap: DerivationGap): String =
    s"${gap.stage.value}|${familyName(gap.family)}|${gap.target.render}|${gap.reason.render}"

  /** The closed family vocabulary rendered without relying on a `toString` nobody pinned. */
  def familyName(family: ClaimFamily): String = family match
    case ClaimFamily.Custom(namespace, name) => s"custom:$namespace:$name"
    case other                               => other.toString
