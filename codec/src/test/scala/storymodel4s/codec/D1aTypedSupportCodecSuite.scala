package storymodel4s.codec

import io.circe.syntax.*
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.story.*
import CoreCodecs.given
import StoryCodecs.given

class D1aTypedSupportCodecSuite extends FunSuite:
  private val model = CodecFixture.draft
  private val spans = SpanSet.of(Vector(
    SpanRef(Some(SurfaceUnitId.unsafe("left")), TextSpan.unsafe(0, 1)),
    SpanRef(Some(SurfaceUnitId.unsafe("right")), TextSpan.unsafe(3, 4))
  )).get
  private val film = SourceBundle.filmEdition(EditionId.unsafe("typed-codec-film"),
    Checksum.ofText("film"), 0L, 100L, RationalTimebase.Millisecond).toOption.get
  private val support = EvidenceSupport.media(film, film.streams.head.id,
    PlaybackIntervalSet.one(PlaybackInterval.on(film.primaryAxis, 1L, 2L).toOption.get)).toOption.get
  private val anchored: TypedSupport = TypedSupport.Anchored(support)

  test("accepting control: Text support has exact historical bytes and references"):
    val typed: TypedSupport = TypedSupport.Text(spans)
    assertEquals(Canonical.print(typed.asJson), Canonical.print(spans.asJson))
    assertEquals(typed.asJson.as[TypedSupport], Right(typed))
    val encoded = StoryModelCodec.encode(model)
    assertEquals(StoryModelCodec.decode(encoded), Right(model))
    assert(!encoded.contains("evidence-support/v1"))
    assertEquals(model.schemaVersion, "0.7.0")

  test("Anchored component encodes full support and text decode refuses it"):
    assertEquals(anchored.asJson, support.asJson)
    assertEquals(anchored.asJson.hcursor.get[String]("schema"), Right("evidence-support/v1"))
    assert(anchored.asJson.as[TypedSupport].isLeft)

  test("all six narrative support components preserve Text and refuse Anchored decoding"):
    val g = model.graph
    val entity = g.entities.values.head
    val event = g.situations.values.collectFirst { case SituationNode.Event(n) => n }.get
    val state = StateNode(event.id, event.predicate, event.description, event.context, event.polarity,
      event.modality, event.support, event.mentions, event.meta)
    val segment = g.segments.values.head
    val context = g.contexts.values.head
    val circumstance = CircumstanceEdge(event.id, CircumstanceKind.Time, "time", spans, event.meta)
    assertEquals(entity.asJson.as[EntityNode], Right(entity))
    assertEquals(event.asJson.as[EventNode], Right(event))
    assertEquals(state.asJson.as[StateNode], Right(state))
    assertEquals(segment.asJson.as[SegmentNode], Right(segment))
    assertEquals(context.asJson.as[ContextFrame], Right(context))
    assertEquals(circumstance.asJson.as[CircumstanceEdge], Right(circumstance))
    assert(entity.copy(support = anchored).asJson.as[EntityNode].isLeft)
    assert(event.copy(support = anchored).asJson.as[EventNode].isLeft)
    assert(state.copy(support = anchored).asJson.as[StateNode].isLeft)
    assert(segment.copy(support = anchored).asJson.as[SegmentNode].isLeft)
    assert(context.copy(support = anchored).asJson.as[ContextFrame].isLeft)
    assert(circumstance.copy(support = anchored).asJson.as[CircumstanceEdge].isLeft)
