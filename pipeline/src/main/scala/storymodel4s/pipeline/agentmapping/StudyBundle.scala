package storymodel4s.pipeline.agentmapping

import io.circe.Json
import storymodel4s.align.{DecisionPolicy, MappingResult}
import storymodel4s.align.bridge.TimedSourceView
import storymodel4s.codec.{ExpectedMappingContext, MappingCodecs}
import storymodel4s.core.Checksum

/** One participant-session's saved mapping under one condition and model
  * (`storymodel4s.agentmapping.bundle/v2`): both judgements as checked records, every unit's
  * answers as stated and as decided against the source, provenance, and a source dictionary.
  *
  * Why rows beside the records: a record says what was chosen, and for a failure only that it
  * failed. A row keeps what the reply stated and how the source judged it. So a scene that is not a
  * target stays visible with its film, a film-only answer stays apart from no assignment, and a
  * scorer can read the bundle without the records' internals. Rows are built from the judgements
  * and never parsed back out of a record. Reading a bundle derives every judgement and both records
  * again and requires the bundle to hold exactly those. A bundle holds source text and unit ids;
  * keep it outside the repository.
  */
object StudyBundle:
  val Schema: String = "storymodel4s.agentmapping.bundle/v2"
  val Fields: Vector[String] = Vector("narrative", "referent")

  /** What a bundle says about where it came from. */
  final case class Provenance(
      participant: String,
      session: String,
      condition: String,
      model: String,
      provider: String,
      protocol: String,
      systemSha256: String,
      manifestSha256: String,
      segmentation: PreparedRecall.Segmentation,
      clockSha256: Option[String] = None
  )

  /** One unit: the study's own number and row key, what the replies established for it, and for
    * each judgement the answer as stated and as decided against the source.
    */
  final case class Row(
      u: Int,
      unit: String,
      row: Json,
      processing: String,
      stated: Map[String, Json],
      judgement: Map[String, StudyRecord.Judgement],
      extras: Vector[(String, Json)]
  )

  private def str(s: String) = Json.fromString(s)

  /** The film each scene belongs to, for sources that have a film level. */
  def collectionOfGroup(view: TimedSourceView): Map[Int, Int] =
    view.segments
      .flatMap(s => s.group.flatMap(g => s.collection.map(c => g.ordinal -> c.ordinal)))
      .toMap

  /** A judgement as a row shows it: its kind and what it names. A scene carries its film, so a
    * scene that is not a target still says which film it is in.
    */
  def judgementJson(view: TimedSourceView, j: StudyRecord.Judgement): Json =
    import StudyRecord.Judgement
    val films = collectionOfGroup(view)
    def scene(n: Int) =
      Vector("scene" -> Json.fromInt(n)) ++ films.get(n).map(c => "collection" -> Json.fromInt(c))
    val detail: Vector[(String, Json)] = j match
      case Judgement.Scene(n, _)          => scene(n)
      case Judgement.NotATarget(n)        => scene(n)
      case Judgement.Collection(c, _)     => Vector("collection" -> Json.fromInt(c))
      case Judgement.TimeOutside(c, _)    => Vector("collection" -> Json.fromInt(c))
      case Judgement.UnknownScene(s)      => Vector("stated" -> str(s))
      case Judgement.UnknownCollection(s) => Vector("stated" -> str(s))
      case Judgement.Invalid(reason)      => Vector("reason" -> str(reason))
      case _                              => Vector.empty
    Json.fromFields(("kind" -> str(j.kind)) +: detail)

  /** One unit's row from what the replay established. */
  def row(
      view: TimedSourceView,
      protocol: StudyReplay.ReplyProtocol,
      unit: StudyReplay.RecallUnit,
      unitId: String,
      reply: StudyReplay.UnitReply,
      extras: Vector[String],
      intervals: Option[SceneIntervals] = None
  ): Row =
    val full = StudyReplay.unitRow(protocol, unit, reply, extras, intervals).hcursor
    val (processing, kept) = reply match
      case StudyReplay.UnitReply.Answered(_)     => "answered" -> extras
      case StudyReplay.UnitReply.Omitted         => "omitted" -> Vector.empty
      case StudyReplay.UnitReply.UnparsableChunk => "unparsable-chunk" -> Vector.empty
    Row(
      unit.u,
      unitId,
      unit.row,
      processing,
      Fields.map(f => f -> full.downField(f).focus.getOrElse(Json.Null)).toMap,
      Fields.map(f => f -> StudyRecord.judge(view, protocol, reply, f, intervals)).toMap,
      kept.map(k => k -> full.downField(k).focus.getOrElse(Json.Null))
    )

  private def rowJson(view: TimedSourceView, r: Row): Json =
    Json.fromFields(
      Vector(
        "u" -> Json.fromInt(r.u),
        "unit" -> str(r.unit),
        "row" -> r.row,
        "processing" -> str(r.processing)
      ) ++ Fields.map { f =>
        f -> Json.obj("stated" -> r.stated(f), "judgement" -> judgementJson(view, r.judgement(f)))
      } :+ ("extras" -> Json.fromFields(r.extras))
    )

  /** The source as a dictionary: films, scenes and rows with their targets, so a chosen scene or a
    * cited row resolves without the annotation file. Times are ticks on the part's own clock.
    */
  def sourceJson(view: TimedSourceView, intervals: Option[SceneIntervals] = None): Json =
    val targetGroups = view.targetGroups.toSet
    val targetCollections = view.targetCollections.toSet
    val films = collectionOfGroup(view)
    val groups = view.segments.flatMap(_.group).distinctBy(_.ordinal).sortBy(_.ordinal)
    val collections = view.segments.flatMap(_.collection).distinctBy(_.ordinal).sortBy(_.ordinal)
    val base = Json.obj(
      "artifact" -> str(view.artifact.hex),
      "representation" -> str(view.representation.digest.hex),
      "collections" -> Json.fromValues(collections.map { c =>
        val ref = view.collectionRefs(c.ordinal)
        Json.obj(
          "collection" -> Json.fromInt(c.ordinal),
          "target" -> str(ref.key),
          "label" -> str(c.label),
          "isTarget" -> Json.fromBoolean(targetCollections(ref))
        )
      }),
      "scenes" -> Json.fromValues(groups.map { g =>
        val ref = view.groupRefs(g.ordinal)
        Json.fromFields(
          Vector(
            "scene" -> Json.fromInt(g.ordinal),
            "target" -> str(ref.key),
            "label" -> str(g.label),
            "isTarget" -> Json.fromBoolean(targetGroups(ref))
          ) ++ films.get(g.ordinal).map(c => "collection" -> Json.fromInt(c))
        )
      }),
      "rows" -> Json.fromValues(view.segments.sortBy(_.ordinal).map { s =>
        Json.fromFields(
          Vector("row" -> Json.fromInt(s.ordinal), "isTarget" -> Json.fromBoolean(s.target)) ++
            s.group.map(g => "scene" -> Json.fromInt(g.ordinal)) ++
            s.locus.toVector.flatMap { l =>
              Vector(
                "part" -> str(l.part),
                "startTick" -> Json.fromLong(l.startTick),
                "endTick" -> Json.fromLong(l.endTick)
              )
            } :+ ("text" -> str(s.text))
        )
      })
    )
    intervals.fold(base)(t => base.deepMerge(Json.obj("clock" -> t.json)))

  /** A digest of one judgement's answers as stated, one line per unit: the unit's number, the kind
    * of answer (or the failure and its reason), and the scene, film and time the answer holds where
    * it holds them. A record names this digest in its decision receipt. So the parts of an answer
    * the record's choice does not determine, such as where in a scene a time fell, or whether a
    * film was named alone or with a time outside its clip, cannot change beside the record.
    */
  def answersDigest(stated: Vector[(Int, Json)]): Checksum =
    def token(j: Option[Json]): String = j match
      case None                => "-"
      case Some(v) if v.isNull => "null"
      case Some(v)             =>
        StudyReplay
          .strictInteger(v)
          .map(_.toString)
          .orElse(
            v.asString.map(text => "s:" + Checksum.ofText(text).hex)
          )
          .getOrElse("x")
    Checksum.ofText(stated.map { (u, answer) =>
      val c = answer.hcursor
      val kind =
        if c.get[String]("status").contains("answered") then c.get[String]("kind").getOrElse("?")
        else "failed:" + c.get[String]("reason").getOrElse("?")
      val held = Vector("scene", "film", "film_stated", "time")
        .map(key => token(c.downField(key).focus))
        .mkString("\t")
      s"$u\t$kind\t$held\n"
    }.mkString)

  /** Both judgements' checked records for one session's rows, each bound to its stated answers. */
  def records(
      condition: String,
      protocolName: String,
      recall: PreparedRecall,
      view: TimedSourceView,
      producer: AgentMappingRecord.Producer,
      rows: Vector[Row]
  ): Either[String, Map[String, MappingResult]] =
    Fields.foldLeft[Either[String, Map[String, MappingResult]]](Right(Map.empty)) { (acc, f) =>
      acc.flatMap { done =>
        val bound =
          producer.copy(answersSha256 = Some(answersDigest(rows.map(r => r.u -> r.stated(f)))))
        StudyRecord
          .record(
            f,
            StudyRecord.policy(condition, protocolName, f),
            recall,
            view,
            bound,
            rows.map(_.judgement(f))
          )
          .map(done.updated(f, _))
          .left
          .map(e => s"$f: ${e.message}")
      }
    }

  /** The bundle document. Records are embedded as their codec JSON, unchanged. */
  def encode(
      provenance: Provenance,
      view: TimedSourceView,
      records: Map[String, MappingResult],
      rows: Vector[Row],
      intervals: Option[SceneIntervals] = None
  ): Json =
    Json.obj(
      "schema" -> str(Schema),
      "provenance" -> Json
        .obj(
          "participant" -> str(provenance.participant),
          "session" -> str(provenance.session),
          "condition" -> str(provenance.condition),
          "model" -> str(provenance.model),
          "provider" -> str(provenance.provider),
          "protocol" -> str(provenance.protocol),
          "systemSha256" -> str(provenance.systemSha256),
          "manifestSha256" -> str(provenance.manifestSha256),
          "segmentation" -> Json.obj(
            "kind" -> str(provenance.segmentation.kind),
            "artifactSha256" -> str(provenance.segmentation.artifactSha256.hex),
            "recipe" -> str(provenance.segmentation.recipe)
          ),
          "modelCalls" -> Json.fromInt(0)
        )
        .deepMerge(Json.fromFields(provenance.clockSha256.map(h => "clockSha256" -> str(h)))),
      "meaning" -> Json.obj(
        "narrative" -> str("the scene the retelling is narrating at this unit"),
        "referent" -> str("the scene containing the specifically mentioned content"),
        "stated" -> str("the answer as the reply gave it, in the form a scorer reads"),
        "judgement" -> str(
          "the answer decided against the source: scene, film (a film with no particular " +
            "scene), time-outside (a time outside the film's clip: the film, no scene), none, " +
            "invalid-scene (a real scene that is not a target), unknown-scene, unknown-film, " +
            "invalid, omitted, unparsable-chunk"
        ),
        "time" -> str(
          "a condition that answers with a time keeps the raw film and time beside the scene " +
            "that contains it; source.clock holds the scene intervals in exact fractions"
        ),
        "extras" -> str("self-reported fields kept verbatim; not scores, not calibrated")
      ),
      "interpretations" -> Json.fromFields(Fields.map { f =>
        f -> Json.obj(
          "policy" -> str(StudyRecord.policy(provenance.condition, provenance.protocol, f).value),
          "recordDigest" -> str(records(f).digest.hex),
          "record" -> MappingCodecs.toJson(records(f))
        )
      }),
      "units" -> Json.fromValues(rows.map(rowJson(view, _))),
      "source" -> sourceJson(view, intervals)
    )

  /** What a reader recovers: both records, re-checked, and the unit rows as written. */
  final case class Loaded(records: Map[String, MappingResult], units: Vector[Json])

  /** Keys a self-reported extra may not use: they would overwrite a row's own fields wherever a row
    * is flattened into a reply file.
    */
  val ReservedKeys: Set[String] = Set("u", "row", "unit", "processing") ++ Fields

  /** What the reader regenerates from the study export to hold a bundle against. */
  final case class Expected(
      participant: String,
      session: String,
      units: Vector[StudyReplay.RecallUnit],
      producer: AgentMappingRecord.Producer,
      intervals: Option[SceneIntervals] = None
  )

  /** The judgement a stated answer gives against this source. A failure's judgement follows from
    * its reason; an answer that does not read back is `None`.
    */
  private def rejudge(
      view: TimedSourceView,
      stated: Json,
      intervals: Option[SceneIntervals],
      timed: Boolean
  ): Option[StudyRecord.Judgement] =
    val c = stated.hcursor
    if stated.asObject.exists(_.contains("time")) then
      // A time answer is converted again from its raw film and time, and must be what is stored.
      val again = StudyReplay.timeStated(
        c.downField("film_stated").focus.getOrElse(Json.Null),
        c.downField("time").focus.getOrElse(Json.Null)
      )
      Option.when(timed && StudyReplay.prediction(again, intervals) == stated) {
        StudyRecord.judge(view, again, intervals)
      }
    else rejudgePlain(view, stated, timed)

  private def rejudgePlain(
      view: TimedSourceView,
      stated: Json,
      timed: Boolean
  ): Option[StudyRecord.Judgement] =
    val c = stated.hcursor
    c.get[String]("status").toOption.flatMap {
      case "answered" =>
        StudyReplay
          .statedOf(stated)
          .filter {
            // In a condition that answers with a time, a scene comes only from a time.
            case _: StudyReplay.Stated.Scene => !timed
            case _                           => true
          }
          .map(StudyRecord.judge(view, _))
      case "failed" =>
        c.get[String]("reason").toOption.map {
          case "omitted"          => StudyRecord.Judgement.Omitted
          case "unparsable-chunk" => StudyRecord.Judgement.UnparsableChunk
          case reason             => StudyRecord.Judgement.Invalid(reason)
        }
      case _ => None
    }

  /** Read a bundle against the study it claims to come from: the source, the recall, the study's
    * units and the producer, all regenerated by the caller.
    *
    * Nothing a row says is taken on trust. Each row must carry its unit's id, number and row key.
    * Each judgement is derived again from its stated answer and must be what the row holds. Each
    * record is decoded through the mapping codec, which re-derives it, and must then equal the
    * record these rows give under this producer and this condition's policy. That last comparison
    * binds the rows, the source, the recall, the model, the provider and the instruction to the
    * record. The record also names a digest of the answers as stated, so an answer cannot be edited
    * beside an unchanged record. Provenance must name the same participant, session, model,
    * provider, instruction and units. Extras and the source dictionary are descriptions and are
    * read as written, except that an extra may not use a row's own key.
    */
  def decode(
      bundle: Json,
      view: TimedSourceView,
      recall: PreparedRecall,
      expected: Expected
  ): Either[String, Loaded] =
    val c = bundle.hcursor
    val p = c.downField("provenance")
    val context = ExpectedMappingContext(recall.inventory, view.representation)
    def says(key: String, value: String): Either[String, Unit] =
      Either.cond(p.get[String](key).contains(value), (), s"provenance names another $key")
    for
      _ <- Either.cond(c.get[String]("schema").contains(Schema), (), "not a v2 mapping bundle")
      condition <- p.get[String]("condition").left.map(_ => "no condition")
      protocol <- p.get[String]("protocol").left.map(_ => "no protocol")
      _ <- says("participant", expected.participant)
      _ <- says("session", expected.session)
      _ <- says("model", expected.producer.model)
      _ <- says("provider", expected.producer.provider)
      _ <- says("systemSha256", expected.producer.systemSha256.hex)
      _ <- Either.cond(
        p.downField("segmentation")
          .get[String]("artifactSha256")
          .toOption
          .contains(recall.segmentation.artifactSha256.hex) &&
          expected.units.map(_.text) == recall.texts,
        (),
        "the recall is not the study's units"
      )
      timed = StudyReplay.ReplyProtocol
        .named(protocol)
        .contains(StudyReplay.ReplyProtocol.StrictChunkedTime)
      _ <- Either.cond(
        timed == expected.intervals.isDefined,
        (),
        "a time condition needs a clip clock, and no other has one"
      )
      _ <- Either.cond(
        c.downField("source").focus.contains(sourceJson(view, expected.intervals)),
        (),
        "the source dictionary is not this source's"
      )
      units <- c.get[Vector[Json]]("units").left.map(_ => "no units")
      _ <- Either.cond(
        units.map(_.hcursor.get[String]("unit").toOption) == recall.unitIds.map(u =>
          Some(u.value)
        ) &&
          units.map(u => (u.hcursor.downField("u").focus, u.hcursor.downField("row").focus)) ==
          expected.units.map(u => (Some(Json.fromInt(u.u)), Some(u.row))),
        (),
        "unit rows are not the recall's units"
      )
      _ <- Either.cond(
        units.forall { u =>
          u.hcursor.downField("extras").focus.flatMap(_.asObject).exists { o =>
            !o.keys.exists(ReservedKeys)
          }
        },
        (),
        "an extra uses a row's own key"
      )
      judged <- Fields.foldLeft[Either[String, Map[String, Vector[StudyRecord.Judgement]]]](
        Right(Map.empty)
      ) { (acc, f) =>
        acc.flatMap { done =>
          val rows = units.map(_.hcursor.downField(f))
          val derived = rows.map { r =>
            r.downField("stated").focus.flatMap(rejudge(view, _, expected.intervals, timed))
          }
          Either.cond(
            derived.forall(_.isDefined) && rows.zip(derived).forall { (r, j) =>
              r.downField("judgement").focus == j.map(judgementJson(view, _))
            },
            done.updated(f, derived.flatten),
            s"a $f judgement is not what its stated answer gives against this source"
          )
        }
      }
      _ <- Either.cond(
        units.zipWithIndex.forall { (u, i) =>
          val kinds = Fields.map(f => judged(f)(i).kind).toSet
          val whole = kinds.intersect(Set("omitted", "unparsable-chunk"))
          val processing =
            if whole.isEmpty then Some("answered") else Option.when(kinds.size == 1)(kinds.head)
          u.hcursor.get[String]("processing").toOption == processing
        },
        (),
        "a unit's processing does not match its judgements"
      )
      records <- Fields.foldLeft[Either[String, Map[String, MappingResult]]](Right(Map.empty)) {
        (acc, f) =>
          for
            done <- acc
            raw <- c
              .downField("interpretations")
              .downField(f)
              .downField("record")
              .focus
              .toRight(s"no $f record")
            record <- MappingCodecs.decode(raw.noSpaces, context).left.map(e => s"$f: ${e.message}")
            policy = StudyRecord.policy(condition, protocol, f)
            _ <- Either.cond(
              record.policies.decision == DecisionPolicy.Declared(policy),
              (),
              s"the $f slot does not hold the $f record"
            )
            bound = expected.producer.copy(answersSha256 =
              Some(
                answersDigest(
                  expected.units
                    .map(_.u)
                    .zip(
                      units.map(
                        _.hcursor.downField(f).downField("stated").focus.getOrElse(Json.Null)
                      )
                    )
                )
              )
            )
            again <- StudyRecord
              .record(f, policy, recall, view, bound, judged(f))
              .left
              .map(e => s"$f: ${e.message}")
            _ <- Either.cond(
              again.digest == record.digest,
              (),
              s"a unit row disagrees with the $f record"
            )
          yield done.updated(f, record)
      }
    yield Loaded(records, units)
