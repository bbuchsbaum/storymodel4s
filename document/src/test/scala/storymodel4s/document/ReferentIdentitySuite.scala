package storymodel4s.document

import munit.FunSuite
import storymodel4s.core.*

/** Laws of ADR 0024: only licensed bases join or separate referent mentions. */
class ReferentIdentitySuite extends FunSuite:
  private val film: SourceBundle =
    SourceBundle
      .filmEdition(
        EditionId.unsafe("referent-film"),
        Checksum.ofText("referent-picture"),
        0L,
        1000L,
        RationalTimebase.Millisecond
      )
      .toOption
      .get
  private val stream = film.streams.head.id
  private def iv(start: Long, end: Long): PlaybackInterval =
    PlaybackInterval.on(film.primaryAxis, start, end).toOption.get
  private def ivs(spans: (Long, Long)*): PlaybackIntervalSet =
    PlaybackIntervalSet.of(spans.toVector.map(iv)).toOption.get
  private def anchored(anchors: EvidenceAnchor*): TypedSupport =
    TypedSupport.Anchored(EvidenceSupport.of(film, anchors.toVector).toOption.get)
  private def onTrack(track: String, start: Long, end: Long): TypedSupport =
    anchored(EvidenceAnchor.Track(film.id, stream, TrackId.unsafe(track), ivs(start -> end)))
  private def spoken(start: Long, end: Long): TypedSupport =
    anchored(EvidenceAnchor.MediaTime(film.id, stream, film.primaryAxis.id, ivs(start -> end)))
  private def id(raw: String): ReferentMentionId = ReferentMentionId.unsafe(raw)
  private def name(raw: String): ProperName = ProperName.of(raw).toOption.get
  private def mention(
      raw: String,
      origin: MentionOrigin,
      support: TypedSupport,
      named: Option[String] = None
  ): ReferentMention =
    ReferentMention.of(id(raw), origin, support, named.map(name)).toOption.get

  private val speech = MentionOrigin.Dialogue(ContextId.unsafe("speech-1"))
  private val v1 = mention("v1", MentionOrigin.Visual, onTrack("t1", 100L, 200L))
  private val v2 = mention("v2", MentionOrigin.Visual, onTrack("t1", 300L, 400L))
  private val v3 = mention("v3", MentionOrigin.Visual, onTrack("t2", 100L, 200L))
  private val crowded = mention("d1", speech, spoken(150L, 180L))
  private val alone = mention("d2", speech, spoken(350L, 380L))
  private val recalled = mention(
    "r1",
    MentionOrigin.Recall(SurfaceUnitId.unsafe("u1")),
    TypedSupport.Text(SpanSet.one(TextSpan.unsafe(0, 5)))
  )
  private val all = Vector(v1, v2, v3, crowded, alone, recalled)
  private def context(coverage: Map[TrackId, PlaybackIntervalSet]): ReferentContext =
    ReferentContext.of(all, coverage).toOption.get
  private val ctx = context(Map(TrackId.unsafe("t1") -> ivs(100L -> 400L)))
  private def same(a: String, b: String, basis: IdentityBasis, in: ReferentContext = ctx) =
    IdentityLink.of(in, id(a), id(b), basis)
  private def apart(a: String, b: String, basis: DistinctnessBasis, in: ReferentContext = ctx) =
    DistinctLink.of(in, id(a), id(b), basis)
  private def refusedWith(result: Either[ReferentError, ?], fragment: String): Unit =
    result match
      case Left(e)  => assert(e.message.contains(fragment), e.message)
      case Right(v) => fail(s"expected a refusal containing '$fragment', got $v")

  test("a proper name is never blank and compares folded"):
    assertEquals(ProperName.of("  "), Left(ReferentError.BlankName))
    assertEquals(name("Mary  Ann"), name("mary ann"))

  test("a visual mention must sit in media time"):
    refusedWith(
      ReferentMention.of(id("x"), MentionOrigin.Visual, recalled.support, None),
      "media time"
    )

  test("a recall mention must sit in transcript text"):
    refusedWith(
      ReferentMention
        .of(id("x"), MentionOrigin.Recall(SurfaceUnitId.unsafe("u")), v1.support, None),
      "transcript"
    )

  test("a dialogue mention may sit in text or media"):
    assert(ReferentMention.of(id("x"), speech, recalled.support, None).isRight)
    assert(ReferentMention.of(id("y"), speech, v1.support, None).isRight)

  test("a context refuses duplicate mention ids"):
    assertEquals(
      ReferentContext.of(Vector(v1, v1), Map.empty),
      Left(ReferentError.DuplicateMention(id("v1")))
    )

  test("a track joins two visual mentions inside one stretch of its coverage"):
    assert(same("v1", "v2", IdentityBasis.ContinuousTrack(TrackId.unsafe("t1"))).isRight)

  test("a gap in track coverage refuses the track basis"):
    val gappy = context(Map(TrackId.unsafe("t1") -> ivs(100L -> 250L, 300L -> 400L)))
    refusedWith(
      same("v1", "v2", IdentityBasis.ContinuousTrack(TrackId.unsafe("t1")), gappy),
      "one stretch"
    )

  test("a track with no recorded coverage refuses the track basis"):
    refusedWith(
      same("v1", "v2", IdentityBasis.ContinuousTrack(TrackId.unsafe("t1")), context(Map.empty)),
      "one stretch"
    )

  test("a mention off the track refuses the track basis"):
    refusedWith(same("v1", "v3", IdentityBasis.ContinuousTrack(TrackId.unsafe("t1"))), "not on")

  test("a track joins only visual mentions"):
    refusedWith(
      same("v1", "d1", IdentityBasis.ContinuousTrack(TrackId.unsafe("t1"))),
      "only visual"
    )

  test("deixis joins an utterance to the one person present"):
    assert(same("d2", "v2", IdentityBasis.DeicticReference).isRight)
    assert(same("v2", "d2", IdentityBasis.DeicticReference).isRight)

  test("deixis refuses when more than one person is present"):
    refusedWith(same("d1", "v1", IdentityBasis.DeicticReference), "more than one")

  test("deixis refuses when the one person present is another"):
    refusedWith(same("d2", "v1", IdentityBasis.DeicticReference), "another")

  test("deixis refuses when nobody is depicted while it is said"):
    val quiet = ReferentContext.of(Vector(v1, alone), Map.empty).toOption.get
    refusedWith(
      IdentityLink.of(quiet, id("d2"), id("v1"), IdentityBasis.DeicticReference),
      "nobody"
    )

  test("deixis joins one utterance mention to one visual mention only"):
    refusedWith(same("v1", "v2", IdentityBasis.DeicticReference), "one utterance")

  test("a name joins mentions only when attached to both"):
    val named = Vector(
      mention("n1", MentionOrigin.Narration, recalled.support, Some("Ana")),
      mention("n2", MentionOrigin.Visual, v1.support, Some("ana")),
      mention("n3", MentionOrigin.Narration, recalled.support, None)
    )
    val in = ReferentContext.of(named, Map.empty).toOption.get
    assert(
      IdentityLink.of(in, id("n1"), id("n2"), IdentityBasis.NamedReference(name("Ana"))).isRight
    )
    refusedWith(
      IdentityLink.of(in, id("n1"), id("n3"), IdentityBasis.NamedReference(name("Ana"))),
      "not attached"
    )

  test("a link needs two different known mentions"):
    assertEquals(
      same("v1", "v1", IdentityBasis.DeicticReference),
      Left(ReferentError.SameMention(id("v1")))
    )
    assertEquals(
      same("v1", "zz", IdentityBasis.DeicticReference),
      Left(ReferentError.UnknownMention(id("zz")))
    )

  test("simultaneous tracks on one stream separate two visual mentions"):
    assert(apart("v1", "v3", DistinctnessBasis.SimultaneousTracks).isRight)

  test("simultaneity needs overlapping time"):
    refusedWith(apart("v2", "v3", DistinctnessBasis.SimultaneousTracks), "do not overlap")

  test("simultaneity cannot separate mentions sharing a track"):
    refusedWith(apart("v1", "v2", DistinctnessBasis.SimultaneousTracks), "share a track")

  test("simultaneity separates only visual mentions"):
    refusedWith(apart("v1", "d1", DistinctnessBasis.SimultaneousTracks), "only visual")

  test("different names separate; the same name or a missing one does not"):
    val named = Vector(
      mention("n1", MentionOrigin.Narration, recalled.support, Some("Ana")),
      mention("n2", MentionOrigin.Narration, recalled.support, Some("Beth")),
      mention("n3", MentionOrigin.Narration, recalled.support, Some("ANA")),
      mention("n4", MentionOrigin.Narration, recalled.support, None)
    )
    val in = ReferentContext.of(named, Map.empty).toOption.get
    assert(DistinctLink.of(in, id("n1"), id("n2"), DistinctnessBasis.DifferentNames).isRight)
    refusedWith(DistinctLink.of(in, id("n1"), id("n3"), DistinctnessBasis.DifferentNames), "same")
    refusedWith(
      DistinctLink.of(in, id("n1"), id("n4"), DistinctnessBasis.DifferentNames),
      "need a name"
    )

  private def resolve(
      identities: Vector[IdentityLink],
      distinctions: Vector[DistinctLink] = Vector.empty,
      hints: Vector[IdentityHint] = Vector.empty
  ): ReferentResolution =
    ReferentIdentity.resolve(ctx, identities, distinctions, hints).toOption.get
  private val track =
    same("v1", "v2", IdentityBasis.ContinuousTrack(TrackId.unsafe("t1"))).toOption.get
  private val deixis = same("d2", "v2", IdentityBasis.DeicticReference).toOption.get

  test("identity is transitive through licensed links"):
    val r = resolve(Vector(track, deixis))
    assertEquals(r.relation(id("v1"), id("d2")), Right(ReferentRelation.Same))

  test("no licensed link leaves the relation unknown, not distinct"):
    assertEquals(
      resolve(Vector.empty).relation(id("v1"), id("r1")),
      Right(ReferentRelation.Unknown)
    )

  test("a licensed distinctness between classes makes them distinct"):
    val split = apart("v1", "v3", DistinctnessBasis.SimultaneousTracks).toOption.get
    val r = resolve(Vector(track), Vector(split))
    assertEquals(r.relation(id("v2"), id("v3")), Right(ReferentRelation.Distinct))

  test("a licensed distinctness inside a class reopens it with its candidates"):
    // One track joins the two mentions while different names attached to them separate them.
    val ana = mention("a1", MentionOrigin.Visual, onTrack("t1", 100L, 200L), Some("Ana"))
    val beth = mention("b1", MentionOrigin.Visual, onTrack("t1", 300L, 400L), Some("Beth"))
    val in = ReferentContext
      .of(Vector(ana, beth), Map(TrackId.unsafe("t1") -> ivs(100L -> 400L)))
      .toOption
      .get
    val joined =
      IdentityLink.of(in, id("a1"), id("b1"), IdentityBasis.ContinuousTrack(TrackId.unsafe("t1")))
    val split = DistinctLink.of(in, id("a1"), id("b1"), DistinctnessBasis.DifferentNames)
    val r = ReferentIdentity
      .resolve(in, Vector(joined.toOption.get), Vector(split.toOption.get), Vector.empty)
      .toOption
      .get
    assertEquals(
      r.relation(id("a1"), id("b1")),
      Right(ReferentRelation.Open(Vector(id("a1"), id("b1"))))
    )
    val settled =
      ReferentIdentity.resolve(in, Vector(joined.toOption.get), Vector.empty, Vector.empty)
    assertEquals(settled.map(_.relation(id("a1"), id("b1"))), Right(Right(ReferentRelation.Same)))

  test("distinctness is not lifted through an open class"):
    // a1, a2, a3 share a track; a2 and a3 carry different names, so the class is open. A
    // simultaneous outsider b is licensed apart from a1 only.
    val a1 = mention("a1", MentionOrigin.Visual, onTrack("t1", 100L, 200L))
    val a2 = mention("a2", MentionOrigin.Visual, onTrack("t1", 300L, 400L), Some("Ana"))
    val a3 = mention("a3", MentionOrigin.Visual, onTrack("t1", 500L, 600L), Some("Beth"))
    val b = mention("b", MentionOrigin.Visual, onTrack("t9", 100L, 200L))
    val in = ReferentContext
      .of(Vector(a1, a2, a3, b), Map(TrackId.unsafe("t1") -> ivs(100L -> 600L)))
      .toOption
      .get
    val t1 = IdentityBasis.ContinuousTrack(TrackId.unsafe("t1"))
    val joins =
      Vector(("a1", "a2"), ("a2", "a3")).map((x, y) => IdentityLink.of(in, id(x), id(y), t1))
    val splits = Vector(
      DistinctLink.of(in, id("a2"), id("a3"), DistinctnessBasis.DifferentNames),
      DistinctLink.of(in, id("a1"), id("b"), DistinctnessBasis.SimultaneousTracks)
    )
    val r = ReferentIdentity
      .resolve(in, joins.map(_.toOption.get), splits.map(_.toOption.get), Vector.empty)
      .toOption
      .get
    assertEquals(r.relation(id("a3"), id("b")), Right(ReferentRelation.Unknown))
    assertEquals(r.relation(id("a1"), id("b")), Right(ReferentRelation.Distinct))
    assertEquals(
      r.relation(id("a1"), id("a3")),
      Right(ReferentRelation.Open(Vector(id("a1"), id("a2"), id("a3"))))
    )

  test("distinctness lifts to every member of two settled classes"):
    val split = apart("v1", "v3", DistinctnessBasis.SimultaneousTracks).toOption.get
    val r = resolve(Vector(track, deixis), Vector(split))
    assertEquals(r.relation(id("d2"), id("v3")), Right(ReferentRelation.Distinct))

  test("touching coverage intervals are one stretch"):
    val touching = context(Map(TrackId.unsafe("t1") -> ivs(100L -> 250L, 250L -> 400L)))
    assert(same("v1", "v2", IdentityBasis.ContinuousTrack(TrackId.unsafe("t1")), touching).isRight)

  test("a distinctness needs two different known mentions"):
    assertEquals(
      apart("v1", "v1", DistinctnessBasis.SimultaneousTracks),
      Left(ReferentError.SameMention(id("v1")))
    )
    assertEquals(
      apart("v1", "zz", DistinctnessBasis.SimultaneousTracks),
      Left(ReferentError.UnknownMention(id("zz")))
    )

  test("contributory hints alone never decide identity or distinctness"):
    val toward =
      IdentityHint(id("v1"), id("v3"), ContributoryBasis.AppearanceMatch, HintDirection.Toward)
    val against =
      IdentityHint(id("v1"), id("v2"), ContributoryBasis.TemporalProximity, HintDirection.Against)
    val r = resolve(Vector(track), hints = Vector(toward, against))
    assertEquals(r.relation(id("v1"), id("v3")), Right(ReferentRelation.Unknown))
    assertEquals(r.relation(id("v1"), id("v2")), Right(ReferentRelation.Same))
    assertEquals(r.hintsBetween(id("v1"), id("v3")), Vector(toward))

  test("deixis licensed in a pruned context is refused in the real one"):
    // Only v1 is present when the crowd is pruned away, so the pruned context licenses deixis.
    val pruned = ReferentContext.of(Vector(v1, crowded), Map.empty).toOption.get
    val laundered = IdentityLink.of(pruned, id("d1"), id("v1"), IdentityBasis.DeicticReference)
    assert(laundered.isRight)
    assertEquals(
      ReferentIdentity.resolve(ctx, Vector(laundered.toOption.get), Vector.empty, Vector.empty),
      Left(ReferentError.ForeignLink(id("d1"), id("v1")))
    )

  test("a track link licensed under other coverage is refused in a gappy context"):
    val gappy = context(Map(TrackId.unsafe("t1") -> ivs(100L -> 250L, 300L -> 400L)))
    assertEquals(
      ReferentIdentity.resolve(gappy, Vector(track), Vector.empty, Vector.empty),
      Left(ReferentError.ForeignLink(id("v1"), id("v2")))
    )

  test("a distinctness licensed in another context is refused"):
    val other = ReferentContext.of(Vector(v1, v3), Map.empty).toOption.get
    val split = DistinctLink.of(other, id("v1"), id("v3"), DistinctnessBasis.SimultaneousTracks)
    assertEquals(
      ReferentIdentity.resolve(ctx, Vector.empty, Vector(split.toOption.get), Vector.empty),
      Left(ReferentError.ForeignLink(id("v1"), id("v3")))
    )

  test("a hint naming an unknown mention is refused"):
    val stray = IdentityHint(
      id("v1"),
      id("zz"),
      ContributoryBasis.CompatibleDescription,
      HintDirection.Toward
    )
    assertEquals(
      ReferentIdentity.resolve(ctx, Vector.empty, Vector.empty, Vector(stray)),
      Left(ReferentError.UnknownMention(id("zz")))
    )

  test("resolution does not depend on link order"):
    assertEquals(resolve(Vector(track, deixis)), resolve(Vector(deixis, track)))
