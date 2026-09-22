package storymodel4s.recall.attack

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.recall.*

class RecallInventoryPackageAttackSuite extends FunSuite:
  // Actual class dependencies force recompilation when a construction door changes.
  private val dependencies = Vector(
    classOf[RecallWord],
    classOf[InventoryUnit],
    classOf[RecallInventory],
    classOf[SegmentationId],
    classOf[WordIdPolicy]
  )
  test("public construction and read controls") {
    assertEquals(dependencies.size, 5)
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*;  val source = StorySource.fromText("one.").toOption.get; val graph = RecallSegmenter.segment(source); RecallInventory.of(graph, Vector(TextSpan.unsafe(0, 3)), WordIdPolicy.inputArtifact(source.rawChecksum))"""
      ).isEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*;  (i: RecallInventory) => (i.words.map(w => (w.id, w.index, w.span)), i.units.map(u => (u.id, u.ordinal, u.words, u.decomposition)), i.digest)"""
      ).isEmpty
    )
  }
  test("word has no caller construction") {
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*; new RecallInventory.Word(??? : RecallWordId, 0, ??? : TextSpan)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*; RecallInventory.Word(??? : RecallWordId, 0, ??? : TextSpan)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*; RecallInventory.Word.of(??? : RecallWordId, 0, ??? : TextSpan)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*; RecallInventory.Word.derived(??? : RecallWordId, 0, ??? : TextSpan)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*; (w: RecallWord) => w.copy(index = 3)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*; RecallInventory.Word.fromProduct((??? : RecallWordId, 0, ??? : TextSpan))"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*; summon[scala.deriving.Mirror.ProductOf[RecallWord]]"""
      ).nonEmpty
    )
  }
  test("inventory and its units cannot be copied or forged") {
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*; new RecallInventory.Unit(??? : RecallUnitId, 0, ??? : SpanSet, Vector.empty)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*; RecallInventory.Unit.derived(??? : RecallUnit, Vector.empty)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*; RecallInventory.Unit.fromProduct((??? : RecallUnitId, 0, ??? : SpanSet, Vector.empty))"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*; (u: InventoryUnit) => u.copy(ordinal = 2)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*; summon[scala.deriving.Mirror.ProductOf[InventoryUnit]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*; new RecallInventory(??? : Checksum, ??? : SegmentationId, ??? : WordIdPolicy, Vector.empty, Vector.empty, Map.empty)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*; (i: RecallInventory) => i.copy(words = Vector.empty)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*; RecallInventory.fromProduct((??? : Checksum, ??? : SegmentationId, ??? : WordIdPolicy, Vector.empty, Vector.empty, Map.empty))"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*; summon[scala.deriving.Mirror.ProductOf[RecallInventory]]"""
      ).nonEmpty
    )
  }
  test("identity has only derived construction") {
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*; new SegmentationId(??? : Checksum)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*; SegmentationId.from("arbitrary")"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*; SegmentationId.fromProduct(Tuple1(??? : Checksum))"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*; summon[scala.deriving.Mirror.ProductOf[SegmentationId]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*; new WordIdPolicy("arbitrary", ??? : Checksum)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.recall.*; import storymodel4s.core.*; (p: WordIdPolicy) => p.copy(name = "arbitrary")"""
      ).nonEmpty
    )
  }
