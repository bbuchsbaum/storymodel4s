package storymodel4s.codec

import cats.data.NonEmptySet
import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import io.circe.syntax.*
import scala.collection.immutable.SortedSet
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.features.{Coverage, Estimate, MissingReason}
import storymodel4s.recall.{RecallGraph, RecallUnitId}
import storymodel4s.recall.RecallGraphStatus.Checked
import CanonicalPrimitives.{*, given}
import CoreCodecs.given
import FeatureCodecs.given
import RecallCodecs.given

/** Failures of contextual HSMM decoding, preserving whether JSON or alignment proof validation
  * refused the artifact.
  */
enum HsmmCodecError:
  case Wire(error: CodecError)
  case Rejected(error: AlignError)

  def message: String = this match
    case Wire(error)     => error.message
    case Rejected(error) => error.message

/** Canonical JSON for a gated [[HsmmResult]].
  *
  * Decoding is deliberately contextual: a result can be reconstructed only against the
  * [[RecallGraph]] and [[SourceView]] whose fingerprints the wire artifact records. Sparse maps are
  * represented as sorted arrays of typed entries, so composite alignment states never become
  * stringly JSON keys and duplicate entries are rejected before construction.
  */
object HsmmResultCodec:
  /** Version of the complete HSMM wire object, independent of the package-wide JSON version.
    *
    * v2 adds a REQUIRED `supportWeight` to every cost breakdown. It is required in both directions,
    * so a v1 decoder cannot read a v2 record and a v1 record cannot satisfy the v2 decoder. There
    * is deliberately NO MIGRATION STEP: a v1 artifact does not carry the support its costs rest on,
    * and inventing a value for it - 1.0, the only candidate - would assert full support for cells
    * whose support was never computed. That is the defect this version exists to record. A v1
    * artifact is not upgradable; it is re-derivable from its inputs, which is what makes the
    * fingerprint chain meaningful.
    *
    * v3 adds a REQUIRED `imputedTerms` to every cost breakdown: the terms that were PRICED from a
    * declared constant rather than measured, with the provider's reason. Same no-migration
    * reasoning, and for the same shape of defect. A v2 artifact records that a cell had, say,
    * 0.9552 support without recording that the highest-weighted term in it was substituted; the
    * only value a migration could invent is "nothing was imputed", which is precisely the false
    * claim THE CHECKED WIRE REFUSES. Deliberately not "makes unrepresentable": CostBreakdown is a
    * case class with a private[align] constructor, so its derived Mirror.fromProduct rebuilds it
    * outside align and bypasses every check here. The invariant is enforced by AlignWire, not by
    * the type, and the difference matters to anyone reading this as a guarantee
    * (bd-01M17ZNXY6AS1CMBQJRH3JMNVX). Note what v2 could not distinguish: two cells competing for
    * the SAME ranked unit, one with a measured semantic distance and one with an abstained
    * provider, were byte-identical in every published field.
    */
  val SchemaVersion: String = "hsmm/v3"

  private final case class StateMassWire(state: AlignState, mass: Double)
  private final case class RowWire(unit: RecallUnitId, mass: Vector[StateMassWire])
  private final case class TransitionMassWire(
      fromState: AlignState,
      toState: AlignState,
      mass: Double
  )
  private final case class FlowStepWire(
      from: RecallUnitId,
      to: RecallUnitId,
      mass: Vector[TransitionMassWire]
  )
  private final case class TermWire(term: CostTerm, value: Double)

  /** A term that was PRICED from a declared constant rather than measured, with the provider's
    * reason. Carried separately from `missingTerms`, which means "absent and contributed nothing":
    * an imputed term is present in `terms` and contributes its full weight to `total`.
    */
  private final case class ImputedWire(term: CostTerm, reason: MissingReason)
  private final case class MemberEstimateWire(member: SourceNodeRef, estimate: Estimate[Double])
  private final case class MemberExclusionWire(
      member: SourceNodeRef,
      contradictions: Vector[Contradiction]
  )
  private final case class ReductionReceiptWire(
      reducer: StructuralReducer,
      members: Vector[MemberEstimateWire],
      excludedMembers: Vector[MemberExclusionWire],
      sourceChartCoverage: StructuralCoverage,
      observedEstimateCoverage: Coverage
  )
  private final case class ReductionWire(
      term: CostTerm,
      receipt: ReductionReceiptWire
  )
  private final case class CostBreakdownWire(
      terms: Vector[TermWire],
      mode: Option[FidelityMode],
      exclusion: Option[Exclusion],
      total: Double,
      missingTerms: Vector[CostTerm],
      sourceChartCoverage: Option[StructuralCoverage],
      reductions: Vector[ReductionWire],
      supportWeight: Double,
      imputedTerms: Vector[ImputedWire]
  )
  private final case class StateCostWire(state: AlignState, cost: CostBreakdownWire)
  private final case class UnitCostsWire(unit: RecallUnitId, costs: Vector[StateCostWire])
  private final case class CandidateAnchorsWire(
      unit: RecallUnitId,
      anchors: Vector[SourceNodeRef]
  )
  private final case class Wire(
      posterior: Vector[RowWire],
      flow: Vector[FlowStepWire],
      viterbi: Vector[AlignState],
      logLikelihood: Double,
      costs: Vector[UnitCostsWire],
      candidateAnchors: Vector[CandidateAnchorsWire],
      admissibilityEcho: Checksum,
      viewFingerprint: Checksum,
      recallChecksum: Checksum,
      refinementPasses: Int
  )

  /** Encode a proved result as canonical JSON text. */
  def encode(result: HsmmResult): String = Canonical.print(toJson(result))

  /** Encode a proved result as a canonical JSON value. */
  def toJson(result: HsmmResult): Json = summon[Encoder[Wire]].apply(Wire.from(result))

  /** Decode canonical JSON text only after revalidating it against `recall` and `view`. */
  def decode(
      text: String,
      recall: RecallGraph[Checked],
      view: SourceView
  ): Either[HsmmCodecError, HsmmResult] =
    Canonical
      .decode[Wire](text)
      .left
      .map(HsmmCodecError.Wire.apply)
      .flatMap(_.materialize(recall, view).left.map(HsmmCodecError.Rejected.apply))

  /** Decode a JSON value only after revalidating it against `recall` and `view`. */
  def decodeJson(
      json: Json,
      recall: RecallGraph[Checked],
      view: SourceView
  ): Either[HsmmCodecError, HsmmResult] =
    Canonical
      .decodeJson[Wire](json)
      .left
      .map(HsmmCodecError.Wire.apply)
      .flatMap(_.materialize(recall, view).left.map(HsmmCodecError.Rejected.apply))

  /** Encoder-only instance: reconstructing the proof always requires explicit context. */
  given Encoder[HsmmResult] = Encoder.instance(toJson)

  private given Encoder[SourceNodeRef] = Encoder.instance {
    case SourceNodeRef.Situation(id) =>
      Json.obj("type" -> "Situation".asJson, "id" -> id.asJson)
    case SourceNodeRef.Segment(id) =>
      Json.obj("type" -> "Segment".asJson, "id" -> id.asJson)
  }
  private given Decoder[SourceNodeRef] = Decoder.instance { c =>
    field[String](c, "type").flatMap {
      case "Situation" => field[SituationId](c, "id").map(SourceNodeRef.Situation.apply)
      case "Segment"   => field[SegmentId](c, "id").map(SourceNodeRef.Segment.apply)
      case other       => Left(DecodingFailure(s"unknown SourceNodeRef $other", c.history))
    }
  }

  private given Encoder[Facet] = enumEncoder(_.toString)
  private given Decoder[Facet] = enumDecoder("Facet", Facet.values, _.toString)

  private given Encoder[FidelityMode] = Encoder.instance {
    case FidelityMode.Faithful          => "Faithful".asJson
    case FidelityMode.Distorted(facets) =>
      Json.obj(
        "type" -> "Distorted".asJson,
        "facets" -> facets.toSortedSet.toVector.asJson
      )
  }
  private given Decoder[FidelityMode] = Decoder.instance { c =>
    c.value.asString match
      case Some("Faithful") => Right(FidelityMode.Faithful)
      case Some(other)      =>
        Left(DecodingFailure(s"unknown FidelityMode $other", c.history))
      case None =>
        for
          tag <- field[String](c, "type")
          _ <- Either.cond(
            tag == "Distorted",
            (),
            DecodingFailure(s"unknown FidelityMode $tag", c.history)
          )
          values <- field[Vector[Facet]](c, "facets")
          _ <- Either.cond(
            values.distinct.size == values.size,
            (),
            DecodingFailure("Distorted facets contain a duplicate", c.history)
          )
          facets <- NonEmptySet
            .fromSet(SortedSet.from(values))
            .toRight(DecodingFailure("Distorted requires at least one facet", c.history))
        yield FidelityMode.Distorted(facets)
  }

  private given Encoder[ExternalState] = enumEncoder(_.toString)
  private given Decoder[ExternalState] =
    enumDecoder("ExternalState", ExternalState.values, _.toString)

  private given Encoder[AlignState] = Encoder.instance {
    case AlignState.Source(ref) =>
      Json.obj("type" -> "Source".asJson, "ref" -> ref.asJson)
    case AlignState.Distorted(ref, facets) =>
      Json.obj(
        "type" -> "Distorted".asJson,
        "ref" -> ref.asJson,
        "facets" -> facets.toSortedSet.toVector.asJson
      )
    case AlignState.External(state) =>
      Json.obj("type" -> "External".asJson, "state" -> state.asJson)
  }
  private given Decoder[AlignState] = Decoder.instance { c =>
    field[String](c, "type").flatMap {
      case "Source"    => field[SourceNodeRef](c, "ref").map(AlignState.Source.apply)
      case "Distorted" =>
        for
          ref <- field[SourceNodeRef](c, "ref")
          values <- field[Vector[Facet]](c, "facets")
          _ <- Either.cond(
            values.distinct.size == values.size,
            (),
            DecodingFailure("Distorted facets contain a duplicate", c.history)
          )
          facets <- NonEmptySet
            .fromSet(SortedSet.from(values))
            .toRight(DecodingFailure("Distorted requires at least one facet", c.history))
        yield AlignState.Distorted(ref, facets)
      case "External" => field[ExternalState](c, "state").map(AlignState.External.apply)
      case other      => Left(DecodingFailure(s"unknown AlignState $other", c.history))
    }
  }

  private given Encoder[CostTerm] = enumEncoder(_.toString)
  private given Decoder[CostTerm] = enumDecoder("CostTerm", CostTerm.values, _.toString)
  private given Encoder[Contradiction] = enumEncoder(_.toString)
  private given Decoder[Contradiction] =
    enumDecoder("Contradiction", Contradiction.values, _.toString)
  private given Encoder[Exclusion] = enumEncoder(_.toString)
  private given Decoder[Exclusion] = enumDecoder("Exclusion", Exclusion.values, _.toString)
  private given Encoder[StructuralReducer] = enumEncoder(_.toString)
  private given Decoder[StructuralReducer] =
    enumDecoder("StructuralReducer", StructuralReducer.values, _.toString)

  private given Encoder[StructuralCoverage] = Encoder.instance { coverage =>
    Json.obj(
      "level" -> coverage.level.asJson,
      "membersWithEvidence" -> coverage.membersWithEvidence.asJson,
      "members" -> coverage.members.asJson
    )
  }
  private given Decoder[StructuralCoverage] = Decoder.instance { c =>
    for
      level <- field[Int](c, "level")
      withEvidence <- field[Int](c, "membersWithEvidence")
      members <- field[Int](c, "members")
    yield StructuralCoverage(level, withEvidence, members)
  }

  private given Encoder[StateMassWire] = Encoder.instance { entry =>
    Json.obj("state" -> entry.state.asJson, "mass" -> entry.mass.asJson)
  }
  private given Decoder[StateMassWire] = Decoder.instance { c =>
    for
      state <- field[AlignState](c, "state")
      mass <- field[Double](c, "mass")
    yield StateMassWire(state, mass)
  }

  private given Encoder[RowWire] = Encoder.instance { row =>
    Json.obj("unit" -> row.unit.asJson, "mass" -> row.mass.asJson)
  }
  private given Decoder[RowWire] = Decoder.instance { c =>
    for
      unit <- field[RecallUnitId](c, "unit")
      mass <- field[Vector[StateMassWire]](c, "mass")
    yield RowWire(unit, mass)
  }

  private given Encoder[TransitionMassWire] = Encoder.instance { entry =>
    Json.obj(
      "fromState" -> entry.fromState.asJson,
      "toState" -> entry.toState.asJson,
      "mass" -> entry.mass.asJson
    )
  }
  private given Decoder[TransitionMassWire] = Decoder.instance { c =>
    for
      from <- field[AlignState](c, "fromState")
      to <- field[AlignState](c, "toState")
      mass <- field[Double](c, "mass")
    yield TransitionMassWire(from, to, mass)
  }

  private given Encoder[FlowStepWire] = Encoder.instance { step =>
    Json.obj("from" -> step.from.asJson, "to" -> step.to.asJson, "mass" -> step.mass.asJson)
  }
  private given Decoder[FlowStepWire] = Decoder.instance { c =>
    for
      from <- field[RecallUnitId](c, "from")
      to <- field[RecallUnitId](c, "to")
      mass <- field[Vector[TransitionMassWire]](c, "mass")
    yield FlowStepWire(from, to, mass)
  }

  private given Encoder[TermWire] = Encoder.instance { term =>
    Json.obj("term" -> term.term.asJson, "value" -> term.value.asJson)
  }
  private given Decoder[TermWire] = Decoder.instance { c =>
    for
      term <- field[CostTerm](c, "term")
      value <- field[Double](c, "value")
    yield TermWire(term, value)
  }

  private given Encoder[ImputedWire] = Encoder.instance { imputed =>
    Json.obj("term" -> imputed.term.asJson, "reason" -> imputed.reason.asJson)
  }
  private given Decoder[ImputedWire] = Decoder.instance { c =>
    for
      term <- field[CostTerm](c, "term")
      reason <- field[MissingReason](c, "reason")
    yield ImputedWire(term, reason)
  }

  private given Encoder[MemberEstimateWire] = Encoder.instance { member =>
    Json.obj("member" -> member.member.asJson, "estimate" -> member.estimate.asJson)
  }
  private given Decoder[MemberEstimateWire] = Decoder.instance { c =>
    for
      member <- field[SourceNodeRef](c, "member")
      estimate <- field[Estimate[Double]](c, "estimate")
    yield MemberEstimateWire(member, estimate)
  }

  private given Encoder[MemberExclusionWire] = Encoder.instance { member =>
    Json.obj(
      "member" -> member.member.asJson,
      "contradictions" -> member.contradictions.asJson
    )
  }
  private given Decoder[MemberExclusionWire] = Decoder.instance { c =>
    for
      member <- field[SourceNodeRef](c, "member")
      contradictions <- field[Vector[Contradiction]](c, "contradictions")
    yield MemberExclusionWire(member, contradictions)
  }

  private given Encoder[ReductionReceiptWire] = Encoder.instance { receipt =>
    Json.obj(
      "reducer" -> receipt.reducer.asJson,
      "members" -> receipt.members.asJson,
      "excludedMembers" -> receipt.excludedMembers.asJson,
      "sourceChartCoverage" -> receipt.sourceChartCoverage.asJson,
      "observedEstimateCoverage" -> receipt.observedEstimateCoverage.asJson
    )
  }
  private given Decoder[ReductionReceiptWire] = Decoder.instance { c =>
    for
      reducer <- field[StructuralReducer](c, "reducer")
      members <- field[Vector[MemberEstimateWire]](c, "members")
      excluded <- field[Vector[MemberExclusionWire]](c, "excludedMembers")
      sourceCoverage <- field[StructuralCoverage](c, "sourceChartCoverage")
      observedCoverage <- field[Coverage](c, "observedEstimateCoverage")
    yield ReductionReceiptWire(reducer, members, excluded, sourceCoverage, observedCoverage)
  }

  private given Encoder[ReductionWire] = Encoder.instance { reduction =>
    Json.obj("term" -> reduction.term.asJson, "receipt" -> reduction.receipt.asJson)
  }
  private given Decoder[ReductionWire] = Decoder.instance { c =>
    for
      term <- field[CostTerm](c, "term")
      receipt <- field[ReductionReceiptWire](c, "receipt")
    yield ReductionWire(term, receipt)
  }

  private given Encoder[CostBreakdownWire] = Encoder.instance { cost =>
    obj(
      "terms" -> cost.terms.asJson,
      "mode" -> opt(cost.mode),
      "exclusion" -> opt(cost.exclusion),
      "total" -> cost.total.asJson,
      "missingTerms" -> cost.missingTerms.asJson,
      "sourceChartCoverage" -> opt(cost.sourceChartCoverage),
      "reductions" -> cost.reductions.asJson,
      "supportWeight" -> cost.supportWeight.asJson,
      "imputedTerms" -> cost.imputedTerms.asJson
    )
  }
  private given Decoder[CostBreakdownWire] = Decoder.instance { c =>
    for
      terms <- field[Vector[TermWire]](c, "terms")
      mode <- field[Option[FidelityMode]](c, "mode")
      exclusion <- field[Option[Exclusion]](c, "exclusion")
      total <- field[Double](c, "total")
      missing <- field[Vector[CostTerm]](c, "missingTerms")
      sourceCoverage <- field[Option[StructuralCoverage]](c, "sourceChartCoverage")
      reductions <- field[Vector[ReductionWire]](c, "reductions")
      supportWeight <- field[Double](c, "supportWeight")
      imputed <- field[Vector[ImputedWire]](c, "imputedTerms")
    yield CostBreakdownWire(
      terms,
      mode,
      exclusion,
      total,
      missing,
      sourceCoverage,
      reductions,
      supportWeight,
      imputed
    )
  }

  private given Encoder[StateCostWire] = Encoder.instance { entry =>
    Json.obj("state" -> entry.state.asJson, "cost" -> entry.cost.asJson)
  }
  private given Decoder[StateCostWire] = Decoder.instance { c =>
    for
      state <- field[AlignState](c, "state")
      cost <- field[CostBreakdownWire](c, "cost")
    yield StateCostWire(state, cost)
  }

  private given Encoder[UnitCostsWire] = Encoder.instance { costs =>
    Json.obj("unit" -> costs.unit.asJson, "costs" -> costs.costs.asJson)
  }
  private given Decoder[UnitCostsWire] = Decoder.instance { c =>
    for
      unit <- field[RecallUnitId](c, "unit")
      costs <- field[Vector[StateCostWire]](c, "costs")
    yield UnitCostsWire(unit, costs)
  }

  private given Encoder[CandidateAnchorsWire] = Encoder.instance { candidates =>
    Json.obj("unit" -> candidates.unit.asJson, "anchors" -> candidates.anchors.asJson)
  }
  private given Decoder[CandidateAnchorsWire] = Decoder.instance { c =>
    for
      unit <- field[RecallUnitId](c, "unit")
      anchors <- field[Vector[SourceNodeRef]](c, "anchors")
    yield CandidateAnchorsWire(unit, anchors)
  }

  private given Encoder[Wire] = Encoder.instance { wire =>
    Json.obj(
      "schemaVersion" -> SchemaVersion.asJson,
      "posterior" -> wire.posterior.asJson,
      "flow" -> wire.flow.asJson,
      "viterbi" -> wire.viterbi.asJson,
      "logLikelihood" -> wire.logLikelihood.asJson,
      "costs" -> wire.costs.asJson,
      "candidateAnchors" -> wire.candidateAnchors.asJson,
      "admissibilityEcho" -> wire.admissibilityEcho.asJson,
      "viewFingerprint" -> wire.viewFingerprint.asJson,
      "recallChecksum" -> wire.recallChecksum.asJson,
      "refinementPasses" -> wire.refinementPasses.asJson
    )
  }
  private given Decoder[Wire] = Decoder.instance { c =>
    for
      version <- field[String](c, "schemaVersion")
      _ <- Either.cond(
        version == SchemaVersion,
        (),
        DecodingFailure(s"unsupported HSMM schema $version; expected $SchemaVersion", c.history)
      )
      posterior <- field[Vector[RowWire]](c, "posterior")
      flow <- field[Vector[FlowStepWire]](c, "flow")
      viterbi <- field[Vector[AlignState]](c, "viterbi")
      logLikelihood <- field[Double](c, "logLikelihood")
      costs <- field[Vector[UnitCostsWire]](c, "costs")
      candidates <- field[Vector[CandidateAnchorsWire]](c, "candidateAnchors")
      echo <- field[Checksum](c, "admissibilityEcho")
      viewFingerprint <- field[Checksum](c, "viewFingerprint")
      recallChecksum <- field[Checksum](c, "recallChecksum")
      passes <- field[Int](c, "refinementPasses")
    yield Wire(
      posterior,
      flow,
      viterbi,
      logLikelihood,
      costs,
      candidates,
      echo,
      viewFingerprint,
      recallChecksum,
      passes
    )
  }

  private object Wire:
    def from(result: HsmmResult): Wire =
      Wire(
        result.posterior.rows.map { row =>
          RowWire(
            row.unit,
            row.mass.toVector.sortBy(_._1.key).map(StateMassWire.apply)
          )
        },
        result.flow.steps.map { step =>
          FlowStepWire(
            step.from,
            step.to,
            step.mass.toVector
              .sortBy { case ((from, to), _) => (from.key, to.key) }
              .map { case ((from, to), mass) => TransitionMassWire(from, to, mass) }
          )
        },
        result.viterbi,
        result.logLikelihood,
        result.costs.toVector.sortBy(_._1.value).map { case (unit, costs) =>
          UnitCostsWire(
            unit,
            costs.toVector.sortBy(_._1.key).map { case (state, cost) =>
              StateCostWire(state, CostBreakdownWire.from(cost))
            }
          )
        },
        result.candidateAnchors.toVector.sortBy(_._1.value).map { case (unit, anchors) =>
          CandidateAnchorsWire(unit, anchors)
        },
        result.admissibilityEcho.checksum,
        result.viewFingerprint.checksum,
        result.recallChecksum,
        result.refinementPasses
      )

  private object CostBreakdownWire:
    def from(cost: CostBreakdown): CostBreakdownWire =
      CostBreakdownWire(
        cost.terms.toVector.sortBy(_._1.ordinal).map(TermWire.apply),
        cost.mode,
        cost.exclusion,
        cost.total,
        cost.missingTerms.toVector.sortBy(_.ordinal),
        cost.sourceChartCoverage,
        cost.reductions.toVector.sortBy(_._1.ordinal).map { case (term, receipt) =>
          ReductionWire(term, ReductionReceiptWire.from(receipt))
        },
        cost.supportWeight,
        cost.imputedTerms.toVector.sortBy(_._1.ordinal).map(ImputedWire.apply)
      )

  private object ReductionReceiptWire:
    def from(receipt: StructuralReductionReceipt): ReductionReceiptWire =
      ReductionReceiptWire(
        receipt.reducer,
        receipt.members.map(member => MemberEstimateWire(member.member, member.estimate)),
        receipt.excludedMembers.map(member =>
          MemberExclusionWire(
            member.member,
            member.contradictions.toVector.sortBy(_.ordinal)
          )
        ),
        receipt.sourceChartCoverage,
        receipt.observedEstimateCoverage
      )

  extension (wire: Wire)
    private def materialize(
        recall: RecallGraph[Checked],
        view: SourceView
    ): Either[AlignError, HsmmResult] =
      for
        rows <- traverse(wire.posterior)(_.materialize)
        posterior <- AlignmentMatrix.of(rows)
        steps <- traverse(wire.flow)(_.materialize)
        costsByUnit <- traverse(wire.costs)(_.materialize)
        costs <- uniqueMap("HsmmResult.costs", costsByUnit)
        anchors <- uniqueMap(
          "HsmmResult.candidateAnchors",
          wire.candidateAnchors.map(entry => entry.unit -> entry.anchors)
        )
        result <- HsmmResult.validated(
          recall,
          view,
          anchors,
          posterior,
          TransitionFlow(steps),
          wire.viterbi,
          wire.logLikelihood,
          costs,
          wire.refinementPasses,
          Some(AdmissibilityEcho.fromChecksum(wire.admissibilityEcho))
        )
        matched <- AlignWire.matched(
          result,
          ViewFingerprint.fromChecksum(wire.viewFingerprint),
          wire.recallChecksum
        )
      yield matched

  extension (wire: RowWire)
    private def materialize: Either[AlignError, AlignmentRow] =
      for
        mass <- uniqueMap(
          "AlignmentRow.mass",
          wire.mass.map(entry => entry.state -> entry.mass)
        )
        row <- AlignmentRow.of(wire.unit, mass)
      yield row

  extension (wire: FlowStepWire)
    private def materialize: Either[AlignError, FlowStep] =
      uniqueMap(
        "FlowStep.mass",
        wire.mass.map(entry => (entry.fromState -> entry.toState) -> entry.mass)
      ).map(FlowStep(wire.from, wire.to, _))

  extension (wire: UnitCostsWire)
    private def materialize: Either[AlignError, (RecallUnitId, Map[AlignState, CostBreakdown])] =
      for
        entries <- traverse(wire.costs) { entry =>
          entry.cost.materialize.map(entry.state -> _)
        }
        costs <- uniqueMap("UnitCosts.costs", entries)
      yield wire.unit -> costs

  extension (wire: CostBreakdownWire)
    private def materialize: Either[AlignError, CostBreakdown] =
      for
        terms <- uniqueMap(
          "CostBreakdown.terms",
          wire.terms.map(term => term.term -> term.value)
        )
        reductions <- traverse(wire.reductions) { reduction =>
          reduction.receipt.materialize.map(reduction.term -> _)
        }
        reductionMap <- uniqueMap("CostBreakdown.reductions", reductions)
        missingTerms <- uniqueSet("CostBreakdown.missingTerms", wire.missingTerms)
        imputedTerms <- uniqueMap(
          "CostBreakdown.imputedTerms",
          wire.imputedTerms.map(i => i.term -> i.reason)
        )
        cost <- AlignWire.costBreakdown(
          terms,
          wire.mode,
          wire.exclusion,
          wire.total,
          missingTerms,
          wire.sourceChartCoverage,
          reductionMap,
          wire.supportWeight,
          imputedTerms
        )
      yield cost

  extension (wire: ReductionReceiptWire)
    private def materialize: Either[AlignError, StructuralReductionReceipt] =
      for
        members <- traverse(wire.members)(member =>
          AlignWire.memberEstimate(member.member, member.estimate)
        )
        excluded <- traverse(wire.excludedMembers) { member =>
          uniqueSet("StructuralMemberExclusion.contradictions", member.contradictions).flatMap(
            AlignWire.memberExclusion(member.member, _)
          )
        }
        receipt <- AlignWire.reductionReceipt(
          wire.reducer,
          members,
          excluded,
          wire.sourceChartCoverage,
          wire.observedEstimateCoverage
        )
      yield receipt

  private def traverse[A, B](values: Vector[A])(
      f: A => Either[AlignError, B]
  ): Either[AlignError, Vector[B]] =
    values.foldLeft[Either[AlignError, Vector[B]]](Right(Vector.empty)) { (acc, value) =>
      for
        built <- acc
        next <- f(value)
      yield built :+ next
    }

  private def uniqueMap[K, V](
      record: String,
      entries: Vector[(K, V)]
  ): Either[AlignError, Map[K, V]] =
    val keys = entries.map(_._1)
    if keys.distinct.size == keys.size then Right(entries.toMap)
    else Left(AlignError.MalformedRecord(record, "duplicate key"))

  private def uniqueSet[A](
      record: String,
      values: Vector[A]
  ): Either[AlignError, Set[A]] =
    if values.distinct.size == values.size then Right(values.toSet)
    else Left(AlignError.MalformedRecord(record, "duplicate value"))
