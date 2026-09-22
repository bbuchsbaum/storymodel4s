package storymodel4s.recall

import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.recall.RecallGraphStatus.Checked

class RecallInventorySuite extends FunSuite:
  private val text = "one gap two three four five six seven eight"
  private val spans =
    "\\S+".r.findAllMatchIn(text).map(m => TextSpan.unsafe(m.start, m.end)).toVector
  private val policy = WordIdPolicy.inputArtifact(Checksum.ofText("exact parsed artifact"))
  private def graph(content: String, ranges: Vector[TextSpan]): RecallGraph[Checked] =
    val source = StorySource.fromText(content).toOption.get
    val units = ranges.zipWithIndex.map { case (span, i) =>
      RecallUnit(
        RecallUnitId.unsafe(s"u$i"),
        i,
        SpanSet.one(span),
        content.substring(span.start, span.endExclusive),
        DiscourseFunction.EpisodicAssertion,
        ExpressedUncertainty.Unmarked,
        PropositionSketch.empty,
        None
      )
    }
    RecallGraph
      .validated(source, SurfaceAnalyzer.analyze(source), units, RecallRelations.empty)
      .toOption
      .get
  private val recall = graph(text, spans.patch(1, Nil, 1))
  private def inventory = RecallInventory.of(recall, spans, policy).toOption.get

  test("distinct UTF-16 unit identities never share segmentation or inventory") {
    val plain = graph("one", Vector(TextSpan.unsafe(0, 3)))
    def withId(code: Int): RecallGraph[Checked] =
      val id = RecallUnitId.from(String.valueOf(code.toChar)).toOption.get
      RecallGraph.validated(plain.copy(units = Vector(plain.units.head.copy(id = id)))).toOption.get
    val a = withId(0xd800)
    val b = withId(0xd801)
    assertNotEquals(a.units.head.id, b.units.head.id)
    val ia = RecallInventory.of(a, Vector(TextSpan.unsafe(0, 3)), policy).toOption.get
    val ib = RecallInventory.of(b, Vector(TextSpan.unsafe(0, 3)), policy).toOption.get
    assertNotEquals(ia.segmentation, ib.segmentation)
    assertNotEquals(ia.digest, ib.digest)
    assert(!ia.describes(b))
    assert(ia.describes(a))
  }

  test("distinct admitted UTF-16 transcripts never share inventory identity") {
    val a = graph("a" + String.valueOf(0xd800.toChar) + "b", Vector(TextSpan.unsafe(0, 3)))
    val b = graph("a" + String.valueOf(0xd801.toChar) + "b", Vector(TextSpan.unsafe(0, 3)))
    assertNotEquals(a.transcript.canonicalText, b.transcript.canonicalText)
    // Legacy hashes replace both malformed sequences identically; G1 must supplement them.
    assertEquals(a.transcript.canonicalChecksum, b.transcript.canonicalChecksum)
    val ia = RecallInventory.of(a, Vector(TextSpan.unsafe(0, 3)), policy).toOption.get
    val ib = RecallInventory.of(b, Vector(TextSpan.unsafe(0, 3)), policy).toOption.get
    assertNotEquals(ia.segmentation, ib.segmentation)
    assertNotEquals(ia.digest, ib.digest)
    assert(!ia.describes(b))
    assert(ia.describes(a))
  }

  test("every parsed word is accounted") {
    val result = inventory
    assertEquals(result.words.map(_.index), (0 until 9).toVector)
    assertEquals(result.units.size, 8)
    assertEquals(result.membership.size, 9)
    assertEquals(
      result.membership(result.words(1).id),
      WordMembership.Unassigned(UnassignedWordReason.NotInAnyUnitSpan)
    )
    assertEquals(
      result.words.map(_.id.value),
      (0 until 9).map(i => s"${policy.artifact.hex}:word:$i").toVector
    )
    assertEquals(
      result.units.flatMap(_.words).toSet,
      result.words.filterNot(_.index == 1).map(_.id).toSet
    )
    assertEquals(result.words.map(_.span), spans)
    assert(result.describes(recall))
  }
  test("word in two units refuses") {
    val overlapping = graph(text, Vector(spans.head, spans.head))
    assertEquals(
      RecallInventory.of(overlapping, spans, policy).swap.toOption.get,
      DomainError.InvariantViolation(
        "recall/inventory/membership",
        "word 0 overlaps multiple units"
      )
    )
    assert(RecallInventory.of(recall, spans, policy).isRight)
  }
  test("unitization changes SegmentationId") {
    val split = graph(text, Vector(TextSpan.unsafe(0, 7), TextSpan.unsafe(8, text.length)))
    assertNotEquals(SegmentationId.of(recall), SegmentationId.of(split))
    assertEquals(SegmentationId.of(recall), SegmentationId.of(recall))
  }
  test("inventory does not describe a resegmented graph") {
    val changed = graph(text, Vector(TextSpan.unsafe(0, text.length)))
    assert(!inventory.describes(changed))
    assert(inventory.describes(recall))
  }
  test("malformed word spans refuse") {
    val malformed = Vector(
      Vector(TextSpan.unsafe(0, 0)),
      Vector(TextSpan.unsafe(0, text.length + 1)),
      Vector(TextSpan.unsafe(1, 4), TextSpan.unsafe(3, 6)),
      spans.reverse,
      Vector(TextSpan.unsafe(text.length + 1, text.length + 2))
    )
    malformed.foreach { words =>
      assertEquals(
        RecallInventory.of(recall, words, policy).swap.toOption.get,
        DomainError.InvariantViolation(
          "recall/inventory/words",
          "word spans must be nonempty, ordered, disjoint and within UTF-16 boundaries"
        )
      )
    }
    val emoji = graph("a😀b", Vector(TextSpan.unsafe(0, 4)))
    Vector(TextSpan.unsafe(0, 2), TextSpan.unsafe(2, 4)).foreach { bad =>
      assert(RecallInventory.of(emoji, Vector(bad), policy).isLeft)
    }
    assert(
      RecallInventory
        .of(
          emoji,
          Vector(TextSpan.unsafe(0, 1), TextSpan.unsafe(1, 3), TextSpan.unsafe(3, 4)),
          policy
        )
        .isRight
    )
  }
  test("every unit NotAssessed") {
    assertEquals(
      inventory.units.map(_.decomposition),
      Vector.fill(8)(DecompositionStatus.NotAssessed(DecompositionReason.NoDecompositionDetector))
    )
    assertEquals(
      inventory.units.map(_.semantics),
      Vector.fill(8)(MappingSemantics.CategoricalReferent)
    )
  }
  test("digest binds parsing and policy while preserving unassigned words") {
    val first = inventory
    val differentWords = RecallInventory.of(recall, spans.patch(1, Nil, 1), policy).toOption.get
    val differentArtifact = RecallInventory
      .of(recall, spans, WordIdPolicy.inputArtifact(Checksum.ofText("another artifact")))
      .toOption
      .get
    assertNotEquals(first.digest, differentWords.digest)
    assertNotEquals(first.digest, differentArtifact.digest)
    assertEquals(first.digest, inventory.digest)
  }
  test("discontiguous unit membership uses exact pieces rather than the hull") {
    val source = StorySource.fromText("one gap two").toOption.get
    val unit = RecallUnit(
      RecallUnitId.unsafe("u"),
      0,
      SpanSet.unsafe(SpanRef(TextSpan.unsafe(0, 3)), SpanRef(TextSpan.unsafe(8, 11))),
      source.canonicalText,
      DiscourseFunction.EpisodicAssertion,
      ExpressedUncertainty.Unmarked,
      PropositionSketch.empty,
      None
    )
    val g = RecallGraph
      .validated(source, SurfaceAnalyzer.analyze(source), Vector(unit), RecallRelations.empty)
      .toOption
      .get
    val value = RecallInventory
      .of(g, Vector(TextSpan.unsafe(0, 3), TextSpan.unsafe(4, 7), TextSpan.unsafe(8, 11)), policy)
      .toOption
      .get
    assertEquals(value.units.head.words.size, 2)
    assertEquals(
      value.membership(value.words(1).id),
      WordMembership.Unassigned(UnassignedWordReason.NotInAnyUnitSpan)
    )
  }
  test("empty parsed inventory is explicit and preserves all units") {
    val value = RecallInventory.of(recall, Vector.empty, policy).toOption.get
    assertEquals(value.words, Vector.empty)
    assertEquals(value.units.size, 8)
    assert(value.units.forall(_.words.isEmpty))
  }
