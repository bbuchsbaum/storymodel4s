package storymodel4s.document

import cats.syntax.all.*
import storymodel4s.acquire.*
import storymodel4s.core.*
import storymodel4s.proposition.{
  AlignmentTarget,
  ChartIdentity,
  ChartOrigin,
  PropositionChart,
  CheckState,
  PropositionRelation,
  SourceRole
}
import storymodel4s.story.ParticipantRole

/** Source-bound participant reliability studies; fitting is not a certificate of generalization.
  * See ADR 0014 for the semantic target and the curator's story-group obligation.
  */
object ParticipantCalibration:
  val EstimatorVersion: String = "participant-role-cell-beta11/v1"

  /** A refusal is data: missing support must not become an invented probability. */
  enum Refusal:
    case InvalidItem(reason: String)
    case InvalidCorpus(reason: String)
    case InsufficientStories(required: Int, actual: Int)
    case TrainingStory
    case DifferentPipeline
    case UnseenCell
    case InsufficientCellStories(actual: Int)

  /** A final adjudication distinguishes inability to decide from an incorrect role. */
  enum Verdict:
    case Correct, Incorrect
    case Unresolved(reason: String)

  /** External attestation, not a claim that this library verified the annotator's work. */
  final case class Judgment(
      item: Checksum,
      verdict: Verdict,
      adjudicator: Fingerprint,
      protocol: Checksum
  )

  /** An exact scored candidate extracted from a checked compiler input, with no construction
    * bypass.
    */
  final class Item private[ParticipantCalibration] (
      val id: Checksum,
      val storyGroup: StoryId,
      val source: Checksum,
      val pipeline: Checksum,
      val producer: Checksum,
      val role: ParticipantRole,
      val score: RawScore,
      val attempt: ParticipantAttempt
  ):
    override def equals(other: Any): Boolean = other match
      case that: Item => id == that.id && storyGroup == that.storyGroup
      case _          => false
    override def hashCode(): Int = (id, storyGroup).##
    override def toString: String = s"CalibrationItem(${id.hex}, ${storyGroup.value})"

  /** Extract one existing candidate; the supplied manifest checksum declares the frozen pipeline.
    */
  def item(
      input: NarrativeCompilerInput,
      attempt: ParticipantAttempt,
      storyGroup: StoryId,
      pipeline: Checksum
  ): Either[Refusal, Item] =
    if !input.participants.contains(attempt) then
      Left(Refusal.InvalidItem("attempt is not part of the checked compiler input"))
    else
      attempt.bundle.proposals match
        case Vector(proposal) if proposal.disposition == ProposalDisposition.Proposed =>
          (proposal.value, proposal.rawScore) match
            case (Some(role), Some(score)) =>
              val chartIds =
                input.localCharts
                  .map((u, e) =>
                    fields(
                      Vector(
                        u.value,
                        chartKey(e.chart),
                        originKey(e.provenance.origin),
                        fields(e.provenance.receipts.map(NarrativeCompiler.renderProviderCall)),
                        fields(
                          e.provenance.alternatives
                            .map((hash, c) => fields(Vector(hash.hex, credenceKey(c))))
                        )
                      )
                    )
                  )
                  .sorted
              val producers = input.localCharts
                .map { (_, e) =>
                  fields(
                    Vector(originKey(e.provenance.origin)) ++
                      e.provenance.receipts.map(producerKey).distinct.sorted
                  )
                }
                .distinct
                .sorted
              val producer = digest(
                Vector(
                  input.source.language.value,
                  producerKey(proposal.receipt.call),
                  proposal.receipt.promptPackage.name,
                  proposal.receipt.promptPackage.version,
                  proposal.receipt.promptPackage.checksum.hex
                ) ++ producers
              )
              val evidence = proposal.evidence.map { ref =>
                val e = ref match
                  case EvidenceRef.Inline(value) => value
                  case EvidenceRef.ById(id)      => input.evidence(id)
                evidenceKey(e)
              }.sorted
              val id = digest(
                Vector(
                  "participant-calibration-item/v1",
                  input.source.canonicalChecksum.hex,
                  pipeline.hex,
                  producer.hex,
                  attempt.situation.sentence.value,
                  attempt.situation.concept.value,
                  attempt.filler.sentence.value,
                  attempt.filler.concept.value,
                  roleKey(role),
                  score.scorer.value,
                  Score.hexBits(score.value),
                  proposal.taskId.value,
                  NarrativeCompiler.renderProviderCall(proposal.receipt.call),
                  proposal.receipt.promptPackage.checksum.hex,
                  fields(chartIds),
                  fields(evidence)
                )
              )
              Right(
                new Item(
                  id,
                  storyGroup,
                  input.source.canonicalChecksum,
                  pipeline,
                  producer,
                  role,
                  score,
                  attempt
                )
              )
            case _ => Left(Refusal.InvalidItem("candidate has no measured role score"))
        case _ => Left(Refusal.InvalidItem("requires one proposed participant candidate"))

  /** A complete, matched adjudication batch: no unknown rows can silently fall out of evaluation.
    */
  final class Corpus private[ParticipantCalibration] (
      val items: Vector[Item],
      val judgments: Vector[Judgment],
      val checksum: Checksum
  ):
    override def equals(other: Any): Boolean = other match
      case that: Corpus => checksum == that.checksum
      case _            => false
    override def hashCode(): Int = checksum.##
    override def toString: String = s"CalibrationCorpus(${checksum.hex}, ${items.size} items)"

  /** Match exactly one final judgment per item, with one protocol and pipeline in a study. */
  def corpus(items: Vector[Item], judgments: Vector[Judgment]): Either[Refusal, Corpus] =
    def invalid(reason: String) = Left(Refusal.InvalidCorpus(reason))
    if items.isEmpty then invalid("no items supplied")
    else if items.map(_.id).distinct.size != items.size then invalid("duplicate item")
    else if items
        .map(i => (i.source, i.attempt.situation, i.attempt.filler))
        .distinct
        .size != items.size
    then invalid("repeated acquisition of the same source candidate")
    else if judgments.map(_.item).distinct.size != judgments.size then invalid("duplicate judgment")
    else if items.map(_.id).toSet != judgments.map(_.item).toSet then
      invalid("judgments must match all and only the supplied items")
    else if judgments.map(_.protocol).distinct.size != 1 then
      invalid("mixed adjudication protocols")
    else if items.map(i => (i.pipeline, i.producer)).distinct.size != 1 then
      invalid("mixed pipelines or producers")
    else if items.groupBy(_.source).values.exists(_.map(_.storyGroup).distinct.size != 1) then
      invalid("the same source was assigned to different story groups")
    else if judgments.exists(_.verdict match
        case Verdict.Unresolved(reason) => reason.trim.isEmpty
        case _                          => false)
    then invalid("unresolved judgment needs a reason")
    else
      val ordered = items.sortBy(_.id.hex)
      val byId = judgments.map(j => j.item -> j).toMap
      val labels = ordered.map(i => byId(i.id))
      val checksum = digest(Vector(EstimatorVersion) ++ ordered.zip(labels).map { (i, j) =>
        fields(
          Vector(
            i.id.hex,
            i.storyGroup.value,
            verdictKey(j.verdict),
            j.adjudicator.value,
            j.protocol.hex
          )
        )
      })
      Right(new Corpus(ordered, labels, checksum))

  /** Cell identity prevents one role, scorer or grade from borrowing another's adjudications. */
  final case class Cell(role: ParticipantRole, score: RawScore)

  /** Sufficient statistics expose how many judgments and distinct story groups support a fit. */
  final class CellFit private[ParticipantCalibration] (
      val correct: Int,
      val incorrect: Int,
      val stories: Set[StoryId],
      val probability: Probability
  ):
    override def equals(other: Any): Boolean = other match
      case that: CellFit =>
        correct == that.correct && incorrect == that.incorrect && stories == that.stories
      case _ => false
    override def hashCode(): Int = (correct, incorrect, stories).##
    override def toString: String =
      s"CellFit(correct=$correct, incorrect=$incorrect, stories=${stories.size})"

  /** A fitted model retains its training corpus and refuses to make in-sample scientific claims. */
  final class Model private[ParticipantCalibration] (
      val id: CalibrationModelId,
      val training: Corpus,
      val cells: Map[Cell, CellFit],
      val excluded: Vector[Checksum]
  ):
    private val groups = training.items.map(_.storyGroup).toSet
    private val sources = training.items.map(_.source).toSet
    private val pipelines = training.items.map(i => (i.pipeline, i.producer)).toSet

    def predict(item: Item): Either[Refusal, Probability] =
      if groups(item.storyGroup) || sources(item.source) then Left(Refusal.TrainingStory)
      else if !pipelines((item.pipeline, item.producer)) then Left(Refusal.DifferentPipeline)
      else
        cells.get(Cell(item.role, item.score)).toRight(Refusal.UnseenCell).flatMap { cell =>
          if cell.stories.size < 2 then Left(Refusal.InsufficientCellStories(cell.stories.size))
          else Right(cell.probability)
        }

    /** Submit this result to the ordinary resolver; the original candidate evidence stays intact.
      */
    def calibrate(item: Item): Either[Refusal, ParticipantAttempt] =
      predict(item).map(p =>
        item.attempt.copy(bundle =
          item.attempt.bundle.copy(
            bases = Vector(CandidateBasis(item.role, AcceptanceBasis.Calibrated(p, id)))
          )
        )
      )

    override def equals(other: Any): Boolean = other match
      case that: Model => id == that.id
      case _           => false
    override def hashCode(): Int = id.##
    override def toString: String = s"ParticipantCalibration(${id.value}, cells=${cells.size})"

  /** Fixed Beta(1,1) cell estimator, never fit on unresolved judgments. */
  def fit(corpus: Corpus): Either[Refusal, Model] =
    val labeled = corpus.items
      .zip(corpus.judgments)
      .filter((_, j) =>
        j.verdict match
          case Verdict.Unresolved(_) => false
          case _                     => true
      )
    val groups = labeled.map(_._1.storyGroup).distinct.size
    if groups < 2 then Left(Refusal.InsufficientStories(2, groups))
    else
      val cells =
        labeled.groupBy((i, _) => Cell(i.role, i.score)).toVector.traverse { (key, rows) =>
          val correct = rows.count(_._2.verdict == Verdict.Correct)
          val incorrect = rows.size - correct
          Probability
            .from((correct.toDouble + 1.0) / (rows.size.toDouble + 2.0))
            .left
            .map(e => Refusal.InvalidCorpus(e.message))
            .map(p => key -> new CellFit(correct, incorrect, rows.map(_._1.storyGroup).toSet, p))
        }
      cells.map { fitted =>
        new Model(
          CalibrationModelId.unsafe(s"participant-calibration:${corpus.checksum.hex}"),
          corpus,
          fitted.toMap,
          corpus.items.zip(corpus.judgments).collect {
            case (i, Judgment(_, Verdict.Unresolved(_), _, _)) => i.id
          }
        )
      }

  /** Each held-out item retains its refusal or prediction, whether its label was resolved or not.
    */
  final class HeldOut private[ParticipantCalibration] (
      val item: Checksum,
      val verdict: Verdict,
      val prediction: Either[Refusal, Probability]
  ):
    override def equals(other: Any): Boolean = other match
      case that: HeldOut =>
        item == that.item && verdict == that.verdict && prediction == that.prediction
      case _ => false
    override def hashCode(): Int = (item, verdict, prediction).##
    override def toString: String = s"HeldOut(${item.hex}, $verdict, $prediction)"

  /** Losses describe labeled predictions only; the denominator and all refusals travel with them.
    */
  final class Fold private[ParticipantCalibration] (
      val story: StoryId,
      val model: Either[Refusal, Model],
      val outcomes: Vector[HeldOut],
      val scored: Int,
      val brier: Option[Double],
      val logLoss: Option[Double]
  ):
    override def equals(other: Any): Boolean = other match
      case that: Fold => story == that.story && model == that.model && outcomes == that.outcomes
      case _          => false
    override def hashCode(): Int = (story, model, outcomes).##
    override def toString: String =
      s"CalibrationFold(${story.value}, scored=$scored/${outcomes.size})"

  /** Refit after removing the entire story group; test labels never enter that fold's model. */
  def leaveStoryOut(data: Corpus): Either[Refusal, Vector[Fold]] =
    val groups = data.items
      .zip(data.judgments)
      .collect {
        case (i, j) if j.verdict == Verdict.Correct || j.verdict == Verdict.Incorrect =>
          i.storyGroup
      }
      .distinct
    if groups.size < 3 then Left(Refusal.InsufficientStories(3, groups.size))
    else
      Right(data.items.map(_.storyGroup).distinct.sortBy(_.value).map { group =>
        val train = data.items.zip(data.judgments).filter(_._1.storyGroup != group)
        val fitted = corpus(train.map(_._1), train.map(_._2)).flatMap(fit)
        val outcomes =
          data.items.zip(data.judgments).filter(_._1.storyGroup == group).map { (i, j) =>
            new HeldOut(i.id, j.verdict, fitted.flatMap(_.predict(i)))
          }
        val scores = outcomes.flatMap { o =>
          val label = o.verdict match
            case Verdict.Correct       => Some(1.0)
            case Verdict.Incorrect     => Some(0.0)
            case Verdict.Unresolved(_) => None
          for p <- o.prediction.toOption; y <- label
          yield (
            (p.value - y) * (p.value - y),
            if y == 1.0 then -math.log(p.value) else -math.log1p(-p.value)
          )
        }
        new Fold(
          group,
          fitted,
          outcomes,
          scores.size,
          Option.when(scores.nonEmpty)(scores.map(_._1).sum / scores.size),
          Option.when(scores.nonEmpty)(scores.map(_._2).sum / scores.size)
        )
      })

  private def producerKey(call: ProviderCall): String = fields(
    Vector(call.provider, call.model, call.version, optionKey(call.promptTemplateVersion)(_.value))
  )

  private def originKey(origin: ChartOrigin): String = origin match
    case ChartOrigin.Hand               => "hand"
    case ChartOrigin.Resolved           => "resolved"
    case ChartOrigin.Parser(p)          => fields(Vector("parser", p.value))
    case ChartOrigin.Agent(p)           => fields(Vector("agent", p.value))
    case ChartOrigin.Converted(from, p) => fields(Vector("converted", from, p.value))

  private def spansKey(spans: SpanSet): String = fields(
    spans.refs.toVector.map(r =>
      fields(
        Vector(optionKey(r.unit)(_.value), r.span.start.toString, r.span.endExclusive.toString)
      )
    )
  )

  private def evidenceKey(e: Evidence): String = fields(
    Vector(
      e.id.value,
      e.extractor.value,
      e.stage.value,
      fields(e.upstream.toVector.map(_.value).sorted),
      optionKey(e.spans)(spansKey)
    )
  )

  private def credenceKey(c: Credence): String = fields(
    Vector(
      c.score.render,
      c.basis match
        case CredenceBasis.Uncalibrated         => "uncalibrated"
        case CredenceBasis.Determined(rule)     => fields(Vector("determined", rule.value))
        case CredenceBasis.Calibrated(p, model) =>
          fields(Vector("calibrated", Score.hexBits(p.value), model.value))
    )
  )

  private def relationKey(r: PropositionRelation): String =
    val source = r.role.source match
      case SourceRole.Numbered(i)      => fields(Vector("numbered", i.toString))
      case SourceRole.Named(n)         => fields(Vector("named", n))
      case SourceRole.Operand(i)       => fields(Vector("operand", i.toString))
      case SourceRole.Extension(ns, n) => fields(Vector("extension", ns, n))
    fields(
      Vector(
        r.from.value,
        source,
        ChartIdentity.targetKey(r.to, _.value),
        optionKey(r.role.normalized)((role, c) => fields(Vector(roleKey(role), credenceKey(c))))
      )
    )

  private def chartKey[C <: CheckState](chart: PropositionChart[C]): String =
    fields(
      Vector(
        ChartIdentity.serialize(chart, _.value),
        fields(
          chart.concepts.toVector
            .sortBy(_._1.value)
            .map((id, c) =>
              fields(
                Vector(
                  id.value,
                  optionKey(c.gloss)(identity),
                  optionKey(c.frame)(f =>
                    fields(Vector(f.namespace, f.id, optionKey(f.senseCredence)(credenceKey)))
                  )
                )
              )
            )
        ),
        fields(chart.relations.map(relationKey).sorted),
        fields(chart.alignments.map { a =>
          val target = a.target match
            case AlignmentTarget.Concepts(ids) =>
              fields(Vector("concepts") ++ ids.toSortedSet.toVector.map(_.value))
            case AlignmentTarget.Relation(r) => fields(Vector("relation", relationKey(r)))
          fields(
            Vector(
              target,
              spansKey(a.spans),
              credenceKey(a.credence),
              a.meta.id.value,
              a.meta.status.toString,
              credenceKey(a.meta.credence),
              fields(a.meta.evidence.toVector.map(evidenceKey).sorted),
              a.meta.provenance.softwareVersion,
              a.meta.provenance.configHash.hex,
              fields(a.meta.provenance.calls.map(NarrativeCompiler.renderProviderCall))
            )
          )
        }.sorted),
        originKey(chart.provenance.origin),
        fields(chart.provenance.receipts.map(NarrativeCompiler.renderProviderCall)),
        fields(
          chart.provenance.alternatives.map((hash, c) => fields(Vector(hash.hex, credenceKey(c))))
        )
      )
    )

  private def optionKey[A](value: Option[A])(render: A => String): String = value match
    case None    => "none"
    case Some(v) => fields(Vector("some", render(v)))

  private def fields(values: Iterable[String]): String = values.map(v => s"${v.length}:$v").mkString
  private def digest(values: Iterable[String]): Checksum = Checksum.ofText(fields(values))
  private def roleKey(role: ParticipantRole): String = role match
    case ParticipantRole.Custom(namespace, label) => fields(Vector("custom", namespace, label))
    case other                                    => fields(Vector("standard", other.toString))
  private def verdictKey(verdict: Verdict): String = verdict match
    case Verdict.Correct            => "correct"
    case Verdict.Incorrect          => "incorrect"
    case Verdict.Unresolved(reason) => fields(Vector("unresolved", reason))
