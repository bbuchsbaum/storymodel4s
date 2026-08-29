package storymodel4s.codec

import io.circe.Json
import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*

/** Contextual and adversarial laws for the gated HSMM wire artifact. */
class AlignCodecSuite extends FunSuite:

  private lazy val fixture = Fixture.build()
  private lazy val encoded = HsmmResultCodec.encode(fixture.result)
  private lazy val json = Canonical.parse(encoded).toOption.get

  test("a real inferred result has a canonical contextual round trip") {
    val decoded = HsmmResultCodec.decode(encoded, fixture.recall, fixture.view)
    assertEquals(decoded, Right(fixture.result))
    assertEquals(decoded.map(HsmmResultCodec.encode), Right(encoded))
    assert(encoded.contains(s"\"schemaVersion\":\"${HsmmResultCodec.SchemaVersion}\""))
    assert(encoded.contains("\"candidateAnchors\""))
    assert(encoded.contains("\"admissibilityEcho\""))
    assert(encoded.contains("\"viewFingerprint\""))
    assert(encoded.contains("\"recallChecksum\""))
  }

  test("a view-only change is rejected by the mandatory fingerprint match") {
    val changed = fixture.view.copy(textLength = fixture.view.textLength + 1)
    HsmmResultCodec.decode(encoded, fixture.recall, changed) match
      case Left(HsmmCodecError.Rejected(AlignError.FingerprintMismatch(field, _, _))) =>
        assertEquals(field, "viewFingerprint")
      case other => fail(s"expected a view fingerprint rejection, got $other")
  }

  test("same transcript boundaries with changed unit text fail recall-checksum matching") {
    val first = fixture.recall.ordered.head
    val changed = fixture.recall.copy(units =
      fixture.recall.units.map(unit =>
        if unit.id == first.id then unit.copy(text = unit.text + " changed") else unit
      )
    )
    HsmmResultCodec.decode(encoded, changed, fixture.view) match
      case Left(HsmmCodecError.Rejected(AlignError.FingerprintMismatch(field, _, _))) =>
        assertEquals(field, "recallChecksum")
      case other => fail(s"expected a recall checksum rejection, got $other")
  }

  test("an admissibility echo mutation is gate drift, never construction authority") {
    val changed = json.mapObject(
      _.add("admissibilityEcho", Json.fromString(Checksum.ofText("wrong echo").hex))
    )
    HsmmResultCodec.decodeJson(changed, fixture.recall, fixture.view) match
      case Left(HsmmCodecError.Rejected(AlignError.GateDrift(_, _))) => ()
      case other => fail(s"expected gate drift, got $other")
  }

  test("an anchored posterior key outside the nominated set is rejected") {
    val firstUnit = fixture.recall.ordered.head.id
    val candidates = fixture.result.candidateAnchors.updated(firstUnit, Vector.empty)
    val admissibility = fixture.recall.ordered.iterator.map { unit =>
      unit.id -> candidates(unit.id).iterator.map { ref =>
        ref -> ModeGate.assess(unit, fixture.view.node(ref).get, fixture.view)
      }.toMap
    }.toMap
    val withoutAnchor = updateFirstObjectInArray(json, "candidateAnchors") { candidate =>
      candidate.mapObject(_.add("anchors", Json.arr()))
    }
    val changed = withoutAnchor.mapObject(
      _.add(
        "admissibilityEcho",
        Json.fromString(AdmissibilityEcho.of(admissibility).checksum.hex)
      )
    )
    HsmmResultCodec.decodeJson(changed, fixture.recall, fixture.view) match
      case Left(HsmmCodecError.Rejected(AlignError.GateViolation(_, _, detail))) =>
        assert(detail.contains("not nominated"), detail)
      case other => fail(s"expected a nomination rejection, got $other")
  }

  test("a cost record whose mode contradicts its map key is rejected at the proof site") {
    val changed = updateFirstSourceCost(json) { cost =>
      val distorted = Json.obj(
        "type" -> Json.fromString("Distorted"),
        "facets" -> Json.arr(Json.fromString("Polarity"))
      )
      cost.mapObject(_.add("mode", distorted))
    }
    HsmmResultCodec.decodeJson(changed, fixture.recall, fixture.view) match
      case Left(HsmmCodecError.Rejected(AlignError.MalformedRecord(record, detail))) =>
        assert(record.nonEmpty && detail.nonEmpty)
      case other => fail(s"expected a state/cost record rejection, got $other")
  }

  test("duplicate sparse entries are rejected before conversion to Map") {
    val changed = updateFirstObjectInArray(json, "posterior") { row =>
      row.mapObject { fields =>
        val mass = fields("mass").flatMap(_.asArray).getOrElse(Vector.empty)
        fields.add("mass", Json.fromValues(mass.headOption.toVector ++ mass))
      }
    }
    HsmmResultCodec.decodeJson(changed, fixture.recall, fixture.view) match
      case Left(HsmmCodecError.Rejected(AlignError.MalformedRecord(record, _))) =>
        assertEquals(record, "AlignmentRow.mass")
      case other => fail(s"expected a duplicate-key rejection, got $other")
  }

  test("the HSMM artifact has its own required schema version") {
    val changed = json.mapObject(_.add("schemaVersion", Json.fromString("hsmm/v0")))
    HsmmResultCodec.decodeJson(changed, fixture.recall, fixture.view) match
      case Left(HsmmCodecError.Wire(error)) =>
        assert(error.message.contains("unsupported HSMM schema"), error.message)
      case other => fail(s"expected a wire-version rejection, got $other")
  }

  test("there is no context-free Decoder[HsmmResult]") {
    val errors = typeCheckErrors("""
      import io.circe.Decoder
      import storymodel4s.align.HsmmResult
      import storymodel4s.codec.HsmmResultCodec.given
      summon[Decoder[HsmmResult]]
    """)
    assert(errors.nonEmpty)
  }

  private def updateFirstObjectInArray(
      document: Json,
      field: String
  )(f: Json => Json): Json =
    document.mapObject { root =>
      val values = root(field).flatMap(_.asArray).getOrElse(Vector.empty)
      root.add(field, Json.fromValues(values.headOption.map(f).toVector ++ values.drop(1)))
    }

  private def updateFirstSourceCost(document: Json)(f: Json => Json): Json =
    document.mapObject { root =>
      val units = root("costs").flatMap(_.asArray).getOrElse(Vector.empty)
      val updated = units.map { unit =>
        unit.mapObject { unitFields =>
          val costs = unitFields("costs").flatMap(_.asArray).getOrElse(Vector.empty)
          var changed = false
          val rewritten = costs.map { stateCost =>
            val isSource =
              stateCost.hcursor.downField("state").get[String]("type") == Right("Source")
            if !changed && isSource then
              changed = true
              stateCost.mapObject { fields =>
                fields("cost").fold(fields)(cost => fields.add("cost", f(cost)))
              }
            else stateCost
          }
          unitFields.add("costs", Json.fromValues(rewritten))
        }
      }
      root.add("costs", Json.fromValues(updated))
    }

  private final case class Fixture(
      recall: RecallGraph,
      view: InMemorySourceView,
      result: HsmmResult
  )

  private object Fixture:
    def build(): Fixture =
      val source = StorySource.fromText("Anna arrived. Bob left.").toOption.get
      val sourceAtlas = SurfaceAnalyzer.analyze(source)
      val sourceSentences = sourceAtlas.sentences
      val firstRef = SourceNodeRef.Situation(SituationId.unsafe("arrive"))
      val secondRef = SourceNodeRef.Situation(SituationId.unsafe("leave"))

      def support(index: Int): SpanSet =
        val sentence = sourceSentences(index)
        SpanSet.one(SpanRef(Some(sentence.id), sentence.span))

      val view = InMemorySourceView(
        Vector(
          NodeSummary(
            firstRef,
            0,
            None,
            0,
            support(0),
            Some("arrive"),
            Vector(ParticipantSummary(SketchRole.Agent, "Anna", Set("anna"))),
            ContextTag.NarratedWorld,
            PolarityTag.Positive,
            ModalityTag.Asserted,
            Vector.empty,
            Set("anna", "arrive")
          ),
          NodeSummary(
            secondRef,
            0,
            None,
            1,
            support(1),
            Some("leave"),
            Vector(ParticipantSummary(SketchRole.Agent, "Bob", Set("bob"))),
            ContextTag.NarratedWorld,
            PolarityTag.Positive,
            ModalityTag.Asserted,
            Vector.empty,
            Set("bob", "leave")
          )
        ),
        Map(
          RelationLayer.DiscourseSuccession -> Vector((firstRef, secondRef, 1.0)),
          RelationLayer.WorldTime -> Vector((firstRef, secondRef, 1.0))
        ),
        Some(Map(firstRef -> 0, secondRef -> 1)),
        source.canonicalText.length
      )

      val transcript = StorySource.fromText("Anna arrived. Bob left.").toOption.get
      val recallAtlas = SurfaceAnalyzer.analyze(transcript)
      val units = recallAtlas.sentences.zipWithIndex.map { (sentence, index) =>
        val (name, predicate) = if index == 0 then ("Anna", "arrive") else ("Bob", "leave")
        RecallUnit(
          RecallUnitId.unsafe(s"u$index"),
          index,
          SpanSet.one(SpanRef(Some(sentence.id), sentence.span)),
          recallAtlas.text(sentence),
          DiscourseFunction.EpisodicAssertion,
          ExpressedUncertainty.Unmarked,
          PropositionSketch(
            Some(predicate),
            Vector(SketchParticipant(SketchRole.Agent, None, name)),
            PolarityTag.Positive,
            ModalityTag.Asserted,
            Vector.empty,
            Vector.empty,
            Vector.empty,
            Set(name.toLowerCase, predicate)
          ),
          None
        )
      }
      val recall = RecallGraph
        .validated(RecallGraph(transcript, recallAtlas, units, RecallRelations.empty))
        .toOption
        .get
      val candidates = Candidates.of(
        Map(units(0).id -> Vector(firstRef), units(1).id -> Vector(secondRef))
      )
      val costModel = DefaultLocalCostModel(semantic = SemanticDistance.lexicalJaccard)
      val result = GraphHsmm
        .infer(recall, view, candidates, costModel)
        .fold(error => throw new IllegalStateException(error.message), identity)
      Fixture(recall, view, result)
