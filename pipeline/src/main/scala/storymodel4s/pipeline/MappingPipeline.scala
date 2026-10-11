package storymodel4s.pipeline

import io.circe.Json
import java.nio.file.Path
import storymodel4s.align.*
import storymodel4s.codec.{Canonical, MappingCodecs, MappingExchange, TemporalProjectionCodecs}
import storymodel4s.core.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** One offline execution boundary owns profiles, registrations and both published estimands. */
object MappingPipeline:
  enum Capability:
    case Mapping, Organization, Order
  enum Readiness:
    case Ready
    case Missing(capabilities: Vector[Capability])
  enum Refusal:
    case InvalidConfig, ForeignRegistration
    case MissingCapability(detail: String)
    case Alignment(error: AlignError)
    case Projection(error: MappingRefusal)
    case Temporal(error: TemporalProjection.Refusal)

  /** Network and retry budgets are explicit: this deterministic profile executes locally once. */
  final case class Config(
      profile: String,
      temperature: Double,
      perLevel: Int,
      tieBudget: Int,
      requested: Vector[Capability],
      networkRequests: Int = 0,
      retries: Int = 0
  )
  val LexicalProfile: String = "mapping-lexical-pair/v1"
  val OnnxProfile: String = "mapping-onnx-pair/v1"
  val CaptionProfile: String = "mapping-caption-pair/v1"

  /** The facade mints this value by consuming its registration's actual originating evidence. */
  final class Run private[MappingPipeline] (
      val execution: MappingRun.Execution,
      val reference: MappingResult,
      val reconstruction: MappingResult,
      val config: Config,
      val registration: Checksum,
      val caption: Option[CaptionMapping.Registered],
      val onnx: Option[OnnxMappingRegistration.Registered]
  ):
    val readiness: Readiness =
      val missing = config.requested.filterNot(_ == Capability.Mapping).distinct
      if missing.isEmpty then Readiness.Ready else Readiness.Missing(missing)
    val identity: Checksum = ContentAddress.digest(
      Vector(
        "mapping-pipeline/v1",
        registration.hex,
        execution.identity.hex,
        reference.digest.hex,
        reconstruction.digest.hex,
        configText(config)
      )
    )
    override def equals(other: Any): Boolean = other match
      case r: Run => identity == r.identity
      case _      => false
    override def hashCode: Int = identity.hashCode
    override def toString: String = s"MappingPipeline.Run(${identity.short()}, $readiness)"

  private def configText(c: Config): String = Canonical.print(
    Json.obj(
      "profile" -> Json.fromString(c.profile),
      "temperature" -> Json.fromString(c.temperature.toString),
      "perLevel" -> Json.fromInt(c.perLevel),
      "tieBudget" -> Json.fromInt(c.tieBudget),
      "requested" -> Json.fromValues(c.requested.map(v => Json.fromString(v.toString))),
      "networkRequests" -> Json.fromInt(c.networkRequests),
      "retries" -> Json.fromInt(c.retries)
    )
  )

  def validate(config: Config): Either[Refusal, Unit] = Either.cond(
    Set(LexicalProfile, CaptionProfile, OnnxProfile)(
      config.profile
    ) && config.temperature.isFinite &&
      config.temperature > 0 && config.perLevel > 0 && config.tieBudget >= config.perLevel &&
      config.networkRequests == 0 && config.retries == 0 &&
      config.requested.nonEmpty && config.requested.distinct.size == config.requested.size,
    (),
    Refusal.InvalidConfig
  )

  private def candidateConfig(c: Config): Either[Refusal, StrictCandidateConfig] =
    StrictCandidateConfig
      .of(c.perLevel, TieBudgetRequest.AtMost(c.tieBudget), false, None)
      .left
      .map(e => Refusal.Alignment(AlignError.InvalidConfig("candidates", e.message)))

  /** Local annotation scoring is executed here; supplied score tables cannot acquire its identity.
    */
  def lexical(
      recall: RecallGraph[Checked],
      view: SourceView,
      inventory: RecallInventory,
      source: SourceRepresentation,
      config: Config
  ): Either[Refusal, Run] =
    for
      _ <- validate(config)
      _ <- Either.cond(config.profile == LexicalProfile, (), Refusal.InvalidConfig)
      candidatesConfig <- candidateConfig(config)
      candidates <- StrictCandidateGenerator
        .canonicalLevel(StrictSemanticChannel.Lexical, candidatesConfig, recall, view, 0)
        .left
        .map(e => Refusal.Alignment(AlignError.InvalidConfig("candidates", e.message)))
      model <- StrictCostModel
        .of(
          StrictSemanticChannel.Lexical,
          weights = CostWeights.semanticOnly,
          functionPrior = FunctionPrior.none,
          externalMismatch = 0.0,
          distortionPenalty = 0.0
        )
        .left
        .map(Refusal.Alignment(_))
      evidence <- LocalEvidence
        .compute(recall, view, candidates, model)
        .left
        .map(Refusal.Alignment(_))
      registration = ContentAddress.digest(
        Vector(
          "executed-canonical-lexical/v1",
          evidence.identity.checksum.hex,
          source.digest.hex,
          inventory.digest.hex,
          configText(config)
        )
      )
      run <- execute(recall, view, inventory, source, evidence, config, registration, None, None)
    yield run

  /** Consuming the registration itself prevents an unrelated genuine envelope being attached. */
  def captions(registration: CaptionMapping.Registered, config: Config): Either[Refusal, Run] =
    val produced = registration.produced
    val session = produced.session
    for
      _ <- validate(config)
      _ <- Either.cond(config.profile == CaptionProfile, (), Refusal.InvalidConfig)
      expected <- candidateConfig(config)
      _ <- Either.cond(
        produced.config.perLevel == expected.perLevel &&
          produced.config.policy == expected.policy && produced.config.lexicalOverlap == expected.lexicalOverlap &&
          produced.config.space == expected.space,
        (),
        Refusal.ForeignRegistration
      )
      run <- execute(
        session.recall,
        session.view,
        produced.inventory,
        session.source,
        produced.evidence,
        config,
        registration.identity,
        Some(registration),
        None
      )
    yield run

  /** An actual ONNX record or checked replay supplies the exact provider/render origin. */
  def registeredOnnx(
      registration: OnnxMappingRegistration.Registered,
      inventory: RecallInventory,
      config: Config
  ): Either[Refusal, Run] =
    val context = registration.context
    val c = registration.config
    val p = c.pricing
    val parameters = Vector(
      c.querySensitivity.toString,
      c.documentSensitivity.toString,
      c.metric.tag,
      c.emptyPolicy.toString,
      c.candidates.policy.toString,
      c.candidates.perLevel.toString,
      c.candidates.lexicalOverlap.toString,
      c.candidates.space.getOrElse("")
    ) ++
      CostTerm.values.toVector.map(t => p.weights(t).toString) ++
      p.functionPrior.costs.toVector
        .sortBy(_._1.ordinal)
        .flatMap((k, v) => Vector(k.toString, v.toString)) ++
      Vector(
        p.externalFloor.toString,
        p.externalMismatch.toString,
        p.missingSemantic.toString,
        p.distortionPenalty.toString
      )
    val records = Vector(registration.queryRecord, registration.documentRecord).flatMap { r =>
      Vector(
        r.provider.render,
        r.runtimeIdentity,
        r.model.modelId,
        r.model.revision,
        r.model.modelChecksum.hex,
        r.model.tokenizerChecksum.hex,
        r.model.maxTokens.toString,
        r.model.license,
        r.result.receipt.digest.render
      )
    }
    val identity = ContentAddress.digest(
      Vector(
        "onnx-mapping-run-registration/v1",
        context.policy,
        context.representation.digest.hex,
        registration.evidence.identity.checksum.hex,
        registration.basis.toString
      ) ++ parameters ++ records
    )
    for
      _ <- validate(config)
      _ <- Either.cond(config.profile == OnnxProfile, (), Refusal.InvalidConfig)
      expected <- candidateConfig(config)
      _ <- Either.cond(
        c.candidates.perLevel == expected.perLevel &&
          c.candidates.policy == expected.policy,
        (),
        Refusal.ForeignRegistration
      )
      run <- execute(
        context.recall,
        context.source,
        inventory,
        context.representation,
        registration.evidence,
        config,
        identity,
        None,
        Some(registration)
      )
    yield run

  private def execute(
      recall: RecallGraph[Checked],
      view: SourceView,
      inventory: RecallInventory,
      source: SourceRepresentation,
      evidence: LocalEvidence,
      config: Config,
      registration: Checksum,
      caption: Option[CaptionMapping.Registered],
      onnx: Option[OnnxMappingRegistration.Registered]
  ): Either[Refusal, Run] =
    for
      universe <- DeclaredUniverse
        .of(view.nodes.filter(_.level == 0).map(_.ref), TargetGrain.SingleLevel(0))
        .left
        .map(Refusal.Projection(_))
      reference <- LocalReference.Profile
        .of(universe, config.temperature)
        .left
        .map(Refusal.Alignment(_))
      // Presentation order is an explicit reconstruction assumption in this named preset.
      roots = view.nodes.filter(_.parent.isEmpty).sortBy(n => (n.discoursePosition, n.ref.key))
      grouping <- StructuredReconstruction.Grouping
        .of(view, roots.zipWithIndex.map((n, i) => n.ref -> i))
        .left
        .map(Refusal.Alignment(_))
      execution <- MappingRun
        .compute(
          recall,
          view,
          inventory,
          source,
          evidence,
          reference,
          StructuredReconstruction.Profile.sherlock(),
          grouping
        )
        .left
        .map(Refusal.Alignment(_))
      local <- execution.referenceRecord.left.map(Refusal.Projection(_))
      structured <- execution.reconstructionRecord.left.map(Refusal.Projection(_))
    yield new Run(execution, local, structured, config, registration, caption, onnx)

  /** Failure of one observation remains visible and does not discard its successful siblings. */
  final class Outcome private[MappingPipeline] (
      val participant: String,
      val session: String,
      val observation: Option[Checksum],
      val failure: Option[String],
      val run: Option[Run],
      val prepared: Option[StudyPreparation.Recall]
  ):
    private def parts = (participant, session, observation, failure, run, prepared)
    override def equals(other: Any): Boolean = other match
      case o: Outcome => parts == o.parts
      case _          => false
    override def hashCode: Int = parts.hashCode
    override def toString: String = s"MappingOutcome($participant, $session, ${run.nonEmpty})"

  /** The attempted batch remains content-bound even if every participant fails or it is empty. */
  final class Batch private[MappingPipeline] (
      val prepared: StudyPreparation.Prepared,
      val config: Config,
      val outcomes: Vector[Outcome]
  ):
    val identity: Checksum = ContentAddress.digest(
      Vector(
        "mapping-batch-plan/v1",
        prepared.source.edition.hex,
        prepared.source.representation.digest.hex,
        prepared.config.profile,
        configText(config)
      ) ++ prepared.outcomes.map(_.attempt.hex)
    )
    override def equals(other: Any): Boolean = other match
      case b: Batch => identity == b.identity
      case _        => false
    override def hashCode: Int = identity.hashCode
    override def toString: String = s"MappingBatch(${identity.short()}, ${outcomes.size} outcomes)"

  def batch(prepared: StudyPreparation.Prepared, config: Config): Either[Refusal, Batch] =
    validate(config)
      .flatMap(_ =>
        Either.cond(
          config.profile == LexicalProfile,
          (),
          Refusal.MissingCapability(
            "registered evidence required: use the typed ONNX or caption registration entry"
          )
        )
      )
      .map { _ =>
        val outcomes = prepared.outcomes.map { input =>
          input.result match
            case Left(reason) =>
              new Outcome(
                input.input.participant,
                input.input.session,
                None,
                Some(reason.toString),
                None,
                None
              )
            case Right(recall) =>
              lexical(
                recall.graph,
                prepared.source,
                recall.inventory,
                prepared.source.representation,
                config
              ) match
                case Left(reason) =>
                  new Outcome(
                    recall.participant,
                    recall.session,
                    Some(recall.observation),
                    Some(reason.toString),
                    None,
                    Some(recall)
                  )
                case Right(run) =>
                  new Outcome(
                    recall.participant,
                    recall.session,
                    Some(recall.observation),
                    None,
                    Some(run),
                    Some(recall)
                  )
        }
        new Batch(prepared, config, outcomes)
      }

  /** Project both owned mapping members over one declared source selection and optional recall
    * clock.
    */
  def temporal(
      run: Run,
      request: TemporalProjection.Request,
      timing: Option[RecallTiming]
  ): Either[Refusal, (TemporalProjection.Projection, TemporalProjection.Projection)] =
    for
      reference <- TemporalProjection
        .compile(run.reference, TemporalQuery.Measure.NormalizedScoreMass, request, timing)
        .left
        .map(Refusal.Temporal(_))
      reconstruction <- TemporalProjection
        .compile(run.reconstruction, TemporalQuery.Measure.ModelPosterior, request, timing)
        .left
        .map(Refusal.Temporal(_))
    yield reference -> reconstruction

  /** Every attempted observation survives, including missing requests and failed mapping runs. */
  def temporal(
      batch: Batch,
      requests: Map[Checksum, TemporalProjection.Request]
  ): Either[Refusal, Vector[
    (Outcome, Either[Refusal, (TemporalProjection.Projection, TemporalProjection.Projection)])
  ]] =
    val known = batch.outcomes.flatMap(_.observation).toSet
    if !requests.keySet.subsetOf(known) then Left(Refusal.InvalidConfig)
    else
      Right(batch.outcomes.map { outcome =>
        val result = for
          run <- outcome.run.toRight(Refusal.MissingCapability("mapping unavailable"))
          request <- outcome.observation
            .flatMap(requests.get)
            .toRight(Refusal.MissingCapability("temporal request absent"))
          pair <- temporal(run, request, outcome.prepared.flatMap(_.timing))
        yield pair
        outcome -> result
      })

  /** Cohort counts use attempted observations; missing timing never becomes zero exposure. */
  def inspectTemporal(
      batch: Batch,
      requests: Map[Checksum, TemporalProjection.Request]
  ): Either[Refusal, Json] = temporal(batch, requests).map { rows =>
    inspect(batch).deepMerge(
      Json.obj(
        "temporalCompleted" -> Json.fromInt(rows.count(_._2.isRight)),
        "temporalUnavailable" -> Json.fromInt(rows.count(_._2.isLeft)),
        "missingRequests" -> Json.fromInt(
          batch.outcomes.count(o => o.observation.forall(id => !requests.contains(id)))
        ),
        "missingTiming" -> Json.fromInt(
          batch.outcomes.count(o =>
            o.observation.flatMap(requests.get).exists(_.recallWindow.nonEmpty) && o.prepared
              .flatMap(_.timing)
              .isEmpty
          )
        ),
        "clockUncertainty" -> Json.fromString("NotModeled"),
        "jointPath" -> Json.fromString("UnavailableWithoutJointEvidence")
      )
    )
  }

  /** Temporal exports reuse disclosure checks and atomic byte-verified resume for the original
    * pair.
    */
  def exportTemporalResults(
      batch: Batch,
      requests: Map[Checksum, TemporalProjection.Request],
      output: Path,
      resume: Boolean = false
  ): Either[FacadeFailure, Checksum] =
    import cats.syntax.all.*
    for
      rows <- temporal(batch, requests).left.map(FacadeFailure.Mapping(_))
      summary <- inspectTemporal(batch, requests).left.map(FacadeFailure.Mapping(_))
      original <- exchangeArtifacts(batch).left.map(FacadeFailure.Publication(_, output))
      replacements <- rows.traverse { (outcome, result) =>
        result.toOption.traverse { (reference, reconstruction) =>
          val run = outcome.run.get
          MappingExchange
            .encodeTemporal(run.execution, reference, reconstruction)
            .left
            .map(e => FacadeFailure.Publication(e.toString, output))
            .map(bundle =>
              run.identity.hex -> bundle.files
                .map((name, text) => s"${run.identity.hex}--$name" -> text)
            )
        }
      }
      rewritten = replacements.flatten
      files = original._1.filterNot((name, _) =>
        rewritten.exists((prefix, _) => name.startsWith(prefix + "--"))
      ) ++ rewritten.flatMap(_._2)
      metadata = Canonical
        .parse(original._2)
        .toOption
        .get
        .hcursor
        .downField("metadata")
        .focus
        .get
        .deepMerge(
          Json.obj(
            "temporal" -> summary,
            "temporalOutcomes" -> Json.fromValues(rows.map { (outcome, result) =>
              Json.obj(
                "observation" -> outcome.observation.fold(Json.Null)(id => Json.fromString(id.hex)),
                "status" -> Json.fromString(result.fold(_.productPrefix, _ => "Complete")),
                "referenceProjection" -> result.toOption
                  .fold(Json.Null)(p => Json.fromString(p._1.digest.hex)),
                "reconstructionProjection" -> result.toOption
                  .fold(Json.Null)(p => Json.fromString(p._2.digest.hex))
              )
            })
          )
        )
      manifest = StudyArtifacts.manifest("mapping-temporal-batch/v1", files, metadata)
      receipt <- StudyArtifacts
        .publish(output, files, manifest, resume)
        .left
        .map(e => FacadeFailure.Publication(e.toString, output))
    yield receipt

  /** Prepare ordinary local files and execute the named profile without internal proof assembly. */
  def analyze(study: Path, config: Config): Either[FacadeFailure, Batch] =
    val input = study.toAbsolutePath
    for
      bytes <- FacadeFailure.read(input)
      text <- StudyPreparation.utf8(bytes).left.map(FacadeFailure.Preparation(_))
      request <- StudyPreparation.config(text).left.map(FacadeFailure.Preparation(_))
      prepared <- StudyPreparation
        .prepare(input.getParent, request)
        .left
        .map(FacadeFailure.Preparation(_))
      result <- batch(prepared, config).left.map(FacadeFailure.Mapping(_))
    yield result

  /** Inspect counts and requested capabilities without publishing participant or source content. */
  def inspect(batch: Batch): Json = Json.obj(
    "plan" -> Json.fromString(batch.identity.hex),
    "attempts" -> Json.fromInt(batch.outcomes.size),
    "completed" -> Json.fromInt(batch.outcomes.count(_.run.nonEmpty)),
    "failed" -> Json.fromInt(batch.outcomes.count(_.failure.nonEmpty)),
    "missingCapabilities" -> Json.fromValues(
      batch.config.requested
        .filterNot(_ == Capability.Mapping)
        .distinct
        .map(v => Json.fromString(v.toString))
    )
  )

  /** Export the same checked paired results, preserving publication and resume guards. */
  def exportResults(
      batch: Batch,
      output: Path,
      resume: Boolean = false
  ): Either[FacadeFailure, Checksum] =
    publishExchange(batch, output, resume).left.map(FacadeFailure.Publication(_, output))

  private def runMetadata(run: Run): Json = Json.obj(
    "run" -> Json.fromString(run.identity.hex),
    "registration" -> Json.fromString(run.registration.hex),
    "execution" -> Json.fromString(run.execution.identity.hex),
    "sharedEvidence" -> Json.fromString(run.execution.reference.evidence.identity.checksum.hex),
    "config" -> Canonical.parse(configText(run.config)).toOption.get,
    "readiness" -> Json.fromString(run.readiness.toString),
    "members" -> Json.arr(
      Json.obj(
        "member" -> Json.fromString("Reference"),
        "kind" -> Json.fromString(MeasureKind.NormalizedScoreMass.toString),
        "profile" -> Json.fromString(run.execution.reference.profile.fingerprint.hex),
        "record" -> Json.fromString(run.reference.digest.hex)
      ),
      Json.obj(
        "member" -> Json.fromString("Reconstruction"),
        "kind" -> Json.fromString(MeasureKind.ModelPosterior.toString),
        "profile" -> Json.fromString(run.execution.reconstruction.profile.fingerprint.hex),
        "record" -> Json.fromString(run.reconstruction.digest.hex)
      )
    )
  )

  private def runFiles(run: Run): Vector[(String, String)] = Vector(
    s"${run.identity.hex}-reference.json" -> MappingCodecs.encode(run.reference),
    s"${run.identity.hex}-reconstruction.json" -> MappingCodecs.encode(run.reconstruction)
  )

  /** Genuine registered runs publish both members with their owning execution and provider origin.
    */
  def publish(run: Run, output: Path, resume: Boolean): Either[String, Checksum] =
    val files = runFiles(run)
    val manifest = StudyArtifacts.manifest("mapping-run/v1", files, runMetadata(run))
    StudyArtifacts.publish(output, files, manifest, resume).left.map(_.toString)

  private[pipeline] def artifacts(batch: Batch): (Vector[(String, String)], String) =
    def processingLabel(status: ProcessingStatus): String = status match
      case ProcessingStatus.Complete                 => "Complete"
      case ProcessingStatus.ExcludedByInputPolicy(_) => "Excluded"
      case ProcessingStatus.Failed(_)                => "Failed"
    val outcomes = batch.outcomes
    val inputs = outcomes
      .flatMap(_.prepared)
      .map(r => s"${r.observation.hex}-recall.json" -> StudyPreparation.encode(r))
    val files = inputs ++ outcomes.flatMap(_.run).flatMap(runFiles)
    val metadata = Json.obj(
      "plan" -> Json.fromString(batch.identity.hex),
      "sourceEdition" -> Json.fromString(batch.prepared.source.edition.hex),
      "config" -> Canonical.parse(configText(batch.config)).toOption.get,
      "attempts" -> Json.fromValues(
        batch.prepared.outcomes.map(o => Json.fromString(o.attempt.hex))
      ),
      "outcomes" -> Json.fromValues(outcomes.map { o =>
        Json.obj(
          "participant" -> Json.fromString(o.participant),
          "session" -> Json.fromString(o.session),
          "observation" -> o.observation.fold(Json.Null)(v => Json.fromString(v.hex)),
          "execution" -> Json.fromString(if o.run.nonEmpty then "complete" else "failed"),
          "structuralValidity" -> Json.fromString(
            if o.run.nonEmpty then "checked" else "not-produced"
          ),
          "failure" -> o.failure.fold(Json.Null)(Json.fromString),
          "units" -> Json.fromValues(o.prepared.toVector.flatMap(_.inventory.units).map { u =>
            Json.obj(
              "unit" -> Json.fromString(u.id.value),
              "reference" -> Json.fromString(
                o.run
                  .flatMap(_.reference.outcome(u.id))
                  .map(o => processingLabel(o.processing))
                  .getOrElse("Failed")
              ),
              "reconstruction" -> Json.fromString(
                o.run
                  .flatMap(_.reconstruction.outcome(u.id))
                  .map(o => processingLabel(o.processing))
                  .getOrElse("Failed")
              )
            )
          }),
          "readiness" -> o.run.fold(Json.fromString("not-executed"))(r =>
            Json.fromString(r.readiness.toString)
          ),
          "run" -> o.run.fold(Json.Null)(r => Json.fromString(r.identity.hex)),
          "registration" -> o.run.fold(Json.Null)(r => Json.fromString(r.registration.hex)),
          "mapping" -> o.run.fold(Json.Null)(runMetadata)
        )
      })
    )
    files -> StudyArtifacts.manifest("mapping-batch/v1", files, metadata)

  def publish(batch: Batch, output: Path, resume: Boolean): Either[String, Checksum] =
    val (files, manifest) = artifacts(batch)
    StudyArtifacts.publish(output, files, manifest, resume).left.map(_.toString)

  private def exchangeFiles(run: Run): Either[String, Vector[(String, String)]] =
    MappingExchange.encode(run.execution).left.map(_.toString).map { bundle =>
      bundle.files.map { (name, text) => s"${run.identity.hex}--$name" -> text }
    }

  /** Export the original pair and lossless tables, retaining the facade's provider registration. */
  def publishExchange(run: Run, output: Path, resume: Boolean): Either[String, Checksum] =
    exchangeFiles(run).flatMap { files =>
      val manifest = StudyArtifacts.manifest("mapping-exchange-run/v1", files, runMetadata(run))
      StudyArtifacts.publish(output, files, manifest, resume).left.map(_.toString)
    }

  /** Failed attempts stay in the batch manifest even when they have no mapping files. */
  private[pipeline] def exchangeArtifacts(
      batch: Batch
  ): Either[String, (Vector[(String, String)], String)] =
    import cats.syntax.all.*
    for
      _ <- batch.outcomes
        .flatMap(_.prepared)
        .traverse(r => StudyPreparation.publishRecall(r).left.map(_.toString))
      pairs <- batch.outcomes.flatMap(_.run).traverse(exchangeFiles)
    yield
      val (original, manifest) = artifacts(batch)
      val inputs = original.filter(_._1.endsWith("-recall.json"))
      val files = inputs ++ pairs.flatten
      val metadata = Canonical.parse(manifest).toOption.get.hcursor.downField("metadata").focus.get
      files -> StudyArtifacts.manifest("mapping-exchange-batch/v1", files, metadata)

  /** Complete paired exchange through the same source, outcomes and publication boundary. */
  def publishExchange(batch: Batch, output: Path, resume: Boolean): Either[String, Checksum] =
    exchangeArtifacts(batch).flatMap { (files, manifest) =>
      StudyArtifacts.publish(output, files, manifest, resume).left.map(_.toString)
    }

/** A thin offline command shares preparation, execution and publication with the typed API. */
object MappingCli:
  val Template: String =
    """{"profile":"mapping-lexical-pair/v1","temperature":"1.0","perLevel":20,"tieBudget":200,"requested":["Mapping"],"networkRequests":0,"retries":0}"""
  private val parser = new io.circe.jawn.JawnParser(maxValueSize = None, allowDuplicateKeys = false)

  def config(text: String): Either[String, MappingPipeline.Config] =
    import cats.syntax.all.*
    for
      json <- parser.parse(text).left.map(_ => "invalid configuration")
      _ <- Either.cond(
        json.asObject.exists(
          _.keys.toSet == Set(
            "profile",
            "temperature",
            "perLevel",
            "tieBudget",
            "requested",
            "networkRequests",
            "retries"
          )
        ),
        (),
        "unknown or missing config fields"
      )
      profile <- json.hcursor.get[String]("profile").left.map(_ => "invalid configuration")
      temperature <- json.hcursor.get[String]("temperature").left.map(_ => "invalid configuration")
      t <- scala.util.Try(temperature.toDouble).toEither.left.map(_ => "invalid temperature")
      perLevel <- json.hcursor.get[Int]("perLevel").left.map(_ => "invalid configuration")
      budget <- json.hcursor.get[Int]("tieBudget").left.map(_ => "invalid configuration")
      names <- json.hcursor.get[Vector[String]]("requested").left.map(_ => "invalid configuration")
      requested <- names.traverse(n =>
        MappingPipeline.Capability.values.find(_.toString == n).toRight("unknown capability")
      )
      network <- json.hcursor.get[Int]("networkRequests").left.map(_ => "invalid configuration")
      retries <- json.hcursor.get[Int]("retries").left.map(_ => "invalid configuration")
      result = MappingPipeline.Config(profile, t, perLevel, budget, requested, network, retries)
      _ <- MappingPipeline.validate(result).left.map(_.toString)
    yield result

  /** 0: complete/ready; 3: valid partial; 4: valid but capability missing; 2: no valid publication.
    */
  def run(args: List[String], out: String => Unit, err: String => Unit): Int =
    def refuse(reason: String): Int = { err(reason); 2 }
    def execute(
        study: String,
        settings: String,
        output: String,
        resume: Boolean,
        exchange: Boolean
    ): Int =
      val result = for
        configPath <- scala.util
          .Try(Path.of(settings).toAbsolutePath)
          .toEither
          .left
          .map(_ => FacadeFailure.InvalidConfiguration)
        bytes <- FacadeFailure.read(configPath)
        text <- StudyPreparation.utf8(bytes).left.map(FacadeFailure.Preparation(_))
        resolved <- config(text).left.map(_ => FacadeFailure.InvalidConfiguration)
        studyPath <- scala.util
          .Try(Path.of(study))
          .toEither
          .left
          .map(_ => FacadeFailure.InvalidConfiguration)
        outcomes <- MappingPipeline.analyze(studyPath, resolved)
        destination <- scala.util
          .Try(Path.of(output))
          .toEither
          .left
          .map(_ => FacadeFailure.InvalidConfiguration)
        receipt <-
          if exchange then MappingPipeline.exportResults(outcomes, destination, resume)
          else
            MappingPipeline
              .publish(outcomes, destination, resume)
              .left
              .map(FacadeFailure.Publication(_, destination))
      yield (outcomes, receipt)
      result match
        case Left(reason)            => refuse(reason.diagnostic.noSpaces)
        case Right((batch, receipt)) =>
          out(
            Canonical.print(
              MappingPipeline
                .inspect(batch)
                .deepMerge(Json.obj("manifest" -> Json.fromString(receipt.hex)))
            )
          )
          if batch.outcomes.exists(_.failure.nonEmpty) then 3
          else if batch.config.requested.exists(_ != MappingPipeline.Capability.Mapping) then 4
          else 0
    def temporalExecute(
        study: String,
        settings: String,
        requestFile: String,
        output: String,
        resume: Boolean
    ): Int =
      def path(value: String): Either[FacadeFailure, Path] =
        scala.util
          .Try(Path.of(value).toAbsolutePath)
          .toEither
          .left
          .map(_ => FacadeFailure.InvalidConfiguration)
      def text(value: Path): Either[FacadeFailure, String] = FacadeFailure
        .read(value)
        .flatMap(bytes => StudyPreparation.utf8(bytes).left.map(FacadeFailure.Preparation(_)))
      val result = for
        settingsPath <- path(settings)
        settingsText <- text(settingsPath)
        resolved <- config(settingsText).left.map(_ => FacadeFailure.InvalidConfiguration)
        studyPath <- path(study)
        batch <- MappingPipeline.analyze(studyPath, resolved)
        requestPath <- path(requestFile)
        requestText <- text(requestPath)
        request <- TemporalProjectionCodecs
          .decodeRequest(requestText, batch.prepared.source.representation)
          .left
          .map(_ => FacadeFailure.InvalidConfiguration)
        requests = batch.outcomes.flatMap(_.observation).map(_ -> request).toMap
        destination <- path(output)
        receipt <- MappingPipeline.exportTemporalResults(batch, requests, destination, resume)
        summary <- MappingPipeline
          .inspectTemporal(batch, requests)
          .left
          .map(FacadeFailure.Mapping(_))
      yield (batch, receipt, summary)
      result match
        case Left(reason)                     => refuse(reason.diagnostic.noSpaces)
        case Right((batch, receipt, summary)) =>
          out(
            Canonical.print(summary.deepMerge(Json.obj("manifest" -> Json.fromString(receipt.hex))))
          )
          if batch.outcomes.exists(_.failure.nonEmpty) ||
            summary.hcursor.get[Int]("temporalUnavailable").toOption.exists(_ > 0) ||
            summary.hcursor.get[Int]("missingTiming").toOption.exists(_ > 0)
          then 3
          else if batch.config.requested.exists(_ != MappingPipeline.Capability.Mapping) then 4
          else 0
    args match
      case List("temporal", study, config, request, output) =>
        temporalExecute(study, config, request, output, false)
      case List("temporal", study, config, request, output, "--resume") =>
        temporalExecute(study, config, request, output, true)
      case List("template")                   => out(Template); 0
      case List("run", study, config, output) => execute(study, config, output, false, false)
      case List("run", study, config, output, "--resume") =>
        execute(study, config, output, true, false)
      case List("exchange", study, config, output) => execute(study, config, output, false, true)
      case List("exchange", study, config, output, "--resume") =>
        execute(study, config, output, true, true)
      case _ =>
        refuse(
          "mappingRun template | run|exchange STUDY.json CONFIG.json OUTPUT [--resume] | temporal STUDY.json CONFIG.json REQUEST.json OUTPUT [--resume]"
        )

object mappingRun:
  def main(args: Array[String]): Unit =
    val status = MappingCli.run(args.toList, println, System.err.println)
    if status != 0 then sys.exit(status)
