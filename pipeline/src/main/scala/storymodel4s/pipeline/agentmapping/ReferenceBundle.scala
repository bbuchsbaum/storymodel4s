package storymodel4s.pipeline.agentmapping

import io.circe.Json
import storymodel4s.core.Checksum

/** One participant-session's reference answers under one declared producer condition
  * (`storymodel4s.agentmapping.reference-bundle/v1`): each unit's outcome, the reply item as
  * accepted, the answer as parsed against the source's rows, and the batches the units were asked
  * in, with their prompt digests and replay receipts.
  *
  * Why the reply beside the answer: the reply is the evidence and the answer is derived from it.
  * Reading a bundle parses every reply again against the source and requires the stored answer to
  * be exactly that, so neither can change without the other.
  *
  * Why the batches: the producer condition must be rendered, not asserted (ADR 0022). Building or
  * reading a bundle checks the condition against the exact prompts and the session's recall: each
  * unit's `ITEM <id>` line occurs once in its batch's prompt; its `CONTEXT:` line is exactly the
  * texts of the window's preceding units (none for a window of 0); its `TARGET:` line is the unit's
  * own text; and, under opaque ids, every id has its scheme's shape and none encodes its unit's id
  * or position. The checks made are recorded in the bundle, and the rendering identity is a digest
  * of the condition and every batch's prompt digest. Hidden order is not checked against the
  * prompts (it needs the whole pool); it stays an asserted part of the condition, and the bundle
  * says so. A bundle holds unit ids and recall-derived words; keep it outside the repository.
  *
  * The harness's name-manipulation arm (`llm_local5.py --manip names`) rewrites the TARGET text, so
  * its prompts are refused with `TargetMismatch` until a manipulation-aware rendering exists; that
  * is deliberate, since the model did not see the unit's own text.
  */
object ReferenceBundle:
  val Schema: String = "storymodel4s.agentmapping.reference-bundle/v1"

  /** Where a bundle came from. */
  final case class Provenance(
      participant: String,
      session: String,
      condition: RecordedCondition.Declared,
      model: String,
      provider: String,
      answers: ReferenceAnswers.Schema,
      systemSha256: String
  )

  /** One unit: the study's number, its unit id, the opaque item id it was asked under, and its
    * outcome.
    */
  final case class UnitAnswer(u: Int, unit: String, item: String, outcome: ReferenceAnswers.Outcome)

  /** One batch as replayed: its exact prompt and its replay receipt. */
  final case class Recorded(batch: ReferenceAnswers.Batch, receipt: ReferenceAnswers.BatchReceipt)

  /** What the prompts showed: the checks made and the rendering identity they give. Built only by
    * `rendering`, from the prompts themselves.
    */
  final class Rendering private[ReferenceBundle] (val checks: Json, val sha256: Checksum):
    override def equals(other: Any): Boolean = other match
      case that: Rendering => checks == that.checks && sha256 == that.sha256
      case _               => false
    override def hashCode: Int = sha256.hashCode
    override def toString: String = s"Rendering(${sha256.short()})"

  /** Why the prompts do not render the declared condition. Each names the unit where it failed. */
  enum RenderingRefusal:
    case NotRendered(rendering: String)
    case BatchListedTwice(batch: Int)
    case NotInRecall(u: Int)
    case NotInOneBatch(u: Int)
    case ItemLine(u: Int)
    case ContextMissing(u: Int)
    case ContextPresent(u: Int)

    /** The CONTEXT line is not the declared window's preceding units. */
    case ContextMismatch(u: Int)

    /** The TARGET line is not the unit's own text. */
    case TargetMismatch(u: Int)
    case IdEncodesPosition(u: Int)
    case UnknownIdScheme(scheme: String)
    case IdShape(u: Int, scheme: String)
    case OutcomeReceipt(u: Int)

    def message: String = this match
      case NotRendered(r)      => ProducerCondition.Refusal.NotRendered("reference path", r).message
      case BatchListedTwice(b) => s"batch $b is listed twice"
      case NotInRecall(u)      => s"unit $u is not a unit of the session's recall"
      case NotInOneBatch(u)    => s"unit $u: its item is not asked in exactly one batch"
      case ItemLine(u)         => s"unit $u: its ITEM line is not once in its prompt"
      case ContextMissing(u)   =>
        s"unit $u: the window has preceding units but its prompt shows no context"
      case ContextPresent(u) =>
        s"unit $u: the window is the target only but its prompt shows context"
      case ContextMismatch(u) =>
        s"unit $u: its CONTEXT is not the declared window's preceding units"
      case TargetMismatch(u)    => s"unit $u: its TARGET is not the unit's own text"
      case IdEncodesPosition(u) =>
        s"unit $u: its item id encodes its position, under declared opaque ids"
      case UnknownIdScheme(sc) => s"the id scheme $sc has no known shape"
      case IdShape(u, sc)      => s"unit $u: its item id does not have the shape of scheme $sc"
      case OutcomeReceipt(u)   => s"unit $u: its outcome disagrees with its batch receipt"

  /** The case-study harness's id scheme: `"x"` and the first ten hex digits of a salted SHA-256
    * (`opaque()` in `llm_local3.py`).
    */
  val HarnessScheme: String = "sha256-prefix10"

  /** The shape of each known opaque id scheme. */
  val IdShapes: Map[String, scala.util.matching.Regex] = Map(HarnessScheme -> "^x[0-9a-f]{10}$".r)

  /** The CONTEXT text the harness renders for a unit at `position` under `k` preceding units: their
    * texts joined by single spaces, or `(start of recall)` when there are none (`items_for` in
    * `llm_local.py`).
    */
  def contextText(texts: Vector[String], position: Int, k: Int): String =
    texts.slice(math.max(0, position - k), position).mkString(" ") match
      case "" => "(start of recall)"
      case s  => s

  private def str(s: String) = Json.fromString(s)

  private def sourceJson(rows: SourceRows): Json = Json.obj(
    "artifact" -> str(rows.artifact.hex),
    "rows" -> Json.fromValues(rows.rows.toVector.sorted.map(Json.fromInt))
  )

  private def unitJson(x: UnitAnswer): Json =
    val head = Vector("u" -> Json.fromInt(x.u), "unit" -> str(x.unit), "item" -> str(x.item))
    Json.fromFields(head ++ (x.outcome match
      case ReferenceAnswers.Outcome.Answered(answer, reply) =>
        Vector(
          "status" -> str("answered"),
          "reply" -> reply,
          "answer" -> ReferenceAnswers.json(answer)
        )
      case ReferenceAnswers.Outcome.Omitted    => Vector("status" -> str("omitted"))
      case ReferenceAnswers.Outcome.Unparsable => Vector("status" -> str("unparsable-batch"))))

  private val Positional = "^[A-Za-z_]{0,6}[-_:#]?0*([0-9]+)$".r

  /** Whether an item id equals or encodes its unit's id or position: the unit id itself, or a short
    * prefix and the unit's number, its zero-based position or its one-based position.
    */
  def encodesPosition(item: String, u: Int, position: Int, unitId: String): Boolean =
    item.contains(unitId) || (item match
      case Positional(n) =>
        BigInt(n) == BigInt(u) || BigInt(n) == BigInt(position) || BigInt(n) == BigInt(position + 1)
      case _ => false)

  /** Everything after each `ITEM <id>` line of a prompt, once per occurrence. */
  private def afterItem(prompt: String, item: String): Vector[String] =
    val head = s"ITEM $item\n"
    Iterator
      .iterate(prompt.indexOf(head))(i => prompt.indexOf(head, i + 1))
      .takeWhile(_ >= 0)
      .filter(i => i == 0 || prompt.charAt(i - 1) == '\n')
      .map(i => prompt.substring(i + head.length))
      .toVector

  /** Check the declared condition against the prompts that carried these units, and give the
    * rendering identity. `recall` is the session's units as (unit id, text), in inventory order.
    * Each item block must be exactly what the harness renders under the declared window: a CONTEXT
    * line holding the window's preceding texts (none for a window of 0), then a TARGET line holding
    * the unit's own text.
    */
  def rendering(
      condition: ProducerCondition,
      recall: Vector[(String, String)],
      units: Vector[UnitAnswer],
      batches: Vector[Recorded]
  ): Either[RenderingRefusal, Rendering] =
    import RenderingRefusal.*
    val texts = recall.map(_._2)
    val scheme = condition.ids match
      case ExposedIds.OpaqueSalted(sc, _) =>
        IdShapes.get(sc).map(sc -> _).toRight(UnknownIdScheme(sc)).map(Some(_))
      case ExposedIds.Positional => Right(None)
    def unit(window: Int, shape: Option[(String, scala.util.matching.Regex)])(
        x: UnitAnswer
    ): Either[RenderingRefusal, Unit] =
      val position = recall.indexWhere(_._1 == x.unit)
      def block(rest: String): Either[RenderingRefusal, Unit] =
        val target = s"TARGET: ${texts(position)}"
        def ends(after: String) =
          // The block ends at the separator before the next block or the closing instruction,
          // so a declared text cannot be a prefix of a longer line.
          Either.cond(after.startsWith(target + "\n\n") || after == target, (), TargetMismatch(x.u))
        if window > 0 then
          val context = s"CONTEXT: ${contextText(texts, position, window)}\n"
          if !rest.startsWith("CONTEXT: ") then Left(ContextMissing(x.u))
          else if !rest.startsWith(context + "TARGET: ") then Left(ContextMismatch(x.u))
          else ends(rest.substring(context.length))
        else if rest.startsWith("CONTEXT: ") then Left(ContextPresent(x.u))
        else ends(rest)
      if position < 0 then Left(NotInRecall(x.u))
      else
        batches.filter(_.batch.items.contains(x.item)) match
          case Vector(own) =>
            val outcome = (x.outcome, own.receipt.accepted) match
              case (ReferenceAnswers.Outcome.Unparsable, accepted) => !accepted
              case (_, accepted)                                   => accepted
            afterItem(own.batch.prompt, x.item) match
              case Vector(rest) =>
                for
                  _ <- block(rest)
                  _ <- Either.cond(
                    shape.isEmpty || !encodesPosition(x.item, x.u, position, x.unit),
                    (),
                    IdEncodesPosition(x.u)
                  )
                  _ <- shape.fold[Either[RenderingRefusal, Unit]](Right(())) { (sc, re) =>
                    Either.cond(re.matches(x.item), (), IdShape(x.u, sc))
                  }
                  _ <- Either.cond(outcome, (), OutcomeReceipt(x.u))
                yield ()
              case _ => Left(ItemLine(x.u))
          case _ => Left(NotInOneBatch(x.u))
    for
      window <- condition.window match
        case p: ContextWindow.Preceding => Right(p.units)
        case ContextWindow.WholeRecall  => Left(NotRendered(condition.rendering))
      shape <- scheme
      _ <- batches
        .groupBy(_.receipt.batch)
        .collectFirst { case (b, bs) if bs.size > 1 => BatchListedTwice(b) }
        .toLeft(())
      _ <- units.foldLeft[Either[RenderingRefusal, Unit]](Right(()))((acc, x) =>
        acc.flatMap(_ => unit(window, shape)(x))
      )
    yield
      val digests = batches.map(b => s"${b.receipt.batch}\t${Checksum.ofText(b.batch.prompt).hex}")
      val items = units.map(_.item)
      new Rendering(
        Json.obj(
          "itemLines" -> str("each unit's ITEM line occurs once in its batch prompt"),
          "context" -> str(
            if window > 0 then
              s"each CONTEXT line is exactly the texts of the $window preceding units, joined " +
                "by spaces, or (start of recall)"
            else "no CONTEXT line: TARGET directly follows every ITEM line"
          ),
          "target" -> str("each TARGET line is exactly the unit's own text"),
          "ids" -> str(shape.fold("positional ids declared; not checked") { (sc, _) =>
            s"every item id has the shape of scheme $sc and none equals or encodes its unit's " +
              "id or position"
          }),
          "idsMonotoneInUnitOrder" -> Json.fromBoolean(items == items.sorted),
          "order" -> str("asserted by the condition; not checked against the prompts")
        ),
        Checksum.ofText(
          (Vector("reference-rendering/v1", condition.rendering) ++ digests).mkString("\n")
        )
      )

  private def batchesJson(batches: Vector[Recorded], units: Vector[UnitAnswer]): Json =
    val own = units.map(_.item).toSet
    Json.fromValues(batches.map { b =>
      Json.obj(
        "batch" -> Json.fromInt(b.receipt.batch),
        "promptSha256" -> str(Checksum.ofText(b.batch.prompt).hex),
        "attempts" -> Json.fromInt(b.receipt.attempts),
        "accepted" -> Json.fromBoolean(b.receipt.accepted),
        "repeatedIds" -> Json.fromInt(b.receipt.repeated),
        "nonStringIds" -> Json.fromInt(b.receipt.nonStringIds),
        "items" -> Json.fromValues(b.batch.items.filter(own).map(str))
      )
    })

  private def renderingJson(r: Rendering): Json =
    Json.obj("sha256" -> str(r.sha256.hex), "checks" -> r.checks)

  /** The bundle document, after checking the condition against the prompts. `recall` is the
    * session's units as (unit id, text) in inventory order; `batches` are the batches that asked
    * this session's units, as replayed.
    */
  def build(
      provenance: Provenance,
      rows: SourceRows,
      recall: Vector[(String, String)],
      batches: Vector[Recorded],
      units: Vector[UnitAnswer]
  ): Either[RenderingRefusal, Json] =
    rendering(provenance.condition.condition, recall, units, batches).map { r =>
      Json.obj(
        "schema" -> str(Schema),
        "provenance" -> Json.obj(
          "participant" -> str(provenance.participant),
          "session" -> str(provenance.session),
          "condition" -> str(provenance.condition.name),
          "producerCondition" -> provenance.condition.condition.json,
          "model" -> str(provenance.model),
          "provider" -> str(provenance.provider),
          "answerSchema" -> str(provenance.answers.name),
          "systemSha256" -> str(provenance.systemSha256),
          "modelCalls" -> Json.fromInt(0)
        ),
        "meaning" -> Json.obj(
          "reply" -> str("the reply item exactly as accepted"),
          "answer" -> str(
            "each field as the reply established it: absent, null, a value, or invalid (raw kept)"
          ),
          "w" -> str("a self-reported weight as written; not normalized, not a probability"),
          "iv" -> str("inclusive source row intervals, each end a row of this source"),
          "rendering" -> str(
            "checks of the condition against the batch prompts, and a digest of the condition " +
              "and every batch prompt digest"
          )
        ),
        "rendering" -> renderingJson(r),
        "batches" -> batchesJson(batches, units),
        "units" -> Json.fromValues(units.map(unitJson)),
        "source" -> sourceJson(rows)
      )
    }

  /** What the reader regenerates to hold a bundle against: who, which units under which item ids,
    * the batches that asked them (replayed again, without a model), and what the producer was.
    */
  final case class Expected(
      participant: String,
      session: String,
      units: Vector[(Int, String, String)],
      recall: Vector[(String, String)],
      batches: Vector[Recorded],
      condition: RecordedCondition.Declared,
      model: String,
      provider: String,
      answers: ReferenceAnswers.Schema,
      systemSha256: String
  )

  /** A bundle read back: its provenance, every unit's outcome re-derived from its reply, and the
    * rendering derived again from the prompts.
    */
  final class Loaded private[ReferenceBundle] (
      val provenance: Provenance,
      val units: Vector[UnitAnswer],
      val rendering: Rendering
  ):
    override def equals(other: Any): Boolean = other match
      case that: Loaded =>
        provenance == that.provenance && units == that.units && rendering == that.rendering
      case _ => false
    override def hashCode: Int = (provenance, rendering).hashCode
    override def toString: String = s"Loaded(${provenance.participant}, ${provenance.session})"

  private def bare(h: io.circe.HCursor): Boolean =
    h.downField("reply").focus.isEmpty && h.downField("answer").focus.isEmpty

  /** Read a bundle against the study it claims to come from. Provenance must name the expected
    * participant, session, condition, model, provider, schema and instruction; the source must be
    * this source's rows; units must be the expected units under their item ids, in order; each
    * stored answer must be what its stored reply gives against this source; and the stored batches
    * and rendering must be what the expected prompts and receipts give.
    */
  def decode(doc: Json, rows: SourceRows, expected: Expected): Either[String, Loaded] =
    val c = doc.hcursor
    val p = c.downField("provenance")
    def says(key: String, value: String): Either[String, Unit] =
      Either.cond(p.get[String](key).contains(value), (), s"provenance names another $key")
    for
      _ <- Either.cond(c.get[String]("schema").contains(Schema), (), "not a reference bundle")
      _ <- says("participant", expected.participant)
      _ <- says("session", expected.session)
      _ <- says("condition", expected.condition.name)
      condition <- p
        .downField("producerCondition")
        .focus
        .toRight("no producer condition")
        .flatMap(ProducerCondition.decode(_).left.map(_.message))
      _ <- Either.cond(
        condition == expected.condition.condition,
        (),
        "the bundle names another producer condition"
      )
      _ <- says("model", expected.model)
      _ <- says("provider", expected.provider)
      _ <- says("answerSchema", expected.answers.name)
      _ <- says("systemSha256", expected.systemSha256)
      _ <- Either.cond(p.get[Int]("modelCalls").contains(0), (), "model call count differs")
      _ <- Either.cond(
        c.downField("source").focus.contains(sourceJson(rows)),
        (),
        "the source rows are not this source's"
      )
      raw <- c.get[Vector[Json]]("units").left.map(_ => "no units")
      _ <- Either.cond(
        raw.map { u =>
          val h = u.hcursor
          (h.get[Int]("u").toOption, h.get[String]("unit").toOption, h.get[String]("item").toOption)
        } == expected.units.map((u, unit, item) => (Some(u), Some(unit), Some(item))),
        (),
        "units are not the expected units under their item ids"
      )
      units <- raw
        .zip(expected.units)
        .foldLeft[Either[String, Vector[UnitAnswer]]](Right(Vector.empty)) {
          case (acc, (u, (n, unit, item))) =>
            acc.flatMap { done =>
              val h = u.hcursor
              val outcome = h.get[String]("status").toOption match
                case Some("answered") =>
                  for
                    reply <- h.downField("reply").focus.toRight(s"unit $n has no reply")
                    _ <- Either.cond(
                      reply.hcursor.get[String]("id").contains(item),
                      (),
                      s"unit $n: the reply is not for its item"
                    )
                    answer = ReferenceAnswers.answer(reply, expected.answers, rows)
                    _ <- Either.cond(
                      h.downField("answer").focus.contains(ReferenceAnswers.json(answer)),
                      (),
                      s"unit $n: the answer is not what its reply gives against this source"
                    )
                  yield ReferenceAnswers.Outcome.Answered(answer, reply)
                case Some("omitted") if bare(h)          => Right(ReferenceAnswers.Outcome.Omitted)
                case Some("unparsable-batch") if bare(h) =>
                  Right(ReferenceAnswers.Outcome.Unparsable)
                case _ => Left(s"unit $n has no known status, or a failed unit carries a reply")
              outcome.map(o => done :+ UnitAnswer(n, unit, item, o))
            }
        }
      _ <- Either.cond(
        c.downField("batches").focus.contains(batchesJson(expected.batches, units)),
        (),
        "the batches are not the expected prompts and receipts"
      )
      again <- rendering(condition, expected.recall, units, expected.batches).left.map(_.message)
      _ <- Either.cond(
        c.downField("rendering").focus.contains(renderingJson(again)),
        (),
        "the rendering is not what the prompts give"
      )
    yield new Loaded(
      Provenance(
        expected.participant,
        expected.session,
        expected.condition,
        expected.model,
        expected.provider,
        expected.answers,
        expected.systemSha256
      ),
      units,
      again
    )
