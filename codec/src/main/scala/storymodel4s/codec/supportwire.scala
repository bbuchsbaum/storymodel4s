package storymodel4s.codec

import io.circe.{Decoder, DecodingFailure, Encoder, HCursor, Json}
import io.circe.syntax.*
import storymodel4s.align.*
import CanonicalPrimitives.{*, given}

/** Shared exact support wire. Contextual consumers still check which support applies. */
private[codec] object SupportAssessmentWire:
  /** The exact weight of one eligible term in a cell's support basis. */
  final case class WeightWire(term: CostTerm, weight: Double)

  /** The tagged support of one cost record, as carried on the wire. Every field is EVIDENCE for the
    * decoder to re-derive from, never a claim it adopts: the share and reasons are recomputed.
    */
  enum Value:
    case Assessed(
        share: Double,
        measuredTerms: Vector[CostTerm],
        eligibleTerms: Vector[CostTerm],
        eligibleWeights: Vector[WeightWire]
    )
    case Unestablished(
        reason: SupportUnestablishedReason,
        measuredTerms: Vector[CostTerm],
        eligibleTerms: Vector[CostTerm],
        eligibleWeights: Vector[WeightWire]
    )
    case NotApplicable(reason: SupportNotApplicableReason)
  private given Encoder[CostTerm] = enumEncoder(_.toString)
  private given Decoder[CostTerm] = enumDecoder("CostTerm", CostTerm.values, _.toString)

  private given Encoder[SupportUnestablishedReason] = enumEncoder(_.toString)
  private given Decoder[SupportUnestablishedReason] =
    enumDecoder("SupportUnestablishedReason", SupportUnestablishedReason.values, _.toString)
  private given Encoder[SupportNotApplicableReason] = enumEncoder(_.toString)
  private given Decoder[SupportNotApplicableReason] =
    enumDecoder("SupportNotApplicableReason", SupportNotApplicableReason.values, _.toString)

  private given Encoder[WeightWire] = Encoder.instance { entry =>
    Json.obj("term" -> entry.term.asJson, "weight" -> entry.weight.asJson)
  }
  private given Decoder[WeightWire] = Decoder.instance { c =>
    for
      _ <- onlyFields(c, "eligible weight", Set("term", "weight"))
      term <- field[CostTerm](c, "term")
      weight <- field[Double](c, "weight")
    yield WeightWire(term, weight)
  }

  private def onlyFields(c: HCursor, record: String, allowed: Set[String]): Decoder.Result[Unit] =
    val extra = c.keys.map(_.toSet -- allowed).getOrElse(Set.empty).toVector.sorted
    if extra.isEmpty then Right(())
    else Left(DecodingFailure(s"$record carries unknown ${extra.mkString(", ")}", c.history))

  private val BasisFields: Set[String] = Set("measuredTerms", "eligibleTerms", "eligibleWeights")
  private val AssessedFields: Set[String] = BasisFields ++ Set("type", "share")
  private val UnestablishedFields: Set[String] = BasisFields ++ Set("type", "reason")
  private val NotApplicableFields: Set[String] = Set("type", "reason")

  given Encoder[Value] = Encoder.instance {
    case Value.Assessed(share, measured, eligible, weights) =>
      Json.obj(
        "type" -> "Assessed".asJson,
        "share" -> share.asJson,
        "measuredTerms" -> measured.asJson,
        "eligibleTerms" -> eligible.asJson,
        "eligibleWeights" -> weights.asJson
      )
    case Value.Unestablished(reason, measured, eligible, weights) =>
      Json.obj(
        "type" -> "Unestablished".asJson,
        "reason" -> reason.asJson,
        "measuredTerms" -> measured.asJson,
        "eligibleTerms" -> eligible.asJson,
        "eligibleWeights" -> weights.asJson
      )
    case Value.NotApplicable(reason) =>
      Json.obj("type" -> "NotApplicable".asJson, "reason" -> reason.asJson)
  }

  /** Each variant has an EXACT field set. A share on a variant that makes no numeric claim, or a
    * basis on a `NotApplicable` record, is refused here rather than silently ignored — an ignored
    * field is a claim the artifact makes and the decoder never checked.
    */
  given Decoder[Value] = Decoder.instance { c =>
    def exactly(tag: String, expected: Set[String]): Decoder.Result[Unit] =
      val present = c.keys.map(_.toSet).getOrElse(Set.empty)
      val extra = (present -- expected).toVector.sorted
      val absent = (expected -- present).toVector.sorted
      if extra.contains("share") then
        Left(DecodingFailure(s"$tag support cannot carry a numeric claim (share)", c.history))
      else if extra.nonEmpty then
        Left(
          DecodingFailure(s"$tag support carries unexpected ${extra.mkString(", ")}", c.history)
        )
      else if absent.nonEmpty then
        Left(DecodingFailure(s"$tag support is missing ${absent.mkString(", ")}", c.history))
      else Right(())
    def basis(c: HCursor) =
      for
        measured <- field[Vector[CostTerm]](c, "measuredTerms")
        eligible <- field[Vector[CostTerm]](c, "eligibleTerms")
        weights <- field[Vector[WeightWire]](c, "eligibleWeights")
      yield (measured, eligible, weights)
    field[String](c, "type").flatMap {
      case tag @ "Assessed" =>
        for
          _ <- exactly(tag, AssessedFields)
          share <- field[Double](c, "share")
          (measured, eligible, weights) <- basis(c)
        yield Value.Assessed(share, measured, eligible, weights)
      case tag @ "Unestablished" =>
        for
          _ <- exactly(tag, UnestablishedFields)
          reason <- field[SupportUnestablishedReason](c, "reason")
          (measured, eligible, weights) <- basis(c)
        yield Value.Unestablished(reason, measured, eligible, weights)
      case tag @ "NotApplicable" =>
        for
          _ <- exactly(tag, NotApplicableFields)
          reason <- field[SupportNotApplicableReason](c, "reason")
        yield Value.NotApplicable(reason)
      case other => Left(DecodingFailure(s"unknown SupportAssessment $other", c.history))
    }
  }

  object Value:
    def from(support: SupportAssessment): Value = support match
      case assessed: SupportAssessment.Assessed =>
        val (measured, eligible, weights) = basis(assessed.basis)
        Value.Assessed(assessed.share, measured, eligible, weights)
      case unestablished: SupportAssessment.Unestablished =>
        val (measured, eligible, weights) = basis(unestablished.basis)
        Value.Unestablished(unestablished.reason, measured, eligible, weights)
      case notApplicable: SupportAssessment.NotApplicable =>
        Value.NotApplicable(notApplicable.reason)

    private def basis(
        basis: CellSupportBasis
    ): (Vector[CostTerm], Vector[CostTerm], Vector[WeightWire]) =
      (
        basis.measuredTerms.toVector.sortBy(_.ordinal),
        basis.eligibleTerms.toVector.sortBy(_.ordinal),
        basis.eligibleWeights.toVector.sortBy(_._1.ordinal).map(WeightWire.apply)
      )

  /** Re-derive support from the carried basis and hold the carried share or reason to it. The share
    * is compared bit for bit: an artifact claiming `0.5` for a basis whose every eligible term was
    * measured (which derives exactly `1.0`) is refused, not adopted.
    */
  def materialize(wire: Value): Either[AlignError, SupportAssessment] =
    val r = "SupportAssessment"
    def derived(
        measured: Vector[CostTerm],
        eligible: Vector[CostTerm],
        weights: Vector[WeightWire]
    ) =
      for
        measuredSet <- uniqueSet(s"$r.measuredTerms", measured)
        eligibleSet <- uniqueSet(s"$r.eligibleTerms", eligible)
        weightMap <- uniqueMap(s"$r.eligibleWeights", weights.map(w => w.term -> w.weight))
        support <- SupportAssessment.fromEvidence(measuredSet, eligibleSet, weightMap)
      yield support
    wire match
      case Value.Assessed(share, measured, eligible, weights) =>
        derived(measured, eligible, weights).flatMap {
          case assessed: SupportAssessment.Assessed
              if java.lang.Double.doubleToRawLongBits(assessed.share) ==
                java.lang.Double.doubleToRawLongBits(share) =>
            Right(assessed)
          case assessed: SupportAssessment.Assessed =>
            Left(
              AlignError.MalformedRecord(
                r,
                s"carried share $share is not the share its basis derives (${assessed.share})"
              )
            )
          case unestablished: SupportAssessment.Unestablished =>
            Left(
              AlignError.MalformedRecord(
                r,
                s"an Assessed share over a basis that establishes none (${unestablished.reason})"
              )
            )
        }
      case Value.Unestablished(reason, measured, eligible, weights) =>
        derived(measured, eligible, weights).flatMap {
          case unestablished: SupportAssessment.Unestablished if unestablished.reason == reason =>
            Right(unestablished)
          case unestablished: SupportAssessment.Unestablished =>
            Left(
              AlignError.MalformedRecord(
                r,
                s"carried reason $reason is not the reason its basis derives " +
                  s"(${unestablished.reason})"
              )
            )
          case assessed: SupportAssessment.Assessed =>
            Left(
              AlignError.MalformedRecord(
                r,
                s"an Unestablished tag over a basis that derives the share ${assessed.share}"
              )
            )
        }
      case Value.NotApplicable(reason) =>
        // Which reason applies is derived from the record's own shape by AlignWire.costBreakdown
        // (external vs unreachable); a mismatch is refused there.
        Right(reason match
          case SupportNotApplicableReason.ExternalState => SupportAssessment.externalState
          case SupportNotApplicableReason.Unreachable   => SupportAssessment.unreachable)

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

  def encode(value: SupportAssessment): Json = summon[Encoder[Value]].apply(Value.from(value))
