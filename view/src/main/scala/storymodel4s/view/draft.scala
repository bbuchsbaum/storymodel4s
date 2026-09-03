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
  OpenReference,
  SentenceCoverage
}
import storymodel4s.story.{
  ModelStatus,
  Severity,
  StoryModel,
  StoryRef,
  ValidationOutcome,
  Violation
}

/** One promotion law a draft did not satisfy, with how many times it failed.
  *
  * Why a count rather than the violations themselves: this travels inside every scene's
  * reproducibility receipt, and a receipt carrying a hundred and thirty-five sentences of prose
  * stops being read. The violations stay on their own marks, where a reader can click through to
  * the words. `law` is the model's own identifier (`story.Violation.law`), copied and never
  * re-coined here.
  */
final case class UnsatisfiedLaw(law: String, severity: Severity, count: PositiveInt)

/** What a draft's own compilation reported about itself, or the fact that nobody supplied it.
  *
  * Why this is a type and not two empty vectors: **a derivation gap is a statement about the
  * derivation, not about the story, so `StoryModel` does not record one.** The gaps and the
  * coverage ledger are written to the sibling `compilation-report.json`, and a consumer holding
  * only a decoded `storymodel.json` has neither. Without this distinction such a consumer compiles
  * a scene whose receipt reads "0 derivation gaps" — which says the compiler derived everything,
  * when the truth is that nobody told the view anything. Those two states must never share a
  * fingerprint.
  */
enum DerivationRecord:
  /** The compilation's own record: the derivations it could not make, and its coverage ledger. */
  case Reported(gaps: Vector[DerivationGap], coverage: Vector[SentenceCoverage])

  /** No record accompanied the model, so this scene can say nothing about derivation at all. */
  case NotSupplied

  /** `Some(n)` when a record was supplied and reported `n` gaps; `None` when none was. */
  def gapCount: Option[Int] = this match
    case Reported(gaps, _) => Some(gaps.size)
    case NotSupplied       => None

  def render: String = gapCount.fold("not supplied")(count => s"$count gaps")

/** The exact promotion state of a draft model, derived from its own validation outcome.
  *
  * Why unforgeable rather than a product: `promoted`, `unsatisfiedLaws` and `gapCount` stand in a
  * derived relation to one [[ValidationOutcome]] and one [[DerivationRecord]], so most combinations
  * of individually lawful field values are false. A caller who could state them independently could
  * mint a receipt reading "promoted, no unsatisfied laws" over a model with a hundred and
  * thirty-five of them, and that receipt would survive every audit downstream. An identity is
  * derived from what it describes, never asserted by the caller.
  *
  * `gapCount` is deliberately an `Option`, and `None` is not `Some(0)`: see [[DerivationRecord]].
  */
final class DraftPromotion private (
    val promoted: Boolean,
    val unsatisfiedLaws: Vector[UnsatisfiedLaw],
    val gapCount: Option[Int]
):
  /** Total violations behind [[unsatisfiedLaws]], counting each occurrence once. */
  def violationCount: Int = unsatisfiedLaws.map(_.count.value).sum

  /** One line naming the promotion state, the laws left unsatisfied and the derivation record. */
  def label: String =
    val promotion = if promoted then "promotable" else "not promotable"
    val derivation =
      gapCount.fold("derivation record not supplied")(count => s"$count derivation gaps")
    s"$promotion; ${unsatisfiedLaws.size} unsatisfied laws " +
      s"($violationCount violations); $derivation"

  override def equals(other: Any): Boolean = other match
    case that: DraftPromotion =>
      promoted == that.promoted && unsatisfiedLaws == that.unsatisfiedLaws &&
      gapCount == that.gapCount
    case _ => false

  override def hashCode(): Int = (promoted, unsatisfiedLaws, gapCount).hashCode()

  override def toString: String = s"DraftPromotion(${label})"

object DraftPromotion:
  /** Derive the promotion state from the validator's own outcome and the compilation's own record.
    */
  def from(outcome: ValidationOutcome, derivation: DerivationRecord): DraftPromotion =
    val laws = outcome.report.violations
      .groupBy(violation => (violation.law, violation.severity))
      .toVector
      .flatMap { case ((law, severity), violations) =>
        PositiveInt.from(violations.size).toOption.map(UnsatisfiedLaw(law, severity, _))
      }
      .sortBy(entry => (entry.law, entry.severity.toString))
    new DraftPromotion(outcome.validated.isDefined, laws, derivation.gapCount)

/** A draft story model bound to the exact evidence of its own incompleteness.
  *
  * Why the model and its derivation record travel together rather than one carrying the other: a
  * gap is a fact about how the model was built, and a model that recorded claims about its own
  * construction would be the wrong shape. The compilation writes them to a sibling artifact, and
  * the view is entitled to consume both — which also means it must be able to say when it received
  * only one, hence [[DerivationRecord.NotSupplied]].
  *
  * Construction sorts every vector so that a compiled scene is a pure function of its inputs (ADR
  * 0002 V-D1) whatever order a caller assembled them in, and derives [[promotion]] rather than
  * accepting it.
  */
final class DraftModel private (
    val model: StoryModel[ModelStatus.Draft],
    val promotion: DraftPromotion,
    val derivation: DerivationRecord,
    val violations: Vector[Violation]
):
  /** The derivations the compilation could not make; empty when it supplied no record. */
  def gaps: Vector[DerivationGap] = derivation match
    case DerivationRecord.Reported(gaps, _) => gaps
    case DerivationRecord.NotSupplied       => Vector.empty

  /** The provider's coverage ledger; empty when the compilation supplied no record. */
  def coverage: Vector[SentenceCoverage] = derivation match
    case DerivationRecord.Reported(_, coverage) => coverage
    case DerivationRecord.NotSupplied           => Vector.empty

  /** Every coverage row that admitted no situation root, with the row's own typed reason. */
  def abstentions: Vector[(SurfaceUnitId, SentenceAbstention)] =
    coverage.flatMap(row => SentenceAbstention.from(row).map(row.sentence -> _))

  /** Every absence this draft carries, in one order, each separated from its own restatements.
    *
    * The order is the order the three producers wrote them in: the compilation's gaps, then its
    * coverage abstentions, then the validator's violations. It is stable because [[DraftModel.of]]
    * sorts all three at construction.
    */
  def absences: Vector[DraftAbsence] = DraftAbsence.enumerate(this)

  override def equals(other: Any): Boolean = other match
    case that: DraftModel =>
      model == that.model && promotion == that.promotion && derivation == that.derivation &&
      violations == that.violations
    case _ => false

  override def hashCode(): Int =
    (model, promotion, derivation, violations).hashCode()

  override def toString: String =
    s"DraftModel(${model.source.id.value}, ${promotion.label}, " +
      s"derivation=${derivation.render})"

object DraftModel:
  /** Bind a draft to its validation outcome and to whatever derivation record accompanied it. */
  def of(
      model: StoryModel[ModelStatus.Draft],
      outcome: ValidationOutcome,
      derivation: DerivationRecord
  ): DraftModel =
    val sorted = derivation match
      case DerivationRecord.NotSupplied              => DerivationRecord.NotSupplied
      case DerivationRecord.Reported(gaps, coverage) =>
        DerivationRecord.Reported(
          gaps.sortBy(gap => (gap.family.toString, gap.target.render, gap.reason.render)),
          coverage.sortBy(row => row.sentence.value)
        )
    new DraftModel(
      model,
      DraftPromotion.from(outcome, sorted),
      sorted,
      outcome.report.violations.sortBy(v => (v.law, v.severity.toString, v.path, v.reason))
    )

  /** Bind a draft that arrived on its own — a decoded `storymodel.json` with no sibling report.
    *
    * The scene is then honest about a smaller thing: it draws the promotion laws the model itself
    * violates and states that it was told nothing about derivation.
    */
  def withoutDerivationRecord(
      model: StoryModel[ModelStatus.Draft],
      outcome: ValidationOutcome
  ): DraftModel = of(model, outcome, DerivationRecord.NotSupplied)

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
    // An open pronoun with several candidates is a split reading; with none, resolution ran and
    // reached nothing.
    case DerivationGapReason.OpenReference(OpenReference.SeveralAntecedents) => Alternatives
    case DerivationGapReason.OpenReference(_)                                => Unresolved

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
    case NarrativeCandidateAddress.EntityReference(mention)        => Vector(mention)

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

/** What one of a draft's own absences says, in the exact record its producer wrote.
  *
  * Why three cases and not one "unknown": a claim family the narrative compiler nominated and could
  * not derive, a sentence the provider refused, and a promotion law the validator found unsatisfied
  * come from three different stages and are actionable in three different ways. A reader who is
  * told only that *something* failed at these words learns nothing they can act on, and the draft
  * path exists precisely so that they can.
  *
  * Every case carries its producer's record whole and restates none of it, so a mark can never say
  * more about a failure than the stage that recorded it did.
  */
enum AbsenceContent:
  /** A claim family nominated at a chart node that the compiler could not derive. */
  case UnresolvedFamily(gap: DerivationGap)

  /** A sentence that admitted no situation root, with the coverage row's own typed reason. */
  case AbstainedSentence(unit: SurfaceUnitId, reason: SentenceAbstention)

  /** A promotion law this model does not satisfy, exactly as the validator stated it. */
  case UnsatisfiedLaw(violation: Violation)

/** One absence a draft carries, separated from an identical restatement of itself.
  *
  * Why `occurrence` is part of the identity: a validator may state one violation twice, and in the
  * reading view two absences that agree on target, support, kind and content would content-address
  * to a single [[AnnotationId]] and be coalesced into one annotation. A reading view showing one
  * mark where the model recorded two absences reports less than the model knows, which is the same
  * defect as reporting more, pointed the other way.
  */
final case class DraftAbsence private (content: AbsenceContent, occurrence: Int):
  /** The reading-view channel this absence is drawn in; one kind per case, injectively. */
  def kind: AnnotationKind = content match
    case AbsenceContent.UnresolvedFamily(_)     => AnnotationKind.Gap
    case AbsenceContent.AbstainedSentence(_, _) => AnnotationKind.Abstention
    case AbsenceContent.UnsatisfiedLaw(_)       => AnnotationKind.UnsatisfiedLaw

  /** The D9 state this absence is in, or `None` for a law, which is a structural defect of the
    * build rather than uncertainty about a value (the same distinction [[EpistemicChannel]] draws).
    */
  def uncertainty: Option[UncertaintyState] = content match
    case AbsenceContent.UnresolvedFamily(gap)   => Some(UncertaintyState.of(gap.reason))
    case AbsenceContent.AbstainedSentence(_, _) => Some(UncertaintyState.Missing)
    case AbsenceContent.UnsatisfiedLaw(_)       => None

  /** The non-colour channel this absence is drawn in (ADR 0002 D9, V-U5). Total, never a colour. */
  def channel: EpistemicChannel = uncertainty.fold(EpistemicChannel.Bracket)(_.channel)

  /** The address the absence is about, resolved identically to the Atlas's own anchor. */
  def subject(story: StoryId): Address = content match
    case AbsenceContent.UnresolvedFamily(gap)      => GapTarget.address(gap.target, story)
    case AbsenceContent.AbstainedSentence(unit, _) =>
      Addressable[CoreRef].address(CoreRef.SurfaceUnit(unit))
    case AbsenceContent.UnsatisfiedLaw(violation) =>
      violation.address.getOrElse(Addressable[CoreRef].address(CoreRef.Story(story)))

  /** Claims and evidence the producer named as the absence's own upstream; empty when it named
    * none. A law and an abstention name none: their subject is already the annotation's target.
    */
  def upstream: Vector[Address] = content match
    case AbsenceContent.UnresolvedFamily(gap) =>
      val coreRef = Addressable[CoreRef]
      gap.upstreamClaims.toVector.sorted.map(id => coreRef.address(CoreRef.Claim(id))) ++
        gap.evidence.map(evidence => coreRef.address(CoreRef.Evidence(evidence.evidenceId)))
    case AbsenceContent.AbstainedSentence(_, _) => Vector.empty
    case AbsenceContent.UnsatisfiedLaw(_)       => Vector.empty

  /** A content key that separates two absences sharing one subject, support and channel. */
  def key: String = content match
    case AbsenceContent.UnresolvedFamily(gap) =>
      s"unresolved-family|${gap.stage.value}|${GapTarget.familyName(gap.family)}|" +
        s"${gap.target.render}|${gap.reason.render}|$occurrence"
    case AbsenceContent.AbstainedSentence(unit, reason) =>
      s"abstained-sentence|${unit.value}|${reason.render}|$occurrence"
    case AbsenceContent.UnsatisfiedLaw(violation) =>
      s"unsatisfied-law|${violation.law}|${violation.severity}|${violation.path}|" +
        s"${violation.reason}|${violation.address.fold("-")(_.render)}|$occurrence"

  /** One deterministic line naming the failure, for the textual twin. */
  def render: String = content match
    case AbsenceContent.UnresolvedFamily(gap) =>
      s"unresolved-family family=${GapTarget.familyName(gap.family)} stage=${gap.stage.value} " +
        s"target=${gap.target.render} reason=${gap.reason.render} occurrence=$occurrence"
    case AbsenceContent.AbstainedSentence(unit, reason) =>
      s"abstained-sentence unit=${unit.value} reason=${reason.render} occurrence=$occurrence"
    case AbsenceContent.UnsatisfiedLaw(violation) =>
      s"unsatisfied-law law=${violation.law} severity=${violation.severity} " +
        s"path=${violation.path} reason=${violation.reason} occurrence=$occurrence"

object DraftAbsence:
  /** Every absence a draft carries, in producer order, with occurrence indices assigned. */
  def enumerate(draft: DraftModel): Vector[DraftAbsence] =
    val contents =
      draft.gaps.map(AbsenceContent.UnresolvedFamily.apply) ++
        draft.abstentions.map((unit, reason) => AbsenceContent.AbstainedSentence(unit, reason)) ++
        draft.violations.map(AbsenceContent.UnsatisfiedLaw.apply)
    val seen = scala.collection.mutable.Map.empty[AbsenceContent, Int]
    contents.map { content =>
      val occurrence = seen.getOrElse(content, 0)
      seen.update(content, occurrence + 1)
      new DraftAbsence(content, occurrence)
    }

/** Where one absence sits on the surface material it concerns, decided once for every projection.
  *
  * Why one object rather than a rule per compiler: the Atlas and the Codex place the same absences
  * over the same words, and two implementations of "which words does this failure concern" would
  * eventually disagree, at which point one of the two pictures would be lying about the other's
  * subject. The rule lives here and both compilers call it.
  */
private[view] object AbsencePlacement:
  /** Exact spans of the surface units an absence names, or the typed reason it has no position. */
  def onUnits(
      model: StoryModel[?],
      units: Vector[SurfaceUnitId],
      clip: SpanSet => Option[SpanSet]
  ): EpistemicPlacement =
    val distinct = units.distinct.sorted
    if distinct.isEmpty then EpistemicPlacement.NoDiscoursePosition(NoPositionReason.WholeWork)
    else
      distinct.find(unit => model.atlas.byId.get(unit).isEmpty) match
        case Some(absent) =>
          EpistemicPlacement.NoDiscoursePosition(NoPositionReason.UnitAbsentFromAtlas(absent))
        case None =>
          val refs =
            distinct.flatMap(unit =>
              model.atlas.byId.get(unit).map(u => SpanRef(Some(u.id), u.span))
            )
          SpanSet
            .of(refs)
            .flatMap(clip)
            .fold(EpistemicPlacement.NoDiscoursePosition(NoPositionReason.BeyondHorizon))(
              EpistemicPlacement.AtSpans.apply
            )

  /** A law claims its subject's own cited words, or none at all; never a guessed position. */
  def forLaw(
      model: StoryModel[?],
      violation: Violation,
      clip: SpanSet => Option[SpanSet]
  ): EpistemicPlacement = violation.address match
    case None    => EpistemicPlacement.NoDiscoursePosition(NoPositionReason.WholeWork)
    case Some(a) =>
      Addressable[StoryRef].parse(a).flatMap(model.supporting) match
        case None =>
          EpistemicPlacement.NoDiscoursePosition(NoPositionReason.SubjectCitesNoSpans(a))
        case Some(support) =>
          clip(support).fold(
            EpistemicPlacement.NoDiscoursePosition(NoPositionReason.BeyondHorizon)
          )(EpistemicPlacement.AtSpans.apply)

  /** The placement of any absence, dispatched on its own typed content. */
  def of(
      model: StoryModel[?],
      absence: DraftAbsence,
      clip: SpanSet => Option[SpanSet]
  ): EpistemicPlacement = absence.content match
    case AbsenceContent.UnresolvedFamily(gap) =>
      onUnits(model, GapTarget.chartNodes(gap.target).map(_.sentence), clip)
    case AbsenceContent.AbstainedSentence(unit, _) => onUnits(model, Vector(unit), clip)
    case AbsenceContent.UnsatisfiedLaw(violation)  => forLaw(model, violation, clip)

/** One absence a draft reading view could not put on any words, with the reason it could not.
  *
  * Why this exists at all: an Atlas mark may carry [[EpistemicPlacement.NoDiscoursePosition]] and
  * still be drawn, because a scene has room for a mark that claims no text. A [[TextAnnotation]]
  * cannot: its support is a nonempty [[SpanSet]] by construction, since an annotation over no words
  * is not an annotation. Dropping those absences would make the reading view quietly smaller than
  * the model, so they are kept here instead, out of the flowing text and in the audit surface.
  */
final case class UnplacedAbsence(
    absence: DraftAbsence,
    subject: Address,
    reason: NoPositionReason
)

/** The complete disclosure a draft reading view makes about its own incompleteness.
  *
  * `marked` and `unplaced` partition every absence the draft carries, and the Codex compiler checks
  * that partition against [[DraftModel.absences]] before a flow is built. That check is the
  * mechanised form of "absence is annotated, not omitted": a compiler that silently dropped an
  * absence produces a ledger that does not add up and no flow at all.
  */
final case class DraftAbsenceLedger private[view] (
    marked: Vector[AnnotationId],
    unplaced: Vector[UnplacedAbsence]
):
  /** How many absences this flow accounts for, placed and unplaced together. */
  def total: Int = marked.size + unplaced.size
