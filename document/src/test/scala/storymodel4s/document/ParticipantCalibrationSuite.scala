package storymodel4s.document

import cats.data.{NonEmptySet, NonEmptyVector}
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import storymodel4s.acquire.*
import storymodel4s.core.*
import storymodel4s.proposition.*
import storymodel4s.story.ParticipantRole
import ParticipantCalibration.*

/** Synthetic calibration mechanics, deliberately not human gold or an empirical calibration result.
  */
class ParticipantCalibrationSuite extends ScalaCheckSuite:
  private val pipeline = Checksum.ofText("synthetic frozen pipeline v1")
  private val protocol = Checksum.ofText("synthetic adjudication protocol v1")
  private val scorer = ScorerId.unsafe("synthetic-role-table")
  private val annotator = Fingerprint.unsafe("synthetic-test-labels")

  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)

  private def example(
      name: String,
      value: Double = 0.5,
      by: ScorerId = scorer,
      role: ParticipantRole = ParticipantRole.Agent,
      manifest: Checksum = pipeline,
      group: Option[StoryId] = None,
      parserVersion: String = "1"
  ): (NarrativeCompilerInput, Item) =
    val source = right(StorySource.fromText(s"$name acted."))
    val atlas = SurfaceAnalyzer.analyze(source)
    val unit = atlas.sentences.head
    val root = ConceptId.unsafe("act")
    val filler = ConceptId.unsafe("person")
    val parser = Fingerprint.unsafe(s"synthetic-parser:$parserVersion")
    def alignment(id: ConceptId, start: Int, end: Int): PropositionAlignment =
      val spans = SpanSet.one(SpanRef(Some(unit.id), TextSpan.unsafe(start, end)))
      val evidence = Evidence(
        EvidenceId.unsafe(s"e:${id.value}"),
        Some(spans),
        Set.empty,
        parser,
        StageId.unsafe("synthetic-parser")
      )
      PropositionAlignment(
        AlignmentTarget.Concepts(NonEmptySet.one(id)),
        spans,
        Credence.unmeasured,
        ClaimMeta.unsafe(
          ClaimId.unsafe(s"c:${id.value}"),
          EpistemicStatus.SurfaceExplicit,
          Credence.unmeasured,
          NonEmptyVector.one(evidence),
          Provenance.deterministic("test", pipeline)
        )
      )
    val assignment =
      RoleAssignment(SourceRole.Numbered(0), Some(role -> Credence.unsafeRaw(value, by)))
    val chart = PropositionChart.unchecked(
      Some(root),
      Map(
        root -> Concept.predicate("act", Some(FrameRef("synthetic", "act-01", None))),
        filler -> Concept.name(name)
      ),
      Vector(PropositionRelation(root, assignment, ConceptTarget.Node(filler))),
      Map.empty,
      Vector.empty,
      Vector(alignment(filler, 0, name.length), alignment(root, name.length + 1, name.length + 6)),
      ChartProvenance(ChartOrigin.Parser(parser), Vector.empty, Vector.empty),
      Some(unit.id)
    )
    val checked = right(ChartValidator.check(chart))
    val input = right(
      ChartProposalProvider.input(
        source,
        atlas,
        Vector(unit.id -> PropositionEvidence.of(checked)),
        None,
        0L
      )
    )
    val sample = right(item(input, input.participants.head, group.getOrElse(source.id), manifest))
    (input, sample)

  private def judgment(i: Item, v: Verdict): Judgment = Judgment(i.id, v, annotator, protocol)
  private def data(rows: (Item, Verdict)*): Corpus =
    right(corpus(rows.map(_._1).toVector, rows.map((i, v) => judgment(i, v)).toVector))
  private def ordinary: (Item, Item, Item, Item) =
    (example("Ada")._2, example("Bea")._2, example("Cora")._2, example("Dora")._2)

  test("Beta-Bernoulli oracle: one success and one failure predict one half") {
    val (a, b, c, _) = ordinary
    val model = right(fit(data(a -> Verdict.Correct, b -> Verdict.Incorrect)))
    assertEqualsDouble(right(model.predict(c)).value, 0.5, 1e-15)
    val cell = model.cells(Cell(a.role, a.score))
    assertEquals((cell.correct, cell.incorrect, cell.stories.size), (1, 1, 2))
    val allCorrect = right(fit(data(a -> Verdict.Correct, b -> Verdict.Correct)))
    assertEqualsDouble(right(allCorrect.predict(c)).value, 0.75, 1e-15)
    assert(math.abs(0.75 - 1.0) > 1e-15)
  }

  test("unresolved is retained and excluded, never a negative or evidence for another story") {
    val (a, b, c, d) = ordinary
    val model = right(
      fit(
        data(
          a -> Verdict.Correct,
          b -> Verdict.Correct,
          c -> Verdict.Unresolved("insufficient context")
        )
      )
    )
    assertEqualsDouble(right(model.predict(d)).value, 0.75, 1e-15)
    assertEquals(model.excluded, Vector(c.id))
    assertEquals(model.predict(c), Left(Refusal.TrainingStory))
    assertEquals(
      fit(data(a -> Verdict.Correct, b -> Verdict.Unresolved("unknown"))),
      Left(Refusal.InsufficientStories(2, 1))
    )
  }

  test("story group and exact source independently prevent training leakage") {
    val (a, b, _, _) = ordinary
    val model = right(fit(data(a -> Verdict.Correct, b -> Verdict.Incorrect)))
    val alias = example("Ada", group = Some(StoryId.unsafe("renamed")))._2
    val version = example("AdaVersion", group = Some(a.storyGroup))._2
    assertEquals(model.predict(alias), Left(Refusal.TrainingStory))
    assertEquals(model.predict(version), Left(Refusal.TrainingStory))
    assert(
      corpus(
        Vector(a, alias),
        Vector(judgment(a, Verdict.Correct), judgment(alias, Verdict.Correct))
      ).isLeft
    )
    val differentItemSameText =
      example("Ada", role = ParticipantRole.Patient, group = Some(StoryId.unsafe("renamed")))._2
    assert(
      corpus(
        Vector(a, differentItemSameText),
        Vector(judgment(a, Verdict.Correct), judgment(differentItemSameText, Verdict.Correct))
      ).isLeft
    )
  }

  test("new scorer, score, role, pipeline and observed parser cannot borrow a fitted probability") {
    val (a, b, _, _) = ordinary
    val model = right(fit(data(a -> Verdict.Correct, b -> Verdict.Correct)))
    Vector(
      example("New", by = ScorerId.unsafe("different")),
      example("New", value = 0.9),
      example("New", role = ParticipantRole.Patient)
    ).foreach { (_, i) =>
      assertEquals(model.predict(i), Left(Refusal.UnseenCell))
    }
    Vector(
      example("New", manifest = Checksum.ofText("different")),
      example("New", parserVersion = "2")
    ).foreach { (_, i) =>
      assertEquals(model.predict(i), Left(Refusal.DifferentPipeline))
    }
  }

  test("one-story cells refuse even when the full corpus has several stories") {
    val a = example("Ada")._2
    val b = example("Bea", role = ParticipantRole.Patient)._2
    val c = example("Cora")._2
    val model = right(fit(data(a -> Verdict.Correct, b -> Verdict.Correct)))
    assertEquals(model.predict(c), Left(Refusal.InsufficientCellStories(1)))
  }

  test("held-out labels cannot influence their fold model or prediction") {
    val (a, b, c, _) = ordinary
    def held(label: Verdict): Fold = right(
      leaveStoryOut(data(a -> label, b -> Verdict.Correct, c -> Verdict.Incorrect))
    ).find(_.story == a.storyGroup).get
    val positive = held(Verdict.Correct)
    val negative = held(Verdict.Incorrect)
    assertEquals(positive.model, negative.model)
    assertEquals(positive.outcomes.map(_.prediction), negative.outcomes.map(_.prediction))
    assertEquals(positive.scored, 1)
    assertEqualsDouble(positive.brier.get, 0.25, 1e-15)
    assertEqualsDouble(positive.logLoss.get, math.log(2.0), 1e-15)
    assert(!right(positive.model).training.items.exists(_.storyGroup == a.storyGroup))
  }

  test("held-out story removes all its items, including alternate versions and unresolved rows") {
    val (a, b, c, _) = ordinary
    val version = example("AdaVersion", group = Some(a.storyGroup))._2
    val study = data(
      a -> Verdict.Correct,
      version -> Verdict.Unresolved("ambiguous"),
      b -> Verdict.Incorrect,
      c -> Verdict.Correct
    )
    val fold = right(leaveStoryOut(study)).find(_.story == a.storyGroup).get
    assertEquals(fold.outcomes.size, 2)
    assertEquals(fold.scored, 1)
    assertEquals(right(fold.model).training.items.map(_.id).toSet, Set(b.id, c.id))
  }

  test("no scorable held-out cells gives absent losses alongside every refusal") {
    val a = example("Ada")._2
    val b = example("Bea", role = ParticipantRole.Patient)._2
    val c = example("Cora", role = ParticipantRole.Theme)._2
    val folds =
      right(leaveStoryOut(data(a -> Verdict.Correct, b -> Verdict.Correct, c -> Verdict.Incorrect)))
    assertEquals(folds.flatMap(_.outcomes).size, 3)
    folds.foreach { f =>
      assertEquals(f.scored, 0)
      assertEquals(f.brier, None)
      assertEquals(f.logLoss, None)
      assert(f.outcomes.forall(_.prediction.isLeft))
    }
  }

  test("corpus requires a complete unique matching batch and one protocol") {
    val (a, b, _, _) = ordinary
    val ja = judgment(a, Verdict.Correct)
    val jb = judgment(b, Verdict.Incorrect)
    assert(corpus(Vector.empty, Vector.empty).isLeft)
    assert(corpus(Vector(a, a), Vector(ja)).isLeft)
    assert(corpus(Vector(a), Vector(ja, ja)).isLeft)
    assert(corpus(Vector(a, b), Vector(ja)).isLeft)
    assert(corpus(Vector(a), Vector(ja, jb)).isLeft)
    assert(corpus(Vector(a, b), Vector(ja, jb.copy(protocol = Checksum.ofText("other")))).isLeft)
    assert(corpus(Vector(a), Vector(ja.copy(verdict = Verdict.Unresolved("  ")))).isLeft)
    assertEquals(
      leaveStoryOut(data(a -> Verdict.Correct, b -> Verdict.Incorrect)),
      Left(Refusal.InsufficientStories(3, 2))
    )
  }

  test("fit identity binds adjudicator, protocol, labels and grouping without truncation") {
    val (a, b, _, _) = ordinary
    val rows = Vector(judgment(a, Verdict.Correct), judgment(b, Verdict.Incorrect))
    def identityOf(js: Vector[Judgment]) = right(fit(right(corpus(Vector(a, b), js)))).id
    val base = identityOf(rows)
    assertNotEquals(base, identityOf(rows.map(_.copy(adjudicator = Fingerprint.unsafe("another")))))
    assertNotEquals(base, identityOf(rows.map(_.copy(protocol = Checksum.ofText("another")))))
    assertNotEquals(base, identityOf(rows.map(_.copy(verdict = Verdict.Correct))))
    assertEquals(base.value.stripPrefix("participant-calibration:").length, 64)
    val alias = example("Ada", group = Some(StoryId.unsafe("regrouped")))._2
    assertNotEquals(base, right(fit(data(alias -> Verdict.Correct, b -> Verdict.Incorrect))).id)
  }

  property("row order never changes the fit; label complementation reflects the prediction") {
    forAll { (aCorrect: Boolean, bCorrect: Boolean) =>
      val (a, b, c, _) = ordinary
      def v(y: Boolean): Verdict = if y then Verdict.Correct else Verdict.Incorrect
      val forward = right(fit(data(a -> v(aCorrect), b -> v(bCorrect))))
      val reversed = right(fit(data(b -> v(bCorrect), a -> v(aCorrect))))
      val complement = right(fit(data(a -> v(!aCorrect), b -> v(!bCorrect))))
      assertEquals(forward, reversed)
      assertEqualsDouble(
        right(forward.predict(c)).value + right(complement.predict(c)).value,
        1.0,
        1e-15
      )
    }
  }

  test("nonfinite scores cannot reach the estimator; finite extremes remain distinct cells") {
    Vector(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity).foreach(v =>
      assert(RawScore.from(v, scorer).isLeft)
    )
    val a = example("Ada", value = Double.MaxValue)._2
    val b = example("Bea", value = Double.MaxValue)._2
    val c = example("Cora", value = Double.MaxValue)._2
    val model = right(fit(data(a -> Verdict.Correct, b -> Verdict.Incorrect)))
    assertEqualsDouble(right(model.predict(c)).value, 0.5, 1e-15)
    assertEquals(example("Ada", value = -0.0)._2.id, example("Ada", value = 0.0)._2.id)
  }

  test("calibration reaches the existing resolver with raw score and evidence unchanged") {
    val (a, b, _, _) = ordinary
    val (input, query) = example("Query")
    val model = right(fit(data(a -> Verdict.Correct, b -> Verdict.Correct)))
    val calibrated = right(model.calibrate(query))
    assertEquals(calibrated.bundle.proposals, query.attempt.bundle.proposals)
    assertEquals(
      calibrated.bundle.bases,
      Vector(
        CandidateBasis(query.role, AcceptanceBasis.Calibrated(Probability.unsafe(0.75), model.id))
      )
    )
    val high =
      right(FamilyPolicy.of(Probability.unsafe(0.7), Probability.unsafe(0.5), 1, true, true, true))
    val policy =
      input.policy.copy(perFamily = input.policy.perFamily + (ClaimFamily.ParticipantRole -> high))
    val rebuilt = right(
      NarrativeCompilerInput.of(
        input.source,
        input.atlas,
        input.localCharts,
        input.evidence.values,
        input.situations,
        input.contexts,
        input.summary,
        input.memberships,
        input.causal,
        input.entityMentions,
        Vector(calibrated),
        input.participantCoverage,
        input.circumstances,
        input.temporal,
        policy,
        input.receipt,
        input.provenance
      )
    )
    val result = right(NarrativeCompiler.compile(rebuilt))
    val participant = result.draft.graph.relations.participants.head
    assertEquals(participant.meta.credence.score, Score.Raw(0.5, scorer))
    assertEquals(
      participant.meta.credence.basis,
      CredenceBasis.Calibrated(Probability.unsafe(0.75), model.id)
    )
    val strict = policy.copy(perFamily =
      policy.perFamily + (ClaimFamily.ParticipantRole ->
        right(
          FamilyPolicy.of(Probability.unsafe(0.9), Probability.unsafe(0.8), 1, true, true, true)
        ))
    )
    assert(
      !Resolver
        .resolve(ClaimFamily.ParticipantRole, calibrated.bundle, strict)
        .isInstanceOf[ResolutionState.Accepted[?]]
    )
  }

  test("item extraction refuses attempts from a different checked input") {
    val (input, _) = example("Ada")
    val (_, other) = example("Bea")
    assert(item(input, other.attempt, other.storyGroup, pipeline).isLeft)
  }

  private def withAttempt(
      input: NarrativeCompilerInput,
      attempt: ParticipantAttempt
  ): NarrativeCompilerInput =
    right(
      NarrativeCompilerInput.of(
        input.source,
        input.atlas,
        input.localCharts,
        input.evidence.values,
        input.situations,
        input.contexts,
        input.summary,
        input.memberships,
        input.causal,
        input.entityMentions,
        Vector(attempt),
        input.participantCoverage,
        input.circumstances,
        input.temporal,
        input.policy,
        input.receipt,
        input.provenance
      )
    )

  test(
    "changing the observed proposer changes identity and refuses transfer even with equal output"
  ) {
    val (a, b, _, _) = ordinary
    val model = right(fit(data(a -> Verdict.Correct, b -> Verdict.Incorrect)))
    val (input, original) = example("New")
    val proposal = original.attempt.bundle.proposals.head
    val receipt = proposal.receipt
    val call = receipt.call
    val changes = Vector(
      receipt.copy(call = call.copy(provider = "another-provider")),
      receipt.copy(call = call.copy(model = "another-model")),
      receipt.copy(call = call.copy(version = "another-version")),
      receipt.copy(call =
        call.copy(promptTemplateVersion = Some(PromptTemplateVersion.unsafe("another-template")))
      ),
      receipt.copy(promptPackage =
        receipt.promptPackage.copy(checksum = Checksum.ofText("another-prompt"))
      )
    )
    changes.foreach { changed =>
      val p = AgentProposal.proposed(
        proposal.taskId,
        original.role,
        NonEmptyVector.fromVectorUnsafe(proposal.evidence),
        proposal.rawScore,
        proposal.conflicts,
        changed
      )
      val attempt =
        original.attempt.copy(bundle = original.attempt.bundle.copy(proposals = Vector(p)))
      val query = right(item(withAttempt(input, attempt), attempt, original.storyGroup, pipeline))
      assertNotEquals(query.id, original.id)
      assertEquals(model.predict(query), Left(Refusal.DifferentPipeline))
    }
  }

  test("receipt changes remain auditable but repeated source candidates cannot pad the sample") {
    val (input, original) = example("Ada")
    val p = original.attempt.bundle.proposals.head
    val changed = AgentProposal.proposed(
      p.taskId,
      original.role,
      NonEmptyVector.fromVectorUnsafe(p.evidence),
      p.rawScore,
      p.conflicts,
      p.receipt.copy(call =
        p.receipt.call.copy(params = p.receipt.call.params + ("extra" -> "recorded"))
      )
    )
    val attempt =
      original.attempt.copy(bundle = original.attempt.bundle.copy(proposals = Vector(changed)))
    val query = right(item(withAttempt(input, attempt), attempt, original.storyGroup, pipeline))
    assertNotEquals(query.id, original.id)
    assertEquals(query.producer, original.producer)
    assert(
      corpus(
        Vector(original, query),
        Vector(judgment(original, Verdict.Correct), judgment(query, Verdict.Correct))
      ).isLeft
    )
  }

  test("graph-isomorphic charts with swapped named fillers cannot share a judgment identity") {
    val (base, _) = example("Ada")
    val (unit, ev) = base.localCharts.head
    val chart = ev.chart
    val person = ConceptId.unsafe("person")
    val second = ConceptId.unsafe("second")
    def paired(swap: Boolean): (PropositionChart[Checked], Item) =
      val concepts = chart.concepts ++ Map(
        person -> Concept.name(if swap then "Bea" else "Ada"),
        second -> Concept.name(if swap then "Ada" else "Bea")
      )
      val relation = chart.relations.head
      val personAlignment = chart.alignments.find(_.target.conceptIds.contains(person)).get
      val duplicateAlignment =
        personAlignment.copy(target = AlignmentTarget.Concepts(NonEmptySet.one(second)))
      val changed = right(
        ChartValidator.check(
          PropositionChart.unchecked(
            chart.focus,
            concepts,
            chart.relations :+ relation.copy(to = ConceptTarget.Node(second)),
            chart.polarity,
            chart.embedded,
            chart.alignments :+ duplicateAlignment,
            chart.provenance,
            chart.sentence
          )
        )
      )
      val input = right(
        ChartProposalProvider.input(
          base.source,
          base.atlas,
          Vector(unit -> PropositionEvidence.of(changed)),
          None,
          0L
        )
      )
      val candidate = input.participants.find(_.filler.concept == person).get
      (changed, right(item(input, candidate, base.source.id, pipeline)))
    val (left, a) = paired(false)
    val (rightChart, b) = paired(true)
    assertEquals(Canonical.checksum(left), Canonical.checksum(rightChart))
    assertEquals(a.attempt.situation, b.attempt.situation)
    assertEquals(a.attempt.filler, b.attempt.filler)
    assertNotEquals(a.id, b.id)
  }

  test("unmeasured and competing candidates are explicit extraction refusals") {
    val (input, sample) = example("Ada")
    val p = sample.attempt.bundle.proposals.head
    val unmeasured = AgentProposal.proposed(
      p.taskId,
      sample.role,
      NonEmptyVector.fromVectorUnsafe(p.evidence),
      None,
      p.conflicts,
      p.receipt
    )
    val attempt =
      sample.attempt.copy(bundle = sample.attempt.bundle.copy(proposals = Vector(unmeasured)))
    assertEquals(
      item(withAttempt(input, attempt), attempt, sample.storyGroup, pipeline),
      Left(Refusal.InvalidItem("candidate has no measured role score"))
    )
    val abstained = AgentProposal.abstained[ParticipantRole](p.taskId, p.receipt)
    val absent = sample.attempt.copy(bundle =
      sample.attempt.bundle.copy(proposals = Vector(abstained), bases = Vector.empty)
    )
    assertEquals(
      item(withAttempt(input, absent), absent, sample.storyGroup, pipeline),
      Left(Refusal.InvalidItem("requires one proposed participant candidate"))
    )
  }

  test("bounded study baseline: 128 candidates in eight groups retain all held-out outcomes") {
    val samples = Vector.tabulate(128)(i =>
      example(s"Person$i", group = Some(StoryId.unsafe(s"group-${i / 16}")))._2
    )
    val labels = samples.zipWithIndex.map((i, n) =>
      judgment(i, if n % 2 == 0 then Verdict.Correct else Verdict.Incorrect)
    )
    val start = System.nanoTime()
    val study = right(corpus(samples, labels))
    val folds = right(leaveStoryOut(study))
    val elapsed = (System.nanoTime() - start).toDouble / 1e6
    assertEquals(folds.size, 8)
    assertEquals(folds.map(_.scored).sum, 128)
    folds.foreach(f => assertEqualsDouble(f.brier.get, 0.25, 1e-15))
    println(s"CALIBRATION_BASELINE items=128 groups=8 folds=8 scored=128 elapsed_ms=$elapsed")
  }

  test("wrapper acquisition provenance changes item identity even when the chart is unchanged") {
    val (input, _) = example("Ada")
    val (unit, ev) = input.localCharts.head
    val call = input.participants.head.bundle.proposals.head.receipt.call
    def wrapped(c: ProviderCall): Item =
      val wrapper = PropositionEvidence(ev.chart, ev.provenance.copy(receipts = Vector(c)))
      val rebuilt = right(
        ChartProposalProvider.input(input.source, input.atlas, Vector(unit -> wrapper), None, 0L)
      )
      right(item(rebuilt, rebuilt.participants.head, input.source.id, pipeline))
    val original = wrapped(call)
    Vector(
      call.copy(params = call.params + ("setting" -> "changed")),
      call.copy(outputChecksum = Checksum.ofText("changed-output")),
      call.copy(seed = Some(987L)),
      call.copy(cached = !call.cached)
    ).foreach { c =>
      val changed = wrapped(c)
      assertEquals(original.producer, changed.producer)
      assertNotEquals(original.id, changed.id)
    }
  }
