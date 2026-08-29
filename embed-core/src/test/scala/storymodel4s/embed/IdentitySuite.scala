package storymodel4s.embed

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll

import storymodel4s.core.{Checksum, Fingerprint}
import storymodel4s.features.FeatureValueSchema

class IdentitySuite extends ScalaCheckSuite:

  private def embedderInfo(name: String, version: String): EmbedderInfo =
    EmbedderInfo(
      ProviderFingerprint.of("model", "tokenizer", "implementation", "runtime"),
      name,
      version,
      Locality.Remote,
      PrivacyClass.PublicOnly,
      8192,
      supportsInstructions = true,
      tokenEmbeddings = false,
      matryoshkaDims = None
    )

  test("PolicyModelIdentity has a checked canonical rendering fixed point") {
    val info = embedderInfo("model|with:delimiters", "v1|revision:2")
    val identity = info.policyModelIdentity
    assertEquals(PolicyModelIdentity.parse(identity.render, info), Some(identity))
    assertEquals(PolicyModelIdentity.parse(identity.render + "trailing", info), None)
    assertEquals(PolicyModelIdentity.parse("policy-model/v1|x:model1:v", info), None)
    assertEquals(PolicyModelIdentity.parse("policy-model/v1|01:a1:1", info), None)
    assertEquals(PolicyModelIdentity.parse(identity.render, info.copy(version = "other")), None)
  }

  test("PolicyModelIdentity length delimiting prevents name/version boundary collisions") {
    val left = embedderInfo("a@b", "c").policyModelIdentity
    val right = embedderInfo("a", "b@c").policyModelIdentity
    assertNotEquals(left, right)
    assertNotEquals(left.render, right.render)
  }

  test("PolicyModelIdentity changes when the advertised embedder version changes") {
    val first = embedderInfo("model", "1").policyModelIdentity
    val second = embedderInfo("model", "2").policyModelIdentity
    assertNotEquals(first, second)
  }

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
      GeometryPair.validated(q, d, GeometryPairRule.IdenticalModelling).isRight &&
      GeometryPair.validated(d, q, GeometryPairRule.IdenticalModelling).isLeft &&
      GeometryPair.validated(q, q, GeometryPairRule.IdenticalModelling).isLeft &&
      truncatedDoc.forall(t =>
        GeometryPair.validated(q, t, GeometryPairRule.IdenticalModelling).isLeft
      )
    }
  }

  property("GeometryPair rejects every hard compatibility-key mutation") {
    forAll(
      Gens.provider,
      Gens.dimension,
      Gens.latePooling.suchThat(r => r.window < r.contextLimit && r.stride < r.window)
    ) { (provider, dimension, generatedRecipe) =>
      val recipe = generatedRecipe.copy(documentDigest = Checksum.ofText("query-document"))
      val query = pooledSpace(
        provider,
        Role.Query,
        dimension,
        recipe.copy(documentDigest = Checksum.ofText("query"))
      )
      val document = pooledSpace(
        provider,
        Role.Document,
        dimension,
        recipe.copy(documentDigest = Checksum.ofText("document"))
      )
      val otherNormalization =
        if document.normalization == Normalization.L2 then Normalization.Unnormalized
        else Normalization.L2
      val otherTruncation = document.truncation match
        case TruncationPolicy.Reject => TruncationPolicy.KeepHead(1)
        case _                       => TruncationPolicy.Reject
      val otherPooling = recipe.pooling match
        case PoolingRule.Mean       => PoolingRule.Max
        case PoolingRule.Max        => PoolingRule.FirstToken
        case PoolingRule.FirstToken => PoolingRule.Mean
      val otherUncovered = recipe.uncovered match
        case UncoveredPolicy.PartialCoverage => UncoveredPolicy.Missing
        case UncoveredPolicy.Missing         => UncoveredPolicy.PartialCoverage
      val recipeMutations = Vector(
        "different late-pooling tokenizer fingerprints" -> recipe.copy(
          tokenizerFingerprint = Fingerprint.unsafe("other-tokenizer")
        ),
        "different late-pooling context limits" -> recipe.copy(
          contextLimit = recipe.contextLimit + 1
        ),
        "different late-pooling windows" -> recipe.copy(window = recipe.window + 1),
        "different late-pooling strides" -> recipe.copy(stride = recipe.stride + 1),
        "different late-pooling overlap merges" -> recipe.copy(
          overlapMerge = recipe.overlapMerge + ":other"
        ),
        "different late-pooling pooling rules" -> recipe.copy(pooling = otherPooling),
        "different late-pooling uncovered policies" -> recipe.copy(uncovered = otherUncovered),
        "different late-pooling matryoshka dimensions" -> recipe.copy(
          matryoshkaDimension = Some(dimension.value)
        )
      )
      val recipeVariants =
        recipeMutations.map { case (reason, r) =>
          reason -> pooledSpace(provider, Role.Document, dimension, r)
        }
      val hardVariants = Vector(
        "different providers" -> pooledSpace(
          ProviderFingerprint.of("other-model", "other-tokenizer", "other-impl", "test"),
          Role.Document,
          dimension,
          recipe
        ),
        "different dimensions" -> pooledSpace(
          provider,
          Role.Document,
          Dimension.unsafe(dimension.value + 1),
          recipe
        ),
        "different normalizations" -> embeddingSpace(
          document,
          normalization = Some(otherNormalization)
        ),
        "different truncation policies" -> embeddingSpace(
          document,
          truncation = Some(otherTruncation)
        )
      ) ++ recipeVariants
      val noLatePooling = EmbeddingSpace
        .of(
          provider,
          Role.Document,
          SemanticView.Surface,
          document.instruction,
          dimension,
          document.normalization,
          document.truncation
        )
        .toOption
        .get

      GeometryPair.validated(query, document, GeometryPairRule.IdenticalModelling).isRight &&
      hardVariants.forall { case (expectedReason, variant) =>
        GeometryPairRule.values.forall { rule =>
          GeometryPair.validated(query, variant, rule) match
            case Left(EmbedError.IncompatibleSpaces(_, _, reason)) => reason == expectedReason
            case _                                                 => false
        }
      } &&
      GeometryPairRule.values.forall(rule =>
        GeometryPair.validated(query, noLatePooling, rule).isLeft
      ) &&
      Vector(
        GeometryPairRule.AllowViewDifference,
        GeometryPairRule.AllowViewAndInstructionDifference
      ).forall { rule =>
        GeometryPair.validated(query, noLatePooling, rule) match
          case Left(EmbedError.IncompatibleSpaces(_, _, reason)) =>
            reason == "different late-pooling presence"
          case _ => false
      }
    }
  }

  property("GeometryPair treats role-specific derivation parents as provenance") {
    forAll(Gens.pair.suchThat(_._1.dimension.value > 2)) { case (query, document) =>
      val target = Dimension.unsafe(query.dimension.value - 1)
      val truncatedQuery = query.truncated(target).toOption.get
      val truncatedDocument = document.truncated(target).toOption.get

      truncatedQuery.parent != truncatedDocument.parent &&
      GeometryPair
        .validated(truncatedQuery, truncatedDocument, GeometryPairRule.IdenticalModelling)
        .isRight
    }
  }

  property("GeometryPair permits modelling differences only through the recorded named rule") {
    forAll(Gens.pair) { case (query, document) =>
      val otherView = SemanticView.Custom("pair-test", query.id.value.take(8))
      val otherInstruction = Some(InstructionDigest.of("document-" + query.id.value))
      val viewDocument = embeddingSpace(document, view = Some(otherView))
      val instructionDocument = embeddingSpace(document, instruction = Some(otherInstruction))
      val bothDocument =
        embeddingSpace(document, view = Some(otherView), instruction = Some(otherInstruction))
      val viewPair = GeometryPair.validated(
        query,
        viewDocument,
        GeometryPairRule.AllowViewDifference
      )
      val instructionPair = GeometryPair.validated(
        query,
        instructionDocument,
        GeometryPairRule.AllowInstructionDifference
      )
      val bothPair = GeometryPair.validated(
        query,
        bothDocument,
        GeometryPairRule.AllowViewAndInstructionDifference
      )

      GeometryPair
        .validated(query, viewDocument, GeometryPairRule.IdenticalModelling)
        .isLeft &&
      GeometryPair
        .validated(query, viewDocument, GeometryPairRule.AllowInstructionDifference)
        .isLeft &&
      viewPair.exists(_.rule == GeometryPairRule.AllowViewDifference) &&
      GeometryPair
        .validated(query, instructionDocument, GeometryPairRule.IdenticalModelling)
        .isLeft &&
      GeometryPair
        .validated(query, instructionDocument, GeometryPairRule.AllowViewDifference)
        .isLeft &&
      instructionPair.exists(_.rule == GeometryPairRule.AllowInstructionDifference) &&
      GeometryPair
        .validated(query, bothDocument, GeometryPairRule.IdenticalModelling)
        .isLeft &&
      GeometryPair
        .validated(query, bothDocument, GeometryPairRule.AllowViewDifference)
        .isLeft &&
      GeometryPair
        .validated(query, bothDocument, GeometryPairRule.AllowInstructionDifference)
        .isLeft &&
      bothPair.exists(_.rule == GeometryPairRule.AllowViewAndInstructionDifference)
    }
  }

  test("GeometryPair rejects the original incompatible late-pooling repro under every rule") {
    val provider = ProviderFingerprint.of("model", "tokenizer", "implementation", "runtime")
    val dimension = Dimension.unsafe(8)
    val queryRecipe = LatePoolingRecipe(
      documentDigest = Checksum.ofText("query"),
      tokenizerFingerprint = Fingerprint.unsafe("tok-a"),
      contextLimit = 512,
      window = 128,
      stride = 64,
      overlapMerge = "mean",
      pooling = PoolingRule.Mean,
      uncovered = UncoveredPolicy.PartialCoverage,
      matryoshkaDimension = None
    )
    val documentRecipe = queryRecipe.copy(
      documentDigest = Checksum.ofText("document"),
      tokenizerFingerprint = Fingerprint.unsafe("tok-b"),
      window = 64,
      stride = 32,
      pooling = PoolingRule.Max,
      uncovered = UncoveredPolicy.Missing
    )
    val query = pooledSpace(provider, Role.Query, dimension, queryRecipe)
    val document = pooledSpace(provider, Role.Document, dimension, documentRecipe)

    assert(
      GeometryPairRule.values.forall(rule => GeometryPair.validated(query, document, rule).isLeft)
    )
  }

  test("GeometryPair cannot bypass its validator or omit its explicit rule") {
    val applyErrors = compileErrors(
      "storymodel4s.embed.GeometryPair(storymodel4s.embed.GeometryId.unsafe(\"query\"), storymodel4s.embed.GeometryId.unsafe(\"document\"), storymodel4s.embed.GeometryPairRule.IdenticalModelling)"
    )
    val copyErrors = compileErrors(
      "def forge(pair: storymodel4s.embed.GeometryPair, document: storymodel4s.embed.GeometryId): storymodel4s.embed.GeometryPair = pair.copy(document = document)"
    )
    val missingRuleErrors = compileErrors(
      "def compare(query: storymodel4s.embed.EmbeddingSpace, document: storymodel4s.embed.EmbeddingSpace) = storymodel4s.embed.GeometryPair.validated(query, document)"
    )

    assert(applyErrors.nonEmpty)
    assert(copyErrors.nonEmpty)
    assert(missingRuleErrors.nonEmpty)
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

  private def pooledSpace(
      provider: ProviderFingerprint,
      role: Role,
      dimension: Dimension,
      recipe: LatePoolingRecipe
  ): EmbeddingSpace =
    EmbeddingSpace
      .of(
        provider,
        role,
        SemanticView.ContextualLatePooled,
        None,
        dimension,
        Normalization.L2,
        TruncationPolicy.Reject,
        Some(recipe)
      )
      .toOption
      .get

  private def embeddingSpace(
      source: EmbeddingSpace,
      view: Option[SemanticView] = None,
      instruction: Option[Option[InstructionDigest]] = None,
      normalization: Option[Normalization] = None,
      truncation: Option[TruncationPolicy] = None
  ): EmbeddingSpace =
    EmbeddingSpace
      .of(
        source.provider,
        source.role,
        view.getOrElse(source.view),
        instruction.getOrElse(source.instruction),
        source.dimension,
        normalization.getOrElse(source.normalization),
        truncation.getOrElse(source.truncation),
        source.latePooling
      )
      .toOption
      .get
