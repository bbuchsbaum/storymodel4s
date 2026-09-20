package storymodel4s.core

import munit.FunSuite

class D1aProjectionSuite extends FunSuite:
  private val text =
    SourceBundle.writtenText(StorySource.fromText("A😀B").toOption.get).toOption.get
  private val spans = SpanSet
    .of(
      Vector(
        SpanRef(Some(SurfaceUnitId.unsafe("first")), TextSpan.unsafe(0, 1)),
        SpanRef(Some(SurfaceUnitId.unsafe("last")), TextSpan.unsafe(3, 4))
      )
    )
    .get
  private val film = SourceBundle
    .filmEdition(
      EditionId.unsafe("projection-film"),
      Checksum.ofText("film"),
      0L,
      100L,
      RationalTimebase.Millisecond
    )
    .toOption
    .get
  private val anchored = EvidenceSupport
    .media(
      film,
      film.streams.head.id,
      PlaybackIntervalSet.one(PlaybackInterval.on(film.primaryAxis, 1L, 2L).toOption.get)
    )
    .toOption
    .get

  test("accepting control: text projection retains exact UTF-16 references and hull"):
    val projected = PrimaryProjection.on(text, TypedSupport.Text(spans)).toOption.get
    assertEquals(projected, PrimaryProjection.TextSpans(text.primaryAxis.id, spans))
    assertEquals(projected.bounds, (0L, 4L))
    assertEquals(TypedSupport.Text(spans).textSpans, Some(spans))
    assertEquals(TypedSupport.Anchored(anchored).textSpans, None)

  test("zero-length and out-of-extent spans remain deferred text validator concerns"):
    Vector(TextSpan.unsafe(1, 1), TextSpan.unsafe(8, 10)).foreach { span =>
      val support = SpanSet.one(span)
      assertEquals(
        PrimaryProjection.on(text, TypedSupport.Text(support)),
        Right(PrimaryProjection.TextSpans(text.primaryAxis.id, support))
      )
    }

  test("text projection refuses anchored support"):
    assert(PrimaryProjection.on(text, TypedSupport.Anchored(anchored)).isLeft)

  test("text projection refuses a non-text primary even with Text support"):
    assert(PrimaryProjection.on(film, TypedSupport.Text(spans)).isLeft)
