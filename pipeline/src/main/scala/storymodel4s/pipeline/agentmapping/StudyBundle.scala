package storymodel4s.pipeline.agentmapping

import io.circe.Json
import storymodel4s.align.{DecisionPolicy, Destination, MappingResult, ProcessingStatus}
import storymodel4s.align.bridge.TimedSourceView
import storymodel4s.codec.{ExpectedMappingContext, MappingCodecs}

/** One participant-session's saved mapping under one condition and model
  * (`storymodel4s.agentmapping.bundle/v2`): both judgements as checked records, every unit's
  * answers as stated and as decided against the source, provenance, and a source dictionary.
  *
  * Why rows beside the records: a record says what was chosen, and for a failure only that it
  * failed. A row keeps what the reply stated and how the source judged it. So a scene that is not a
  * target stays visible with its film, a film-only answer stays apart from no assignment, and a
  * scorer can read the bundle without the records' internals. Rows are built from the judgements
  * and never parsed back out of a record. Reading a bundle re-checks the records through the
  * mapping codec and requires every row to agree with them. A bundle holds source text and unit
  * ids; keep it outside the repository.
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
      segmentation: PreparedRecall.Segmentation
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
      extras: Vector[String]
  ): Row =
    val full = StudyReplay.unitRow(protocol, unit, reply, extras).hcursor
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
      Fields.map(f => f -> StudyRecord.judge(view, protocol, reply, f)).toMap,
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
  def sourceJson(view: TimedSourceView): Json =
    val targetGroups = view.targetGroups.toSet
    val targetCollections = view.targetCollections.toSet
    val films = collectionOfGroup(view)
    val groups = view.segments.flatMap(_.group).distinctBy(_.ordinal).sortBy(_.ordinal)
    val collections = view.segments.flatMap(_.collection).distinctBy(_.ordinal).sortBy(_.ordinal)
    Json.obj(
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

  /** The bundle document. Records are embedded as their codec JSON, unchanged. */
  def encode(
      provenance: Provenance,
      view: TimedSourceView,
      records: Map[String, MappingResult],
      rows: Vector[Row]
  ): Json =
    Json.obj(
      "schema" -> str(Schema),
      "provenance" -> Json.obj(
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
      ),
      "meaning" -> Json.obj(
        "narrative" -> str("the scene the retelling is narrating at this unit"),
        "referent" -> str("the scene containing the specifically mentioned content"),
        "stated" -> str("the answer as the reply gave it, in the form a scorer reads"),
        "judgement" -> str(
          "the answer decided against the source: scene, film (a film with no particular " +
            "scene), none, invalid-scene (a real scene that is not a target), unknown-scene, " +
            "unknown-film, invalid, omitted, unparsable-chunk"
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
      "source" -> sourceJson(view)
    )

  /** What a reader recovers: both records, re-checked, and the unit rows as written. */
  final case class Loaded(records: Map[String, MappingResult], units: Vector[Json])

  private def agrees(view: TimedSourceView, record: MappingResult, kinds: Vector[Json]): Boolean =
    kinds.size == record.outcomes.size && kinds.zip(record.outcomes).forall { (j, outcome) =>
      val c = j.hcursor
      val complete = outcome.processing == ProcessingStatus.Complete
      val chosen = outcome.decision.flatMap(_.chosen)
      c.get[String]("kind").toOption match
        case Some("scene") =>
          complete && c.get[Int]("scene").toOption.flatMap(view.groupRefs.get).exists { ref =>
            chosen.contains(Destination.Target(ref))
          }
        case Some("film") =>
          complete && c.get[Int]("collection").toOption.flatMap(view.collectionRefs.get).exists {
            ref => chosen.contains(Destination.Target(ref))
          }
        case Some("none") => complete && chosen.isEmpty
        case Some(_)      => !complete
        case None         => false
    }

  private val FailureKinds = Set("invalid", "omitted", "unparsable-chunk")
  private def failureKind(j: Json): Boolean =
    j.hcursor.get[String]("kind").exists(FailureKinds)

  /** Read a bundle against a regenerated source and recall. Each record is decoded through the
    * mapping codec, which re-derives and compares it, and must carry its slot's policy. Each row
    * must be the unit the recall has in that place, its judgement must be what its stated answer
    * gives against this source, and it must agree with both records. Provenance and the source
    * dictionary are read as written.
    */
  def decode(bundle: Json, view: TimedSourceView, recall: PreparedRecall): Either[String, Loaded] =
    val c = bundle.hcursor
    val context = ExpectedMappingContext(recall.inventory, view.representation)
    for
      _ <- Either.cond(c.get[String]("schema").contains(Schema), (), "not a v2 mapping bundle")
      condition <- c.downField("provenance").get[String]("condition").left.map(_ => "no condition")
      protocol <- c.downField("provenance").get[String]("protocol").left.map(_ => "no protocol")
      units <- c.get[Vector[Json]]("units").left.map(_ => "no units")
      _ <- Either.cond(
        units.map(_.hcursor.get[String]("unit").toOption) == recall.unitIds.map(u => Some(u.value)),
        (),
        "unit rows are not the recall's units"
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
            _ <- Either.cond(
              record.policies.decision ==
                DecisionPolicy.Declared(StudyRecord.policy(condition, protocol, f)),
              (),
              s"the $f slot does not hold the $f record"
            )
            rows = units.map(_.hcursor.downField(f))
            _ <- Either.cond(
              rows.forall { r =>
                val written = r.downField("judgement").focus
                val answered = r.downField("stated").get[String]("status").contains("answered")
                r.downField("stated").focus.flatMap(StudyReplay.statedOf) match
                  case Some(s) => written.contains(judgementJson(view, StudyRecord.judge(view, s)))
                  // A failure states nothing, so its judgement must be a failure too.
                  case None => !answered && written.exists(failureKind)
              },
              (),
              s"a $f judgement is not what its stated answer gives against this source"
            )
            _ <- Either.cond(
              agrees(view, record, rows.flatMap(_.downField("judgement").focus)),
              (),
              s"a unit row disagrees with the $f record"
            )
          yield done.updated(f, record)
      }
    yield Loaded(records, units)
