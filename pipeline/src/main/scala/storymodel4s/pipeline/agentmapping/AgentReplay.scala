package storymodel4s.pipeline.agentmapping

import io.circe.Json
import java.util.regex.Pattern
import storymodel4s.provider.agent.{ExchangeFailure, ModelReply}

/** Replays one participant's agent mapping from recorded exchanges, following the recorded
  * harness's control flow exactly: per chunk, up to three attempts, each retry adding a fixed
  * strict-JSON suffix; a reply counts when it parses to an object with a `units` array.
  *
  * Why replay is a separate step from scoring: the replies are the evidence, so the library must
  * reproduce from them, with no model call, the same per-unit choices the harness wrote. A retry
  * that the harness made is itself a recorded exchange; one the replay needs but cannot find is an
  * unrecorded call and refuses the participant, never a silent gap.
  */
object AgentReplay:
  val Attempts: Int = 3

  /** The suffix the harness appended on attempt `n` (none on the first). */
  def suffix(attempt: Int): String =
    if attempt == 0 then "" else s"\n\nReply with strictly valid JSON only (attempt $attempt)."

  /** Why replay refused a participant. */
  enum Failure:
    case Unrecorded(participant: String, chunk: Int, attempt: Int)
    case Exchange(participant: String, chunk: Int, attempt: Int, code: String)
    case BadUnitKey(participant: String, chunk: Int)
    case MissingId(participant: String, unit: Int)

  /** What one chunk took: attempts used and whether a reply was accepted. */
  final case class ChunkReceipt(chunk: Int, attempts: Int, accepted: Boolean)

  private val RowRef =
    Pattern.compile("""(?<=[\[,\s])r(\d+)(?=[\],\s])""", Pattern.UNICODE_CHARACTER_CLASS)

  /** The first JSON object (else array) in a reply, as the harness extracted it: strip, drop a code
    * fence, rewrite row references like `r114` to `114`, then parse the outermost brackets.
    */
  def parseReply(text: String): Option[Json] =
    val s0 = FilmOutline.harnessStrip(text)
    val s1 =
      if !s0.startsWith("```") then Some(s0)
      else
        s0.indexOf('\n') match
          case -1 => None
          case nl =>
            val rest = s0.substring(nl + 1)
            Some(rest.lastIndexOf("```") match
              case -1 => rest
              case k  => rest.substring(0, k))
    s1.flatMap { s1v =>
      val s = RowRef.matcher(s1v).replaceAll("$1")
      Vector('{' -> '}', '[' -> ']').iterator
        .map { (open, close) =>
          val i = s.indexOf(open.toInt)
          val j = s.lastIndexOf(close.toInt)
          if i != -1 && j > i then io.circe.jawn.parse(s.substring(i, j + 1)).toOption else None
        }
        .collectFirst { case Some(j) => j }
    }

  /** A unit key as Python's `int()` read it: an integral or truncated number, or a numeric string.
    */
  private[agentmapping] def unitKey(x: Json): Option[Int] =
    x.hcursor.downField("u").focus.flatMap { u =>
      u.asNumber
        .map(_.toDouble)
        .filter(d => d.isFinite && math.abs(d) < Int.MaxValue)
        .map(_.toInt)
        .orElse(u.asString.flatMap(s => FilmOutline.harnessStrip(s).toIntOption))
    }

  /** One chunk's replay: the prompt, attempts used, and the accepted reply's attempt and units.
    * `accepted` is empty when every attempt failed to parse.
    */
  final case class ChunkReplay(
      chunk: Int,
      prompt: String,
      attempts: Int,
      accepted: Option[(Int, Vector[Json])]
  ):
    def receipt: ChunkReceipt = ChunkReceipt(chunk, attempts, accepted.isDefined)

  /** Replay every chunk of one participant, following the harness's attempt loop. `serve` answers
    * one prompt from recordings and never calls a model.
    */
  def chunks(
      name: String,
      outline: String,
      units: Vector[AgentPrompts.RecallUnit],
      chunk: Int,
      serve: String => Either[ExchangeFailure, ModelReply]
  ): Either[Failure, Vector[ChunkReplay]] =
    units
      .grouped(chunk)
      .toVector
      .zipWithIndex
      .foldLeft[Either[Failure, Vector[ChunkReplay]]](
        Right(Vector.empty)
      ) { case (acc, (g, ci)) =>
        acc.flatMap { done =>
          val prompt = AgentPrompts.prompt(outline, units, g.head.index, g.last.index)
          def attempt(n: Int): Either[Failure, ChunkReplay] =
            if n == Attempts then Right(ChunkReplay(ci, prompt, n, None))
            else
              serve(prompt + suffix(n)) match
                case Left(ExchangeFailure.RecordingMissing(_)) =>
                  Left(Failure.Unrecorded(name, ci, n))
                case Left(other)  => Left(Failure.Exchange(name, ci, n, other.code.toString))
                case Right(reply) =>
                  parseReply(reply.text).flatMap(
                    _.hcursor.get[Vector[Json]]("units").toOption
                  ) match
                    case Some(items) => Right(ChunkReplay(ci, prompt, n + 1, Some(n -> items)))
                    case None        => attempt(n + 1)
          attempt(0).map(done :+ _)
        }
      }

  /** Each answered unit's last reply item (a later chunk overwrites an earlier one, as the harness
    * did), with the chunk and attempt that produced it.
    */
  def answered(
      name: String,
      replays: Vector[ChunkReplay]
  ): Either[Failure, Map[Int, (Json, ChunkReplay, Int)]] =
    replays.foldLeft[Either[Failure, Map[Int, (Json, ChunkReplay, Int)]]](Right(Map.empty)) {
      (acc, r) =>
        r.accepted.fold(acc) { (attempt, items) =>
          items.foldLeft(acc) { (m, x) =>
            m.flatMap(mm =>
              unitKey(x)
                .map(k => mm.updated(k, (x, r, attempt)))
                .toRight(
                  Failure.BadUnitKey(name, r.chunk)
                )
            )
          }
        }
    }

  /** Replay one participant into the reply file the arm import reads. A unit the replies never
    * answered is left out and listed in `missing` (the arm import records a provider failure); a
    * reply item without a `narrative` field keeps that absence (the arm import records invalid
    * output). Only an explicit null reaches the arm as a null.
    */
  def participant(
      name: String,
      outline: String,
      units: Vector[AgentPrompts.RecallUnit],
      ids: Map[Int, String],
      chunk: Int,
      serve: String => Either[ExchangeFailure, ModelReply]
  ): Either[Failure, (Json, Vector[ChunkReceipt])] =
    for
      replays <- chunks(name, outline, units, chunk, serve)
      out <- answered(name, replays)
      rows <- units.foldLeft[Either[Failure, Vector[Json]]](Right(Vector.empty)) { (acc, u) =>
        acc.flatMap { done =>
          ids.get(u.index).toRight(Failure.MissingId(name, u.index)).map { id =>
            out.get(u.index).fold(done) { (item, _, _) =>
              done :+ Json.fromFields(
                Vector("index" -> Json.fromInt(u.index), "id" -> Json.fromString(id)) ++
                  item.hcursor.downField("narrative").focus.map("narrative" -> _)
              )
            }
          }
        }
      }
    yield Json.obj(
      "participant" -> Json.fromString(name),
      "missing" -> Json.fromValues(
        units.filterNot(u => out.contains(u.index)).map(u => Json.fromInt(u.index))
      ),
      "units" -> Json.fromValues(rows)
    ) -> replays.map(_.receipt)
