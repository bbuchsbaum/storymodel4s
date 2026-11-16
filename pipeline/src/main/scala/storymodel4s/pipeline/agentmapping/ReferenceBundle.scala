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
  * reading a bundle checks the condition against the exact prompts: each unit's `ITEM <id>` line
  * occurs once in its batch's prompt; a `CONTEXT:` line follows it exactly when the window has
  * preceding units; and, under opaque ids, no item id equals or encodes its unit's id or position.
  * The checks made are recorded in the bundle, and the rendering identity is a digest of the
  * condition and every batch's prompt digest. Hidden order is not checked against the prompts (it
  * needs the whole pool); it stays an asserted part of the condition, and the bundle says so. A
  * bundle holds unit ids and recall-derived words; keep it outside the repository.
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

  /** What the prompts showed: the checks made and the rendering identity they give. */
  final case class Rendering(checks: Json, sha256: Checksum)

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

  /** The lines that follow `ITEM <id>` in a prompt, once per occurrence. */
  private def afterItem(prompt: String, item: String): Vector[Option[String]] =
    val lines = prompt.split("\n", -1).toVector
    lines.indices.filter(i => lines(i) == s"ITEM $item").map(i => lines.lift(i + 1)).toVector

  /** Check the declared condition against the prompts that carried these units, and give the
    * rendering identity.
    */
  def rendering(
      condition: ProducerCondition,
      units: Vector[UnitAnswer],
      batches: Vector[Recorded]
  ): Either[String, Rendering] =
    val k = condition.window match
      case p: ContextWindow.Preceding => Right(p.units)
      case ContextWindow.WholeRecall  =>
        Left(
          ProducerCondition.Refusal.NotRendered("reference path", condition.rendering).message
        )
    val opaque = condition.ids match
      case _: ExposedIds.OpaqueSalted => true
      case ExposedIds.Positional      => false
    def batchOf(item: String) = batches.filter(_.batch.items.contains(item))
    for
      window <- k
      _ <- Either.cond(
        batches.map(_.receipt.batch).distinct.size == batches.size,
        (),
        "a batch is listed twice"
      )
      _ <- units.zipWithIndex.foldLeft[Either[String, Unit]](Right(())) { case (acc, (x, i)) =>
        acc.flatMap { _ =>
          batchOf(x.item) match
            case Vector(own) =>
              val next = afterItem(own.batch.prompt, x.item)
              val context = next.headOption.flatten.exists(_.startsWith("CONTEXT: "))
              val target = next.headOption.flatten.exists(_.startsWith("TARGET: "))
              val outcome = (x.outcome, own.receipt.accepted) match
                case (ReferenceAnswers.Outcome.Unparsable, accepted) => !accepted
                case (_, accepted)                                   => accepted
              if next.size != 1 then Left(s"unit ${x.u}: its ITEM line is not once in its prompt")
              else if window > 0 && !context then
                Left(s"unit ${x.u}: the window has preceding units but its prompt shows no context")
              else if window == 0 && !target then
                Left(s"unit ${x.u}: the window is the target only but its prompt shows context")
              else if opaque && encodesPosition(x.item, x.u, i, x.unit) then
                Left(s"unit ${x.u}: its item id encodes its position, under declared opaque ids")
              else if !outcome then
                Left(s"unit ${x.u}: its outcome disagrees with its batch receipt")
              else Right(())
            case _ => Left(s"unit ${x.u}: its item is not asked in exactly one batch")
        }
      }
    yield
      val digests = batches.map(b => s"${b.receipt.batch}\t${Checksum.ofText(b.batch.prompt).hex}")
      Rendering(
        Json.obj(
          "itemLines" -> str("each unit's ITEM line occurs once in its batch prompt"),
          "context" -> str(
            if window > 0 then "a CONTEXT line follows every ITEM line"
            else "a TARGET line directly follows every ITEM line"
          ),
          "ids" -> str(
            if opaque then "no item id equals or encodes its unit's id or position"
            else "positional ids declared; not checked"
          ),
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

  /** The bundle document, after checking the condition against the prompts. `batches` are the
    * batches that asked this session's units, as replayed.
    */
  def build(
      provenance: Provenance,
      rows: SourceRows,
      batches: Vector[Recorded],
      units: Vector[UnitAnswer]
  ): Either[String, Json] =
    rendering(provenance.condition.condition, units, batches).map { r =>
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
  final case class Loaded(provenance: Provenance, units: Vector[UnitAnswer], rendering: Rendering)

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
      again <- rendering(condition, units, expected.batches)
      _ <- Either.cond(
        c.downField("rendering").focus.contains(renderingJson(again)),
        (),
        "the rendering is not what the prompts give"
      )
    yield Loaded(
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
