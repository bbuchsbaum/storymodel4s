package storymodel4s.embed

import munit.FunSuite
import storymodel4s.core.{Checksum, Fingerprint}

/** A checked embedding recipe must never retain a nonpositive input-token limit. */
class TruncationPolicySuite extends FunSuite:
  private val provider = ProviderFingerprint.of("model", "tokenizer", "implementation", "runtime")
  private val dimension = Dimension.unsafe(8)
  private val late = LatePoolingRecipe
    .of(
      Checksum.ofText("synthetic document"),
      Fingerprint.unsafe("tokenizer"),
      16,
      8,
      4,
      "merge:mean",
      PoolingRule.Mean,
      UncoveredPolicy.PartialCoverage,
      None
    )
    .toOption
    .get

  private def space(policy: TruncationPolicy, pooled: Boolean = false) =
    EmbeddingSpace.of(
      provider,
      Role.Document,
      if pooled then SemanticView.ContextualLatePooled else SemanticView.Surface,
      None,
      dimension,
      Normalization.L2,
      policy,
      if pooled then Some(late) else None
    )

  test("head declarations refuse zero and negative token limits") {
    Vector(0, -1, Int.MinValue).foreach { n =>
      Vector(false, true).foreach { pooled =>
        assertEquals(
          space(TruncationPolicy.KeepHead(n), pooled),
          Left(EmbedError.InvalidRecipe("truncation maxTokens must be positive"))
        )
      }
    }
  }

  test("tail declarations refuse zero and negative token limits") {
    Vector(0, -1, Int.MinValue).foreach { n =>
      Vector(false, true).foreach { pooled =>
        assertEquals(
          space(TruncationPolicy.KeepTail(n), pooled),
          Left(EmbedError.InvalidRecipe("truncation maxTokens must be positive"))
        )
      }
    }
  }

  test("Reject remains admitted for ordinary and late-pooled recipes") {
    Vector(false, true).foreach { pooled =>
      assertEquals(
        space(TruncationPolicy.Reject, pooled).map(_.truncation),
        Right(TruncationPolicy.Reject)
      )
    }
  }

  test("positive head and tail limits preserve identity through the largest Int") {
    Vector(1, 7, Int.MaxValue).foreach { n =>
      val head = space(TruncationPolicy.KeepHead(n)).toOption.get
      val tail = space(TruncationPolicy.KeepTail(n)).toOption.get
      assertEquals(head.truncation, TruncationPolicy.KeepHead(n))
      assertEquals(tail.truncation, TruncationPolicy.KeepTail(n))
      assertNotEquals(head.id, tail.id)
      assertEquals(space(head.truncation).map(_.id), Right(head.id))
      assertEquals(space(tail.truncation).map(_.id), Right(tail.id))
      val reduced = head.truncated(Dimension.unsafe(4)).toOption.get
      assertEquals(reduced.truncation, head.truncation)
      assertEquals(reduced.parent, Some(head.id))
    }
  }
