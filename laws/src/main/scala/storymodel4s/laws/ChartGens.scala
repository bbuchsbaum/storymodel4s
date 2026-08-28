package storymodel4s.laws

import cats.data.{NonEmptySet, NonEmptyVector}
import org.scalacheck.{Arbitrary, Gen}
import storymodel4s.core.*
import storymodel4s.proposition.*

/** Public generators for valid propositional charts (the proposition module's test generators,
  * republished for downstream law suites).
  */
object ChartGens:
  val lemma: Gen[String] =
    Gen.oneOf(
      "go",
      "hear",
      "hide",
      "say",
      "think",
      "want",
      "fight",
      "hit",
      "feel",
      "die",
      "tell",
      "young-man",
      "warrior",
      "canoe",
      "river",
      "log",
      "arrow",
      "ghost",
      "house",
      "fire",
      "sick"
    )

  val frameRef: Gen[FrameRef] = for
    l <- lemma
    n <- Gen.chooseNum(1, 3)
    c <- Gen.option(Gen.chooseNum(-3.0, 3.0).map(Credence.unsafeRaw))
  yield FrameRef("propbank", f"$l-$n%02d", c)

  val concept: Gen[Concept] = Gen.frequency(
    5 -> (for
      l <- lemma
      f <- Gen.option(frameRef)
    yield Concept(Lemma.unsafe(l), None, f, ConceptKind.Predicate)),
    5 -> lemma.map(Concept.entity),
    1 -> lemma.map(Concept.property),
    1 -> Gen.const(Concept.unknown)
  )

  val participantRole: Gen[ParticipantRole] = Gen.oneOf(
    ParticipantRole.Agent,
    ParticipantRole.Patient,
    ParticipantRole.Theme,
    ParticipantRole.Location,
    ParticipantRole.Time,
    ParticipantRole.Custom("x", "y")
  )

  /** Named roles use the bare AMR spelling (`location`, not `:location`), as the interop bridge. */
  val sourceRole: Gen[SourceRole] = Gen.frequency(
    6 -> Gen.chooseNum(0, SourceRole.MaxNumbered).map(SourceRole.Numbered.apply),
    3 -> Gen.oneOf("location", "time", "mod", "manner").map(SourceRole.Named.apply),
    1 -> Gen.chooseNum(1, 3).map(SourceRole.Operand.apply)
  )

  def roleAssignment(hasFrame: Boolean): Gen[RoleAssignment] = for
    s <- sourceRole
    norm <- s match
      case SourceRole.Numbered(_) if !hasFrame => Gen.const(None)
      case _                                   =>
        Gen.option(for
          r <- participantRole
          c <- Gen.chooseNum(-2.0, 2.0).map(Credence.unsafeRaw)
        yield (r, c))
  yield RoleAssignment(s, norm)

  val literal: Gen[LiteralValue] = Gen.oneOf(
    Gen.alphaStr.map(LiteralValue.Text.apply),
    Gen.chooseNum(0, 1000).map(n => LiteralValue.Number(BigDecimal(n))),
    Gen.oneOf("-", "+", "imperative").map(LiteralValue.Symbol.apply)
  )

  val claimMeta: Gen[ClaimMeta] = for
    id <- Gen.chooseNum(0, 1000000)
    span <- CoreGens.textSpan
  yield ClaimMeta.unsafe(
    ClaimId.unsafe(s"claim:$id"),
    EpistemicStatus.SurfaceExplicit,
    Credence.unsafeRaw(1.0),
    NonEmptyVector.one(
      Evidence(
        EvidenceId.unsafe(s"ev:$id"),
        Some(SpanSet.one(span)),
        Set.empty,
        Fingerprint.unsafe("test:gen:0"),
        StageId.unsafe("test")
      )
    ),
    Provenance.deterministic("test", Checksum.ofText("test"))
  )

  private def id(i: Int): ConceptId = ConceptId.unsafe(s"c$i")

  /** A structurally valid chart with 1–7 concepts. */
  val validChart: Gen[PropositionChart[Checked]] = for
    n <- Gen.chooseNum(1, 7)
    cs <- Gen.listOfN(n, concept)
    concepts = cs.zipWithIndex.map((c, i) => id(i) -> c).toMap
    nRel <- Gen.chooseNum(0, n * 2)
    rels <- Gen.listOfN(
      nRel,
      for
        from <- Gen.chooseNum(0, n - 1)
        role <- roleAssignment(concepts(id(from)).frame.nonEmpty)
        to <- Gen.frequency(
          6 -> Gen.chooseNum(0, n - 1).map(j => ConceptTarget.Node(id(j))),
          2 -> literal.map(ConceptTarget.Literal.apply),
          1 -> Gen.const(ConceptTarget.Unknown)
        )
      yield PropositionRelation(id(from), role, to)
    )
    pol <- Gen.mapOf(
      for
        i <- Gen.chooseNum(0, n - 1)
        p <- Gen.oneOf(Polarity.Positive, Polarity.Negative)
      yield id(i) -> p
    )
    emb <-
      if n < 2 then Gen.const(Vector.empty)
      else
        Gen
          .listOf(
            for
              a <- Gen.chooseNum(0, n - 1)
              b <- Gen.chooseNum(0, n - 1) if a != b
              k <- Gen.oneOf(EmbeddingKind.values.toSeq)
            yield EmbeddedProposition(id(a), k, id(b))
          )
          .map(_.toVector.distinct.take(2))
    alignment: Gen[PropositionAlignment] =
      for
        i <- Gen.chooseNum(0, n - 1)
        span <- CoreGens.spanSet
        c <- Gen.chooseNum(0.0, 1.0).map(Credence.unsafeRaw)
        m <- claimMeta
      yield PropositionAlignment(AlignmentTarget.Concepts(NonEmptySet.one(id(i))), span, c, m)
    aligns <- Gen.listOf(alignment).map(_.toVector.take(3))
    focus <- Gen.option(Gen.chooseNum(0, n - 1).map(id))
  yield ChartValidator
    .validate(
      PropositionChart.unchecked(focus, concepts, rels.toVector.distinct, pol, emb, aligns)
    )
    .toOption
    .get

  given Arbitrary[PropositionChart[Checked]] = Arbitrary(validChart)
