package storymodel4s.pipeline

import cats.syntax.all.*
import cats.data.NonEmptyVector
import io.circe.{Decoder, Json}
import io.circe.jawn.JawnParser
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path}
import scala.util.control.NonFatal
import storymodel4s.align.*
import storymodel4s.align.bridge.{StorySourceView, TextSourceView}
import storymodel4s.codec.{Canonical, RecallTimingCodecs, StoryModelCodec, TextSourceArtifactCodec}
import storymodel4s.codec.RecallCodecs.given
import storymodel4s.core.*
import storymodel4s.corpus.intake.RecallTimingIntake
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.story.{ModelStatus, StoryValidator, TextModel}

/** Offline ordinary-file adaptation preserves observation identity before any mapping runs. */
object StudyPreparation:
  val Schema: String = "storymodel-study/v1"
  val RecallProfile: String = "recall-baseline-lexical-words/v1"
  val TimingHeader: Vector[String] = Vector("participant", "session", "word", "onset", "end")
  private val parser = new JawnParser(maxValueSize = None, allowDuplicateKeys = false)

  enum Refusal:
    case InvalidConfig, InputRead, UnsafePath, InvalidUtf8, InvalidSource, InvalidRecall
    case DuplicateObservation, InvalidTiming, ForeignTiming, ArtifactMismatch
    case MissingCapability(capability: String)
    case DisclosureRefused

  enum SourceInput:
    case Text(path: String, kind: SurfaceUnitKind)
    case AdmittedText(path: String, kind: SurfaceUnitKind)
    case CheckedText(path: String, kind: SurfaceUnitKind)
    case SurfaceAtlas(path: String, atlas: String, kind: SurfaceUnitKind)
    case StoryModel(path: String)
    case TimedAnnotations(path: String, parts: String)
    case CheckedTimed(path: String)
    case CheckedVideo(path: String)
    case CheckedAV(path: String, packagePath: String, manifest: String, edition: String, blend: Double)
    case Video(path: String)

  /** A request selects a checked adapter, never grants publication by assertion. */
  enum Admission:
    case LocalOnly, ProjectSyntheticControl
    case Sherlock(sourceId: String, lineage: String)

  /** Participant and session identify the observation; a filename never does. */
  final case class RecallInput(
      participant: String,
      session: String,
      path: String,
      timing: Option[String] = None,
      admission: Admission = Admission.LocalOnly
  )
  final case class Config(source: SourceInput, recalls: Vector[RecallInput], profile: String)

  /** A checked join retains the exact imported bytes through the graph's raw text. */
  final class Recall private[StudyPreparation] (
      val participant: String,
      val session: String,
      val sourceEdition: Checksum,
      val observation: Checksum,
      val graph: RecallGraph[Checked],
      val inventory: RecallInventory,
      val timing: Option[RecallTiming],
      val disclosure: RecallDisclosure.Input
  ):
    private def parts = (
      participant,
      session,
      sourceEdition,
      observation,
      graph,
      inventory.digest,
      timing.map(_.digest),
      RecallDisclosure.receiptDescription(disclosure)
    )
    override def equals(other: Any): Boolean = other match
      case r: Recall => parts == r.parts
      case _         => false
    override def hashCode: Int = parts.hashCode
    override def toString: String =
      s"PreparedRecall(${observation.short()}, ${inventory.words.size} words)"

  /** Checked source content retains the replay artifact and its complete edition identity. */
  final class Source private[StudyPreparation] (
      private val underlying: SourceView,
      val representation: SourceRepresentation,
      val artifact: String,
      val captionContext: Option[(storymodel4s.media.CaptionPackage, Double)] = None
  ) extends SourceView:
    private val textPackage: Option[TextSourcePackage] = underlying match
      case text: TextSourceView => Some(text.sourcePackage)
      case _                    => None
    private val model: Option[TextModel[ModelStatus.Validated]] =
      StoryModelCodec.decode(artifact).toOption.flatMap(StoryValidator.validate(_).validated)

    /** Imported caption provenance is retained without reconstructing media execution authority. */
    val mediaCaptions: Option[storymodel4s.codec.MediaCaptionSourceCodec.Imported] =
      storymodel4s.codec.MediaCaptionSourceCodec.decode(artifact).toOption

    /** Checked correspondence declarations remain available for exact package registration. */
    val boundCaptions: Option[storymodel4s.codec.BoundCaptionSourceCodec.Imported] =
      storymodel4s.codec.BoundCaptionSourceCodec.decode(artifact).toOption

    /** Existing source exchange is available only when the input was a checked text package. */
    def textSourceExchange: Option[TextSourceArtifactCodec.Bundle] =
      textPackage.map(TextSourceArtifactCodec.exchange)

    /** Narrative targets are available only from a decoded and revalidated StoryModel. */
    def narrativeModel: Option[TextModel[ModelStatus.Validated]] = model

    val edition: Checksum = Checksum.ofText(artifact)
    val nodes: Vector[NodeSummary] = underlying.nodes
    def node(ref: SourceNodeRef): Option[NodeSummary] = underlying.node(ref)
    def adjacency(layer: RelationLayer): Map[SourceNodeRef, Map[SourceNodeRef, Double]] =
      underlying.adjacency(layer)
    val worldOrder: Option[Map[SourceNodeRef, Int]] = underlying.worldOrder
    val scoringLength: Int = underlying.scoringLength
    override def equals(other: Any): Boolean = other match
      case s: Source => edition == s.edition && representation.digest == s.representation.digest
      case _         => false
    override def hashCode: Int = (edition, representation.digest).hashCode
    override def toString: String = s"PreparedSource(${edition.short()})"

  /** Each attempted participant remains present, even when its input refuses. */
  final case class Outcome(input: RecallInput, result: Either[Refusal, Recall], attempt: Checksum)
  final class Prepared private[StudyPreparation] (
      val source: Source,
      val outcomes: Vector[Outcome],
      val config: Config
  ):
    override def equals(other: Any): Boolean = other match
      case p: Prepared =>
        source.representation.digest == p.source.representation.digest &&
        outcomes == p.outcomes && config == p.config
      case _ => false
    override def hashCode: Int = (source.representation.digest, outcomes, config).hashCode
    override def toString: String = s"PreparedStudy(${outcomes.size} observations)"

  /** Bind the exact checked source artifact, including its raw/canonical lineage. */
  def edition(source: Source): Checksum = source.edition

  private def digest(values: String*): Checksum = ContentAddress.digest(values.toVector)
  private def validLabel(s: String): Boolean = s.nonEmpty && s.length <= 200 &&
    s == s.trim && !s.exists(c => Character.isISOControl(c) || Character.isSurrogate(c))
  private def require(ok: Boolean, error: Refusal): Either[Refusal, Unit] =
    Either.cond(ok, (), error)

  /** Resolve relative inputs under the study directory and refuse links or special files. */
  private[pipeline] def read(root: Path, name: String): Either[Refusal, Array[Byte]] =
    try
      val base = root.toRealPath()
      val relative = Path.of(name)
      val path = base.resolve(relative).normalize()
      if relative.isAbsolute || !path.startsWith(base) then Left(Refusal.UnsafePath)
      else if !Files.exists(path, LinkOption.NOFOLLOW_LINKS) then Left(Refusal.InputRead)
      else if !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
        !path.toRealPath().startsWith(base)
      then Left(Refusal.UnsafePath)
      else Right(Files.readAllBytes(path))
    catch case NonFatal(_) => Left(Refusal.InputRead)

  private[pipeline] def utf8(bytes: Array[Byte]): Either[Refusal, String] =
    val text = new String(bytes, UTF_8)
    Either.cond(text.getBytes(UTF_8).sameElements(bytes), text, Refusal.InvalidUtf8)

  /** Source preparation occurs once; one malformed recall never discards its siblings. */
  def prepare(root: Path, config: Config): Either[Refusal, Prepared] =
    prepare(root, config, Map.empty)

  /** Retained disclosure witnesses must bind the exact ordinary recall file being prepared. */
  def prepare(
      root: Path,
      config: Config,
      disclosures: Map[(String, String), RecallDisclosure.Input]
  ): Either[Refusal, Prepared] =
    for
      _ <- require(config.profile == RecallProfile, Refusal.InvalidConfig)
      _ <- require(
        disclosures.keySet.subsetOf(config.recalls.map(r => (r.participant, r.session)).toSet),
        Refusal.InvalidConfig
      )
      _ <- require(
        config.recalls.forall(r => validLabel(r.participant) && validLabel(r.session)),
        Refusal.InvalidConfig
      )
      _ <- require(
        config.recalls.map(r => (r.participant, r.session)).distinct.size == config.recalls.size,
        Refusal.DuplicateObservation
      )
      source <- config.source match
        case SourceInput.Video(_) =>
          Left(Refusal.MissingCapability("configured-video-source-perception"))
        case SourceInput.Text(path, kind) =>
          read(root, path)
            .flatMap(AdmittedStoryInput.plain(_).left.map(_ => Refusal.InvalidSource))
            .flatMap(TextSourceView.of(_, kind).left.map(_ => Refusal.InvalidSource))
            .map(v =>
              new Source(v, v.representation, TextSourceArtifactCodec.encode(v.sourcePackage))
            )
        case SourceInput.AdmittedText(path, kind) =>
          read(root, path)
            .flatMap(AdmittedStoryInput.load(_).left.map(_ => Refusal.InvalidSource))
            .flatMap(v =>
              TextSourceView.of(v.sourcePackage, kind).left.map(_ => Refusal.InvalidSource)
            )
            .map(v =>
              new Source(v, v.representation, TextSourceArtifactCodec.encode(v.sourcePackage))
            )
        case SourceInput.CheckedText(path, kind) =>
          read(root, path)
            .flatMap(TextSourceArtifactCodec.decode(_).left.map(_ => Refusal.InvalidSource))
            .flatMap(TextSourceView.of(_, kind).left.map(_ => Refusal.InvalidSource))
            .map(v =>
              new Source(v, v.representation, TextSourceArtifactCodec.encode(v.sourcePackage))
            )
        case SourceInput.SurfaceAtlas(path, atlas, kind) =>
          for
            text <- read(root, path)
            generated <- AdmittedStoryInput.plain(text).left.map(_ => Refusal.InvalidSource)
            atlasBytes <- read(root, atlas)
            supplied <- TextSourceCli
              .checkedAtlas(generated, atlasBytes)
              .left
              .map(_ => Refusal.InvalidSource)
            view <- TextSourceView.of(supplied, kind).left.map(_ => Refusal.InvalidSource)
          yield new Source(view, view.representation, TextSourceArtifactCodec.encode(supplied))
        case SourceInput.StoryModel(path) =>
          for
            bytes <- read(root, path)
            text <- utf8(bytes)
            draft <- StoryModelCodec.decode(text).left.map(_ => Refusal.InvalidSource)
            model <- StoryValidator.validate(draft).validated.toRight(Refusal.InvalidSource)
            view = StorySourceView.validated(model)
            representation <- SourceRepresentation
              .of(
                view,
                NonEmptyVector.one(BundleEntry.text(model.source.canonicalChecksum)),
                None,
                view.nodes.map(node => node.ref -> SourceSupportStatus.located(node.support)).toMap
              )
              .left
              .map(_ => Refusal.InvalidSource)
          yield new Source(view, representation, StoryModelCodec.encode(model))
        case SourceInput.TimedAnnotations(path, parts) =>
          for
            csv <- read(root, path)
            descriptor <- read(root, parts)
            artifact <- TimedAnnotationInput
              .encode(csv, descriptor)
              .left
              .map(_ => Refusal.InvalidSource)
            view <- TimedAnnotationInput.decode(artifact).left.map(_ => Refusal.InvalidSource)
          yield new Source(view, view.representation, artifact)
        case SourceInput.CheckedTimed(path) =>
          for
            bytes <- read(root, path)
            artifact <- utf8(bytes)
            view <- TimedAnnotationInput.decode(artifact).left.map(_ => Refusal.InvalidSource)
          yield new Source(view, view.representation, artifact)
        case SourceInput.CheckedAV(path, packagePath, manifest, edition, blend) =>
          for
            bytes <- read(root, path)
            artifact <- utf8(bytes)
            joined <- storymodel4s.codec.BoundCaptionSourceCodec.decode(artifact).left.map(_ => Refusal.InvalidSource)
            _ <- require(blend.isFinite && blend >= 0.0 && blend <= 1.0, Refusal.InvalidConfig)
            pin <- Checksum.from(manifest).left.map(_ => Refusal.InvalidConfig)
            expectedEdition <- EditionId.from(edition).left.map(_ => Refusal.InvalidConfig)
            packageManifest <- read(root, packagePath + "/manifest.json")
            pack <- storymodel4s.media.CaptionPackage.load(root.resolve(packagePath), packageManifest, pin, expectedEdition)
              .left.map(_ => Refusal.InvalidSource)
            media <- VideoSourceInput.project(pack).left.map(_ => Refusal.InvalidSource)
            _ <- require(media.artifact == joined.media.artifact, Refusal.ArtifactMismatch)
          yield new Source(joined.view, joined.view.representation, joined.artifact, Some(pack -> blend))
        case SourceInput.CheckedVideo(path) =>
          for
            bytes <- read(root, path)
            artifact <- utf8(bytes)
            imported <- storymodel4s.codec.MediaCaptionSourceCodec
              .decode(artifact)
              .left
              .map(_ => Refusal.InvalidSource)
          yield new Source(imported.view, imported.view.representation, imported.artifact)
      outcomes = config.recalls.map { input =>
        val transcript = read(root, input.path)
        val timing = input.timing.map(path => read(root, path))
        val lineage = input.admission match
          case Admission.Sherlock(_, path) => Some(read(root, path))
          case _                           => None
        val supplied = disclosures.get((input.participant, input.session))
        def readIdentity(value: Either[Refusal, Array[Byte]]): String =
          value.fold(e => "refused:" + e.toString, b => "bytes:" + Checksum.ofBytes(b).hex)
        val result = for
          bytes <- transcript
          admitted <- admit(input, bytes, lineage)
          disclosure = supplied.getOrElse(admitted)
          _ <- require(
            disclosure.text == admitted.text &&
              (!disclosures.contains(
                (input.participant, input.session)
              ) || input.admission == Admission.LocalOnly),
            Refusal.DisclosureRefused
          )
          value <- recall(input.participant, input.session, edition(source), disclosure, None)
          timed <- timing match
            case None           => Right(value)
            case Some(observed) => observed.flatMap(timingCsv(value, _))
        yield timed
        val attempt = digest(
          "study-input-attempt/v1",
          input.participant,
          input.session,
          input.path,
          readIdentity(transcript),
          input.timing.getOrElse(""),
          timing.fold("not-requested")(readIdentity),
          input.admission.toString,
          lineage.fold("not-required")(readIdentity),
          supplied.fold("not-supplied")(RecallDisclosure.receiptDescription),
          result.fold(
            e => "refused:" + e.toString,
            r => RecallDisclosure.receiptDescription(r.disclosure)
          )
        )
        Outcome(input, result, attempt)
      }
    yield new Prepared(source, outcomes, config)

  private def admit(
      input: RecallInput,
      bytes: Array[Byte],
      lineage: Option[Either[Refusal, Array[Byte]]]
  ): Either[Refusal, RecallDisclosure.Input] =
    input.admission match
      case Admission.LocalOnly               => utf8(bytes).map(RecallDisclosure.local)
      case Admission.ProjectSyntheticControl =>
        utf8(bytes).flatMap(
          RecallDisclosure.projectSynthetic(_).left.map(_ => Refusal.DisclosureRefused)
        )
      case Admission.Sherlock(source, _) =>
        lineage
          .getOrElse(Left(Refusal.DisclosureRefused))
          .flatMap(record =>
            RecallDisclosure
              .sherlock(source, bytes, record)
              .left
              .map(_ => Refusal.DisclosureRefused)
          )

  private def recall(
      participant: String,
      session: String,
      edition: Checksum,
      disclosure: RecallDisclosure.Input,
      suppliedTiming: Option[String]
  ): Either[Refusal, Recall] =
    for
      _ <- require(validLabel(participant) && validLabel(session), Refusal.InvalidRecall)
      text <- TextSourcePackage.fromText(disclosure.text).left.map(_ => Refusal.InvalidRecall)
      observation = digest(
        "recall-observation/v1",
        participant,
        session,
        text.source.rawChecksum.hex,
        "surface-lexical-token-parser/v1"
      )
      source <- StorySource
        .fromText(disclosure.text, explicitId = Some(StoryId.unsafe("recall:" + observation.hex)))
        .left
        .map(_ => Refusal.InvalidRecall)
      graph <-
        try Right(RecallSegmenter.segment(source))
        catch case NonFatal(_) => Left(Refusal.InvalidRecall)
      atlas = graph.atlas
      inventory <- RecallInventory
        .of(
          graph,
          SurfaceSequence(atlas).lexicalTokens.map(_.span),
          WordIdPolicy.inputArtifact(observation)
        )
        .left
        .map(_ => Refusal.InvalidRecall)
      timing <- suppliedTiming.traverse(
        RecallTimingCodecs.decode(_, inventory).left.map(_ => Refusal.ForeignTiming)
      )
    yield new Recall(
      participant,
      session,
      edition,
      observation,
      graph,
      inventory,
      timing,
      disclosure
    )

  /** CSV observations attach by exact lexical word and participant/session, never fuzzy matching.
    */
  private def timingCsv(value: Recall, bytes: Array[Byte]): Either[Refusal, Recall] =
    import RecallTiming.{Refusal as _, *}
    for
      table <- RecallTimingIntake.records(bytes).left.map(_ => Refusal.InvalidTiming)
      _ <- require(table.headOption.contains(TimingHeader), Refusal.InvalidTiming)
      rows = table.drop(1)
      _ <- require(
        rows.size == value.inventory.words.size && rows.forall(_.size == 5),
        Refusal.InvalidTiming
      )
      _ <- require(
        rows.forall(r => r(0) == value.participant && r(1) == value.session),
        Refusal.ForeignTiming
      )
      artifact = Checksum.ofBytes(bytes)
      clock = Clock.declared(
        artifact,
        ClockKey.unsafe("study-word-seconds"),
        digest("study-word-timing/v1", value.observation.hex),
        RecordingIdentity.Unestablished,
        Origin.Unestablished
      )
      entries <- rows.zip(value.inventory.words).traverse { (r, word) =>
        for
          _ <- require(
            word.span.slice(value.graph.transcript.canonicalText).contains(r(2)),
            Refusal.ForeignTiming
          )
          onset <- Option
            .when(r(3).nonEmpty)(r(3))
            .traverse(decimalSeconds(_).left.map(_ => Refusal.InvalidTiming))
          end <- Option
            .when(r(4).nonEmpty)(r(4))
            .traverse(decimalSeconds(_).left.map(_ => Refusal.InvalidTiming))
          observation <- (onset, end) match
            case (None, None)    => Right(Observation.Missing(MissingReason.BlankSourceCell))
            case (Some(a), None) => Right(Observation.OnsetOnly(a, Basis.SourceReported(artifact)))
            case (Some(a), Some(b)) =>
              Right(Observation.Interval(a, b, Basis.SourceReported(artifact)))
            case _ => Left(Refusal.InvalidTiming)
        yield Entry(word.id, clock, observation)
      }
      timing <- RecallTiming
        .checked(
          value.inventory,
          clock,
          entries,
          Provenance(
            digest("study-word-correspondence/v1", value.observation.hex, artifact.hex),
            RecordingLink.NotEstablished
          )
        )
        .left
        .map(_ => Refusal.InvalidTiming)
    yield new Recall(
      value.participant,
      value.session,
      value.sourceEdition,
      value.observation,
      value.graph,
      value.inventory,
      Some(timing),
      value.disclosure
    )

  /** The wrapper reuses public graph/timing codecs and reconstructs the inventory from its parser.
    */
  def encode(value: Recall): String = Canonical.print(
    Json.obj(
      "schemaVersion" -> Json.fromString("prepared-recall/v2"),
      "disclosure" -> Canonical
        .parse(RecallDisclosure.receiptDescription(value.disclosure))
        .toOption
        .get,
      "participant" -> Json.fromString(value.participant),
      "session" -> Json.fromString(value.session),
      "sourceEdition" -> Json.fromString(value.sourceEdition.hex),
      "profile" -> Json.fromString(RecallProfile),
      "observation" -> Json.fromString(value.observation.hex),
      "inventory" -> Json.fromString(value.inventory.digest.hex),
      "graph" -> Canonical.parse(Canonical.encode(value.graph)).toOption.get,
      "timing" -> value.timing.fold(Json.Null)(RecallTimingCodecs.toJson)
    )
  )

  /** A source edition is required externally; a self-described package cannot change that join. */
  def decode(text: String, sourceEdition: Checksum): Either[Refusal, Recall] =
    decodeWith(text, sourceEdition, None)

  /** A serialized description is not authority; reconstruction requires the retained input. */
  def decode(
      text: String,
      sourceEdition: Checksum,
      disclosure: RecallDisclosure.Input
  ): Either[Refusal, Recall] =
    decodeWith(text, sourceEdition, Some(disclosure))

  /** The disclosure boundary is checked before returning any recall text for a workspace. */
  def publishRecall(value: Recall): Either[Refusal, String] =
    require(
      RecallDisclosure.canPublishLocalWorkspace(value.disclosure, value.graph.transcript.rawText),
      Refusal.DisclosureRefused
    ).map(_ => encode(value))

  private def decodeWith(
      text: String,
      sourceEdition: Checksum,
      supplied: Option[RecallDisclosure.Input]
  ): Either[Refusal, Recall] =
    for
      json <- parser.parse(text).left.map(_ => Refusal.ArtifactMismatch)
      participant <- field[String](json, "participant")
      session <- field[String](json, "session")
      graphJson <- field[Json](json, "graph")
      graph <- Canonical
        .decodeJson[RecallGraph[Checked]](graphJson)
        .left
        .map(_ => Refusal.InvalidRecall)
      timing <- field[Option[Json]](json, "timing")
      value <- recall(
        participant,
        session,
        sourceEdition,
        supplied.getOrElse(RecallDisclosure.local(graph.transcript.rawText)),
        timing.map(Canonical.print)
      )
      _ <- require(Canonical.parse(encode(value)).contains(json), Refusal.ArtifactMismatch)
    yield value

  private def field[A: Decoder](json: Json, name: String): Either[Refusal, A] =
    json.hcursor.get[A](name).left.map(_ => Refusal.InvalidConfig)

  /** Unknown fields and unsupported profiles refuse before input files are read. */
  def config(text: String): Either[Refusal, Config] =
    for
      json <- parser.parse(text).left.map(_ => Refusal.InvalidConfig)
      _ <- require(
        json.asObject.exists(_.keys.toSet == Set("schemaVersion", "source", "recalls", "profile")),
        Refusal.InvalidConfig
      )
      schema <- field[String](json, "schemaVersion")
      _ <- require(schema == Schema, Refusal.InvalidConfig)
      profile <- field[String](json, "profile")
      s <- field[Json](json, "source")
      format <- field[String](s, "format")
      path <- field[String](s, "path")
      source <-
        if format == "timed-annotations" then
          for
            _ <- require(
              s.asObject.exists(_.keys.toSet == Set("format", "path", "parts")),
              Refusal.InvalidConfig
            )
            parts <- field[String](s, "parts")
          yield SourceInput.TimedAnnotations(path, parts)
        else if format == "checked-timed" then
          require(s.asObject.exists(_.keys.toSet == Set("format", "path")), Refusal.InvalidConfig)
            .map(_ => SourceInput.CheckedTimed(path))
        else if format == "checked-av" then
          for
            _ <- require(s.asObject.exists(_.keys.toSet == Set("format", "path", "package", "manifestSha256", "edition", "blend")), Refusal.InvalidConfig)
            pack <- field[String](s, "package")
            pin <- field[String](s, "manifestSha256")
            edition <- field[String](s, "edition")
            blendText <- field[String](s, "blend")
            blend <- blendText.toDoubleOption.toRight(Refusal.InvalidConfig)
          yield SourceInput.CheckedAV(path, pack, pin, edition, blend)
        else if format == "checked-video" then
          require(s.asObject.exists(_.keys.toSet == Set("format", "path")), Refusal.InvalidConfig)
            .map(_ => SourceInput.CheckedVideo(path))
        else if format == "story-model" then
          require(s.asObject.exists(_.keys.toSet == Set("format", "path")), Refusal.InvalidConfig)
            .map(_ => SourceInput.StoryModel(path))
        else
          for
            kind <- field[String](s, "kind")
            k <- SurfaceUnitKind.values.find(_.toString == kind).toRight(Refusal.InvalidConfig)
            _ <- require(
              s.asObject.exists(
                _.keys.toSet ==
                  (if format == "surface-atlas" then Set("format", "path", "atlas", "kind")
                   else Set("format", "path", "kind"))
              ),
              Refusal.InvalidConfig
            )
            value <- format match
              case "text"          => Right(SourceInput.Text(path, k))
              case "admitted-text" => Right(SourceInput.AdmittedText(path, k))
              case "checked-text"  => Right(SourceInput.CheckedText(path, k))
              case "surface-atlas" =>
                field[String](s, "atlas").map(SourceInput.SurfaceAtlas(path, _, k))
              case "video" => Right(SourceInput.Video(path))
              case _       => Left(Refusal.InvalidConfig)
          yield value
      rows <- field[Vector[Json]](json, "recalls")
      inputs <- rows.traverse { r =>
        for
          _ <- require(
            r.asObject.exists(o =>
              o.keys.toSet == Set(
                "participant",
                "session",
                "path",
                "timing"
              ) || o.keys.toSet == Set("participant", "session", "path", "timing", "admission")
            ),
            Refusal.InvalidConfig
          )
          p <- field[String](r, "participant")
          session <- field[String](r, "session")
          path <- field[String](r, "path")
          timing <- field[Option[String]](r, "timing")
          admission <- r.hcursor
            .get[Option[Json]]("admission")
            .left
            .map(_ => Refusal.InvalidConfig)
            .flatMap {
              case None        => Right(Admission.LocalOnly)
              case Some(value) =>
                field[String](value, "kind").flatMap {
                  case "project-synthetic-control" =>
                    require(
                      value.asObject.exists(_.keys.toSet == Set("kind")),
                      Refusal.InvalidConfig
                    ).map(_ => Admission.ProjectSyntheticControl)
                  case "sherlock" =>
                    for
                      _ <- require(
                        value.asObject.exists(_.keys.toSet == Set("kind", "sourceId", "lineage")),
                        Refusal.InvalidConfig
                      )
                      id <- field[String](value, "sourceId")
                      lineage <- field[String](value, "lineage")
                    yield Admission.Sherlock(id, lineage)
                  case _ => Left(Refusal.InvalidConfig)
                }
            }
        yield RecallInput(p, session, path, timing, admission)
      }
    yield Config(source, inputs, profile)
