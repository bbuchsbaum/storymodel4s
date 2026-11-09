package storymodel4s.pipeline.agentmapping

import io.circe.Json
import storymodel4s.provider.agent.{ExchangeFailure, ModelReply}

/** The full v2 localization answer for each recall unit, read from recorded replies before any
  * projection.
  *
  * Why: the harness kept only `narrative` and wrote a missing unit as null, so an omitted unit, a
  * malformed field and an explicit null were indistinguishable downstream. Here each field keeps
  * exactly what the reply established: absent, explicit null, a valid value, or an invalid value
  * (kept verbatim). Nothing is inferred about the model's intent, and no missing number becomes 0.
  */
object AgentAnswers:

  /** What one reply field established. */
  enum Field[+A]:
    case Absent
    case Null
    case Value(value: A)
    case Invalid(raw: Json)

  /** One self-reported narrative alternative. `p` is kept as the reply wrote it; it is a
    * self-report, not a probability, and the alternatives are not a distribution.
    */
  final case class Alternative(scene: Int, p: Json)

  /** A unit the replies answered: each field as established, and where the answer came from. */
  final case class Answer(
      narrative: Field[Int],
      referent: Field[Int],
      alternatives: Field[Vector[Alternative]],
      rows: Field[Vector[Int]],
      confidence: Field[Double],
      chunk: Int,
      attempt: Int,
      prompt: String
  )

  /** One inventory unit's processing outcome. */
  enum Outcome:
    case Answered(answer: Answer)

    /** The chunk's reply parsed but did not include this unit. */
    case Omitted(chunk: Int, attempts: Int)

    /** Every attempt for the unit's chunk failed to parse as a reply. */
    case Unparsable(chunk: Int, attempts: Int)

  private def scene(scenes: Int)(j: Json): Option[Int] =
    j.asNumber.flatMap(_.toInt).filter(s => s >= 1 && s <= scenes)

  private def field[A](item: Json, key: String)(read: Json => Option[A]): Field[A] =
    item.hcursor.downField(key).focus match
      case None            => Field.Absent
      case Some(Json.Null) => Field.Null
      case Some(v)         => read(v).fold(Field.Invalid(v))(Field.Value(_))

  private def alternatives(scenes: Int)(j: Json): Option[Vector[Alternative]] =
    j.asArray.flatMap { xs =>
      val parsed = xs.map(
        _.asArray
          .collect {
            case Vector(s, p) if p.isNumber => scene(scenes)(s).map(Alternative(_, p))
          }
          .flatten
      )
      Option.when(parsed.forall(_.isDefined))(parsed.flatten)
    }

  private def rows(j: Json): Option[Vector[Int]] =
    j.asArray.flatMap { xs =>
      val parsed = xs.map(_.asNumber.flatMap(_.toInt).filter(_ >= 0))
      Option.when(parsed.forall(_.isDefined))(parsed.flatten)
    }

  private def confidence(j: Json): Option[Double] =
    j.asNumber.map(_.toDouble).filter(d => d.isFinite && d >= 0.0 && d <= 1.0)

  /** Read one reply item. Scene numbers must be integral and in `1..scenes`; rows are non-negative
    * integers (whether a row exists is checked against the source later); confidence is a finite
    * number in `[0, 1]`.
    */
  def answer(item: Json, scenes: Int, chunk: Int, attempt: Int, prompt: String): Answer =
    Answer(
      field(item, "narrative")(scene(scenes)),
      field(item, "referent")(scene(scenes)),
      field(item, "alts")(alternatives(scenes)),
      field(item, "rows")(rows),
      field(item, "conf")(confidence),
      chunk,
      attempt,
      prompt
    )

  /** Replay one participant and give every inventory unit's outcome, in unit order, with the chunk
    * replays (for the receipt). `serve` answers from recordings and never calls a model.
    */
  def participant(
      name: String,
      outline: String,
      units: Vector[AgentPrompts.RecallUnit],
      chunk: Int,
      scenes: Int,
      serve: String => Either[ExchangeFailure, ModelReply]
  ): Either[
    AgentReplay.Failure,
    (
        Vector[(AgentPrompts.RecallUnit, Outcome)],
        Vector[
          AgentReplay.ChunkReplay
        ]
    )
  ] =
    for
      replays <- AgentReplay.chunks(name, outline, units, chunk, serve)
      out <- AgentReplay.answered(name, replays)
    yield
      val outcomes = units.grouped(chunk).toVector.zip(replays).flatMap { (group, own) =>
        group.map { u =>
          u -> out
            .get(u.index)
            .fold(
              if own.accepted.isEmpty then Outcome.Unparsable(own.chunk, own.attempts)
              else Outcome.Omitted(own.chunk, own.attempts)
            ) { (item, r, attempt) =>
              Outcome.Answered(
                answer(item, scenes, r.chunk, attempt, r.prompt + AgentReplay.suffix(attempt))
              )
            }
        }
      }
      outcomes -> replays
