package storymodel4s.core

import munit.FunSuite

class D1aAtlasSuite extends FunSuite:
  private val film = SourceBundle
    .filmEdition(
      EditionId.unsafe("atlas-film"),
      Checksum.ofText("film"),
      0L,
      100L,
      RationalTimebase.Millisecond
    )
    .toOption
    .get
  private val support = EvidenceSupport
    .media(
      film,
      film.streams.head.id,
      PlaybackIntervalSet.one(PlaybackInterval.on(film.primaryAxis, 0L, 20L).toOption.get)
    )
    .toOption
    .get
  private val surface =
    SurfaceAnalyzer.analyze(StorySource.fromText("Alpha beta. Gamma.").toOption.get)
  private val receipt = SourceDerivationReceipt
    .of("captions", "recipe-one", Vector(Checksum.ofText("film")))
    .toOption
    .get
  private val bound = BoundProposalSurface.of(surface, receipt)
  private def unit(id: String, sentence: Option[SurfaceUnitId] = None): NarrativeProposalUnit =
    NarrativeProposalUnit.of(NarrativeProposalUnitId.unsafe(id), support, sentence)

  test("accepting control: unique units and bound sentences have exact lookup"):
    val first = unit("one", Some(surface.sentences.head.id))
    val second = unit("two", Some(surface.sentences.last.id))
    val atlas = AnchoredNarrativeAtlas.of(film, Vector(first, second), Some(bound)).toOption.get
    assertEquals(atlas.unit(first.id), Some(first))
    assertEquals(atlas.supportOf(second.id), Some(support))
    assertEquals(atlas.unit(NarrativeProposalUnitId.unsafe("missing")), None)
    assert(AnchoredNarrativeAtlas.of(film, Vector(unit("without-surface"))).isRight)

  test("refuse duplicate proposal unit IDs before map construction"):
    assert(AnchoredNarrativeAtlas.of(film, Vector(unit("same"), unit("same"))).isLeft)

  test("refuse duplicated bound sentence"):
    val sentence = Some(surface.sentences.head.id)
    assert(
      AnchoredNarrativeAtlas
        .of(film, Vector(unit("one", sentence), unit("two", sentence)), Some(bound))
        .isLeft
    )

  test("refuse missing, foreign and nonsentence proposal surfaces"):
    assert(
      AnchoredNarrativeAtlas.of(film, Vector(unit("one", Some(surface.sentences.head.id)))).isLeft
    )
    assert(
      AnchoredNarrativeAtlas
        .of(film, Vector(unit("one", Some(SurfaceUnitId.unsafe("foreign")))), Some(bound))
        .isLeft
    )
    assert(
      AnchoredNarrativeAtlas
        .of(film, Vector(unit("one", Some(surface.tokens.head.id))), Some(bound))
        .isLeft
    )

  test("refuse support belonging to another bundle"):
    val other = SourceBundle
      .filmEdition(
        EditionId.unsafe("other"),
        Checksum.ofText("other"),
        0L,
        100L,
        RationalTimebase.Millisecond
      )
      .toOption
      .get
    assert(AnchoredNarrativeAtlas.of(other, Vector(unit("one"))).isLeft)

  test("refuse text and annotation timeline primaries explicitly"):
    val text = SourceBundle.writtenText(surface.source).toOption.get
    val annotation = SourceBundle
      .annotationTable(Checksum.ofText("annotation"), 0L, 100L, RationalTimebase.Millisecond)
      .toOption
      .get
    assert(AnchoredNarrativeAtlas.of(text, Vector.empty).isLeft)
    assert(AnchoredNarrativeAtlas.of(annotation, Vector.empty).isLeft)

  test("surface binding distinguishes canonical bytes, unit structure and receipt"):
    val changedText = SurfaceAnalyzer.analyze(
      StorySource.fromText("Omega beta. Gamma.", explicitId = Some(surface.source.id)).toOption.get
    )
    assertNotEquals(bound.checksum, BoundProposalSurface.of(changedText, receipt).checksum)
    val withoutTokens = SurfaceAtlas
      .of(surface.source, surface.units.filterNot(_.kind == SurfaceUnitKind.Token))
      .toOption
      .get
    assertNotEquals(bound.checksum, BoundProposalSurface.of(withoutTokens, receipt).checksum)
    val changedReceipt =
      SourceDerivationReceipt.of("captions", "recipe-two", receipt.inputChecksums).toOption.get
    val reassociated = BoundProposalSurface.of(surface, changedReceipt)
    assertEquals(bound.checksum, reassociated.checksum)
    assertNotEquals(bound.identity, reassociated.identity)
    val reordered = SurfaceAtlas.of(surface.source, surface.units.reverse).toOption.get
    assertEquals(bound.checksum, BoundProposalSurface.of(reordered, receipt).checksum)

  test("bound surface receipt association distinguishes legacy NUL collision"):
    val left = SourceDerivationReceipt.of("a\u0000b", "c", Vector.empty).toOption.get
    val right = SourceDerivationReceipt.of("a", "b\u0000c", Vector.empty).toOption.get
    assertEquals(left.identity, right.identity)
    assertNotEquals(
      BoundProposalSurface.of(surface, left).identity,
      BoundProposalSurface.of(surface, right).identity
    )

  test("surface digest binds each unit field independently"):
    val parent = SurfaceUnit(
      SurfaceUnitId.unsafe("parent"),
      SurfaceUnitKind.Paragraph,
      TextSpan.unsafe(0, 10),
      0,
      None
    )
    val child = SurfaceUnit(
      SurfaceUnitId.unsafe("child"),
      SurfaceUnitKind.Token,
      TextSpan.unsafe(0, 3),
      0,
      None
    )
    def digest(value: SurfaceUnit): Checksum = BoundProposalSurface
      .of(SurfaceAtlas.of(surface.source, Vector(parent, value)).toOption.get, receipt)
      .checksum
    val baseline = digest(child)
    // Keep the unit before "parent" in canonical order, so ordering cannot mask an omitted ID.
    assertNotEquals(baseline, digest(child.copy(id = SurfaceUnitId.unsafe("child-renamed"))))
    assertNotEquals(baseline, digest(child.copy(kind = SurfaceUnitKind.Sentence)))
    assertNotEquals(baseline, digest(child.copy(span = TextSpan.unsafe(1, 3))))
    assertNotEquals(baseline, digest(child.copy(span = TextSpan.unsafe(0, 4))))
    assertNotEquals(baseline, digest(child.copy(ordinal = 1)))
    assertNotEquals(baseline, digest(child.copy(parent = Some(parent.id))))
