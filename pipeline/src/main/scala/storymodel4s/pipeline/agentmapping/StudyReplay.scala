package storymodel4s.pipeline.agentmapping

import io.circe.Json
import storymodel4s.provider.agent.{ExchangeFailure, ModelReply}

/** Replays one participant-session of any study from recorded exchanges, with prompts taken as
  * recorded and the reply rules named by a protocol.
  *
  * Why: the two research harnesses that produced our recordings read replies by slightly different
  * rules. A replay must reproduce what each harness concluded, unit by unit, or the saved results
  * are not the recorded results. The differing rules are therefore one named value, and everything
  * else (the attempt loop, the retry suffix, the lookup by exact prompt text) is shared. Prompts
  * are opaque: this code renders nothing, so it knows no film, corpus or packet layout. It does
  * know the reply contract's field names (`narrative`, `referent` and their `_film` fields); a
  * study with other judgements would need those named in its export. It refuses a prompt that does
  * not show every unit of the session, because otherwise "the model saw this unit" would rest on
  * whoever exported the prompts.
  */
object StudyReplay:

  /** The rules by which a harness read a reply. */
  enum ReplyProtocol:
    /** The first harness: a unit key is coerced as Python's `int()` did; an answer for a unit
      * outside the chunk asked about is accepted and may overwrite; a key that cannot be read
      * refuses the session; a judgement is its scene field alone.
      */
    case CoercingOpen

    /** The second harness: a unit key must be an integer literal; an item that is not for a unit of
      * the chunk asked about is dropped and counted; a judgement needs both its scene field and its
      * film field.
      */
    case StrictChunked

    /** The second harness in a condition that answers with a time: each judgement is a film field
      * and a time field. Chunk rules are those of `StrictChunked`.
      */
    case StrictChunkedTime

  object ReplyProtocol:
    /** A protocol by the name an export records. Names are versioned, so a change of rules is a new
      * name and an old export cannot be read under new rules by accident.
      */
    def named(name: String): Option[ReplyProtocol] = name match
      case "coercing-open/v1"       => Some(CoercingOpen)
      case "strict-chunked/v1"      => Some(StrictChunked)
      case "strict-chunked-time/v1" => Some(StrictChunkedTime)
      case _                        => None

  /** One recall unit as the study numbers it. `row` is the study's own key, kept verbatim. */
  final case class RecallUnit(u: Int, row: Json, text: String)

  /** One recorded prompt and the units it asked about, `lo` to `hi` inclusive. */
  final case class Chunk(lo: Int, hi: Int, prompt: String)

  /** What one chunk took, and what its accepted reply held besides answers to its own units. */
  final case class Receipt(
      chunk: Int,
      attempts: Int,
      accepted: Boolean,
      itemsOutsideChunk: Int,
      repeatedUnits: Int
  )

  /** Why a session could not be replayed. */
  enum Failure:
    case Unrecorded(chunk: Int, attempt: Int)
    case Exchange(chunk: Int, attempt: Int, code: String)
    case BadUnitKey(chunk: Int)
    case PromptOmitsUnit(chunk: Int, unit: Int)
    case UnitOutsideChunks(unit: Int)
    case ChunkBounds(chunk: Int)

  /** What the replies established for one unit, before any judgement is read from it. */
  enum UnitReply:
    case Answered(item: Json)

    /** The unit's chunk had an accepted reply that did not answer it. */
    case Omitted

    /** No attempt for the unit's chunk gave a usable reply. */
    case UnparsableChunk

  /** One judgement as the reply stated it. Whether a scene exists or is a valid target is not
    * decided here; that needs the source.
    */
  enum Stated:
    case Scene(scene: BigInt, filmStated: Json)
    case Collection(film: BigInt)
    case NoAssignment

    /** A film and a well-formed time in it. Which scene that is needs the film's clip clock. */
    case Time(film: BigInt, time: String)

    /** `raw` is what the reply gave, kept beside the reason when the answer was a time. */
    case Invalid(reason: String, raw: Vector[(String, Json)] = Vector.empty)

  private val IntegerLiteral = "-?[0-9]+".r

  /** An integer written as an integer: `5`, never `5.0`, `5e0`, `"5"` or `true`. */
  private[agentmapping] def strictInteger(j: Json): Option[BigInt] =
    j.asNumber.filter(n => IntegerLiteral.matches(n.toString)).flatMap(_.toBigInt)

  private def unitKey(protocol: ReplyProtocol, item: Json): Option[Int] = protocol match
    case ReplyProtocol.CoercingOpen                                    => AgentReplay.unitKey(item)
    case ReplyProtocol.StrictChunked | ReplyProtocol.StrictChunkedTime =>
      item.asObject
        .flatMap(_("u"))
        .flatMap(strictInteger)
        .filter(_.isValidInt)
        .map(_.toInt)

  /** Read one judgement, `field`, from a reply item. */
  def stated(protocol: ReplyProtocol, item: Json, field: String): Stated =
    val fields = item.asObject
    val scene = fields.flatMap(_(field))
    protocol match
      case ReplyProtocol.CoercingOpen =>
        scene match
          case None                => Stated.Invalid("missing-field")
          case Some(s) if s.isNull => Stated.NoAssignment
          case Some(s)             =>
            s.asNumber
              .flatMap(_.toBigInt)
              .fold(Stated.Invalid("invalid-scene"))(
                Stated.Scene(_, Json.Null)
              )
      case ReplyProtocol.StrictChunked =>
        (scene, fields.flatMap(_(s"${field}_film"))) match
          case (Some(s), Some(film)) =>
            strictInteger(s) match
              case Some(n)           => Stated.Scene(n, film)
              case None if !s.isNull => Stated.Invalid("invalid-scene")
              case None              =>
                strictInteger(film) match
                  case Some(f)             => Stated.Collection(f)
                  case None if film.isNull => Stated.NoAssignment
                  case None                => Stated.Invalid("invalid-film")
          case _ => Stated.Invalid("missing-field")
      case ReplyProtocol.StrictChunkedTime =>
        (fields.flatMap(_(s"${field}_film")), fields.flatMap(_(s"${field}_time"))) match
          case (Some(film), Some(time)) => timeStated(film, time)
          case _                        => Stated.Invalid("missing-field")

  /** One judgement of a time condition, from its film and time values. No time is a film alone, or
    * no assignment when the film is null too. A time must be well formed and must come with an
    * integer film; otherwise the judgement is invalid, and the raw values are kept.
    */
  def timeStated(film: Json, time: Json): Stated =
    if time.isNull then
      strictInteger(film) match
        case Some(f)             => Stated.Collection(f)
        case None if film.isNull => Stated.NoAssignment
        case None                => Stated.Invalid("invalid-film")
    else
      val raw = Vector("film_stated" -> film, "time" -> time)
      time.asString.filter(SceneIntervals.parseTime(_).isDefined) match
        case None                   => Stated.Invalid("invalid-time", raw)
        case Some(_) if film.isNull => Stated.Invalid("time-without-film", raw)
        case Some(text)             =>
          strictInteger(film).fold(Stated.Invalid("invalid-film", raw))(Stated.Time(_, text))

  private def failed(reason: String, raw: Vector[(String, Json)] = Vector.empty): Json =
    Json.fromFields(
      Vector("status" -> Json.fromString("failed"), "reason" -> Json.fromString(reason)) ++ raw
    )

  /** A stated judgement in the reply-file form the scorer reads. A time is converted with the
    * film's clip clock: the scene that contains it, the scenes near it, and the raw answer beside
    * them; or, outside the clip, the film alone as `time_outside`.
    */
  def prediction(value: Stated, intervals: Option[SceneIntervals] = None): Json =
    def answered(fields: (String, Json)*): Json =
      Json.fromFields(("status" -> Json.fromString("answered")) +: fields)
    value match
      case Stated.Time(film, time) =>
        val raw = Vector("film_stated" -> Json.fromBigInt(film), "time" -> Json.fromString(time))
        (intervals, SceneIntervals.parseTime(time)) match
          case (Some(table), Some((exact, seconds))) =>
            def rest(near: Vector[Int]) =
              (("near" -> Json.fromValues(near.map(Json.fromInt))) +: raw) :+
                ("seconds" -> Json.fromDoubleOrNull(seconds))
            table.locate(film, exact) match
              case SceneIntervals.Located.UnknownFilm        => failed("unknown-film", raw)
              case SceneIntervals.Located.Scene(scene, near) =>
                answered(
                  (("kind" -> Json.fromString("scene")) +: ("scene" -> Json.fromInt(scene)) +:
                    rest(near))*
                )
              case SceneIntervals.Located.Outside(near) =>
                answered(
                  (("kind" -> Json.fromString("time_outside")) +:
                    ("film" -> Json.fromBigInt(film)) +: rest(near))*
                )
          case _ => failed("no-clip-clock", raw)
      case Stated.Scene(scene, film) =>
        answered(
          "kind" -> Json.fromString("scene"),
          "scene" -> Json.fromBigInt(scene),
          "film_stated" -> film
        )
      case Stated.Collection(film) =>
        answered("kind" -> Json.fromString("film"), "film" -> Json.fromBigInt(film))
      case Stated.NoAssignment         => answered("kind" -> Json.fromString("none"))
      case Stated.Invalid(reason, raw) => failed(reason, raw)

  /** Read a judgement back from its reply-file form; `None` for a failure or an unknown form. It is
    * the inverse of `prediction` on the three kinds of answer.
    */
  def statedOf(prediction: Json): Option[Stated] =
    val c = prediction.hcursor
    if !c.get[String]("status").contains("answered") then None
    else
      c.get[String]("kind").toOption.flatMap {
        case "scene" =>
          c.downField("scene").focus.flatMap(strictInteger).map { n =>
            Stated.Scene(n, c.downField("film_stated").focus.getOrElse(Json.Null))
          }
        case "film" => c.downField("film").focus.flatMap(strictInteger).map(Stated.Collection(_))
        case "none" => Some(Stated.NoAssignment)
        case _      => None
      }

  /** One unit's row of a reply file: both judgements, and the extra fields the condition asked for,
    * each as the reply gave it or null. A unit with no answer has only its two failures.
    */
  def unitRow(
      protocol: ReplyProtocol,
      unit: RecallUnit,
      reply: UnitReply,
      extras: Vector[String],
      intervals: Option[SceneIntervals] = None
  ): Json =
    val head = Vector("u" -> Json.fromInt(unit.u), "row" -> unit.row)
    reply match
      case UnitReply.Answered(item) =>
        Json.fromFields(
          head ++ Vector(
            "referent" -> prediction(stated(protocol, item, "referent"), intervals),
            "narrative" -> prediction(stated(protocol, item, "narrative"), intervals)
          ) ++ extras.map(k => k -> item.asObject.flatMap(_(k)).getOrElse(Json.Null))
        )
      case other =>
        val reason = if other == UnitReply.Omitted then "omitted" else "unparsable-chunk"
        Json.fromFields(head ++ Vector("referent" -> failed(reason), "narrative" -> failed(reason)))

  /** Replay one participant-session. `serve` answers one prompt from recordings and never calls a
    * model; `unitLine` is how a prompt shows one unit, and `chunkLine` how it names the units it
    * asks about.
    */
  def session(
      units: Vector[RecallUnit],
      chunks: Vector[Chunk],
      protocol: ReplyProtocol,
      attempts: Int,
      suffix: Int => String,
      unitLine: RecallUnit => String,
      chunkLine: Chunk => String,
      serve: String => Either[ExchangeFailure, ModelReply]
  ): Either[Failure, (Vector[(RecallUnit, UnitReply)], Vector[Receipt])] =
    def owner(u: Int): Vector[Int] =
      chunks.zipWithIndex.collect { case (c, i) if c.lo <= u && u <= c.hi => i }
    // A unit is shown when its line is a whole line of the prompt. A substring test would pass a
    // truncated text, or a line that happens to occur inside another unit or the film record.
    val shown = chunks.zipWithIndex.iterator.flatMap { (c, i) =>
      val lines = c.prompt.split('\n').toSet
      units.find(u => !lines(unitLine(u))).map(u => Failure.PromptOmitsUnit(i, u.u))
    }.nextOption
    val uncovered = units.find(u => owner(u.u).size != 1).map(u => Failure.UnitOutsideChunks(u.u))
    // The prompt itself says which units it asks about; the recorded bounds must be those.
    val asked = chunks.zipWithIndex.collectFirst {
      case (c, i) if !c.prompt.split('\n').contains(chunkLine(c)) => Failure.ChunkBounds(i)
    }
    // Each chunk's bounds must be the first and last unit it owns, and the chunks must take the
    // units in order. Otherwise shifted bounds would change which answers the strict rules drop.
    val bounds =
      if uncovered.nonEmpty then None
      else
        val owned = chunks.indices.toVector.map(i => units.filter(u => owner(u.u).head == i))
        chunks.indices
          .find { i =>
            owned(i).isEmpty || owned(i).head.u != chunks(i).lo || owned(i).last.u != chunks(i).hi
          }
          .map(Failure.ChunkBounds(_))
          .orElse(Option.when(owned.flatten != units)(Failure.ChunkBounds(0)))
    def attempt(ci: Int, prompt: String, n: Int): Either[Failure, (Option[Vector[Json]], Int)] =
      if n == attempts then Right(None -> n)
      else
        serve(prompt + suffix(n)) match
          case Left(ExchangeFailure.RecordingMissing(_)) => Left(Failure.Unrecorded(ci, n))
          case Left(other)  => Left(Failure.Exchange(ci, n, other.code.toString))
          case Right(reply) =>
            AgentReplay
              .parseReply(reply.text)
              .flatMap(_.hcursor.get[Vector[Json]]("units").toOption) match
              case Some(items) => Right(Some(items) -> (n + 1))
              case None        => attempt(ci, prompt, n + 1)
    type State = (Map[Int, Json], Set[Int], Vector[Receipt])
    def take(state: State, c: Chunk, ci: Int): Either[Failure, State] =
      val (answers, acceptedChunks, receipts) = state
      attempt(ci, c.prompt, 0).flatMap { (items, used) =>
        val start: Either[Failure, (Map[Int, Json], Int, Int)] = Right((answers, 0, 0))
        items
          .getOrElse(Vector.empty)
          .foldLeft(start) { (acc, item) =>
            acc.flatMap { (map, outside, repeated) =>
              val key = unitKey(protocol, item)
              protocol match
                case ReplyProtocol.CoercingOpen =>
                  key
                    .toRight(Failure.BadUnitKey(ci))
                    .map(k =>
                      (map.updated(k, item), outside, repeated + (if map.contains(k) then 1 else 0))
                    )
                case ReplyProtocol.StrictChunked | ReplyProtocol.StrictChunkedTime =>
                  key.filter(k => item.isObject && c.lo <= k && k <= c.hi) match
                    case None    => Right((map, outside + 1, repeated))
                    case Some(k) =>
                      Right(
                        (
                          map.updated(k, item),
                          outside,
                          repeated + (if map.contains(k) then 1 else 0)
                        )
                      )
            }
          }
          .map { (map, outside, repeated) =>
            (
              map,
              if items.isDefined then acceptedChunks + ci else acceptedChunks,
              receipts :+ Receipt(ci, used, items.isDefined, outside, repeated)
            )
          }
      }
    shown.orElse(uncovered).orElse(bounds).orElse(asked) match
      case Some(failure) => Left(failure)
      case None          =>
        val start: Either[Failure, State] = Right((Map.empty, Set.empty, Vector.empty))
        chunks.zipWithIndex
          .foldLeft(start) { case (acc, (c, ci)) => acc.flatMap(take(_, c, ci)) }
          .map { (answers, acceptedChunks, receipts) =>
            units.map { u =>
              u -> answers
                .get(u.u)
                .fold(
                  if acceptedChunks(owner(u.u).head) then UnitReply.Omitted
                  else UnitReply.UnparsableChunk
                )(UnitReply.Answered(_))
            } -> receipts
          }
