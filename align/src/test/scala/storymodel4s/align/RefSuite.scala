package storymodel4s.align

import cats.data.NonEmptySet
import munit.FunSuite
import storymodel4s.core.{Addressable, SituationId}
import storymodel4s.recall.RecallUnitId

class RefSuite extends FunSuite:
  private val evidence = Addressable[AlignRef]
  private val first = RecallUnitId.unsafe("u1")
  private val second = RecallUnitId.unsafe("u2")
  private val anchor = SourceNodeRef.Situation(SituationId.unsafe("s1"))
  private val distorted =
    AlignState.Distorted(anchor, NonEmptySet.of(Facet.Actor, Facet.Polarity))
  private val faithful = AlignState.Source(anchor)

  private def assertRoundTrip(ref: AlignRef): Unit =
    val address = evidence.address(ref)
    assertEquals(evidence.parse(address), Some(ref), address.render)

  test("distorted cells retain their anchor and facets in the address key"):
    val ref = AlignRef.Cell(first, distorted)
    assertEquals(
      evidence.address(ref).key.parts.toVector,
      Vector("u1", "situation", "s1", "distorted", "Actor,Polarity")
    )
    assertRoundTrip(ref)

  test("a faithful-to-distorted transition round-trips across unequal state-key lengths"):
    assertRoundTrip(AlignRef.Transition(first, second, faithful, distorted))

  test("a distorted-to-faithful transition round-trips across unequal state-key lengths"):
    assertRoundTrip(AlignRef.Transition(first, second, distorted, faithful))

  test("a distorted-to-distorted transition round-trips"):
    assertRoundTrip(AlignRef.Transition(first, second, distorted, distorted))
