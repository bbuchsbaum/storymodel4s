package storymodel4s.proposition

import cats.data.{NonEmptySet, NonEmptyVector}
import org.scalacheck.{Arbitrary, Gen}
import storymodel4s.core.*

/** Generators for valid charts and for charts with exactly one violation each. */
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

  val textSpan: Gen[TextSpan] = for
    a <- Gen.chooseNum(0, 500)
    b <- Gen.chooseNum(0, 500)
  yield TextSpan.unsafe(math.min(a, b), math.max(a, b))

  val spanSet: Gen[SpanSet] =
    Gen.nonEmptyListOf(textSpan.map(SpanRef(_))).map(rs => SpanSet.of(rs).get)

  val claimMeta: Gen[ClaimMeta] = for
    id <- Gen.chooseNum(0, 1000000)
    span <- textSpan
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
          .map { es =>
            val v = es.toVector.distinct.take(2)
            // Keep the generated pair acyclic so the DuplicateEmbedding case stays minimal.
            if v.size == 2 && v(0).container == v(1).content && v(0).content == v(1).container
            then v.take(1)
            else v
          }
    alignment: Gen[PropositionAlignment] =
      for
        i <- Gen.chooseNum(0, n - 1)
        span <- spanSet
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

  /** Deterministic renaming that avoids the canonical prefix and the generator prefix. */
  def renamed[C <: CheckState](chart: PropositionChart[C], salt: Int): PropositionChart[C] =
    val ids = chart.conceptIds
    val shuffled = ids.zipWithIndex.sortBy((_, i) => (i * 7919 + salt) % 104729).map(_._1)
    val map = ids.zip(shuffled).map((a, b) => a -> ConceptId.unsafe(s"z${b.value}_$salt")).toMap
    chart.relabel(map)

  /** Charts with exactly one injected violation, paired with the expected violation class. */
  val invalidChart: Gen[(PropositionChart[Unchecked], String)] =
    validChart.flatMap { ok =>
      val u = ok.unchecked
      val ghost = ConceptId.unsafe("ghost")
      val first = u.conceptIds.head
      val withFrame = u.conceptIds.find(i => u.concepts(i).frame.nonEmpty)
      val frameless = u.conceptIds.find(i => u.concepts(i).frame.isEmpty)
      type Case = (PropositionChart[Unchecked], String)
      def cs(chart: PropositionChart[Unchecked], name: String): Option[Case] = Some((chart, name))
      val cases: Vector[Case] = Vector(
        cs(u.copy(focus = Some(ghost)), "MissingFocus"),
        cs(
          u.copy(relations =
            u.relations :+ PropositionRelation(ghost, RoleAssignment.arg(0), ConceptTarget.Unknown)
          ),
          "DanglingRelationSource"
        ),
        cs(
          u.copy(relations =
            u.relations :+
              PropositionRelation(first, RoleAssignment.named("x"), ConceptTarget.Node(ghost))
          ),
          "DanglingRelationTarget"
        ),
        cs(
          u.copy(relations =
            u.relations :+
              PropositionRelation(first, RoleAssignment.named(":location"), ConceptTarget.Unknown)
          ),
          "InvalidRoleName"
        ),
        cs(
          u.copy(relations =
            u.relations :+
              PropositionRelation(first, RoleAssignment.named("ARG0"), ConceptTarget.Unknown)
          ),
          "NumberedRoleAsNamed"
        ),
        cs(
          u.copy(relations =
            u.relations :+
              PropositionRelation(
                first,
                RoleAssignment(SourceRole.Extension("x", "arg2"), None),
                ConceptTarget.Unknown
              )
          ),
          "NumberedRoleAsNamed"
        ),
        u.embedded.headOption.flatMap(e =>
          cs(u.copy(embedded = u.embedded :+ e), "DuplicateEmbedding")
        ),
        cs(
          u.copy(relations =
            u.relations :+
              PropositionRelation(
                first,
                RoleAssignment(SourceRole.Numbered(11), None),
                ConceptTarget.Unknown
              )
          ),
          "NumberedRoleOutOfRange"
        ),
        frameless.flatMap(f =>
          cs(
            u.copy(relations =
              u.relations :+
                PropositionRelation(
                  f,
                  RoleAssignment(
                    SourceRole.Numbered(0),
                    Some((ParticipantRole.Agent, Credence.unsafeRaw(1.0)))
                  ),
                  ConceptTarget.Unknown
                )
            ),
            "UnlicensedNormalization"
          )
        ),
        u.relations.headOption.flatMap(r =>
          cs(u.copy(relations = u.relations :+ r), "DuplicateRelation")
        ),
        cs(u.copy(polarity = u.polarity.updated(ghost, Polarity.Negative)), "DanglingPolarity"),
        cs(
          u.copy(embedded = u.embedded :+ EmbeddedProposition(ghost, EmbeddingKind.Speech, first)),
          "DanglingEmbeddingContainer"
        ),
        cs(
          u.copy(embedded = u.embedded :+ EmbeddedProposition(first, EmbeddingKind.Belief, first)),
          "SelfEmbedding"
        ),
        withFrame.flatMap(_ =>
          cs(
            u.copy(alignments =
              u.alignments :+ PropositionAlignment(
                AlignmentTarget.Concepts(NonEmptySet.one(ghost)),
                SpanSet.one(TextSpan.unsafe(0, 1)),
                Credence.unsafeRaw(1.0),
                claimMeta.sample.get
              )
            ),
            "DanglingAlignmentConcept"
          )
        )
      ).flatten
      Gen.oneOf(cases)
    }

  given Arbitrary[PropositionChart[Checked]] = Arbitrary(validChart)
