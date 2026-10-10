package storymodel4s.pipeline

import cats.syntax.all.*
import io.circe.{Decoder, Json}
import io.circe.jawn.JawnParser
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path}
import scala.util.control.NonFatal
import storymodel4s.align.*
import storymodel4s.align.bridge.TextSourceView
import storymodel4s.codec.{Canonical, RecallTimingCodecs, TextSourceArtifactCodec}
import storymodel4s.codec.RecallCodecs.given
import storymodel4s.core.*
import storymodel4s.corpus.intake.RecallTimingIntake
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

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

  enum SourceInput:
    case Text(path: String, kind: SurfaceUnitKind)
    case CheckedText(path: String, kind: SurfaceUnitKind)
    case TimedAnnotations(path: String, parts: String)
    case CheckedTimed(path: String)
    case Video(path: String)

  /** Participant and session identify the observation; a filename never does. */
  final case class RecallInput(
      participant: String,
      session: String,
      path: String,
      timing: Option[String] = None
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
      val timing: Option[RecallTiming]
  ):
    private def parts = (
      participant,
      session,
      sourceEdition,
      observation,
      graph,
      inventory.digest,
      timing.map(_.digest)
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
      val artifact: String
  ) extends SourceView:
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
    for
      _ <- require(config.profile == RecallProfile, Refusal.InvalidConfig)
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
            .flatMap(TextSourcePackage.fromUtf8(_).left.map(_ => Refusal.InvalidSource))
            .flatMap(TextSourceView.of(_, kind).left.map(_ => Refusal.InvalidSource))
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
      outcomes = config.recalls.map { input =>
        val transcript = read(root, input.path)
        val timing = input.timing.map(path => read(root, path))
        def readIdentity(value: Either[Refusal, Array[Byte]]): String =
          value.fold(e => "refused:" + e.toString, b => "bytes:" + Checksum.ofBytes(b).hex)
        val attempt = digest(
          "study-input-attempt/v1",
          input.participant,
          input.session,
          input.path,
          readIdentity(transcript),
          input.timing.getOrElse(""),
          timing.fold("not-requested")(readIdentity)
        )
        val result = for
          bytes <- transcript
          text <- utf8(bytes)
          value <- recall(input.participant, input.session, edition(source), text, None)
          timed <- timing match
            case None           => Right(value)
            case Some(observed) => observed.flatMap(timingCsv(value, _))
        yield timed
        Outcome(input, result, attempt)
      }
    yield new Prepared(source, outcomes, config)

  private def recall(
      participant: String,
      session: String,
      edition: Checksum,
      raw: String,
      suppliedTiming: Option[String]
  ): Either[Refusal, Recall] =
    for
      _ <- require(validLabel(participant) && validLabel(session), Refusal.InvalidRecall)
      text <- TextSourcePackage.fromText(raw).left.map(_ => Refusal.InvalidRecall)
      observation = digest(
        "recall-observation/v1",
        participant,
        session,
        text.source.rawChecksum.hex,
        "surface-lexical-token-parser/v1"
      )
      source <- StorySource
        .fromText(raw, explicitId = Some(StoryId.unsafe("recall:" + observation.hex)))
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
    yield new Recall(participant, session, edition, observation, graph, inventory, timing)

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
      Some(timing)
    )

  /** The wrapper reuses public graph/timing codecs and reconstructs the inventory from its parser.
    */
  def encode(value: Recall): String = Canonical.print(
    Json.obj(
      "schemaVersion" -> Json.fromString("prepared-recall/v1"),
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
        graph.transcript.rawText,
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
        else
          for
            kind <- field[String](s, "kind")
            k <- SurfaceUnitKind.values.find(_.toString == kind).toRight(Refusal.InvalidConfig)
            _ <- require(
              s.asObject.exists(_.keys.toSet == Set("format", "path", "kind")),
              Refusal.InvalidConfig
            )
            value <- format match
              case "text"         => Right(SourceInput.Text(path, k))
              case "checked-text" => Right(SourceInput.CheckedText(path, k))
              case "video"        => Right(SourceInput.Video(path))
              case _              => Left(Refusal.InvalidConfig)
          yield value
      rows <- field[Vector[Json]](json, "recalls")
      inputs <- rows.traverse { r =>
        for
          _ <- require(
            r.asObject.exists(_.keys.toSet == Set("participant", "session", "path", "timing")),
            Refusal.InvalidConfig
          )
          p <- field[String](r, "participant")
          session <- field[String](r, "session")
          path <- field[String](r, "path")
          timing <- field[Option[String]](r, "timing")
        yield RecallInput(p, session, path, timing)
      }
    yield Config(source, inputs, profile)
