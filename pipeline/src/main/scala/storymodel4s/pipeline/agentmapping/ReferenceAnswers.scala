package storymodel4s.pipeline.agentmapping

import io.circe.{Json, JsonNumber}
import storymodel4s.align.bridge.TimedSourceView
import storymodel4s.core.Checksum
import storymodel4s.provider.agent.{ExchangeFailure, ModelReply}

/** The rows of one source that a reference answer may cite.
  *
  * Why a checked type: whether a row exists is a fact about one source. Built only from a source
  * view, it carries that source's artifact, so an interval checked against it says which source it
  * was checked against.
  */
final class SourceRows private (val artifact: Checksum, val rows: Set[Int]):
  def contains(row: Int): Boolean = rows(row)
  override def equals(other: Any): Boolean = other match
    case that: SourceRows => artifact == that.artifact && rows == that.rows
    case _                => false
  override def hashCode: Int = (artifact, rows).hashCode
  override def toString: String = s"SourceRows(${artifact.short()}, ${rows.size} rows)"

object SourceRows:
  def of(view: TimedSourceView): SourceRows =
    new SourceRows(view.artifact, view.segments.map(_.ordinal).toSet)

/** An inclusive interval of source rows, both ends rows of the source it was checked against.
  *
  * Why a checked type: a row number alone is lawful, but an interval is valid only for a source
  * that has both rows, in order. The interval is born from that check and names its source.
  */
final class RowInterval private (val first: Int, val last: Int, val source: Checksum):
  override def equals(other: Any): Boolean = other match
    case that: RowInterval => first == that.first && last == that.last && source == that.source
    case _                 => false
  override def hashCode: Int = (first, last, source).hashCode
  override def toString: String = s"RowInterval($first, $last)"

object RowInterval:
  /** The interval `first..last` when both are rows of `rows` and `first <= last`. */
  def of(first: Int, last: Int, rows: SourceRows): Option[RowInterval] =
    Option.when(first <= last && rows.contains(first) && rows.contains(last))(
      new RowInterval(first, last, rows.artifact)
    )

/** Answers in the case-study reference schema (case study 1, `llm_local3.py` and `llm_local5.py`):
  * per item a talk type, a hedge, and film references, each with its anchor words, its form (v2) or
  * extent, occurrence, mode and details (v3), and competing hypotheses `{w, iv}`.
  *
  * Why parsed here and not converted: what a reply stated must be kept before any projection. Each
  * field keeps exactly what the reply established, with the same semantics as `AgentAnswers`:
  * absent, explicit null, a valid value, or an invalid value kept verbatim. A weight `w` is a
  * self-report kept as the number the reply wrote; it is never normalized, summed or read as a
  * probability (ADRs 0030 and 0031). Row intervals are checked against the source's rows; an
  * interval outside the source makes its `iv` field invalid. Conversion to align reference types is
  * a separate step.
  */
object ReferenceAnswers:
  import AgentAnswers.Field

  /** The answer schemas, one per harness prompt. */
  enum Schema(val name: String):
    /** `llm_local3.py`: item `talk`, `hedge` as a flag, and references with a `form`. */
    case LocalV2 extends Schema("case-study-reference/v2")

    /** `llm_local5.py`: item `spans` with their talk, `hedge` as a kind with `hedge_words`, and
      * references with `extent`, `occurrence`, `mode` and `details`.
      */
    case LocalV3 extends Schema("case-study-reference/v3")

  object Schema:
    def named(name: String): Option[Schema] = values.find(_.name == name)

  /** A closed vocabulary read from its reply token. */
  sealed trait Token:
    def token: String

  enum Talk(val token: String) extends Token:
    case Episodic extends Talk("episodic")
    case Metamemory extends Talk("metamemory")
    case Commentary extends Talk("commentary")
    case Fragment extends Talk("fragment")

    /** v3 only. */
    case Repair extends Talk("repair")

  enum Form(val token: String) extends Token:
    case Specific extends Form("specific")
    case Extended extends Form("extended")
    case Repeated extends Form("repeated")
    case Summary extends Form("summary")
    case Joint extends Form("joint")

  enum Extent(val token: String) extends Token:
    case Moment extends Extent("moment")
    case Stretch extends Extent("stretch")

  enum Occurrence(val token: String) extends Token:
    case Once extends Occurrence("once")
    case Repeated extends Occurrence("repeated")
    case Generic extends Occurrence("generic")

  enum Mode(val token: String) extends Token:
    case Detailed extends Mode("detailed")
    case Gist extends Mode("gist")

  enum HedgeKind(val token: String) extends Token:
    case Content extends HedgeKind("content")
    case Memory extends HedgeKind("memory")

  enum DetailKind(val token: String) extends Token:
    case Who extends DetailKind("who")
    case Action extends DetailKind("action")
    case Place extends DetailKind("place")
    case Time extends DetailKind("time")
    case Object extends DetailKind("object")
    case Dialogue extends DetailKind("dialogue")
    case Perceptual extends DetailKind("perceptual")

  /** One v3 span of the target. */
  final case class Span(words: Field[String], talk: Field[Talk])

  /** One v3 distinguishing detail. */
  final case class Detail(kind: Field[DetailKind], words: Field[String])

  /** One competing hypothesis: a self-reported weight as written, and its row intervals. */
  final case class Hypothesis(w: Field[JsonNumber], iv: Field[Vector[RowInterval]])

  /** What a reference says about its kind, by schema. */
  enum Shape:
    case Formed(form: Field[Form])
    case Faceted(
        extent: Field[Extent],
        occurrence: Field[Occurrence],
        mode: Field[Mode],
        details: Field[Vector[Detail]]
    )

  final case class Reference(words: Field[String], shape: Shape, alts: Field[Vector[Hypothesis]])

  /** What an item says about its talk type, by schema. */
  enum TalkAnswer:
    case Whole(talk: Field[Talk])
    case Spans(spans: Field[Vector[Span]])

  /** What an item says about hedging, by schema. */
  enum HedgeAnswer:
    case Flag(hedged: Field[Boolean])
    case Kind(kind: Field[HedgeKind], words: Field[String])

  final case class Answer(talk: TalkAnswer, hedge: HedgeAnswer, refs: Field[Vector[Reference]])

  /** One item's processing outcome. `reply` is the reply item exactly as accepted. */
  enum Outcome:
    case Answered(answer: Answer, reply: Json)

    /** The batch's reply parsed but did not include this item. */
    case Omitted

    /** Every attempt for the item's batch failed to parse as a reply. */
    case Unparsable

  private def field[A](item: Json, key: String)(read: Json => Option[A]): Field[A] =
    item.hcursor.downField(key).focus match
      case None            => Field.Absent
      case Some(Json.Null) => Field.Null
      case Some(v)         => read(v).fold(Field.Invalid(v))(Field.Value(_))

  private def all[A](j: Json)(read: Json => Option[A]): Option[Vector[A]] =
    j.asArray.flatMap { xs =>
      val parsed = xs.map(read)
      Option.when(parsed.forall(_.isDefined))(parsed.flatten)
    }

  private def token[A <: Token](values: Seq[A])(j: Json): Option[A] =
    j.asString.flatMap(s => values.find(_.token == s))

  private def words(j: Json): Option[String] = j.asString

  private def weight(j: Json): Option[JsonNumber] =
    j.asNumber.filter(_.toBigDecimal.exists(w => w >= 0 && w <= 1))

  private def row(j: Json): Option[Int] =
    j.asNumber.filter(n => StudyReplay.strictInteger(Json.fromJsonNumber(n)).isDefined).flatMap {
      _.toInt
    }

  private def intervals(rows: SourceRows)(j: Json): Option[Vector[RowInterval]] =
    all(j)(_.asArray.flatMap {
      case Vector(a, b) =>
        for
          first <- row(a)
          last <- row(b)
          iv <- RowInterval.of(first, last, rows)
        yield iv
      case _ => None
    })

  private def hypothesis(rows: SourceRows)(j: Json): Option[Hypothesis] =
    j.asObject.map(_ => Hypothesis(field(j, "w")(weight), field(j, "iv")(intervals(rows))))

  private def detail(j: Json): Option[Detail] =
    j.asObject.map(_ =>
      Detail(field(j, "type")(token(DetailKind.values.toVector)), field(j, "words")(words))
    )

  private def span(j: Json): Option[Span] =
    j.asObject.map(_ =>
      Span(field(j, "words")(words), field(j, "talk")(token(Talk.values.toVector)))
    )

  private def reference(schema: Schema, rows: SourceRows)(j: Json): Option[Reference] =
    j.asObject.map { _ =>
      val shape = schema match
        case Schema.LocalV2 => Shape.Formed(field(j, "form")(token(Form.values.toVector)))
        case Schema.LocalV3 =>
          Shape.Faceted(
            field(j, "extent")(token(Extent.values.toVector)),
            field(j, "occurrence")(token(Occurrence.values.toVector)),
            field(j, "mode")(token(Mode.values.toVector)),
            field(j, "details")(all(_)(detail))
          )
      Reference(field(j, "words")(words), shape, field(j, "alts")(all(_)(hypothesis(rows))))
    }

  /** Read one reply item under `schema` against the source's rows. */
  def answer(item: Json, schema: Schema, rows: SourceRows): Answer =
    val v2Talk = Talk.values.toVector.filter(_ != Talk.Repair)
    schema match
      case Schema.LocalV2 =>
        Answer(
          TalkAnswer.Whole(field(item, "talk")(token(v2Talk))),
          HedgeAnswer.Flag(field(item, "hedge")(_.asBoolean)),
          field(item, "refs")(all(_)(reference(schema, rows)))
        )
      case Schema.LocalV3 =>
        Answer(
          TalkAnswer.Spans(field(item, "spans")(all(_)(span))),
          HedgeAnswer.Kind(
            field(item, "hedge")(token(HedgeKind.values.toVector)),
            field(item, "hedge_words")(words)
          ),
          field(item, "refs")(all(_)(reference(schema, rows)))
        )

  // Encoding: every field states what the reply established, and a value is written as read.

  private def enc[A](f: Field[A])(value: A => Json): Json = f match
    case Field.Absent     => Json.obj("status" -> Json.fromString("absent"))
    case Field.Null       => Json.obj("status" -> Json.fromString("null"))
    case Field.Value(v)   => Json.obj("status" -> Json.fromString("value"), "value" -> value(v))
    case Field.Invalid(r) => Json.obj("status" -> Json.fromString("invalid"), "raw" -> r)

  private def tok(t: Token): Json = Json.fromString(t.token)
  private def str(s: String): Json = Json.fromString(s)

  private def encHypothesis(h: Hypothesis): Json = Json.obj(
    "w" -> enc(h.w)(Json.fromJsonNumber),
    "iv" -> enc(h.iv)(ivs =>
      Json.fromValues(ivs.map(iv => Json.arr(Json.fromInt(iv.first), Json.fromInt(iv.last))))
    )
  )

  private def encReference(r: Reference): Json =
    val shape = r.shape match
      case Shape.Formed(form)                           => Vector("form" -> enc(form)(tok))
      case Shape.Faceted(extent, occurrence, mode, det) =>
        Vector(
          "extent" -> enc(extent)(tok),
          "occurrence" -> enc(occurrence)(tok),
          "mode" -> enc(mode)(tok),
          "details" -> enc(det)(ds =>
            Json.fromValues(
              ds.map(d => Json.obj("type" -> enc(d.kind)(tok), "words" -> enc(d.words)(str)))
            )
          )
        )
    Json.fromFields(
      Vector("words" -> enc(r.words)(str)) ++ shape :+
        ("alts" -> enc(r.alts)(hs => Json.fromValues(hs.map(encHypothesis))))
    )

  /** An answer as a bundle stores it. */
  def json(a: Answer): Json =
    val talk = a.talk match
      case TalkAnswer.Whole(t)  => "talk" -> enc(t)(tok)
      case TalkAnswer.Spans(ss) =>
        "spans" -> enc(ss)(xs =>
          Json.fromValues(
            xs.map(s => Json.obj("words" -> enc(s.words)(str), "talk" -> enc(s.talk)(tok)))
          )
        )
    val hedge = a.hedge match
      case HedgeAnswer.Flag(h)        => Vector("hedge" -> enc(h)(Json.fromBoolean))
      case HedgeAnswer.Kind(kind, ws) =>
        Vector("hedge" -> enc(kind)(tok), "hedge_words" -> enc(ws)(str))
    Json.fromFields(
      (talk +: hedge) :+ ("refs" -> enc(a.refs)(rs => Json.fromValues(rs.map(encReference))))
    )

  // Replay.

  /** One recorded batch: the exact prompt sent, and the opaque item ids it asked about. */
  final case class Batch(prompt: String, items: Vector[String])

  /** What one batch took: attempts used, whether a reply was accepted, and how many reply items
    * repeated an earlier id (the last one is kept, as the harness kept it), and how many reply
    * items had an id that is not a string. Such an item is refused: it answers no asked item, where
    * the harness would have read it through `str()`.
    */
  final case class BatchReceipt(
      batch: Int,
      attempts: Int,
      accepted: Boolean,
      repeated: Int,
      nonStringIds: Int
  )

  enum ReplayFailure:
    case Unrecorded(batch: Int, attempt: Int)
    case Exchange(batch: Int, attempt: Int, code: String)
    case RepeatedItem(item: String)

  /** The retry suffix of the reference harness on attempt `n` (none on the first). */
  def suffix(attempt: Int): String =
    if attempt == 0 then "" else s"\n\nReply with strictly valid JSON only ($attempt)."

  val Attempts: Int = 3

  /** Replay recorded batches with no model call: per batch, up to `Attempts` attempts, each retry
    * adding `suffix`; a reply counts when it parses to an object with an `items` array. Items whose
    * string `id` the batch asked about are kept; others are ignored. An attempt the replay needs
    * but cannot find refuses the replay. Every asked item gets an outcome.
    */
  def replay(
      batches: Vector[Batch],
      schema: Schema,
      rows: SourceRows,
      serve: String => Either[ExchangeFailure, ModelReply]
  ): Either[ReplayFailure, (Map[String, Outcome], Vector[BatchReceipt])] =
    val asked = batches.flatMap(_.items)
    asked
      .diff(asked.distinct)
      .headOption
      .map(ReplayFailure.RepeatedItem(_))
      .toLeft(())
      .flatMap { _ =>
        batches.zipWithIndex.foldLeft[
          Either[ReplayFailure, (Map[String, Outcome], Vector[BatchReceipt])]
        ](Right(Map.empty -> Vector.empty)) { case (acc, (batch, b)) =>
          acc.flatMap { (done, receipts) =>
            one(batch, b, serve).map { (attempts, accepted) =>
              val want = batch.items.toSet
              val kept = accepted.toVector.flatten.flatMap { item =>
                item.hcursor.get[String]("id").toOption.filter(want).map(_ -> item)
              }
              val byId = kept.toMap
              val outcomes = batch.items.map { id =>
                id -> byId
                  .get(id)
                  .fold(if accepted.isEmpty then Outcome.Unparsable else Outcome.Omitted) { item =>
                    Outcome.Answered(answer(item, schema, rows), item)
                  }
              }
              (
                done ++ outcomes,
                receipts :+ BatchReceipt(
                  b,
                  attempts,
                  accepted.isDefined,
                  kept.size - byId.size,
                  accepted.toVector.flatten
                    .count(item => item.hcursor.downField("id").focus.exists(!_.isString))
                )
              )
            }
          }
        }
      }

  private def one(
      batch: Batch,
      b: Int,
      serve: String => Either[ExchangeFailure, ModelReply]
  ): Either[ReplayFailure, (Int, Option[Vector[Json]])] =
    def attempt(n: Int): Either[ReplayFailure, (Int, Option[Vector[Json]])] =
      if n == Attempts then Right(n -> None)
      else
        serve(batch.prompt + suffix(n)) match
          case Left(ExchangeFailure.RecordingMissing(_)) => Left(ReplayFailure.Unrecorded(b, n))
          case Left(other)                               =>
            Left(ReplayFailure.Exchange(b, n, other.code.toString))
          case Right(reply) =>
            AgentReplay
              .parseReply(reply.text)
              .flatMap(_.hcursor.get[Vector[Json]]("items").toOption) match
              case Some(items) => Right((n + 1) -> Some(items))
              case None        => attempt(n + 1)
    attempt(0)
