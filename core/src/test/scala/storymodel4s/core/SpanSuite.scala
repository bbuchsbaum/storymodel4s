package storymodel4s.core

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import Gens.given

class SpanSuite extends ScalaCheckSuite:
  property("TextSpan.of accepts iff 0 <= start <= end"):
    forAll(org.scalacheck.Gen.chooseNum(-5, 20), org.scalacheck.Gen.chooseNum(-5, 20)) { (a, b) =>
      TextSpan.of(a, b).isRight == (a >= 0 && a <= b)
    }

  property("length is end - start and contains is consistent with bounds"):
    forAll { (s: TextSpan, i: Int) =>
      s.length == s.endExclusive - s.start &&
      s.contains(i) == (i >= s.start && i < s.endExclusive)
    }

  property("overlaps is symmetric and empty spans overlap nothing"):
    forAll { (a: TextSpan, b: TextSpan) =>
      a.overlaps(b) == b.overlaps(a) && (!a.isEmpty || !a.overlaps(b))
    }

  property("union is defined iff spans touch, and covers both"):
    forAll { (a: TextSpan, b: TextSpan) =>
      a.union(b) match
        case Some(u) => a.touches(b) && u.contains(a) && u.contains(b) && u == a.hull(b)
        case None    => !a.touches(b)
    }

  test("slice and shift"):
    val s = TextSpan.unsafe(2, 5)
    assertEquals(s.slice("abcdefg"), Right("cde"))
    assert(s.slice("abc").isLeft)
    assertEquals(s.shift(3), TextSpan.of(5, 8))
    assert(s.shift(-3).isLeft)

  property("SpanSet is sorted and deduplicated"):
    forAll { (set: SpanSet) =>
      val v = set.refs.toVector
      v == v.sorted && v.distinct == v
    }

  property("SpanSet ++ is commutative and idempotent"):
    forAll { (a: SpanSet, b: SpanSet) =>
      (a ++ b) == (b ++ a) && (a ++ a) == a
    }

  property("minSpan covers every member"):
    forAll { (set: SpanSet) => set.spans.forall(set.minSpan.contains) }

  property("coveredLength is between max member length and minSpan length"):
    forAll { (set: SpanSet) =>
      val c = set.coveredLength
      c >= set.spans.map(_.length).toVector.max && c <= set.minSpan.length
    }

  test("coveredLength counts overlap once; isContiguous detects gaps"):
    val a = SpanRef(TextSpan.unsafe(0, 5))
    val b = SpanRef(TextSpan.unsafe(3, 8))
    val c = SpanRef(TextSpan.unsafe(10, 12))
    assertEquals(SpanSet.unsafe(a, b).coveredLength, 8)
    assert(SpanSet.unsafe(a, b).isContiguous)
    assert(!SpanSet.unsafe(a, c).isContiguous)
    assertEquals(SpanSet.unsafe(a, c).coveredLength, 7)
    assert(SpanSet.unsafe(a, SpanRef(TextSpan.unsafe(5, 6))).isContiguous)

  test("SpanSet.of rejects empty input"):
    assertEquals(SpanSet.of(Nil), None)
