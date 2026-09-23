package storymodel4s.codec

import io.circe.Json
import storymodel4s.core.*
import storymodel4s.features.CanonicalDouble
import storymodel4s.proposition.ParticipantRole
import storymodel4s.story.*

/** Declared-lossy analysis tables derived from one validated text StoryModel (ADR 0020).
  *
  * Why lossy on purpose: the canonical, lossless interchange is `StoryModelCodec`; these tables are
  * for R and Python users and name every structure they leave out in the manifest's loss records.
  * The wire is the mapping exchange's (`quoted-tsv/v1`), so one reader discipline covers both.
  */
object StoryModelExport:
  val SchemaVersion: String = "storymodel-export/v0.1"

  enum Error:
    /** Playback-anchored support met in a text model; it is never cast to character offsets. */
    case PlaybackSupport(claim: String)

    /** A text cell holds an unpaired UTF-16 surrogate and cannot be written as UTF-8. */
    case InvalidUtf16(file: String)

  /** Names are fixed by this schema; contents encode as UTF-8 without a BOM. */
  final class Bundle private[StoryModelExport] (val files: Vector[(String, String)]):
    def manifest: String = files.head._2
    def digest: Checksum = Checksum.ofText(manifest)

  /** One structure the tables do not carry.
    *
    * `dropped` counts items observed in the input; `None` means the input never supplied the
    * structure, which is a different fact from supplying zero of it. `claims` counts the
    * `ClaimMeta` of `StoryModel.claims` that leave with it, for the accounting law.
    */
  final case class Loss(structure: String, dropped: Option[Int], claims: Int, reason: String)

  private enum CellType(val wire: String):
    case Text extends CellType("utf8-string")
    case Integer extends CellType("decimal-integer")
    case Bits extends CellType("ieee754-binary64-hex")
    case Structured extends CellType("canonical-json")

  private final case class Column(name: String, kind: CellType)
  private final case class Table(
      name: String,
      columns: Vector[Column],
      rows: Vector[Vector[String]]
  ):
    def text: String =
      (columns.map(_.name) +: rows).map(_.map(quote).mkString("\t")).mkString("", "\n", "\n")
    def schema: Json = Json.fromValues(
      columns.map(c =>
        Json.obj("name" -> Json.fromString(c.name), "type" -> Json.fromString(c.kind.wire))
      )
    )

  private def quote(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

  private def validUtf16(text: String): Boolean =
    var index = 0
    var valid = true
    while index < text.length && valid do
      val c = text.charAt(index)
      if c >= '\ud800' && c <= '\udbff' then
        valid = index + 1 < text.length && text.charAt(index + 1) >= '\udc00' && text.charAt(
          index + 1
        ) <= '\udfff'
        index += 2
      else
        valid = !(c >= '\udc00' && c <= '\udfff')
        index += 1
    valid

  // ---- cell encodings -------------------------------------------------------------------------
  private def json(value: Json): String = MappingJson.print(value)
  private def tagged(status: String, fields: (String, Json)*): String =
    json(MappingJson.tagged(status, fields*))
  private val notApplicable: String = tagged("not-applicable")
  private def present(value: Json): String = tagged("present", "value" -> value)
  private def absent(reason: String): String =
    tagged("absent", "reason" -> Json.fromString(reason))
  private def optional[A](value: Option[A], reason: String)(encode: A => Json): String =
    value.fold(absent(reason))(v => present(encode(v)))

  /** A vocabulary value; custom terms keep namespace and label apart (ADR 0020 §4). */
  private def standard(value: String): String =
    tagged("standard", "value" -> Json.fromString(value))
  private def custom(namespace: String, label: String): String =
    tagged(
      "custom",
      "namespace" -> Json.fromString(namespace),
      "label" -> Json.fromString(label)
    )
  private def entityType(t: EntityType): String = t match
    case EntityType.Custom(ns, l) => custom(ns, l)
    case other                    => standard(other.toString)
  private def role(r: ParticipantRole): String = r match
    case ParticipantRole.Custom(ns, l) => custom(ns, l)
    case other                         => standard(other.toString)
  private def entityRelation(r: EntityRelation): String = r match
    case EntityRelation.Custom(ns, l) => custom(ns, l)
    case other                        => standard(other.toString)

  private def holder(kind: ContextKind): String = kind.heldBy match
    case None                                 => notApplicable
    case Some(ContextHolder.Named(entity))    => tagged("named", "entity" -> Json.fromString(entity.value))
    case Some(ContextHolder.Unattributed(gap)) =>
      tagged("unattributed", "reason" -> Json.fromString(gap.render))
  private def contextKindName(kind: ContextKind): String = kind match
    case ContextKind.NarratedWorld  => "NarratedWorld"
    case ContextKind.Speech(_)      => "Speech"
    case ContextKind.Belief(_)      => "Belief"
    case ContextKind.Desire(_)      => "Desire"
    case ContextKind.Intention(_)   => "Intention"
    case ContextKind.Hypothetical   => "Hypothetical"
    case ContextKind.Counterfactual => "Counterfactual"
    case ContextKind.Memory(_)      => "Memory"
    case ContextKind.Imagination(_) => "Imagination"

  private def summary(s: SegmentSummary): String = s match
    case SegmentSummary.Stated(r) => tagged("stated", "value" -> Json.fromString(r.value))
    case SegmentSummary.Unsummarized(gap) =>
      tagged("unsummarized", "reason" -> Json.fromString(gap.render))

  // ---- tables ---------------------------------------------------------------------------------
  /** The tables carry these claims; everything else in `StoryModel.claims` is a counted loss. */
  private final case class Exported(meta: ClaimMeta, support: Option[TypedSupport])

  /** Encode a validated text model. Drafts are refused by type; there is no unchecked overload. */
  def encode(model: TextModel[ModelStatus.Validated]): Either[Error, Bundle] =
    import CellType.*
    val digest = StoryModelCodec.contentChecksum(model).hex
    val graph = model.graph
    val entities = graph.entities.values.toVector.sortBy(_.id)
    val situations = graph.situations.values.toVector.sortBy(_.id)
    val contexts = graph.contexts.values.toVector.sortBy(_.id)
    val segments = graph.segments.values.toVector.sortBy(_.id)
    val layers = graph.relations
    val position = model.discoursePosition

    def table(name: String, columns: Vector[(String, CellType)], rows: Vector[Vector[String]]) =
      Table(
        name + ".tsv",
        Column("model_digest", Text) +: columns.map(Column.apply.tupled),
        rows.map(digest +: _)
      )

    val nodeRows =
      entities.map(e => Vector(e.id.value, "entity", e.meta.id.value, e.meta.status.toString, notApplicable)) ++
        situations.map(s =>
          Vector(
            s.id.value,
            s.kindName,
            s.meta.id.value,
            s.meta.status.toString,
            present(Json.fromString(s.context.value))
          )
        ) ++
        contexts.map(c =>
          Vector(
            c.id.value,
            "context",
            c.meta.id.value,
            c.meta.status.toString,
            optional(c.parent, "root-context")(p => Json.fromString(p.value))
          )
        ) ++
        segments.map(s =>
          Vector(s.id.value, "segment", s.meta.id.value, s.meta.status.toString, notApplicable)
        )

    val entityRows = entities.map(e => Vector(e.id.value, entityType(e.entityType), e.label.value))
    val situationRows = situations.map { s =>
      val aspect = s match
        case SituationNode.Event(n) =>
          optional(n.aspect, "not-supplied")(a => Json.fromString(a.toString))
        case SituationNode.State(_) => notApplicable
      Vector(
        s.id.value,
        s.kindName,
        s.predicate.lemma,
        optional(s.predicate.frame, "not-supplied")(Json.fromString),
        s.predicate.gloss,
        s.description,
        s.polarity.toString,
        s.modality.toString,
        aspect,
        optional(position.get(s.id), "not-in-discourse-order")(Json.fromInt)
      )
    }
    val contextRows =
      contexts.map(c => Vector(c.id.value, contextKindName(c.kind), holder(c.kind)))
    val segmentRows = segments.map(s =>
      Vector(s.id.value, s.kind.toString, s.level.toString, summary(s.summary))
    )

    def relation(
        meta: ClaimMeta,
        layer: String,
        from: String,
        rel: String,
        to: String,
        context: String
    ): Vector[String] = Vector(meta.id.value, layer, from, rel, to, context, meta.status.toString)
    val relationRows =
      layers.participants.map(e =>
        relation(e.meta, "participant", e.situation.value, role(e.role), e.entity.value, notApplicable)
      ) ++ layers.temporal.map(e =>
        relation(
          e.meta,
          "temporal",
          e.from.value,
          standard(e.relation.toString),
          e.to.value,
          present(Json.fromString(e.context.value))
        )
      ) ++ layers.causal.map(e =>
        relation(
          e.meta,
          "causal",
          e.cause.value,
          standard(e.relation.toString),
          e.effect.value,
          notApplicable
        )
      ) ++ layers.goals.map(e =>
        relation(e.meta, "goal", e.from.value, standard(e.relation.toString), e.to.value, notApplicable)
      ) ++ layers.stateChanges.map(e =>
        relation(
          e.meta,
          "state-change",
          e.event.value,
          standard(e.change.toString),
          e.state.value,
          notApplicable
        )
      ) ++ layers.references.map(e =>
        relation(e.meta, "reference", e.from.value, standard(e.mode.toString), e.to.value, notApplicable)
      ) ++ layers.entityRelations.map(e =>
        relation(
          e.meta,
          "entity-relation",
          e.from.value,
          entityRelation(e.relation),
          e.to.value,
          notApplicable
        )
      )
    val circumstanceRows = layers.circumstances.map(e =>
      Vector(e.meta.id.value, e.situation.value, e.kind.render, e.label, e.meta.status.toString)
    )
    val hierarchyRows = model.hierarchy.containment.map { e =>
      val (kind, member) = e.member match
        case NarrativeMember.Situation(id) => ("situation", id.value)
        case NarrativeMember.Segment(id)   => ("segment", id.value)
      Vector(
        e.meta.id.value,
        kind,
        member,
        e.parent.value,
        e.kind.toString,
        CanonicalDouble.render(e.weight),
        e.meta.status.toString
      )
    }

    val exported: Vector[Exported] =
      entities.map(e => Exported(e.meta, Some(e.support))) ++
        situations.map(s => Exported(s.meta, Some(s.support))) ++
        contexts.map(c => Exported(c.meta, Some(c.support))) ++
        segments.map(s => Exported(s.meta, Some(s.support))) ++
        (layers.participants.map(_.meta) ++ layers.temporal.map(_.meta) ++
          layers.causal.map(_.meta) ++ layers.goals.map(_.meta) ++
          layers.stateChanges.map(_.meta) ++ layers.references.map(_.meta) ++
          layers.entityRelations.map(_.meta)).map(Exported(_, None)) ++
        layers.circumstances.map(e => Exported(e.meta, Some(e.support))) ++
        model.hierarchy.containment.map(e => Exported(e.meta, None))

    def spanRows(
        claim: ClaimId,
        origin: String,
        evidenceIndex: String,
        spans: SpanSet
    ): Vector[Vector[String]] =
      spans.refs.toVector.zipWithIndex.map { (ref, i) =>
        Vector(
          claim.value,
          origin,
          evidenceIndex,
          i.toString,
          optional(ref.unit, "not-supplied")(u => Json.fromString(u.value)),
          ref.span.start.toString,
          ref.span.endExclusive.toString
        )
      }

    def evidenceTables(item: Exported): Either[Error, (Vector[Vector[String]], Vector[Vector[String]])] =
      val claim = item.meta.id
      val support: Either[Error, Vector[Vector[String]]] = item.support match
        case None                          => Right(Vector.empty)
        case Some(TypedSupport.Text(s))    => Right(spanRows(claim, "node-support", notApplicable, s))
        case Some(TypedSupport.Anchored(_)) => Left(Error.PlaybackSupport(claim.value))
      val items = item.meta.evidence.toVector.zipWithIndex
      if items.exists(_._1.anchors.isDefined) then Left(Error.PlaybackSupport(claim.value))
      else
        support.map { supportRows =>
          val evidenceRows = items.map { (ev, i) =>
            Vector(
              claim.value,
              i.toString,
              ev.id.value,
              ev.stage.value,
              // An empty set is a measured "no upstream claims", so it is present, not absent.
              present(Json.fromValues(ev.upstream.toVector.map(_.value).sorted.map(Json.fromString))),
              ev.spans.fold(0)(_.refs.length).toString
            )
          }
          val evidenceSpans = items.flatMap { (ev, i) =>
            ev.spans.toVector.flatMap(s =>
              spanRows(claim, "claim-evidence", present(Json.fromInt(i)), s)
            )
          }
          (evidenceRows, supportRows ++ evidenceSpans)
        }

    val evidenceAll = exported.foldLeft[Either[Error, (Vector[Vector[String]], Vector[Vector[String]])]](
      Right((Vector.empty, Vector.empty))
    ) { (acc, item) =>
      for
        (ev, sp) <- acc
        (ev2, sp2) <- evidenceTables(item)
      yield (ev ++ ev2, sp ++ sp2)
    }

    evidenceAll.flatMap { (evidenceRows, spanRowsAll) =>
      val tables = Vector(
        table(
          "nodes",
          Vector(
            "node_id" -> Text,
            "node_kind" -> Text,
            "claim_id" -> Text,
            "claim_status" -> Text,
            "context" -> Structured
          ),
          nodeRows
        ),
        table("entities", Vector("node_id" -> Text, "entity_type" -> Structured, "label" -> Text), entityRows),
        table(
          "situations",
          Vector(
            "node_id" -> Text,
            "situation_kind" -> Text,
            "predicate_lemma" -> Text,
            "frame" -> Structured,
            "gloss" -> Text,
            "description" -> Text,
            "polarity" -> Text,
            "modality" -> Text,
            "aspect" -> Structured,
            "discourse_position" -> Structured
          ),
          situationRows
        ),
        table(
          "contexts",
          Vector("node_id" -> Text, "context_kind" -> Text, "holder" -> Structured),
          contextRows
        ),
        table(
          "segments",
          Vector(
            "node_id" -> Text,
            "segment_kind" -> Text,
            "level" -> Integer,
            "summary" -> Structured
          ),
          segmentRows
        ),
        table(
          "relations",
          Vector(
            "claim_id" -> Text,
            "layer" -> Text,
            "from" -> Text,
            "relation" -> Structured,
            "to" -> Text,
            "context" -> Structured,
            "claim_status" -> Text
          ),
          relationRows
        ),
        table(
          "circumstances",
          Vector(
            "claim_id" -> Text,
            "situation" -> Text,
            "circumstance_kind" -> Text,
            "label" -> Text,
            "claim_status" -> Text
          ),
          circumstanceRows
        ),
        table(
          "hierarchy",
          Vector(
            "claim_id" -> Text,
            "member_kind" -> Text,
            "member" -> Text,
            "parent" -> Text,
            "hierarchy_kind" -> Text,
            "weight" -> Bits,
            "claim_status" -> Text
          ),
          hierarchyRows
        ),
        table(
          "evidence",
          Vector(
            "claim_id" -> Text,
            "evidence_index" -> Integer,
            "evidence_id" -> Text,
            "stage" -> Text,
            "upstream" -> Structured,
            "span_count" -> Integer
          ),
          evidenceRows
        ),
        table(
          "spans",
          Vector(
            "claim_id" -> Text,
            "origin" -> Text,
            "evidence_index" -> Structured,
            "ref_index" -> Integer,
            "surface_unit" -> Structured,
            "utf16_start" -> Integer,
            "utf16_end_exclusive" -> Integer
          ),
          spanRowsAll
        )
      )
      tables
        .find(t => t.rows.exists(_.exists(cell => !validUtf16(cell))))
        .map(t => Error.InvalidUtf16(t.name))
        .toLeft(())
        .map { _ =>
          val contents = tables.map(t => t.name -> t.text)
          val files = contents.map { (name, text) =>
            val t = tables.find(_.name == name).get
            Json.obj(
              "name" -> Json.fromString(name),
              "sha256" -> Json.fromString(Checksum.ofText(text).hex),
              "bytes" -> Json.fromString(text.getBytes("UTF-8").length.toString),
              "format" -> Json.fromString("quoted-tsv/v1"),
              "table" -> MappingJson.tagged(
                "present",
                "columns" -> t.schema,
                "rows" -> Json.fromString(t.rows.size.toString)
              )
            )
          }
          val manifest = MappingJson.print(
            Json.obj(
              "schemaVersion" -> Json.fromString(SchemaVersion),
              "model_digest" -> Json.fromString(digest),
              "model_status" -> Json.fromString("validated"),
              "storymodel_schema_version" -> Json.fromString(model.schemaVersion),
              "story_id" -> Json.fromString(model.storyId.value),
              "source_checksum" -> Json.fromString(model.sourceChecksum.hex),
              "capabilities" -> Json.obj(
                "text_evidence" -> MappingJson.tagged("present"),
                "playback_evidence" -> unavailable("text-model-only/v0.1"),
                "canonical_model" -> unavailable("not-embedded/v0.1")
              ),
              "exported_claims" -> Json.fromString(exported.size.toString),
              "losses" -> Json.fromValues(losses(model, exported.size, evidenceRows.size).map(render)),
              "files" -> Json.fromValues(files)
            )
          )
          new Bundle(("manifest.json" -> manifest) +: contents)
        }
    }

  private def unavailable(reason: String): Json =
    MappingJson.tagged("unavailable", "reason" -> Json.fromString(reason))

  private def render(loss: Loss): Json = loss.dropped match
    case Some(n) =>
      MappingJson.tagged(
        "dropped",
        "structure" -> Json.fromString(loss.structure),
        "dropped" -> Json.fromString(n.toString),
        "claims" -> Json.fromString(loss.claims.toString),
        "reason" -> Json.fromString(loss.reason)
      )
    case None =>
      MappingJson.tagged(
        "not-supplied",
        "structure" -> Json.fromString(loss.structure),
        "claims" -> Json.fromString(loss.claims.toString),
        "reason" -> Json.fromString(loss.reason)
      )

  /** Every structure the tables leave out, counted from the input (ADR 0020 §6). */
  def losses(
      model: TextModel[ModelStatus.Validated],
      exportedClaims: Int,
      exportedEvidence: Int
  ): Vector[Loss] =
    val g = model.graph
    val entities = g.entities.values.toVector
    val situations = g.situations.values.toVector
    val stated = g.segments.values.toVector.flatMap(_.summary.stated)
    val attributes = entities.map(_.attributes.size).sum
    val resolvedValues = entities.size + stated.size
    val alternatives = entities.map(_.label.alternatives.size).sum + stated.map(_.alternatives.size).sum
    val steps = model.trajectory.steps.size
    Vector(
      Loss("trajectory-steps", Some(steps), steps, "flow steps and world-time transitions"),
      Loss(
        "boundary-beliefs",
        Some(model.hierarchy.boundaryBeliefs.size),
        0,
        "candidate segment boundaries"
      ),
      Loss("feature-spaces", Some(model.featureSpaces.size), 0, "feature space declarations"),
      Loss("sidecars", Some(model.sidecars.size), 0, "feature sidecar manifests"),
      Loss("feature-refs", Some(model.featureRefs.size), 0, "feature references"),
      Loss("descriptors", Some(model.descriptors.size), model.descriptors.size, "segment descriptors"),
      Loss("hypotheses", Some(model.hypotheses.size), model.hypotheses.size, "open hypotheses"),
      Loss(
        "sensory-profiles",
        Some(model.sensoryProfiles.values.map(_.size).sum),
        0,
        "sensory profiles"
      ),
      Loss("scoped-attributes", Some(attributes), attributes, "context-scoped entity attributes"),
      Loss(
        "mentions",
        Some(entities.map(_.mentions.length).sum + situations.map(_.mentions.length).sum),
        0,
        "entity and situation mention ids"
      ),
      Loss(
        "resolved-alternatives",
        Some(alternatives),
        0,
        "rival values of entity labels and segment summaries"
      ),
      Loss(
        "resolved-value-claims",
        Some(resolvedValues),
        resolvedValues,
        "the label and summary values are exported; their own claims are not"
      ),
      Loss(
        "claim-credence-provenance",
        Some(exportedClaims),
        0,
        "credence and provenance of every exported claim"
      ),
      Loss(
        "evidence-extractors",
        Some(exportedEvidence),
        0,
        "extractor fingerprint of every exported evidence item"
      ),
      Loss(
        "build-receipt",
        model.receipt.map(_ => 1),
        0,
        "build receipt"
      ),
      Loss("source-text", Some(1), 0, "the source text; bound by source_checksum")
    )
