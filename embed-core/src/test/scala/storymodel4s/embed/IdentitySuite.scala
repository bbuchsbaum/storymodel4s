package storymodel4s.embed

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll

import storymodel4s.features.FeatureValueSchema

class IdentitySuite extends ScalaCheckSuite:

  property("equal recipes yield equal GeometryIds; ids are content-addressed") {
    forAll(Gens.space) { s =>
      val again = EmbeddingSpace
        .of(s.provider, s.role, s.view, s.instruction, s.dimension, s.normalization, s.truncation)
        .toOption
        .get
      again.id == s.id && again == s
    }
  }

  property("changing any recipe component changes the GeometryId") {
    forAll(Gens.space) { s =>
      val otherRole = if s.role == Role.Query then Role.Document else Role.Query
      val otherNorm =
        if s.normalization == Normalization.L2 then Normalization.Unnormalized else Normalization.L2
      val variants = Vector(
        EmbeddingSpace.of(
          s.provider,
          otherRole,
          s.view,
          s.instruction,
          s.dimension,
          s.normalization,
          s.truncation
        ),
        EmbeddingSpace.of(
          s.provider,
          s.role,
          s.view,
          s.instruction,
          s.dimension,
          otherNorm,
          s.truncation
        ),
        EmbeddingSpace.of(
          s.provider,
          s.role,
          s.view,
          Some(InstructionDigest.of("x-" + s.id.value)),
          s.dimension,
          s.normalization,
          s.truncation
        ),
        EmbeddingSpace.of(
          s.provider,
          s.role,
          s.view,
          s.instruction,
          Dimension.unsafe(s.dimension.value + 1),
          s.normalization,
          s.truncation
        ),
        EmbeddingSpace.of(
          s.provider,
          s.role,
          s.view,
          s.instruction,
          s.dimension,
          s.normalization,
          TruncationPolicy.KeepHead(7)
        ),
        EmbeddingSpace.of(
          ProviderFingerprint.of("other", "t", "i", "r"),
          s.role,
          s.view,
          s.instruction,
          s.dimension,
          s.normalization,
          s.truncation
        ),
        EmbeddingSpace.of(
          s.provider,
          s.role,
          SemanticView.Custom("v", "zz-" + s.id.value.take(4)),
          s.instruction,
          s.dimension,
          s.normalization,
          s.truncation
        )
      ).map(_.toOption.get)
      variants.forall(_.id != s.id) && variants.map(_.id).distinct.size == variants.size
    }
  }

  property("truncation derives a new id with the parent recorded and L2 normalization") {
    forAll(Gens.space.suchThat(_.dimension.value > 2)) { s =>
      val t = s.truncated(Dimension.unsafe(s.dimension.value - 1)).toOption.get
      t.id != s.id && t.parent.contains(s.id) && t.normalization == Normalization.L2 &&
      t.dimension.value == s.dimension.value - 1 &&
      s.truncated(s.dimension).isLeft
    }
  }

  property("GeometryPair validates only query/document pairs of one recipe") {
    forAll(Gens.pair) { case (q, d) =>
      val truncatedDoc = d.truncated(Dimension.unsafe(1)).toOption
      GeometryPair.validated(q, d).isRight &&
      GeometryPair.validated(d, q).isLeft &&
      GeometryPair.validated(q, q).isLeft &&
      truncatedDoc.forall(t => GeometryPair.validated(q, t).isLeft)
    }
  }

  test("late-pooled view requires a validated recipe; other views must not carry one") {
    val p = ProviderFingerprint.of("m", "t", "i", "r")
    val d = Dimension.unsafe(4)
    val recipe = LatePoolingRecipe(
      storymodel4s.core.Checksum.ofText("doc"),
      storymodel4s.core.Fingerprint.unsafe("tok"),
      contextLimit = 512,
      window = 128,
      stride = 64,
      overlapMerge = "mean",
      pooling = PoolingRule.Mean,
      uncovered = UncoveredPolicy.PartialCoverage,
      matryoshkaDimension = None
    )
    assert(
      EmbeddingSpace
        .of(
          p,
          Role.Document,
          SemanticView.ContextualLatePooled,
          None,
          d,
          Normalization.L2,
          TruncationPolicy.Reject
        )
        .isLeft
    )
    assert(
      EmbeddingSpace
        .of(
          p,
          Role.Document,
          SemanticView.Surface,
          None,
          d,
          Normalization.L2,
          TruncationPolicy.Reject,
          Some(recipe)
        )
        .isLeft
    )
    assert(
      EmbeddingSpace
        .of(
          p,
          Role.Document,
          SemanticView.ContextualLatePooled,
          None,
          d,
          Normalization.L2,
          TruncationPolicy.Reject,
          Some(recipe.copy(stride = 999))
        )
        .isLeft
    )
    val ok = EmbeddingSpace
      .of(
        p,
        Role.Document,
        SemanticView.ContextualLatePooled,
        None,
        d,
        Normalization.L2,
        TruncationPolicy.Reject,
        Some(recipe)
      )
      .toOption
      .get
    val other = EmbeddingSpace
      .of(
        p,
        Role.Document,
        SemanticView.ContextualLatePooled,
        None,
        d,
        Normalization.L2,
        TruncationPolicy.Reject,
        Some(recipe.copy(window = 64))
      )
      .toOption
      .get
    assertNotEquals(ok.id, other.id)
  }

  property("toFeatureSpace declares a vector schema of the recipe's dimension and normalization") {
    forAll(Gens.space) { s =>
      val fs = s.toFeatureSpace
      fs.valueSchema == FeatureValueSchema.Vector(s.dimension.value) &&
      fs.normalized == (s.normalization == Normalization.L2) &&
      fs.id.value.contains(s.id.value)
    }
  }
