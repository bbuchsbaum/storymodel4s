package storymodel4s.pipelineprobe

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** The producer condition, its recorded forms and checked row intervals cannot be built outside
  * their factories. Each refusal has a positive control: the same values through the factory
  * compile, so the refusal is the constructor's privacy and not a missing import.
  */
class ProducerConditionBoundarySuite extends FunSuite:

  test("ProducerCondition is built only by its factory"):
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.agentmapping.*; ProducerCondition.of(ContextWindow.WholeRecall, OrderExposure.Visible, ExposedIds.Positional)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.agentmapping.*; new ProducerCondition(ContextWindow.WholeRecall, OrderExposure.PooledShuffle(1L), ExposedIds.Positional)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.agentmapping.*; ProducerCondition(ContextWindow.WholeRecall, OrderExposure.PooledShuffle(1L), ExposedIds.Positional)"""
      ).nonEmpty
    )

  test("a preceding-units window is built only by its factory"):
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.agentmapping.*; ContextWindow.Preceding.of(2)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.agentmapping.*; new ContextWindow.Preceding(-1)"""
      ).nonEmpty
    )

  test("recorded conditions are built only by their factories"):
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.agentmapping.*; (RecordedCondition.legacy("a"), RecordedCondition.declared("a", ProducerCondition.WholeRecallInOrder))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.agentmapping.*; new RecordedCondition.Legacy("no name")"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.agentmapping.*; new RecordedCondition.Declared("a", ProducerCondition.WholeRecallInOrder)"""
      ).nonEmpty
    )

  test("row intervals and source rows are built only from a source"):
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.agentmapping.*; import storymodel4s.align.bridge.TimedSourceView; RowInterval.of(1, 2, SourceRows.of(??? : TimedSourceView))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.agentmapping.*; import storymodel4s.core.Checksum; new RowInterval(1, 999, Checksum.ofText("x"))"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.agentmapping.*; import storymodel4s.core.Checksum; new SourceRows(Checksum.ofText("x"), Set(1, 2))"""
      ).nonEmpty
    )
