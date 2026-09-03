package storymodel4s.pipeline

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal
import storymodel4s.codec.{FeatureMaterializer, FeaturesArtifact, MaterializedFeatures}
import storymodel4s.core.*
import storymodel4s.features.*
import storymodel4s.story.{ModelStatus, StoryModel, StoryValidator}

/** A word-level measure the caller asked the build to lay over the text.
  *
  * Why a closed vocabulary with one open case: the two computed measures need nothing outside the
  * text and are always available; a lexicon is any word→value table the caller supplies, named so
  * two tables of the same kind can be told apart, and identified by its content once loaded.
  */
enum FeatureRequest:
  case TokenLength
  case TypeFrequency
  case Lexicon(path: Path, name: String)

  def render: String = this match
    case TokenLength         => "token-length"
    case TypeFrequency       => "type-frequency"
    case Lexicon(path, name) => s"lexicon=$name@$path"

object FeatureRequest:
  val Flag: String = "--feature"

  /** `token-length`, `type-frequency`, `lexicon=<path>` (the name is the file's stem) or
    * `lexicon=<name>@<path>`.
    */
  def parse(spec: String): Either[PipelineError, FeatureRequest] =
    spec.trim match
      case "token-length"                => Right(TokenLength)
      case "type-frequency"              => Right(TypeFrequency)
      case s if s.startsWith("lexicon=") =>
        val rest = s.stripPrefix("lexicon=")
        val (name, path) = rest.indexOf('@') match
          case -1 => (stem(rest), rest)
          case at => (rest.take(at), rest.drop(at + 1))
        if path.isEmpty || name.isEmpty then
          Left(PipelineError.FeatureRefused(s"lexicon spec needs a path: $spec"))
        else Right(Lexicon(Path.of(path), name))
      case other => Left(PipelineError.FeatureRefused(s"unknown feature spec: $other"))

  /** Split trailing command-line arguments into feature requests (`--feature <spec>` or
    * `--feature=<spec>`, any number) and whatever is left, in order.
    */
  def fromArgs(args: Seq[String]): Either[PipelineError, (Vector[FeatureRequest], Vector[String])] =
    def loop(
        rest: List[String],
        features: Vector[FeatureRequest],
        other: Vector[String]
    ): Either[PipelineError, (Vector[FeatureRequest], Vector[String])] =
      rest match
        case Nil                                   => Right((features, other))
        case a :: tail if a.startsWith(Flag + "=") =>
          parse(a.stripPrefix(Flag + "=")).flatMap(f => loop(tail, features :+ f, other))
        case a :: spec :: tail if a == Flag =>
          parse(spec).flatMap(f => loop(tail, features :+ f, other))
        case a :: Nil if a == Flag =>
          Left(PipelineError.FeatureRefused(s"$Flag needs a spec"))
        case a :: tail => loop(tail, features, other :+ a)
    loop(args.toList, Vector.empty, Vector.empty)

  private def stem(path: String): String =
    val file = path.split('/').lastOption.getOrElse(path)
    file.lastIndexOf('.') match
      case -1 | 0 => file
      case dot    => file.take(dot)

/** Loads a word→value table from a text file: one row per line, `word<TAB>value` or `word,value`,
  * `#` comments and blank lines skipped, and a first row whose value is not a number taken as a
  * header. Everything else is a refusal naming the line, never a silently dropped row.
  */
object LexiconFile:
  def load(path: Path, name: String): Either[PipelineError, LexiconTable] =
    val text =
      try Right(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
      catch
        case NonFatal(error) =>
          Left(
            PipelineError.FeatureRefused(s"cannot read lexicon $name: ${error.getClass.getName}")
          )
    text.flatMap(parse(_, name)).flatMap { rows =>
      LexiconTable
        .of(name, s"word-level values from lexicon $name", None, rows)
        .left
        .map(e => PipelineError.FeatureRefused(s"lexicon $name: ${e.message}"))
    }

  def parse(text: String, name: String): Either[PipelineError, Vector[(String, Double)]] =
    val lines = text.linesIterator.zipWithIndex.toVector
    val rows = lines
      .map((line, i) => (line.trim, i + 1))
      .filter((line, _) => line.nonEmpty && !line.startsWith("#"))
    enum Row:
      case Value(word: String, value: Double)
      case NotANumber(number: Int, cell: String)
      case Refused(number: Int, reason: String)
    val parsed = rows.map { (line, number) =>
      val cells = line.split("[\\t,]", -1).map(_.trim)
      if cells.length < 2 then Row.Refused(number, "expected word and value")
      else
        cells(1).toDoubleOption match
          case Some(v) if v.isFinite => Row.Value(cells(0), v)
          case Some(v)               => Row.Refused(number, s"non-finite value $v")
          case None                  => Row.NotANumber(number, cells(1))
    }
    // A header is a first row whose value cell is not a number at all (`value`, `rating`), and
    // it is a header only when rows follow it. `NaN` and `Infinity` are numbers and are refused,
    // never taken for a header; a non-numeric cell anywhere else is a refusal.
    val body = parsed match
      case Row.NotANumber(_, _) +: tail if tail.nonEmpty => tail
      case all                                           => all
    body.collectFirst {
      case Row.Refused(number, reason)  => (number, reason)
      case Row.NotANumber(number, cell) => (number, s"value '$cell' is not a number")
    } match
      case Some((number, reason)) =>
        Left(PipelineError.FeatureRefused(s"lexicon $name line $number: $reason"))
      case None => Right(body.collect { case Row.Value(word, value) => word -> value })

/** The feature stage of a build: measures each requested feature over the text, reduces it to the
  * sentence and situation grains, and materializes every track onto the draft.
  *
  * Why after the compiler and not inside it: the compiler owns the scientific transformation from
  * text to narrative (ADR 0005) and a measure reads only the surface; the situation grain needs the
  * compiled situations' supports, so the stage runs on the compiled draft. A model built with no
  * feature request carries an empty `features.json`, which says no feature was measured, as
  * distinct from a build that wrote no record.
  */
object FeatureStage:
  val Stage: StageId = StageId.unsafe("features")

  final class Built private[pipeline] (
      val model: StoryModel[ModelStatus.Draft],
      val sidecars: Map[FeatureSpaceId, Array[Byte]],
      val artifact: FeaturesArtifact
  )

  def build(
      draft: StoryModel[ModelStatus.Draft],
      requests: Vector[FeatureRequest]
  ): Either[PipelineError, Built] =
    for
      measures <- requests.foldLeft[Either[PipelineError, Vector[LexicalMeasure]]](
        Right(Vector.empty)
      ) { (acc, request) =>
        acc.flatMap(done => measure(request).map(done :+ _))
      }
      _ <- distinctSpaces(measures)
      tracks <- measures
        .foldLeft[Either[PipelineError, Vector[FeatureTrack[? <: FeatureTarget, Double]]]](
          Right(Vector.empty)
        ) { (acc, m) => acc.flatMap(done => tracksOf(draft, m).map(done ++ _)) }
      materialized <- FeatureMaterializer
        .materialize(draft, tracks)
        .left
        .map(e => PipelineError.FeatureRefused(e.message))
      stamped = stamp(materialized.model, requests, measures)
      _ <- featureLaws(stamped)
      artifact <- FeaturesArtifact
        .of(stamped, materialized.tracks)
        .left
        .map(e => PipelineError.FeatureRefused(e.message))
    yield new Built(stamped, materialized.sidecars, artifact)

  private def measure(request: FeatureRequest): Either[PipelineError, LexicalMeasure] =
    request match
      case FeatureRequest.TokenLength         => Right(TokenLength)
      case FeatureRequest.TypeFrequency       => Right(TypeFrequency)
      case FeatureRequest.Lexicon(path, name) => LexiconFile.load(path, name).map(LexiconMeasure(_))

  private def distinctSpaces(measures: Vector[LexicalMeasure]): Either[PipelineError, Unit] =
    val ids = measures.map(_.space.id)
    Either.cond(
      ids.distinct.size == ids.size,
      (),
      PipelineError.FeatureRefused("two feature requests measure the same space")
    )

  /** The raw token track and its two declared reductions: the mean over each sentence and over each
    * situation in discourse order.
    */
  private def tracksOf(
      draft: StoryModel[ModelStatus.Draft],
      m: LexicalMeasure
  ): Either[PipelineError, Vector[FeatureTrack[? <: FeatureTarget, Double]]] =
    val sequence = SurfaceSequence(draft.atlas)
    val resolver = SupportResolver(
      sequence,
      situation = id => draft.graph.situations.get(id).map(_.support)
    )
    (for
      raw <- TokenTracks.measure(sequence, m)
      sentences <- TokenTracks.perSentence(raw, sequence)
      situations <- TokenTracks.perSituation(raw, resolver, draft.graph.discourseOrder)
    yield Vector(raw, sentences, situations)).left
      .map(e => PipelineError.FeatureRefused(s"${m.space.id.value}: ${e.message}"))

  /** The build receipt gains a `features` stage naming what was measured, when anything was. */
  private def stamp(
      model: StoryModel[ModelStatus.Draft],
      requests: Vector[FeatureRequest],
      measures: Vector[LexicalMeasure]
  ): StoryModel[ModelStatus.Draft] =
    if measures.isEmpty then model
    else
      val digest = ContentAddress.digest(
        "features/v1" +: measures.map(m => s"${m.space.id.value}=${m.identity.hex}")
      )
      val receipt = model.receipt.map(r => r.copy(stages = r.stages :+ (Stage, digest)))
      StoryModel.draft(
        model.source,
        model.atlas,
        model.graph,
        model.hierarchy,
        model.trajectory,
        model.featureSpaces,
        model.sidecars,
        model.featureRefs,
        model.descriptors,
        model.hypotheses,
        model.sensoryProfiles,
        receipt,
        model.schemaVersion
      )

  /** The model's own feature laws over the augmented draft; any of them failing is a refusal, since
    * the compiler's outcome was measured on a draft without these fields.
    */
  private def featureLaws(model: StoryModel[ModelStatus.Draft]): Either[PipelineError, Unit] =
    val violations =
      StoryValidator.validate(model).report.violations.filter(_.law.startsWith("feature."))
    violations.headOption match
      case Some(v) => Left(PipelineError.FeatureRefused(s"${v.law} at ${v.path}: ${v.reason}"))
      case None    => Right(())
