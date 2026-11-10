package storymodel4s.pipeline.agentmapping

import storymodel4s.align.*
import storymodel4s.align.bridge.{MediaLocus, TimedSegment, TimedSourceView}
import storymodel4s.core.{Checksum, StorySource, SurfaceSequence}
import storymodel4s.corpus.intake.SherlockAnnotations
import storymodel4s.recall.{RecallInventory, RecallSegmenter, RecallUnitId, WordIdPolicy}

/** Builds checked categorical mapping records from typed agent answers.
  *
  * Why two records: the v2 answer gives two named interpretations per unit, the scene being
  * narrated and the scene the mentioned content belongs to. Each becomes its own record under its
  * own decision policy, so neither can be mistaken for the other or for a scientific reference.
  * Every inventory unit gets an outcome: a choice, an explicit no-assignment, or a processing
  * failure with its cause. No score, mass or probability is created.
  */
object AgentMappingRecord:

  /** Which of the answer's two scene judgements a record carries. */
  enum Interpretation(val field: String, val nullReason: String):
    case Narrative extends Interpretation("narrative", "model reported no narrative assignment")
    case Referent extends Interpretation("referent", "model reported no specific referent")

    def policy: DecisionPolicyId = DecisionPolicyId.unsafe(s"agent-localization/v2:$field")
    def of(answer: AgentAnswers.Answer): AgentAnswers.Field[Int] = this match
      case Narrative => answer.narrative
      case Referent  => answer.referent

  /** The film as mapping targets: a timed source view and the target of each scene ordinal. */
  final case class Film(view: TimedSourceView, scenes: Map[Int, SourceNodeRef])

  /** Who produced the decisions, for the decision stage's receipt. `recallSha256` is the recall
    * word table the units came from.
    */
  final case class Producer(
      provider: String,
      model: String,
      systemSha256: Checksum,
      outlineSha256: Checksum,
      recallSha256: Checksum
  ):
    /** The model as far as it is known: a provider label and a model name, no registered artifact.
      * The digest is of exactly that description, so it cannot be mistaken for the prompt or for a
      * model file.
      */
    def identity: Checksum = Checksum.ofText(s"model-by-name/v1\n$provider\n$model")

  private def locus(m: SherlockAnnotations.MediaLocus): MediaLocus = m match
    case SherlockAnnotations.MediaLocus.Extent(p, iv)  => MediaLocus.Extent(p, iv)
    case SherlockAnnotations.MediaLocus.Instant(p, at) => MediaLocus.Instant(p, at)

  /** Annotation rows as timed leaves and coded scenes as their groups. `artifact` is the hash of
    * the annotation bytes, so the target ids are content-addressed.
    */
  def film(atlas: SherlockAnnotations.Atlas, artifact: Checksum): Either[String, Film] =
    val segments = atlas.rows.map { row =>
      TimedSegment(
        row.row,
        row.description,
        atlas.mediaByRow.get(row.row).map(locus),
        atlas.sceneOf(row.row).map(s => TimedSegment.Group(s.ordinal, s.label))
      )
    }
    val parts = Map(
      atlas.manifest.partA.partId -> atlas.partABundle,
      atlas.manifest.partB.partId -> atlas.partBBundle
    )
    TimedSourceView.of(segments, parts, artifact).left.map(_.message).flatMap { view =>
      val leaves = view.nodes.filter(_.level == 0).sortBy(_.discoursePosition)
      val byRow = atlas.rows.map(_.row).sorted.zip(leaves).toMap
      val scenes =
        atlas.scenes.flatMap(s => byRow.get(s.firstRow).flatMap(_.parent).map(s.ordinal -> _))
      Either.cond(
        leaves.size == atlas.rows.size && scenes.size == atlas.scenes.size &&
          scenes.map(_._2).distinct.size == scenes.size,
        Film(view, scenes.toMap),
        "scenes do not map one-to-one onto source groups"
      )
    }

  /** The recall inventory, rebuilt by the unit manifest's recipe: the word column joined by single
    * spaces, titled `sherlock-recall`, segmented by the standard segmenter.
    */
  def inventory(recallCsv: Array[Byte]): Either[String, RecallInventory] =
    recall(recallCsv).map(_.inventory)

  /** A rebuilt recall: the inventory and each unit's text, in unit order. */
  final case class Recall(inventory: RecallInventory, texts: Vector[String])

  /** Bind the unit table the model was shown to the rebuilt inventory. A unit id encodes only the
    * transcript and the unit's position, so equal ids do not show that the model saw this unit's
    * text. The table text must also equal the unit's text (tabs and line breaks as one space,
    * trimmed, which is how the table was written); otherwise an answer about one stretch of speech
    * would be saved against another.
    */
  def bind(
      units: Vector[AgentPrompts.RecallUnit],
      ids: Map[Int, String],
      recall: Recall
  ): Either[String, Vector[RecallUnitId]] =
    val named = units.map(u => ids.get(u.index))
    val inventoryIds = recall.inventory.units.map(_.id)
    if named.exists(_.isEmpty) then Left("a unit has no manifest id")
    else if named.flatten != inventoryIds.map(_.value) then
      Left("unit table ids do not match the rebuilt recall inventory")
    else
      units
        .zip(recall.texts)
        .find((u, text) => text.replaceAll("[\\t\\n\\r]+", " ").trim != u.text)
        .map((u, _) => s"unit ${u.index}: table text is not the rebuilt unit's text")
        .toLeft(inventoryIds)

  /** The inventory and unit texts, by the unit manifest's recipe (see `inventory`). */
  def recall(recallCsv: Array[Byte]): Either[String, Recall] =
    val content = new String(recallCsv, java.nio.charset.StandardCharsets.UTF_8)
    val lines = content.split('\n').toVector.map(_.stripSuffix("\r")).filter(_.nonEmpty)
    lines match
      case header +: rows if header.split(',').headOption.exists(_.trim == "Words") =>
        val words = rows.flatMap(_.split(',').headOption.map(_.trim).filter(_.nonEmpty))
        for
          _ <- Either.cond(words.nonEmpty, (), "no recall words found")
          source <- StorySource
            .fromText(words.mkString(" "), Some("sherlock-recall"))
            .left
            .map(_.message)
          graph = RecallSegmenter.segment(source)
          inventory <- RecallInventory
            .of(
              graph,
              SurfaceSequence(graph.atlas).lexicalTokens.map(_.span),
              WordIdPolicy.inputArtifact(Checksum.ofBytes(recallCsv))
            )
            .left
            .map(_.message)
        yield Recall(inventory, graph.ordered.map(_.text))
      case _ => Left("expected a header row starting with 'Words'")

  private def ledger(
      interpretation: Interpretation,
      producer: Producer
  ): Either[MappingRefusal, (StageLedger, UnitStageRefs)] =
    ledgerFor(interpretation.policy, producer)

  /** The stage ledger of a recorded categorical producer: the decision stage carries the producer's
    * receipt, and every other stage is marked as not run.
    */
  private[agentmapping] def ledgerFor(
      policy: DecisionPolicyId,
      producer: Producer
  ): Either[MappingRefusal, (StageLedger, UnitStageRefs)] =
    for
      identity <- ProviderIdentity.of(producer.provider, producer.model, producer.identity)
      receipt <- StageReceipt.of(
        Stage.Decision,
        policy.value,
        producer.systemSha256,
        Vector(producer.outlineSha256, producer.recallSha256),
        ProviderStatus.Identified(identity)
      )
      entries <- Stage.values.toVector.foldLeft[Either[MappingRefusal, Vector[StageEntry]]](
        Right(Vector.empty)
      ) { (acc, stage) =>
        val provenance =
          if stage == Stage.Decision then
            StageProvenance.unknown(UnknownProvenanceReason.CallerSuppliedDecision, Some(receipt))
          else StageProvenance.unknown(UnknownProvenanceReason.NotRun, None)
        acc.flatMap(done => StageEntry.of(stage, provenance).map(done :+ _))
      }
      built <- StageLedger.of(entries.sortBy(e => (e.stage.ordinal, e.id.digest.hex)))
      refs <- UnitStageRefs.of(
        built,
        built.at(Stage.Inference).head.id,
        built.at(Stage.Candidates).head.id,
        built.at(Stage.Decision).head.id
      )
    yield built -> refs

  private def outcome(
      unit: RecallUnitId,
      interpretation: Interpretation,
      result: AgentAnswers.Outcome,
      film: Film,
      stages: UnitStageRefs
  ): Either[MappingRefusal, UnitOutcome] =
    import AgentAnswers.{Field, Outcome}
    def invalid(detail: String) =
      Right(UnitOutcome.failed(unit, ProcessingFailure.InvalidOutput(detail)))
    result match
      case Outcome.Omitted(chunk, attempts) =>
        Right(
          UnitOutcome.failed(
            unit,
            ProcessingFailure.ProviderFailure(
              s"unit omitted from the accepted reply (chunk $chunk, $attempts attempts)"
            )
          )
        )
      case Outcome.Unparsable(chunk, attempts) =>
        invalid(s"no parsable reply for chunk $chunk after $attempts attempts")
      case Outcome.Answered(answer) =>
        interpretation.of(answer) match
          case Field.Absent     => invalid(s"reply item has no ${interpretation.field} field")
          case Field.Invalid(_) => invalid(s"${interpretation.field} is not a scene in range")
          case Field.Null       =>
            UnitOutcome.categorical(
              unit,
              DecisionRequest.Abstain(interpretation.policy, interpretation.nullReason),
              Vector.empty,
              stages
            )
          case Field.Value(scene) =>
            film.scenes.get(scene) match
              case None      => invalid(s"${interpretation.field} names no scene in the source")
              case Some(ref) =>
                val chosen = Destination.Target(ref)
                UnitOutcome.categorical(
                  unit,
                  DecisionRequest.ExternalDecode(chosen, interpretation.policy),
                  Vector(
                    MappingLink
                      .ungated(chosen, stages, CandidateSetId.categorical(stages.candidates, unit))
                  ),
                  stages
                )

  /** One interpretation's checked record. `outcomes` must list every inventory unit, in order. */
  def record(
      interpretation: Interpretation,
      inventory: RecallInventory,
      film: Film,
      producer: Producer,
      outcomes: Vector[(RecallUnitId, AgentAnswers.Outcome)]
  ): Either[MappingRefusal, MappingResult] =
    val grain = TargetGrain.SingleLevel(1)
    for
      universe <- DeclaredUniverse.of(film.scenes.values.toVector, grain)
      policies <- MappingPolicies.of(
        InferencePolicy.Unspecified("no inference executed; recorded model choice"),
        ContextPolicy.Unspecified("the model read the film packet and the surrounding recall"),
        CandidatePolicy.Unknown("every scene was offered in the prompt; no candidate set computed"),
        ReferencePrior.NotApplicable("categorical choice; no scores"),
        DecisionPolicy.Declared(interpretation.policy),
        universe
      )
      roles <- UnitRoles.of(
        AnalysisGrain.InferenceUnit(inventory.segmentation),
        AnalysisGrain.InferenceUnit(inventory.segmentation),
        AnalysisGrain.Targets(grain)
      )
      built <- ledger(interpretation, producer)
      (stageLedger, stages) = built
      rows <- outcomes.foldLeft[Either[MappingRefusal, Vector[UnitOutcome]]](Right(Vector.empty)) {
        case (acc, (unit, result)) =>
          acc.flatMap(done => outcome(unit, interpretation, result, film, stages).map(done :+ _))
      }
      result <- MappingResult.checked(
        inventory,
        film.view.representation,
        policies,
        roles,
        stageLedger,
        rows
      )
    yield result
