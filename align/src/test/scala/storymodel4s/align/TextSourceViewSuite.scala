package storymodel4s.align

import munit.FunSuite
import storymodel4s.align.bridge.TextSourceView
import storymodel4s.core.*

/** Synthetic coordinate diagnostics, not narrative or participant data. */
class TextSourceViewSuite extends FunSuite:
  private val source = TextSourcePackage.fromText("A😀; B.\n\nC.").toOption.get

  test("chosen surface grain yields flat targets and ordinal succession"):
    val view = TextSourceView.of(source, SurfaceUnitKind.Clause).toOption.get
    assertEquals(view.nodes.size, 3)
    assert(view.nodes.forall(n => n.level == 0 && n.parent.isEmpty))
    assertEquals(view.nodes.map(_.discoursePosition), Vector(0, 1, 2))
    assertEquals(
      view.weight(RelationLayer.DiscourseSuccession, view.nodes(0).ref, view.nodes(1).ref),
      1.0
    )
    assertEquals(
      view.weight(RelationLayer.DiscourseSuccession, view.nodes(1).ref, view.nodes(0).ref),
      0.0
    )
    assertEquals(view.adjacency(RelationLayer.WorldTime), Map.empty)
    assertEquals(view.worldOrder, None)

  test("source support and fingerprint join the exact same checked view"):
    val view = TextSourceView.of(source, SurfaceUnitKind.Sentence).toOption.get
    assertEquals(view.representation.viewFingerprint, ViewFingerprint.of(view))
    assertEquals(view.representation.targets.size, 2)
    assert(view.representation.targets.forall(_.axisMembership.isEmpty))
    assertEquals(view.scoringLength, source.source.canonicalText.length)
    val supports = view.nodes.map(_.support).collect { case TypedSupport.Text(s) => s }
    assertEquals(supports.map(_.minSpan), source.at(SurfaceUnitKind.Sentence).map(_.unit.span))
    assertEquals(
      supports.flatMap(_.refs.toVector.flatMap(_.unit)),
      source.at(SurfaceUnitKind.Sentence).map(_.unit.id)
    )

  test("narrative propositions, importance and world relations remain unavailable"):
    val view = TextSourceView.of(source, SurfaceUnitKind.Paragraph).toOption.get
    assert(
      view.nodes.forall(n =>
        !n.propositional.declares && n.importance.toOption.isEmpty && n.evidence.isEmpty
      )
    )
    assert(view.nodes.forall(n => n.predicate.isEmpty && n.participants.isEmpty))
    assertEquals(view.adjacency(RelationLayer.Causal), Map.empty)
    assertEquals(view.adjacency(RelationLayer.Hierarchy), Map.empty)

  test("absent supplied grain and token requests are refused"):
    val atlas = SurfaceAtlas.of(source.source, source.atlas.paragraphs).toOption.get
    val supplied = TextSourcePackage.fromAtlas(atlas).toOption.get
    assert(TextSourceView.of(supplied, SurfaceUnitKind.Sentence).isLeft)
    assert(TextSourceView.of(source, SurfaceUnitKind.Token).isLeft)

  test("different grains and text contents cannot share view fingerprints"):
    val sentences = TextSourceView.of(source, SurfaceUnitKind.Sentence).toOption.get
    val clauses = TextSourceView.of(source, SurfaceUnitKind.Clause).toOption.get
    assertNotEquals(ViewFingerprint.of(sentences), ViewFingerprint.of(clauses))
    val changed = TextSourcePackage.fromText("X😀; B.\n\nC.").toOption.get
    val other = TextSourceView.of(changed, SurfaceUnitKind.Sentence).toOption.get
    assertNotEquals(ViewFingerprint.of(sentences), ViewFingerprint.of(other))
