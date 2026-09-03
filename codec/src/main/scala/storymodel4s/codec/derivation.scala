package storymodel4s.codec

import java.nio.charset.StandardCharsets

import io.circe.{Decoder, DecodingFailure, Encoder, HCursor, Json}
import io.circe.syntax.*
import storymodel4s.acquire.{
  ClaimFamily,
  EvidenceRef,
  FindingCode,
  RejectionReason,
  ResolutionFailure
}
import storymodel4s.core.*
import storymodel4s.document.*
import storymodel4s.story.{ModelStatus, StoryModel}
import storymodel4s.view.DerivationRecord
import CanonicalPrimitives.{*, given}
import CoreCodecs.given
import PropositionCodecs.given

/** The derivation record of one compilation, as a standalone interchange artifact.
  *
  * Why a separate artifact and not fields on `storymodel.json`: a derivation gap is a statement
  * about the derivation, not about the story (ADR 0002 §11 A1), so the model must not carry it. And
  * why not the pipeline's `compilation-report.json`: that report is a human-readable account
  * written with counts and one-way renders, and a viewer holding it can reconstruct neither the gap
  * channel nor the abstention channel; it declares them as channels it could not compile. This
  * artifact carries the record's contents, typed, so `decode(encode(x)) == x`.
  *
  * The record binds to the model it describes three ways, from weakest to strongest: the story id,
  * the canonical source checksum, and `modelChecksum`, the SHA-256 of the canonical model text —
  * the exact bytes of `storymodel.json`. A consumer that pairs the two files can therefore refuse a
  * record written for a different build of the same text, not only for a different text.
  *
  * The relation between `attempts` and `gaps` is the one [[DerivationReceipt]] establishes: every
  * attempt has a distinct target, every gap names an attempted target whose disposition is
  * `NotEmitted` with the gap's own reason, and a target carries at most one gap. The coverage
  * ledger names each sentence once. Every combination the constructor refuses is a record that
  * describes no compilation.
  */
final class DerivationArtifact private (
    val storyId: StoryId,
    val canonicalSourceChecksum: Checksum,
    val modelChecksum: Checksum,
    val compilationFingerprint: Checksum,
    val candidateSet: Checksum,
    val attempts: Vector[DerivationAttempt],
    val gaps: Vector[DerivationGap],
    val coverage: Vector[SentenceCoverage],
    val summaryCoverage: SummaryCoverage
):
  /** The record in the shape the view consumes. */
  def record: DerivationRecord = DerivationRecord.Reported(gaps, coverage)

  /** Whether this record was written for exactly `model`: same story, same source, same bytes. */
  def describes[S <: ModelStatus](model: StoryModel[S]): Boolean =
    storyId == model.source.id &&
      canonicalSourceChecksum == model.source.canonicalChecksum &&
      modelChecksum == StoryModelCodec.contentChecksum(model)

  override def equals(other: Any): Boolean = other match
    case that: DerivationArtifact =>
      storyId == that.storyId &&
      canonicalSourceChecksum == that.canonicalSourceChecksum &&
      modelChecksum == that.modelChecksum &&
      compilationFingerprint == that.compilationFingerprint &&
      candidateSet == that.candidateSet &&
      attempts == that.attempts &&
      gaps == that.gaps &&
      coverage == that.coverage &&
      summaryCoverage == that.summaryCoverage
    case _ => false

  override def hashCode(): Int =
    (
      storyId,
      canonicalSourceChecksum,
      modelChecksum,
      compilationFingerprint,
      candidateSet,
      attempts,
      gaps,
      coverage,
      summaryCoverage
    ).##

  override def toString: String =
    s"DerivationArtifact(${storyId.value}, model=${modelChecksum.short()}, " +
      s"attempts=${attempts.size}, gaps=${gaps.size}, coverage=${coverage.size})"

object DerivationArtifact:
  /** Build the record of one compilation and the proposals it was compiled from. The model checksum
    * is taken from the compilation's own draft, so the record binds to the bytes
    * `StoryModelCodec.encode` writes for it.
    */
  def from(
      compilation: NarrativeCompilation,
      proposals: ChartProposals
  ): Either[DomainError, DerivationArtifact] =
    of(
      compilation.draft.source.id,
      compilation.draft.source.canonicalChecksum,
      StoryModelCodec.contentChecksum(compilation.draft),
      compilation.fingerprint,
      compilation.derivation.candidateSet,
      compilation.derivation.attempts,
      compilation.derivation.gaps,
      proposals.coverage,
      proposals.summaryCoverage
    )

  def of(
      storyId: StoryId,
      canonicalSourceChecksum: Checksum,
      modelChecksum: Checksum,
      compilationFingerprint: Checksum,
      candidateSet: Checksum,
      attempts: Vector[DerivationAttempt],
      gaps: Vector[DerivationGap],
      coverage: Vector[SentenceCoverage],
      summaryCoverage: SummaryCoverage
  ): Either[DomainError, DerivationArtifact] =
    val byTarget = attempts.map(a => a.target -> a).toMap
    val failure =
      if byTarget.size != attempts.size then Some("derivation attempts must have unique targets")
      else if gaps.map(_.target).distinct.size != gaps.size then
        Some("each attempted target may have at most one gap")
      else
        gaps
          .collectFirst {
            case gap if !byTarget.contains(gap.target) =>
              s"gap ${gap.target.render} names no attempted target"
            case gap
                if byTarget(gap.target).disposition != DerivationDisposition.NotEmitted(
                  gap.reason
                ) =>
              s"gap ${gap.target.render} disagrees with its attempt's disposition"
            case gap if byTarget(gap.target).family != gap.family =>
              s"gap ${gap.target.render} disagrees with its attempt's family"
          }
          .orElse {
            val sentences = coverage.map(_.sentence)
            if sentences.distinct.size != sentences.size then
              Some("coverage must name each sentence at most once")
            else None
          }
    failure
      .toLeft(
        new DerivationArtifact(
          storyId,
          canonicalSourceChecksum,
          modelChecksum,
          compilationFingerprint,
          candidateSet,
          attempts,
          gaps,
          coverage,
          summaryCoverage
        )
      )
      .left
      .map(DomainError.InvariantViolation("derivation-record", _))

/** Codecs for the derivation vocabulary of `document` and `acquire`.
  *
  * Conventions follow [[Canonical]]: parameterless enum cases are their names, parameterized cases
  * are objects tagged by `"type"`, identifiers are strings, sets are sorted arrays, and every
  * `Double` is its IEEE-754 bit pattern.
  */
object DerivationCodecs:
  given Encoder[ChartNodeRef] = Encoder.instance { ref =>
    Json.obj("sentence" -> ref.sentence.asJson, "concept" -> ref.concept.asJson)
  }
  given Decoder[ChartNodeRef] = Decoder.instance { c =>
    for
      sentence <- field[SurfaceUnitId](c, "sentence")
      concept <- field[storymodel4s.proposition.ConceptId](c, "concept")
    yield ChartNodeRef(sentence, concept)
  }

  private val simpleFamilies: Vector[ClaimFamily] = Vector(
    ClaimFamily.ReportedToRootPromotion,
    ClaimFamily.EventCoreference,
    ClaimFamily.RoleReversal,
    ClaimFamily.Polarity,
    ClaimFamily.StrictPrecedence,
    ClaimFamily.CausalEdge,
    ClaimFamily.TargetEpisodeMembership,
    ClaimFamily.EntityMention,
    ClaimFamily.EntityCoreference,
    ClaimFamily.SituationMention,
    ClaimFamily.ParticipantRole,
    ClaimFamily.ParticipantCoverage,
    ClaimFamily.SituationCircumstance,
    ClaimFamily.Modality,
    ClaimFamily.ContextAssignment,
    ClaimFamily.TemporalRelation,
    ClaimFamily.GoalRelation,
    ClaimFamily.StateChange,
    ClaimFamily.Reference,
    ClaimFamily.Boundary,
    ClaimFamily.SegmentMembership,
    ClaimFamily.DiscourseTrajectory,
    ClaimFamily.Summary,
    ClaimFamily.DetailAtom
  )

  given Encoder[ClaimFamily] = Encoder.instance {
    case ClaimFamily.Custom(ns, name) =>
      Json.obj("type" -> "Custom".asJson, "namespace" -> ns.asJson, "name" -> name.asJson)
    case family => family.toString.asJson
  }
  given Decoder[ClaimFamily] = Decoder.instance { c =>
    c.value.asString match
      case Some(s) =>
        simpleFamilies
          .find(_.toString == s)
          .toRight(DecodingFailure(s"unknown ClaimFamily $s", c.history))
      case None =>
        tagged(c) { case "Custom" =>
          for
            ns <- field[String](c, "namespace")
            name <- field[String](c, "name")
          yield ClaimFamily.Custom(ns, name)
        }
  }

  given Encoder[FindingCode] = enumEncoder(_.toString)
  given Decoder[FindingCode] = enumDecoder("FindingCode", FindingCode.values, _.toString)

  given Encoder[ResolutionFailure] = Encoder.instance {
    case ResolutionFailure.InsufficientAgreement(have, need) =>
      Json.obj(
        "type" -> "InsufficientAgreement".asJson,
        "have" -> have.asJson,
        "need" -> need.asJson
      )
    case ResolutionFailure.InsufficientSupport(score) =>
      Json.obj("type" -> "InsufficientSupport".asJson, "score" -> score.asJson)
    case ResolutionFailure.BlockingFinding(codes) =>
      Json.obj("type" -> "BlockingFinding".asJson, "codes" -> codes.asJson)
    case failure => failure.toString.asJson
  }
  given Decoder[ResolutionFailure] = Decoder.instance { c =>
    c.value.asString match
      case Some("NoProposal")     => Right(ResolutionFailure.NoProposal)
      case Some("Uncalibrated")   => Right(ResolutionFailure.Uncalibrated)
      case Some("NoSpanEvidence") => Right(ResolutionFailure.NoSpanEvidence)
      case Some(other)            =>
        Left(DecodingFailure(s"unknown ResolutionFailure $other", c.history))
      case None =>
        tagged(c) {
          case "InsufficientAgreement" =>
            for
              have <- field[Int](c, "have")
              need <- field[Int](c, "need")
            yield ResolutionFailure.InsufficientAgreement(have, need)
          case "InsufficientSupport" =>
            field[Double](c, "score").map(ResolutionFailure.InsufficientSupport.apply)
          case "BlockingFinding" =>
            field[Vector[FindingCode]](c, "codes").map(ResolutionFailure.BlockingFinding.apply)
        }
  }

  given Encoder[RejectionReason] = Encoder.instance {
    case RejectionReason.StructurallyInvalid(violations) =>
      Json.obj("type" -> "StructurallyInvalid".asJson, "violations" -> violations.asJson)
    case RejectionReason.BlockingFinding(codes) =>
      Json.obj("type" -> "BlockingFinding".asJson, "codes" -> codes.asJson)
    case RejectionReason.BelowRejectBand(probability, band) =>
      Json.obj(
        "type" -> "BelowRejectBand".asJson,
        "probability" -> probability.asJson,
        "band" -> band.asJson
      )
    case RejectionReason.NoSourceSupport => "NoSourceSupport".asJson
  }
  given Decoder[RejectionReason] = Decoder.instance { c =>
    c.value.asString match
      case Some("NoSourceSupport") => Right(RejectionReason.NoSourceSupport)
      case Some(other)             =>
        Left(DecodingFailure(s"unknown RejectionReason $other", c.history))
      case None =>
        tagged(c) {
          case "StructurallyInvalid" =>
            field[Vector[String]](c, "violations").map(RejectionReason.StructurallyInvalid.apply)
          case "BlockingFinding" =>
            field[Vector[FindingCode]](c, "codes").map(RejectionReason.BlockingFinding.apply)
          case "BelowRejectBand" =>
            for
              probability <- field[Probability](c, "probability")
              band <- field[Probability](c, "band")
            yield RejectionReason.BelowRejectBand(probability, band)
        }
  }

  given Encoder[EvidenceRef] = Encoder.instance {
    case EvidenceRef.ById(id)         => Json.obj("type" -> "ById".asJson, "id" -> id.asJson)
    case EvidenceRef.Inline(evidence) =>
      Json.obj("type" -> "Inline".asJson, "evidence" -> evidence.asJson)
  }
  given Decoder[EvidenceRef] = Decoder.instance { c =>
    tagged(c) {
      case "ById"   => field[EvidenceId](c, "id").map(EvidenceRef.ById.apply)
      case "Inline" => field[Evidence](c, "evidence").map(EvidenceRef.Inline.apply)
    }
  }

  given Encoder[DomainError] = Encoder.instance {
    case DomainError.InvalidSpan(start, endExclusive, reason) =>
      Json.obj(
        "type" -> "InvalidSpan".asJson,
        "start" -> start.asJson,
        "endExclusive" -> endExclusive.asJson,
        "reason" -> reason.asJson
      )
    case DomainError.InvalidId(kind, raw, reason) =>
      Json.obj(
        "type" -> "InvalidId".asJson,
        "kind" -> kind.asJson,
        "raw" -> raw.asJson,
        "reason" -> reason.asJson
      )
    case DomainError.InvalidProbability(value) =>
      Json.obj("type" -> "InvalidProbability".asJson, "value" -> value.asJson)
    case DomainError.InvariantViolation(path, reason) =>
      Json.obj(
        "type" -> "InvariantViolation".asJson,
        "path" -> path.asJson,
        "reason" -> reason.asJson
      )
    case DomainError.DuplicateId(kind, id) =>
      Json.obj("type" -> "DuplicateId".asJson, "kind" -> kind.asJson, "id" -> id.asJson)
    case DomainError.InvalidFormat(kind, raw, reason) =>
      Json.obj(
        "type" -> "InvalidFormat".asJson,
        "kind" -> kind.asJson,
        "raw" -> raw.asJson,
        "reason" -> reason.asJson
      )
  }
  given Decoder[DomainError] = Decoder.instance { c =>
    tagged(c) {
      case "InvalidSpan" =>
        for
          start <- field[Int](c, "start")
          end <- field[Int](c, "endExclusive")
          reason <- field[String](c, "reason")
        yield DomainError.InvalidSpan(start, end, reason)
      case "InvalidId" =>
        for
          kind <- field[String](c, "kind")
          raw <- field[String](c, "raw")
          reason <- field[String](c, "reason")
        yield DomainError.InvalidId(kind, raw, reason)
      case "InvalidProbability" =>
        field[Double](c, "value").map(DomainError.InvalidProbability.apply)
      case "InvariantViolation" =>
        for
          path <- field[String](c, "path")
          reason <- field[String](c, "reason")
        yield DomainError.InvariantViolation(path, reason)
      case "DuplicateId" =>
        for
          kind <- field[String](c, "kind")
          id <- field[String](c, "id")
        yield DomainError.DuplicateId(kind, id)
      case "InvalidFormat" =>
        for
          kind <- field[String](c, "kind")
          raw <- field[String](c, "raw")
          reason <- field[String](c, "reason")
        yield DomainError.InvalidFormat(kind, raw, reason)
    }
  }

  given Encoder[NarrativeCandidateAddress] = Encoder.instance {
    case NarrativeCandidateAddress.Situation(source) =>
      Json.obj("type" -> "Situation".asJson, "source" -> source.asJson)
    case NarrativeCandidateAddress.ContextAssignment(source) =>
      Json.obj("type" -> "ContextAssignment".asJson, "source" -> source.asJson)
    case NarrativeCandidateAddress.StorySummary(story) =>
      Json.obj("type" -> "StorySummary".asJson, "story" -> story.asJson)
    case NarrativeCandidateAddress.SegmentMembership(story, member) =>
      Json.obj(
        "type" -> "SegmentMembership".asJson,
        "story" -> story.asJson,
        "member" -> member.asJson
      )
    case NarrativeCandidateAddress.Causal(from, to) =>
      Json.obj("type" -> "Causal".asJson, "from" -> from.asJson, "to" -> to.asJson)
    case NarrativeCandidateAddress.TrajectoryStep(from, to) =>
      Json.obj("type" -> "TrajectoryStep".asJson, "from" -> from.asJson, "to" -> to.asJson)
    case NarrativeCandidateAddress.EntityMention(mention) =>
      Json.obj("type" -> "EntityMention".asJson, "mention" -> mention.asJson)
    case NarrativeCandidateAddress.Participant(situation, filler) =>
      Json.obj(
        "type" -> "Participant".asJson,
        "situation" -> situation.asJson,
        "filler" -> filler.asJson
      )
    case NarrativeCandidateAddress.ParticipantCoverage(situation) =>
      Json.obj("type" -> "ParticipantCoverage".asJson, "situation" -> situation.asJson)
    case NarrativeCandidateAddress.Circumstance(situation, filler) =>
      Json.obj(
        "type" -> "Circumstance".asJson,
        "situation" -> situation.asJson,
        "filler" -> filler.asJson
      )
    case NarrativeCandidateAddress.Temporal(from, to) =>
      Json.obj("type" -> "Temporal".asJson, "from" -> from.asJson, "to" -> to.asJson)
  }
  given Decoder[NarrativeCandidateAddress] = Decoder.instance { c =>
    def node(name: String) = field[ChartNodeRef](c, name)
    tagged(c) {
      case "Situation"         => node("source").map(NarrativeCandidateAddress.Situation.apply)
      case "ContextAssignment" =>
        node("source").map(NarrativeCandidateAddress.ContextAssignment.apply)
      case "StorySummary" =>
        field[StoryId](c, "story").map(NarrativeCandidateAddress.StorySummary.apply)
      case "SegmentMembership" =>
        for
          story <- field[StoryId](c, "story")
          member <- node("member")
        yield NarrativeCandidateAddress.SegmentMembership(story, member)
      case "Causal" =>
        for
          from <- node("from")
          to <- node("to")
        yield NarrativeCandidateAddress.Causal(from, to)
      case "TrajectoryStep" =>
        for
          from <- node("from")
          to <- node("to")
        yield NarrativeCandidateAddress.TrajectoryStep(from, to)
      case "EntityMention" => node("mention").map(NarrativeCandidateAddress.EntityMention.apply)
      case "Participant"   =>
        for
          situation <- node("situation")
          filler <- node("filler")
        yield NarrativeCandidateAddress.Participant(situation, filler)
      case "ParticipantCoverage" =>
        node("situation").map(NarrativeCandidateAddress.ParticipantCoverage.apply)
      case "Circumstance" =>
        for
          situation <- node("situation")
          filler <- node("filler")
        yield NarrativeCandidateAddress.Circumstance(situation, filler)
      case "Temporal" =>
        for
          from <- node("from")
          to <- node("to")
        yield NarrativeCandidateAddress.Temporal(from, to)
    }
  }

  given Encoder[DerivationGapReason] = Encoder.instance {
    case DerivationGapReason.Unresolved(reason) =>
      Json.obj("type" -> "Unresolved".asJson, "reason" -> reason.asJson)
    case DerivationGapReason.Rejected(reason) =>
      Json.obj("type" -> "Rejected".asJson, "reason" -> reason.asJson)
    case DerivationGapReason.MissingUpstream(addresses) =>
      Json.obj("type" -> "MissingUpstream".asJson, "addresses" -> addresses.asJson)
    case DerivationGapReason.UnscopableRelation(from, to) =>
      Json.obj("type" -> "UnscopableRelation".asJson, "from" -> from.asJson, "to" -> to.asJson)
    case DerivationGapReason.InvalidAccepted(error) =>
      Json.obj("type" -> "InvalidAccepted".asJson, "error" -> error.asJson)
    case reason => reason.toString.asJson
  }
  given Decoder[DerivationGapReason] = Decoder.instance { c =>
    c.value.asString match
      case Some("Alternatives")        => Right(DerivationGapReason.Alternatives)
      case Some("MissingRawScore")     => Right(DerivationGapReason.MissingRawScore)
      case Some("MissingSpanEvidence") => Right(DerivationGapReason.MissingSpanEvidence)
      case Some(other)                 =>
        Left(DecodingFailure(s"unknown DerivationGapReason $other", c.history))
      case None =>
        tagged(c) {
          case "Unresolved" =>
            field[ResolutionFailure](c, "reason").map(DerivationGapReason.Unresolved.apply)
          case "Rejected" =>
            field[RejectionReason](c, "reason").map(DerivationGapReason.Rejected.apply)
          case "MissingUpstream" =>
            field[Vector[NarrativeCandidateAddress]](c, "addresses")
              .map(DerivationGapReason.MissingUpstream.apply)
          case "UnscopableRelation" =>
            for
              from <- field[ChartNodeRef](c, "from")
              to <- field[ChartNodeRef](c, "to")
            yield DerivationGapReason.UnscopableRelation(from, to)
          case "InvalidAccepted" =>
            field[DomainError](c, "error").map(DerivationGapReason.InvalidAccepted.apply)
        }
  }

  given Encoder[DerivationGap] = Encoder.instance { gap =>
    Json.obj(
      "stage" -> gap.stage.asJson,
      "family" -> gap.family.asJson,
      "target" -> gap.target.asJson,
      "reason" -> gap.reason.asJson,
      "upstreamClaims" -> sortedSetEncoder[ClaimId](gap.upstreamClaims),
      "evidence" -> gap.evidence.asJson
    )
  }
  given Decoder[DerivationGap] = Decoder.instance { c =>
    for
      stage <- field[StageId](c, "stage")
      family <- field[ClaimFamily](c, "family")
      target <- field[NarrativeCandidateAddress](c, "target")
      reason <- field[DerivationGapReason](c, "reason")
      upstream <- field[Vector[ClaimId]](c, "upstreamClaims")
      evidence <- field[Vector[EvidenceRef]](c, "evidence")
    yield DerivationGap(stage, family, target, reason, upstream.toSet, evidence)
  }

  given Encoder[DerivationDisposition] = Encoder.instance {
    case DerivationDisposition.Emitted(claim) =>
      Json.obj("type" -> "Emitted".asJson, "claim" -> claim.asJson)
    case DerivationDisposition.NotEmitted(reason) =>
      Json.obj("type" -> "NotEmitted".asJson, "reason" -> reason.asJson)
  }
  given Decoder[DerivationDisposition] = Decoder.instance { c =>
    tagged(c) {
      case "Emitted"    => field[ClaimId](c, "claim").map(DerivationDisposition.Emitted.apply)
      case "NotEmitted" =>
        field[DerivationGapReason](c, "reason").map(DerivationDisposition.NotEmitted.apply)
    }
  }

  given Encoder[DerivationAttempt] = Encoder.instance { attempt =>
    Json.obj(
      "target" -> attempt.target.asJson,
      "family" -> attempt.family.asJson,
      "disposition" -> attempt.disposition.asJson
    )
  }
  given Decoder[DerivationAttempt] = Decoder.instance { c =>
    for
      target <- field[NarrativeCandidateAddress](c, "target")
      family <- field[ClaimFamily](c, "family")
      disposition <- field[DerivationDisposition](c, "disposition")
    yield DerivationAttempt(target, family, disposition)
  }

  given Encoder[AbstentionReason] = Encoder.instance {
    case AbstentionReason.FocusNotPredicate(kind) =>
      Json.obj("type" -> "FocusNotPredicate".asJson, "kind" -> kind.asJson)
    case AbstentionReason.BranchNotAdmissible(kind) =>
      Json.obj("type" -> "BranchNotAdmissible".asJson, "kind" -> kind.asJson)
    case reason => reason.toString.asJson
  }
  given Decoder[AbstentionReason] = Decoder.instance { c =>
    c.value.asString match
      case Some("NoFocus")              => Right(AbstentionReason.NoFocus)
      case Some("NoCoordinationBranch") => Right(AbstentionReason.NoCoordinationBranch)
      case Some("NestedCoordination")   => Right(AbstentionReason.NestedCoordination)
      case Some(other)                  =>
        Left(DecodingFailure(s"unknown AbstentionReason $other", c.history))
      case None =>
        tagged(c) {
          case "FocusNotPredicate" =>
            field[storymodel4s.proposition.ConceptKind](c, "kind")
              .map(AbstentionReason.FocusNotPredicate.apply)
          case "BranchNotAdmissible" =>
            field[storymodel4s.proposition.ConceptKind](c, "kind")
              .map(AbstentionReason.BranchNotAdmissible.apply)
        }
  }

  given Encoder[FillerCounts] = Encoder.instance { counts =>
    Json.obj(
      "referents" -> counts.referents.asJson,
      "circumstances" -> counts.circumstances.asJson,
      "eventualities" -> counts.eventualities.asJson,
      "nonReferential" -> counts.nonReferential.asJson,
      "unlicensed" -> counts.unlicensed.asJson,
      "ambiguous" -> counts.ambiguous.asJson
    )
  }
  given Decoder[FillerCounts] = Decoder.instance { c =>
    for
      referents <- field[Int](c, "referents")
      circumstances <- field[Int](c, "circumstances")
      eventualities <- field[Int](c, "eventualities")
      nonReferential <- field[Int](c, "nonReferential")
      unlicensed <- field[Int](c, "unlicensed")
      ambiguous <- field[Int](c, "ambiguous")
    yield FillerCounts(
      referents,
      circumstances,
      eventualities,
      nonReferential,
      unlicensed,
      ambiguous
    )
  }

  given Encoder[CoordinatedBranch] = Encoder.instance {
    case CoordinatedBranch.Admitted(root, role, counts) =>
      Json.obj(
        "type" -> "Admitted".asJson,
        "root" -> root.asJson,
        "role" -> role.asJson,
        "counts" -> counts.asJson
      )
    case CoordinatedBranch.Abstained(root, role, reason) =>
      Json.obj(
        "type" -> "Abstained".asJson,
        "root" -> root.asJson,
        "role" -> role.asJson,
        "reason" -> reason.asJson
      )
  }
  given Decoder[CoordinatedBranch] = Decoder.instance { c =>
    tagged(c) {
      case "Admitted" =>
        for
          root <- field[ChartNodeRef](c, "root")
          role <- field[storymodel4s.proposition.SourceRole](c, "role")
          counts <- field[FillerCounts](c, "counts")
        yield CoordinatedBranch.Admitted(root, role, counts)
      case "Abstained" =>
        for
          root <- field[ChartNodeRef](c, "root")
          role <- field[storymodel4s.proposition.SourceRole](c, "role")
          reason <- field[AbstentionReason](c, "reason")
        yield CoordinatedBranch.Abstained(root, role, reason)
    }
  }

  given Encoder[SentenceCoverage] = Encoder.instance {
    case SentenceCoverage.Proposed(root, counts) =>
      Json.obj("type" -> "Proposed".asJson, "root" -> root.asJson, "counts" -> counts.asJson)
    case SentenceCoverage.Coordinated(coordinator, branches) =>
      Json.obj(
        "type" -> "Coordinated".asJson,
        "coordinator" -> coordinator.asJson,
        "branches" -> branches.asJson
      )
    case SentenceCoverage.Abstained(anchor, reason) =>
      Json.obj("type" -> "Abstained".asJson, "anchor" -> anchor.asJson, "reason" -> reason.asJson)
    case SentenceCoverage.EmptyChart(unit) =>
      Json.obj("type" -> "EmptyChart".asJson, "unit" -> unit.asJson)
    case SentenceCoverage.NoChart(unit) =>
      Json.obj("type" -> "NoChart".asJson, "unit" -> unit.asJson)
  }
  given Decoder[SentenceCoverage] = Decoder.instance { c =>
    tagged(c) {
      case "Proposed" =>
        for
          root <- field[ChartNodeRef](c, "root")
          counts <- field[FillerCounts](c, "counts")
        yield SentenceCoverage.Proposed(root, counts)
      case "Coordinated" =>
        for
          coordinator <- field[ChartNodeRef](c, "coordinator")
          branches <- field[Vector[CoordinatedBranch]](c, "branches")
        yield SentenceCoverage.Coordinated(coordinator, branches)
      case "Abstained" =>
        for
          anchor <- field[ChartNodeRef](c, "anchor")
          reason <- field[AbstentionReason](c, "reason")
        yield SentenceCoverage.Abstained(anchor, reason)
      case "EmptyChart" => field[SurfaceUnitId](c, "unit").map(SentenceCoverage.EmptyChart.apply)
      case "NoChart"    => field[SurfaceUnitId](c, "unit").map(SentenceCoverage.NoChart.apply)
    }
  }

  given Encoder[TitleProvenance] = enumEncoder(_.render)
  given Decoder[TitleProvenance] = Decoder.decodeString.emap { raw =>
    TitleProvenance.parse(raw).toRight(s"unknown TitleProvenance: $raw")
  }

  given Encoder[SummaryCoverage] = Encoder.instance {
    case SummaryCoverage.Proposed(title, provenance) =>
      Json.obj(
        "type" -> "Proposed".asJson,
        "title" -> title.asJson,
        "provenance" -> provenance.asJson
      )
    case coverage => coverage.toString.asJson
  }
  given Decoder[SummaryCoverage] = Decoder.instance { c =>
    c.value.asString match
      case Some("NoTitle")            => Right(SummaryCoverage.NoTitle)
      case Some("TitleUnestablished") => Right(SummaryCoverage.TitleUnestablished)
      case Some(other)                =>
        Left(DecodingFailure(s"unknown SummaryCoverage $other", c.history))
      case None =>
        tagged(c) { case "Proposed" =>
          for
            title <- field[String](c, "title")
            provenance <- field[TitleProvenance](c, "provenance")
          yield SummaryCoverage.Proposed(title, provenance)
        }
  }

  /** Dispatch on the `"type"` tag of an object encoding; an unknown tag is a decoding failure
    * naming the tag rather than a `MatchError`.
    */
  private def tagged[A](c: HCursor)(
      cases: PartialFunction[String, Decoder.Result[A]]
  ): Decoder.Result[A] =
    field[String](c, "type").flatMap { tag =>
      cases.applyOrElse(
        tag,
        (t: String) => Left(DecodingFailure(s"unknown type tag $t", c.history))
      )
    }

/** The `derivation.json` artifact: canonical text, the checksum of that text, and decoding that
  * refuses a record for a different story, source, or model.
  */
object DerivationRecordCodec:
  import DerivationCodecs.given

  val SchemaVersion: String = "derivation-record/v1"

  given Encoder[DerivationArtifact] = Encoder.instance { artifact =>
    Json.obj(
      "schemaVersion" -> Json.fromString(SchemaVersion),
      "storyId" -> artifact.storyId.asJson,
      "canonicalSourceChecksum" -> artifact.canonicalSourceChecksum.asJson,
      "modelChecksum" -> artifact.modelChecksum.asJson,
      "compilationFingerprint" -> artifact.compilationFingerprint.asJson,
      "candidateSet" -> artifact.candidateSet.asJson,
      "attempts" -> artifact.attempts.asJson,
      "gaps" -> artifact.gaps.asJson,
      "coverage" -> artifact.coverage.asJson,
      "summaryCoverage" -> artifact.summaryCoverage.asJson
    )
  }

  given Decoder[DerivationArtifact] = Decoder.instance { c =>
    for
      version <- field[String](c, "schemaVersion")
      _ <-
        if version == SchemaVersion then Right(())
        else
          Left(
            DecodingFailure(
              s"unsupported schema version $version (supported: $SchemaVersion)",
              c.history
            )
          )
      storyId <- field[StoryId](c, "storyId")
      sourceChecksum <- field[Checksum](c, "canonicalSourceChecksum")
      modelChecksum <- field[Checksum](c, "modelChecksum")
      fingerprint <- field[Checksum](c, "compilationFingerprint")
      candidateSet <- field[Checksum](c, "candidateSet")
      attempts <- field[Vector[DerivationAttempt]](c, "attempts")
      gaps <- field[Vector[DerivationGap]](c, "gaps")
      coverage <- field[Vector[SentenceCoverage]](c, "coverage")
      summary <- field[SummaryCoverage](c, "summaryCoverage")
      artifact <- domain(
        c,
        DerivationArtifact.of(
          storyId,
          sourceChecksum,
          modelChecksum,
          fingerprint,
          candidateSet,
          attempts,
          gaps,
          coverage,
          summary
        )
      )
    yield artifact
  }

  def encode(artifact: DerivationArtifact): String = Canonical.encode(artifact)

  /** Decode a record without binding it to a model. The caller must then check
    * [[DerivationArtifact.describes]] before pairing it with one.
    */
  def decode(text: String): Either[CodecError, DerivationArtifact] =
    Canonical.parse(text).flatMap { json =>
      Canonical.decodeJson[DerivationArtifact](json).flatMap { artifact =>
        if json == summon[Encoder[DerivationArtifact]](artifact) then Right(artifact)
        else
          Left(
            CodecError.Decode("$", "artifact contains unknown or noncanonical data fields")
          )
      }
    }

  /** Decode a record and refuse it unless it was written for exactly `model`. */
  def decode[S <: ModelStatus](
      model: StoryModel[S],
      text: String
  ): Either[CodecError, DerivationArtifact] =
    decode(text).flatMap { artifact =>
      if artifact.storyId != model.source.id then
        Left(CodecError.Decode("$.storyId", "story id does not match the supplied model"))
      else if artifact.canonicalSourceChecksum != model.source.canonicalChecksum then
        Left(
          CodecError.Decode(
            "$.canonicalSourceChecksum",
            "canonical source checksum does not match the supplied model"
          )
        )
      else if artifact.modelChecksum != StoryModelCodec.contentChecksum(model) then
        Left(
          CodecError.Decode(
            "$.modelChecksum",
            "model checksum does not match the supplied model's canonical text"
          )
        )
      else Right(artifact)
    }

  /** Decode strict UTF-8 bytes; malformed input is refused rather than replacement-decoded. */
  def decode(bytes: Array[Byte]): Either[CodecError, DerivationArtifact] =
    val text = new String(bytes, StandardCharsets.UTF_8)
    if text.getBytes(StandardCharsets.UTF_8).sameElements(bytes) then decode(text)
    else Left(CodecError.Parse(s"$SchemaVersion artifact is not strict UTF-8"))

  /** Content checksum of the exact canonical UTF-8 bytes returned by [[encode]]. */
  def checksum(artifact: DerivationArtifact): Checksum =
    Checksum.ofBytes(encode(artifact).getBytes(StandardCharsets.UTF_8))
