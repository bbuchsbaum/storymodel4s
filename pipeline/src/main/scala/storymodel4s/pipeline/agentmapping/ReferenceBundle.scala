package storymodel4s.pipeline.agentmapping

import io.circe.Json

/** One participant-session's reference answers under one declared producer condition
  * (`storymodel4s.agentmapping.reference-bundle/v1`): each unit's outcome, the reply item as
  * accepted, and the answer as parsed against the source's rows.
  *
  * Why the reply beside the answer: the reply is the evidence and the answer is derived from it.
  * Reading a bundle parses every reply again against the source and requires the stored answer to
  * be exactly that, so neither can change without the other. A reference bundle always declares its
  * producer condition: these answers come from runs whose context, order and ids differ, and a name
  * alone cannot say which. A bundle holds unit ids and recall-derived words; keep it outside the
  * repository.
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

  def encode(provenance: Provenance, rows: SourceRows, units: Vector[UnitAnswer]): Json =
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
        "iv" -> str("inclusive source row intervals, each end a row of this source")
      ),
      "units" -> Json.fromValues(units.map(unitJson)),
      "source" -> sourceJson(rows)
    )

  /** What the reader regenerates to hold a bundle against: who, which units under which item ids,
    * and what the producer was.
    */
  final case class Expected(
      participant: String,
      session: String,
      units: Vector[(Int, String, String)],
      condition: RecordedCondition.Declared,
      model: String,
      provider: String,
      answers: ReferenceAnswers.Schema,
      systemSha256: String
  )

  /** A bundle read back: its provenance and every unit's outcome, re-derived from its reply. */
  final case class Loaded(provenance: Provenance, units: Vector[UnitAnswer])

  /** Read a bundle against the study it claims to come from. Provenance must name the expected
    * participant, session, condition, model, provider, schema and instruction; the source must be
    * this source's rows; units must be the expected units under their item ids, in order; and each
    * stored answer must be what its stored reply gives against this source.
    */
  private def bare(h: io.circe.HCursor): Boolean =
    h.downField("reply").focus.isEmpty && h.downField("answer").focus.isEmpty

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
      units
    )
