package storymodel4s.embed

import org.scalacheck.{Arbitrary, Gen}

import storymodel4s.core.{Checksum, Fingerprint}

object Gens:
  val provider: Gen[ProviderFingerprint] =
    for
      m <- Gen.identifier.map(_.take(8))
      t <- Gen.identifier.map(_.take(8))
      i <- Gen.identifier.map(_.take(6))
    yield ProviderFingerprint.of(m, t, i, "test")

  val dimension: Gen[Dimension] = Gen.choose(2, 16).map(Dimension.unsafe)

  val role: Gen[Role] = Gen.oneOf(Role.Query, Role.Document)

  val view: Gen[SemanticView] =
    Gen.oneOf(
      Gen.const(SemanticView.Surface),
      Gen.const(SemanticView.Gloss),
      Gen.const(SemanticView.ContextualTemplate),
      Gen.const(SemanticView.Segment),
      Gen.identifier.map(n => SemanticView.Custom("t", n.take(6)))
    )

  val truncation: Gen[TruncationPolicy] =
    Gen.oneOf(
      Gen.const(TruncationPolicy.Reject),
      Gen.choose(8, 512).map(TruncationPolicy.KeepHead(_)),
      Gen.choose(8, 512).map(TruncationPolicy.KeepTail(_))
    )

  val normalization: Gen[Normalization] = Gen.oneOf(Normalization.Unnormalized, Normalization.L2)

  val instruction: Gen[Option[InstructionDigest]] =
    Gen.option(Gen.alphaStr.map(InstructionDigest.of))

  val latePooling: Gen[LatePoolingRecipe] =
    for
      limit <- Gen.choose(16, 512)
      window <- Gen.choose(1, limit)
      stride <- Gen.choose(1, window)
      pooling <- Gen.oneOf(PoolingRule.Mean, PoolingRule.Max, PoolingRule.FirstToken)
      unc <- Gen.oneOf(UncoveredPolicy.PartialCoverage, UncoveredPolicy.Missing)
      doc <- Gen.alphaStr
    yield LatePoolingRecipe
      .of(
        Checksum.ofText(doc),
        Fingerprint.unsafe("tok"),
        limit,
        window,
        stride,
        "merge:mean",
        pooling,
        unc,
        None
      )
      .toOption
      .get

  /** A root recipe (non-late-pooled view). */
  val space: Gen[EmbeddingSpace] =
    for
      p <- provider
      r <- role
      v <- view
      i <- instruction
      d <- dimension
      n <- normalization
      t <- truncation
    yield EmbeddingSpace.of(p, r, v, i, d, n, t).toOption.get

  /** A compatible query/document pair from one recipe. */
  val pair: Gen[(EmbeddingSpace, EmbeddingSpace)] =
    for
      p <- provider
      v <- view
      i <- instruction
      d <- dimension
      n <- normalization
      t <- truncation
    yield (
      EmbeddingSpace.of(p, Role.Query, v, i, d, n, t).toOption.get,
      EmbeddingSpace.of(p, Role.Document, v, i, d, n, t).toOption.get
    )

  def unnormalized(d: Dimension): Gen[ValidatedVector] =
    Gen
      .listOfN(d.value, Gen.choose(-10.0, 10.0))
      .map(vs => ValidatedVector.of(d, Normalization.Unnormalized, vs.toVector).toOption.get)

  def unit(d: Dimension): Gen[ValidatedVector] =
    Gen
      .listOfN(d.value, Gen.choose(-10.0, 10.0))
      .suchThat(_.exists(_ != 0.0))
      .map(vs => ValidatedVector.l2(d, vs.toVector).toOption.get)

  val text: Gen[String] =
    Gen
      .nonEmptyListOf(
        Gen.oneOf("the", "young", "man", "went", "hunting", "seals", "river", "fog", "canoe")
      )
      .map(_.mkString(" "))

  given Arbitrary[EmbeddingSpace] = Arbitrary(space)
