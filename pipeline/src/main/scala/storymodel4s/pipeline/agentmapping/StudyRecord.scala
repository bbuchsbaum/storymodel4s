package storymodel4s.pipeline.agentmapping

import storymodel4s.align.*
import storymodel4s.align.bridge.TimedSourceView
import storymodel4s.core.Checksum
import storymodel4s.recall.RecallUnitId

/** Turns a study's replayed answers into checked categorical mapping records.
  *
  * Why: the replay says what a reply stated; whether a stated scene exists and may be chosen is a
  * fact about the source. This is where the two meet. Each judgement becomes one outcome for its
  * unit: a choice of a scene, a choice of a film with no particular scene, an explicit
  * non-assignment, or a failure with its reason. The targets come from the source view itself, so a
  * record cannot declare a scene the source does not offer.
  */
object StudyRecord:

  /** One judgement for one unit, decided against the source. */
  enum Judgement:
    case Scene(number: Int, target: SourceNodeRef)

    /** A film with no particular scene. */
    case Collection(number: Int, target: SourceNodeRef)
    case NoAssignment

    /** A scene the source has but does not offer as a target, such as a title card. */
    case NotATarget(scene: Int)
    case UnknownScene(stated: String)
    case UnknownCollection(stated: String)
    case Invalid(reason: String)
    case Omitted
    case UnparsableChunk

    /** The short name used in counts and receipts. */
    def kind: String = this match
      case _: Scene             => "scene"
      case _: Collection        => "film"
      case NoAssignment         => "none"
      case _: NotATarget        => "invalid-scene"
      case _: UnknownScene      => "unknown-scene"
      case _: UnknownCollection => "unknown-film"
      case _: Invalid           => "invalid"
      case Omitted              => "omitted"
      case UnparsableChunk      => "unparsable-chunk"

  /** Decide one stated judgement against the source's scenes and films. */
  def judge(view: TimedSourceView, stated: StudyReplay.Stated): Judgement =
    val targetScenes = view.targetGroups.toSet
    val targetFilms = view.targetCollections.toSet
    stated match
      case StudyReplay.Stated.Scene(n, _) =>
        Option.when(n.isValidInt)(n.toInt).flatMap(k => view.groupRefs.get(k).map(k -> _)) match
          case None                                => Judgement.UnknownScene(n.toString)
          case Some((k, ref)) if targetScenes(ref) => Judgement.Scene(k, ref)
          case Some((k, _))                        => Judgement.NotATarget(k)
      case StudyReplay.Stated.Collection(n) =>
        Option
          .when(n.isValidInt)(n.toInt)
          .flatMap(k => view.collectionRefs.get(k).filter(targetFilms).map(k -> _)) match
          case Some((k, ref)) => Judgement.Collection(k, ref)
          case None           => Judgement.UnknownCollection(n.toString)
      case StudyReplay.Stated.NoAssignment    => Judgement.NoAssignment
      case StudyReplay.Stated.Invalid(reason) => Judgement.Invalid(reason)

  /** One unit's judgement for `field`, from what the replay established for the unit. */
  def judge(
      view: TimedSourceView,
      protocol: StudyReplay.ReplyProtocol,
      reply: StudyReplay.UnitReply,
      field: String
  ): Judgement = reply match
    case StudyReplay.UnitReply.Answered(item) =>
      judge(view, StudyReplay.stated(protocol, item, field))
    case StudyReplay.UnitReply.Omitted         => Judgement.Omitted
    case StudyReplay.UnitReply.UnparsableChunk => Judgement.UnparsableChunk

  /** The targets a record may choose, taken from the source: its target scenes, and its target
    * films when it has a film level.
    */
  def universe(view: TimedSourceView): Either[MappingRefusal, DeclaredUniverse] =
    if view.collectionRefs.isEmpty then
      DeclaredUniverse.of(view.targetGroups, TargetGrain.SingleLevel(1))
    else
      DeclaredUniverse.of(
        view.targetGroups ++ view.targetCollections,
        TargetGrain.Hierarchy(Vector(1, 2))
      )

  /** The decision policy of one judgement of one condition. The condition and reply protocol are
    * part of the name, so records made under different rules are never the same policy.
    */
  def policy(condition: String, protocolName: String, field: String): DecisionPolicyId =
    DecisionPolicyId.unsafe(s"agent-localization:$condition:$protocolName:$field")

  private def outcome(
      unit: RecallUnitId,
      field: String,
      policy: DecisionPolicyId,
      judgement: Judgement,
      stages: UnitStageRefs
  ): Either[MappingRefusal, UnitOutcome] =
    def choose(ref: SourceNodeRef) =
      val chosen = Destination.Target(ref)
      UnitOutcome.categorical(
        unit,
        DecisionRequest.ExternalDecode(chosen, policy),
        Vector(
          MappingLink.ungated(chosen, stages, CandidateSetId.categorical(stages.candidates, unit))
        ),
        stages
      )
    def invalid(detail: String) =
      Right(UnitOutcome.failed(unit, ProcessingFailure.InvalidOutput(detail)))
    judgement match
      case Judgement.Scene(_, ref)      => choose(ref)
      case Judgement.Collection(_, ref) => choose(ref)
      case Judgement.NoAssignment       =>
        UnitOutcome.categorical(
          unit,
          DecisionRequest.Abstain(policy, s"model reported no $field assignment"),
          Vector.empty,
          stages
        )
      case Judgement.NotATarget(scene) =>
        invalid(s"$field names scene $scene, which is not a target")
      case Judgement.UnknownScene(s) =>
        invalid(s"$field names scene $s, which the source does not have")
      case Judgement.UnknownCollection(s) =>
        invalid(s"$field names film $s, which is not a film of the source")
      case Judgement.Invalid(reason) => invalid(s"$field: $reason")
      case Judgement.UnparsableChunk => invalid("no usable reply for the unit's chunk")
      case Judgement.Omitted         =>
        Right(
          UnitOutcome.failed(
            unit,
            ProcessingFailure.ProviderFailure("unit omitted from the accepted reply")
          )
        )

  /** One judgement's checked record for one session. `judgements` lists every unit of the recall,
    * in order.
    */
  def record(
      field: String,
      policy: DecisionPolicyId,
      recall: PreparedRecall,
      view: TimedSourceView,
      producer: AgentMappingRecord.Producer,
      judgements: Vector[Judgement]
  ): Either[MappingRefusal, MappingResult] =
    val inventory = recall.inventory
    if judgements.size != inventory.units.size then Left(MappingRefusal.OutcomeInventoryMismatch)
    else
      for
        declared <- universe(view)
        policies <- MappingPolicies.of(
          InferencePolicy.Unspecified("no inference executed; recorded model choice"),
          ContextPolicy.Unspecified("the model read the film record and the session's recall"),
          CandidatePolicy.Unknown(
            "every target was offered in the prompt; no candidate set computed"
          ),
          ReferencePrior.NotApplicable("categorical choice; no scores"),
          DecisionPolicy.Declared(policy),
          declared
        )
        roles <- UnitRoles.of(
          AnalysisGrain.InferenceUnit(inventory.segmentation),
          AnalysisGrain.InferenceUnit(inventory.segmentation),
          AnalysisGrain.Targets(declared.grain)
        )
        built <- AgentMappingRecord.ledgerFor(policy, producer)
        (ledger, stages) = built
        rows <- inventory.units
          .map(_.id)
          .zip(judgements)
          .foldLeft[Either[MappingRefusal, Vector[UnitOutcome]]](
            Right(Vector.empty)
          ) { case (acc, (unit, j)) =>
            acc.flatMap(done => outcome(unit, field, policy, j, stages).map(done :+ _))
          }
        result <- MappingResult.checked(
          inventory,
          view.representation,
          policies,
          roles,
          ledger,
          rows
        )
      yield result

  /** Who produced a study's decisions, for the decision stage's receipt. */
  def producer(
      provider: String,
      model: String,
      system: String,
      view: TimedSourceView,
      recall: PreparedRecall
  ): AgentMappingRecord.Producer =
    AgentMappingRecord.Producer(
      provider,
      model,
      Checksum.ofText(system),
      view.artifact,
      recall.segmentation.artifactSha256
    )
