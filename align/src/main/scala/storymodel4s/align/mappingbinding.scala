package storymodel4s.align

import storymodel4s.core.*
import storymodel4s.features.{CanonicalDouble, Estimate}
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Exact input and result identity. This does not certify that an estimator executed. */
final class DerivationBinding private (
    val recallChecksum: Checksum,
    val recallSupplement: Checksum,
    val inventoryDigest: Checksum,
    val viewFingerprint: ViewFingerprint,
    val scopeDigest: Checksum,
    val resultDigest: Checksum
):
  def digest: Checksum = MappingRender.digest(
    Vector(
      "mapping-binding/v1",
      recallChecksum.hex,
      recallSupplement.hex,
      inventoryDigest.hex,
      viewFingerprint.checksum.hex,
      scopeDigest.hex,
      resultDigest.hex
    )
  )
  override def equals(other: Any): Boolean = other match
    case that: DerivationBinding => digest == that.digest
    case _                       => false
  override def hashCode: Int = digest.hashCode

  private[align] def checkResult(result: HsmmResult): Either[MappingRefusal, Unit] =
    if result.recallChecksum != recallChecksum then
      Left(MappingRefusal.BindingMismatch("recallChecksum"))
    else if result.viewFingerprint != viewFingerprint then
      Left(MappingRefusal.BindingMismatch("viewFingerprint"))
    else if MappingBindingRender.result(result) != resultDigest then
      Left(MappingRefusal.BindingMismatch("resultDigest"))
    else Right(())

  private[align] def checkContext(
      recall: RecallGraph[Checked],
      view: SourceView,
      source: SourceRepresentation
  ): Either[MappingRefusal, Unit] =
    if AlignWire.recallChecksum(recall) != recallChecksum then
      Left(MappingRefusal.BindingMismatch("recallChecksum"))
    else if MappingBindingRender.recall(recall) != recallSupplement then
      Left(MappingRefusal.BindingMismatch("recallSupplement"))
    else if ViewFingerprint.of(view) != viewFingerprint || source.viewFingerprint != viewFingerprint
    then Left(MappingRefusal.BindingMismatch("viewFingerprint"))
    else if MappingSourceRender.scope(
        view.nodes
      ) != scopeDigest || source.scopeDigest != scopeDigest
    then Left(MappingRefusal.BindingMismatch("scopeDigest"))
    else Right(())

object DerivationBinding:
  def of(
      result: HsmmResult,
      recall: RecallGraph[Checked],
      inventory: RecallInventory,
      view: SourceView,
      source: SourceRepresentation
  ): Either[MappingRefusal, DerivationBinding] =
    val checkedView = MappingBindingRender.snapshot(view)
    if result.recallChecksum != AlignWire.recallChecksum(recall) then
      Left(MappingRefusal.BindingMismatch("recallChecksum"))
    else if !inventory.describes(recall) then
      Left(MappingRefusal.BindingMismatch("inventoryDigest"))
    else if result.viewFingerprint != ViewFingerprint.of(
        checkedView
      ) || source.viewFingerprint != result.viewFingerprint
    then Left(MappingRefusal.BindingMismatch("viewFingerprint"))
    else if MappingSourceRender.scope(checkedView.nodes) != source.scopeDigest then
      Left(MappingRefusal.BindingMismatch("scopeDigest"))
    else
      Right(
        new DerivationBinding(
          result.recallChecksum,
          MappingBindingRender.recall(recall),
          inventory.digest,
          source.viewFingerprint,
          source.scopeDigest,
          MappingBindingRender.result(result)
        )
      )

/** Supplied values never acquire derived authority merely by sharing a numeric shape. */
sealed trait MeasureDerivation
object MeasureDerivation:
  case object Supplied extends MeasureDerivation
  final class FromResult private (val binding: DerivationBinding, val unit: RecallUnitId)
      extends MeasureDerivation
  object FromResult:
    private[MeasureDerivation] def checked(
        binding: DerivationBinding,
        unit: RecallUnitId
    ): FromResult = new FromResult(binding, unit)
  def fromResult(
      result: HsmmResult,
      unit: RecallUnitId,
      binding: DerivationBinding
  ): Either[MappingRefusal, FromResult] =
    binding.checkResult(result).flatMap { _ =>
      if !result.posterior.rows.exists(_.unit == unit) then Left(MappingRefusal.UnknownUnit(unit))
      else Right(FromResult.checked(binding, unit))
    }

/** New identities preserve every fidelity input and every result field consumed by G1. Legacy
  * fingerprints remain mandatory match fields, but are not the sole binding.
  */
private[align] object MappingBindingRender:
  import MappingRender.{sequence, optional}

  /** Capture each exposed coordinate once; later validation and assessment read this value. */
  def snapshot(view: SourceView): SourceView =
    val capturedNodes = view.nodes
    val capturedAdjacency =
      RelationLayer.values.toVector.map(layer => layer -> view.adjacency(layer)).toMap
    val capturedWorldOrder = view.worldOrder
    val capturedScoringLength = view.scoringLength
    new SourceView:
      val nodes: Vector[NodeSummary] = capturedNodes
      private val index = nodes.map(n => n.ref -> n).toMap
      def node(ref: SourceNodeRef): Option[NodeSummary] = index.get(ref)
      def adjacency(layer: RelationLayer): Map[SourceNodeRef, Map[SourceNodeRef, Double]] =
        capturedAdjacency(layer)
      val worldOrder: Option[Map[SourceNodeRef, Int]] = capturedWorldOrder
      val scoringLength: Int = capturedScoringLength

  private def number(v: Double): String = CanonicalDouble.render(v)
  private def state(v: AlignState): String = sequence(AlignState.keyParts(v))
  private def weighted(values: Map[AlignState, Double]): String = sequence(
    values.toVector
      .map((s, v) => state(s) -> number(v))
      .sortBy(_._1)
      .map((k, v) => sequence(Vector(k, v)))
  )
  def recall(value: RecallGraph[Checked]): Checksum = MappingRender.digest(
    Vector(
      "mapping-recall-supplement/v1",
      value.transcript.canonicalText,
      sequence(value.ordered.map { u =>
        val p = u.proposition
        sequence(
          Vector(
            u.id.value,
            optional(p.predicate),
            optional(p.outcome),
            optional(p.cause),
            sequence(
              p.participants.map(s =>
                sequence(
                  Vector(
                    s.role.toString,
                    s.label,
                    s.specified.toString,
                    sequence(s.aliases.toVector.sorted)
                  )
                )
              )
            ),
            sequence(p.locations),
            p.polarity.toString,
            p.modality.toString
          )
        )
      })
    )
  )
  private def basis(value: CellSupportBasis): String = sequence(
    Vector(
      sequence(value.measuredTerms.toVector.map(_.toString)),
      sequence(value.eligibleTerms.toVector.map(_.toString)),
      sequence(
        value.eligibleWeights.toVector.map((term, weight) =>
          sequence(Vector(term.toString, number(weight)))
        )
      )
    )
  )
  def support(value: SupportAssessment): String = value match
    case s: SupportAssessment.Assessed =>
      sequence(Vector("assessed", number(s.share), basis(s.basis)))
    case s: SupportAssessment.Unestablished =>
      sequence(Vector("unestablished", s.reason.toString, basis(s.basis)))
    case s: SupportAssessment.NotApplicable => sequence(Vector("not-applicable", s.reason.toString))
  private def coverage(value: StructuralCoverage): String = sequence(
    Vector(value.level.toString, value.membersWithEvidence.toString, value.members.toString)
  )
  private def credence(value: Credence): String = sequence(
    Vector(
      value.score match
        case Score.Unmeasured     => "unmeasured"
        case Score.Raw(v, scorer) => sequence(Vector("raw", number(v), scorer.value))
      ,
      value.basis match
        case CredenceBasis.Uncalibrated         => "uncalibrated"
        case CredenceBasis.Calibrated(p, model) =>
          sequence(Vector("calibrated", number(p.value), model.value))
        case CredenceBasis.Determined(rule) => sequence(Vector("determined", rule.value))
    )
  )
  private def estimate(value: Estimate[Double]): String = value match
    case Estimate.Observed(v, c) =>
      sequence(Vector("observed", number(v), optional(c.map(credence))))
    case Estimate.Missing(reason) =>
      sequence(Vector("missing", MappingSourceRender.missing(reason)))
  private def reduction(value: StructuralReductionReceipt): String = sequence(
    Vector(
      value.reducer.toString,
      sequence(value.members.map(m => sequence(Vector(m.member.key, estimate(m.estimate))))),
      sequence(
        value.excludedMembers.map(m =>
          sequence(
            Vector(
              m.member.key,
              sequence(m.contradictions.toVector.sortBy(_.ordinal).map(_.toString))
            )
          )
        )
      ),
      coverage(value.sourceChartCoverage),
      value.observedEstimateCoverage.eligible.toString,
      value.observedEstimateCoverage.observed.toString
    )
  )
  private def cost(value: CostBreakdown): String = sequence(
    Vector(
      number(value.total),
      optional(value.mode.map(_.render)),
      optional(value.exclusion.map(_.toString)),
      sequence(
        value.terms.toVector
          .sortBy(_._1.ordinal)
          .map((t, v) => sequence(Vector(t.toString, number(v))))
      ),
      sequence(value.missingTerms.toVector.sortBy(_.ordinal).map(_.toString)),
      optional(value.sourceChartCoverage.map(coverage)),
      sequence(
        value.reductions.toVector
          .sortBy(_._1.ordinal)
          .map((t, r) => sequence(Vector(t.toString, reduction(r))))
      ),
      support(value.support),
      sequence(
        value.imputedTerms.toVector
          .sortBy(_._1.ordinal)
          .map((t, reason) => sequence(Vector(t.toString, MappingSourceRender.missing(reason))))
      )
    )
  )
  def result(value: HsmmResult): Checksum = MappingRender.digest(
    Vector(
      "mapping-hsmm-result/v1",
      value.recallChecksum.hex,
      value.viewFingerprint.checksum.hex,
      sequence(
        value.posterior.rows.map(row => sequence(Vector(row.unit.value, weighted(row.mass))))
      ),
      sequence(
        value.flow.steps.map(step =>
          sequence(
            Vector(
              step.from.value,
              step.to.value,
              sequence(
                step.mass.toVector
                  .map { case ((a, b), mass) =>
                    sequence(Vector(state(a), state(b))) -> number(mass)
                  }
                  .sortBy(_._1)
                  .map((key, v) => sequence(Vector(key, v)))
              )
            )
          )
        )
      ),
      sequence(value.viterbi.map(state)),
      number(value.logLikelihood),
      value.refinementPasses.toString,
      sequence(
        value.costs.toVector
          .sortBy(_._1.value)
          .map((unit, costs) =>
            sequence(
              Vector(
                unit.value,
                sequence(
                  costs.toVector
                    .map((s, c) => state(s) -> cost(c))
                    .sortBy(_._1)
                    .map((key, c) => sequence(Vector(key, c)))
                )
              )
            )
          )
      ),
      sequence(
        value.candidateAnchors.toVector
          .sortBy(_._1.value)
          .map((unit, refs) => sequence(Vector(unit.value, sequence(refs.map(_.key)))))
      ),
      sequence(
        value.admissibility.toVector
          .sortBy(_._1.value)
          .map((unit, rows) =>
            sequence(
              Vector(
                unit.value,
                sequence(
                  rows.toVector
                    .sortBy(_._1.key)
                    .map((ref, a) =>
                      sequence(
                        Vector(
                          ref.key,
                          a.faithful.toString,
                          sequence(a.contradictions.map(_.toString)),
                          optional(
                            a.distortion
                              .map(fs => sequence(fs.toSortedSet.toVector.map(_.toString)))
                          )
                        )
                      )
                    )
                )
              )
            )
          )
      ),
      sequence(
        value.sourceSupport.toVector
          .sortBy(_._1.key)
          .map((ref, s) => sequence(Vector(ref.key, MappingSourceRender.support(s))))
      ),
      value.textWireCompatible.toString
    )
  )
