package storymodel4s.document

import cats.data.{NonEmptySet, NonEmptyVector}
import munit.FunSuite
import storymodel4s.acquire.*
import storymodel4s.core.*
import storymodel4s.proposition.{Polarity as ChartPolarity, *}
import storymodel4s.story.*

/** Court for [[ContextPlacement]] and the frames the compiler derives from it.
  *
  * Every chart here is hand-built and checked, and every test states one rule, so deleting a rule
  * turns a named test red rather than moving a fifty-sentence total. The source text carries real
  * quotation marks because the marks are the observation the cross-sentence rule reads.
  */
class ContextPlacementSuite extends FunSuite:
  private val text =
    """He said: "I accompanied the ghosts." "We fought. Many were killed." He became quiet."""
  private val source = StorySource
    .titled(
      text,
      StoryTitle.callerSupplied("Placement court").fold(e => fail(e.message), identity)
    )
    .fold(e => fail(e.message), identity)
  private val atlas = SurfaceAnalyzer.analyze(source)
  private val parser = Fingerprint.unsafe("test:chart-parser:1")
  private val parserStage = StageId.unsafe("test-chart-parser")

  private def id(name: String): ConceptId = ConceptId.unsafe(name)

  private def chartCall(salt: String): ProviderCall =
    ProviderCall(
      "test-parser",
      "test-model",
      "1",
      None,
      source.canonicalChecksum,
      Checksum.ofText(s"chart:$salt"),
      Map("salt" -> salt),
      None,
      cached = false
    )

  private def spanIn(at: SurfaceAtlas, unit: SurfaceUnit, word: String): SpanRef =
    val body = at.text(unit)
    val place = body.indexOf(word)
    assert(place >= 0, s"'$word' is not in '$body'")
    SpanRef(
      Some(unit.id),
      TextSpan.unsafe(unit.span.start + place, unit.span.start + place + word.length)
    )

  private def alignIn(
      at: SurfaceAtlas,
      unit: SurfaceUnit,
      word: String,
      concept: String
  ): PropositionAlignment =
    val spans = SpanSet.one(spanIn(at, unit, word))
    val evidence = Evidence(
      EvidenceId.unsafe(s"ev:align:${unit.id.value}:$word"),
      Some(spans),
      Set.empty,
      parser,
      parserStage
    )
    PropositionAlignment(
      AlignmentTarget.Concepts(NonEmptySet.one(id(concept))),
      spans,
      Credence.unsafeRaw(1.0),
      ClaimMeta.unsafe(
        ClaimId.unsafe(s"claim:align:${unit.id.value}:$word"),
        EpistemicStatus.SurfaceExplicit,
        Credence.unsafeRaw(1.0),
        NonEmptyVector.one(evidence),
        Provenance.deterministic("test", Checksum.ofText("test-parser"))
      )
    )

  private def spanOf(unit: SurfaceUnit, word: String): SpanRef =
    val body = atlas.text(unit)
    val at = body.indexOf(word)
    assert(at >= 0, s"'$word' is not in '$body'")
    SpanRef(
      Some(unit.id),
      TextSpan.unsafe(unit.span.start + at, unit.span.start + at + word.length)
    )

  private def align(unit: SurfaceUnit, word: String, concept: String): PropositionAlignment =
    val spans = SpanSet.one(spanOf(unit, word))
    val evidence = Evidence(
      EvidenceId.unsafe(s"ev:align:${unit.id.value}:$word"),
      Some(spans),
      Set.empty,
      parser,
      parserStage
    )
    PropositionAlignment(
      AlignmentTarget.Concepts(NonEmptySet.one(id(concept))),
      spans,
      Credence.unsafeRaw(1.0),
      ClaimMeta.unsafe(
        ClaimId.unsafe(s"claim:align:${unit.id.value}:$word"),
        EpistemicStatus.SurfaceExplicit,
        Credence.unsafeRaw(1.0),
        NonEmptyVector.one(evidence),
        Provenance.deterministic("test", Checksum.ofText("test-parser"))
      )
    )

  private def checked(
      unit: SurfaceUnit,
      focus: String,
      concepts: Map[String, Concept],
      relations: Vector[PropositionRelation] = Vector.empty,
      polarity: Map[String, ChartPolarity] = Map.empty,
      embedded: Vector[EmbeddedProposition] = Vector.empty,
      alignments: Vector[PropositionAlignment] = Vector.empty,
      salt: String = ""
  ): PropositionEvidence =
    val unchecked = PropositionChart.unchecked(
      Some(id(focus)),
      concepts.map((name, concept) => id(name) -> concept),
      relations,
      polarity.map((name, value) => id(name) -> value),
      embedded,
      alignments,
      ChartProvenance(
        ChartOrigin.Parser(parser),
        Vector(chartCall(s"${unit.id.value}$salt")),
        Vector.empty
      ),
      Some(unit.id)
    )
    PropositionEvidence.of(ChartValidator.check(unchecked).fold(v => fail(v.toString), identity))

  private def rel(from: String, role: RoleAssignment, to: String): PropositionRelation =
    PropositionRelation(id(from), role, ConceptTarget.Node(id(to)))

  private def frame(name: String): Option[FrameRef] = Some(FrameRef("propbank", name, None))
  private val agent =
    RoleAssignment(SourceRole.Numbered(0), Some((ParticipantRole.Agent, Credence.unsafeRaw(0.9))))
  private val theme =
    RoleAssignment(SourceRole.Numbered(1), Some((ParticipantRole.Theme, Credence.unsafeRaw(0.9))))

  private def compileWith(
      src: StorySource,
      atl: SurfaceAtlas,
      charts: Vector[(SurfaceUnitId, PropositionEvidence)]
  ): NarrativeCompilation =
    val input = ChartProposalProvider
      .input(src, atl, charts, None, 0L)
      .fold(e => fail(e.message), identity)
    NarrativeCompiler.compile(input).fold(e => fail(e.message), identity)

  private def compile(charts: Vector[(SurfaceUnitId, PropositionEvidence)]): NarrativeCompilation =
    compileWith(source, atlas, charts)

  /** `He said: "I accompanied the ghosts."` — say-01 with its content embedded under ARG1. */
  private def saidChart: PropositionEvidence =
    checked(
      atlas.sentences(0),
      "s",
      Map(
        "s" -> Concept.predicate("say", frame("say-01")),
        "h" -> Concept.entity("he"),
        "a" -> Concept.predicate("accompany", frame("accompany-01"))
      ),
      Vector(rel("s", agent, "h"), rel("s", theme, "a")),
      polarity = Map("s" -> ChartPolarity.Positive),
      embedded = Vector(EmbeddedProposition(id("s"), EmbeddingKind.Speech, id("a"))),
      alignments = Vector(
        align(atlas.sentences(0), "said", "s"),
        align(atlas.sentences(0), "He", "h"),
        align(atlas.sentences(0), "accompanied", "a")
      ),
      salt = "said"
    )

  /** `"We fought.` — a whole sentence inside a quotation that no chart of its own can see. */
  private def foughtChart: PropositionEvidence =
    checked(
      atlas.sentences(1),
      "f",
      Map("f" -> Concept.predicate("fight", frame("fight-01")), "w" -> Concept.entity("we")),
      Vector(rel("f", agent, "w")),
      polarity = Map("f" -> ChartPolarity.Positive),
      alignments = Vector(
        align(atlas.sentences(1), "fought", "f"),
        align(atlas.sentences(1), "We", "w")
      ),
      salt = "fought"
    )

  private def placement: TextPlacement =
    ContextPlacement.read(
      source,
      atlas,
      Vector(
        atlas.sentences(0).id -> saidChart.chart,
        atlas.sentences(1).id -> foughtChart.chart
      )
    )

  private def place(unit: SurfaceUnit, ev: PropositionEvidence, root: String) =
    ContextPlacement.place(ev.chart, id(root), unit, placement)

  // ---- the positive root-world rule ------------------------------------------------------

  test("root world is the positive reading: outside every quotation and held by nothing") {
    assertEquals(
      place(atlas.sentences(0), saidChart, "s"),
      Right(ContextAssignmentProposal.NarratedWorld)
    )
  }

  test("a concept held by an embedding is not root world even with no quotation in sight") {
    val plain = StorySource
      .titled(
        "He said he accompanied them.",
        StoryTitle.callerSupplied("No marks").fold(e => fail(e.message), identity)
      )
      .fold(e => fail(e.message), identity)
    val plainAtlas = SurfaceAnalyzer.analyze(plain)
    val unit = plainAtlas.sentences(0)
    val chart = PropositionEvidence.of(
      ChartValidator
        .check(
          PropositionChart.unchecked(
            Some(id("s")),
            Map(
              id("s") -> Concept.predicate("say", frame("say-01")),
              id("a") -> Concept.predicate("accompany", frame("accompany-01"))
            ),
            Vector(rel("s", theme, "a")),
            Map(id("s") -> ChartPolarity.Positive),
            Vector(EmbeddedProposition(id("s"), EmbeddingKind.Speech, id("a"))),
            Vector.empty,
            ChartProvenance(ChartOrigin.Parser(parser), Vector(chartCall("plain")), Vector.empty),
            Some(unit.id)
          )
        )
        .fold(v => fail(v.toString), identity)
    )
    val read = ContextPlacement.read(plain, plainAtlas, Vector(unit.id -> chart.chart))
    assert(read.quotations.isEmpty)
    val placed = ContextPlacement.place(chart.chart, id("a"), unit, read)
    assertNotEquals(placed, Right(ContextAssignmentProposal.NarratedWorld): Any)
    assertEquals(placed.map(_.steps.size), Right(1))
  }

  // ---- cross-sentence quotation -----------------------------------------------------------

  test("a sentence wholly inside a quotation is placed in a speech context") {
    val placed = place(atlas.sentences(1), foughtChart, "f")
    assertNotEquals(placed, Right(ContextAssignmentProposal.NarratedWorld): Any)
    placed match
      case Right(ContextAssignmentProposal.Held(path)) =>
        assertEquals(path.length, 1)
        path.head match
          case ContextStep.Quoted(q, _) =>
            assertEquals(
              text.substring(q.start, q.endExclusive),
              "\"We fought. Many were killed.\""
            )
          case other => fail(s"expected a quotation step, got $other")
      case other => fail(s"expected Held, got $other")
  }

  test("a quotation the chart already accounts for is dropped, so the holder is not doubled") {
    place(atlas.sentences(0), saidChart, "a") match
      case Right(ContextAssignmentProposal.Held(path)) =>
        assertEquals(path.length, 1)
        assert(path.head.isInstanceOf[ContextStep.Embedded], path.head.toString)
      case other => fail(s"expected one embedded step, got $other")
  }

  test("an anchor that straddles a quotation mark is refused, never called root world") {
    val whole = TextSpan.unsafe(0, text.indexOf("ghosts") + 6)
    ContextPlacement.read(source, atlas, Vector.empty).scanned match
      case Right(scan) =>
        assertEquals(
          scan.containment(whole),
          QuotationContainment.Straddling(scan.spans.head)
        )
      case Left(defect) => fail(s"scan refused: ${defect.render}")
  }

  test("an unreadable text refuses every placement instead of defaulting to the narrated world") {
    val broken = StorySource
      .titled(
        """He said: "we fought and nobody closed it.""",
        StoryTitle.callerSupplied("Unclosed").fold(e => fail(e.message), identity)
      )
      .fold(e => fail(e.message), identity)
    val brokenAtlas = SurfaceAnalyzer.analyze(broken)
    val read = ContextPlacement.read(broken, brokenAtlas, Vector.empty)
    val unit = brokenAtlas.sentences(0)
    val placed = ContextPlacement.place(saidChart.chart, id("s"), unit, read)
    assertEquals(
      placed,
      Left(PlacementRefusal.TextUnreadable(QuotationDefect.UnclosedOpen(9)))
    )
  }

  // ---- attribution -------------------------------------------------------------------------

  test("a quotation with one candidate speaker names it; the compiler resolves it to an entity") {
    val model = compile(
      Vector(
        atlas.sentences(0).id -> saidChart,
        atlas.sentences(1).id -> foughtChart
      )
    ).draft
    val fought = model.graph.situations.values.find(_.predicate.lemma == "fight").get
    val frameOf = model.graph.contexts(fought.context)
    frameOf.kind match
      case ContextKind.Speech(ContextHolder.Named(entity)) =>
        assertEquals(model.graph.entities(entity).label.value, "he")
      case other => fail(s"expected an attributed speech context, got ${other.label}")
    assertEquals(frameOf.parent.map(model.graph.contexts(_).kind), Some(ContextKind.NarratedWorld))
  }

  test("a quotation with no speech verb before it is unattributed, and still not root world") {
    val quoted = StorySource
      .titled(
        """He became quiet. "Arrows are in the canoe." He was dead.""",
        StoryTitle.callerSupplied("Unattributed").fold(e => fail(e.message), identity)
      )
      .fold(e => fail(e.message), identity)
    val quotedAtlas = SurfaceAnalyzer.analyze(quoted)
    val unit = quotedAtlas.sentences(1)
    val chart = PropositionEvidence.of(
      ChartValidator
        .check(
          PropositionChart.unchecked(
            Some(id("b")),
            Map(
              id("b") -> Concept.predicate("be-located", frame("be-located-at-91")),
              id("r") -> Concept.entity("arrow")
            ),
            Vector(rel("b", theme, "r")),
            Map(id("b") -> ChartPolarity.Positive),
            Vector.empty,
            Vector.empty,
            ChartProvenance(ChartOrigin.Parser(parser), Vector(chartCall("arrows")), Vector.empty),
            Some(unit.id)
          )
        )
        .fold(v => fail(v.toString), identity)
    )
    val read = ContextPlacement.read(quoted, quotedAtlas, Vector(unit.id -> chart.chart))
    ContextPlacement.place(chart.chart, id("b"), unit, read) match
      case Right(ContextAssignmentProposal.Held(path)) =>
        assertEquals(
          path.head,
          ContextStep.Quoted(
            read.quotations.head,
            HolderCandidate.Missing(HolderGap.NoCandidate)
          )
        )
      case other => fail(s"expected an unattributed quotation step, got $other")
  }

  test("the speaker lookback stops one sentence back, so an old speech verb does not attribute") {
    val far = StorySource
      .titled(
        "He said: \"one.\" He walked home. He lit a fire. \"two.\"",
        StoryTitle.callerSupplied("Lookback").fold(e => fail(e.message), identity)
      )
      .fold(e => fail(e.message), identity)
    val farAtlas = SurfaceAnalyzer.analyze(far)
    val saidUnit = farAtlas.sentences(0)
    val chart = PropositionEvidence.of(
      ChartValidator
        .check(
          PropositionChart.unchecked(
            Some(id("s")),
            Map(
              id("s") -> Concept.predicate("say", frame("say-01")),
              id("h") -> Concept.entity("he"),
              id("o") -> Concept.predicate("one", frame("one-01"))
            ),
            Vector(rel("s", agent, "h"), rel("s", theme, "o")),
            Map(id("s") -> ChartPolarity.Positive),
            Vector(EmbeddedProposition(id("s"), EmbeddingKind.Speech, id("o"))),
            Vector.empty,
            ChartProvenance(ChartOrigin.Parser(parser), Vector(chartCall("far")), Vector.empty),
            Some(saidUnit.id)
          )
        )
        .fold(v => fail(v.toString), identity)
    )
    val read = ContextPlacement.read(far, farAtlas, Vector(saidUnit.id -> chart.chart))
    val last = read.quotations.last
    assertEquals(read.speakers(last), HolderCandidate.Missing(HolderGap.NoCandidate))
  }

  test("two candidate speakers abstain rather than picking one") {
    val two = StorySource
      .titled(
        "He said and she replied: \"hello.\" \"Nobody knows.\"",
        StoryTitle.callerSupplied("Two speakers").fold(e => fail(e.message), identity)
      )
      .fold(e => fail(e.message), identity)
    val twoAtlas = SurfaceAnalyzer.analyze(two)
    val unit = twoAtlas.sentences(0)
    val chart = PropositionEvidence.of(
      ChartValidator
        .check(
          PropositionChart.unchecked(
            Some(id("a")),
            Map(
              id("a") -> Concept.entity("and"),
              id("s") -> Concept.predicate("say", frame("say-01")),
              id("r") -> Concept.predicate("reply", frame("say-01")),
              id("h") -> Concept.entity("he"),
              id("w") -> Concept.entity("she"),
              id("g") -> Concept.predicate("greet", frame("greet-01")),
              id("g2") -> Concept.predicate("greet", frame("greet-01"))
            ),
            Vector(
              PropositionRelation(
                id("a"),
                RoleAssignment(SourceRole.Operand(1), None),
                ConceptTarget.Node(id("s"))
              ),
              PropositionRelation(
                id("a"),
                RoleAssignment(SourceRole.Operand(2), None),
                ConceptTarget.Node(id("r"))
              ),
              rel("s", agent, "h"),
              rel("s", theme, "g"),
              rel("r", agent, "w"),
              rel("r", theme, "g2")
            ),
            Map(id("s") -> ChartPolarity.Positive, id("r") -> ChartPolarity.Positive),
            Vector(
              EmbeddedProposition(id("s"), EmbeddingKind.Speech, id("g")),
              EmbeddedProposition(id("r"), EmbeddingKind.Speech, id("g2"))
            ),
            Vector(
              alignIn(twoAtlas, unit, "said", "s"),
              alignIn(twoAtlas, unit, "replied", "r"),
              alignIn(twoAtlas, unit, "He", "h"),
              alignIn(twoAtlas, unit, "she", "w"),
              alignIn(twoAtlas, unit, "hello", "g")
            ),
            ChartProvenance(ChartOrigin.Parser(parser), Vector(chartCall("two")), Vector.empty),
            Some(unit.id)
          )
        )
        .fold(v => fail(v.toString), identity)
    )
    // The second quotation is a sentence of its own, so its speaker is looked for one sentence
    // back and both reporting predicates answer. Two speakers is not one speaker.
    val second = twoAtlas.sentences(1)
    val knowsChart = PropositionEvidence.of(
      ChartValidator
        .check(
          PropositionChart.unchecked(
            Some(id("k")),
            Map(
              id("k") -> Concept.predicate("know", frame("know-01")),
              id("n") -> Concept.entity("nobody")
            ),
            Vector(rel("k", agent, "n")),
            Map(id("k") -> ChartPolarity.Positive),
            Vector.empty,
            Vector(
              alignIn(twoAtlas, second, "knows", "k"),
              alignIn(twoAtlas, second, "Nobody", "n")
            ),
            ChartProvenance(ChartOrigin.Parser(parser), Vector(chartCall("knows")), Vector.empty),
            Some(second.id)
          )
        )
        .fold(v => fail(v.toString), identity)
    )
    val read = ContextPlacement.read(
      two,
      twoAtlas,
      Vector(unit.id -> chart.chart, second.id -> knowsChart.chart)
    )
    read.speakers(read.quotations(1)) match
      case HolderCandidate.Fillers(refs) => assertEquals(refs.length, 2)
      case other => fail(s"expected two offered speakers, got ${other.render}")

    val model =
      compileWith(two, twoAtlas, Vector(unit.id -> chart, second.id -> knowsChart))
    val knows = model.draft.graph.situations.values.find(_.predicate.lemma == "know").get
    assertEquals(
      model.draft.graph.contexts(knows.context).kind,
      ContextKind.Speech(ContextHolder.Unattributed(HolderGap.SeveralCandidates))
    )
  }

  test("dialogue attributes to the speaker of its own sentence, not the previous one") {
    val dialogue = StorySource
      .titled(
        "He said: \"one.\" She said: \"two.\"",
        StoryTitle.callerSupplied("Dialogue").fold(e => fail(e.message), identity)
      )
      .fold(e => fail(e.message), identity)
    val dialogueAtlas = SurfaceAnalyzer.analyze(dialogue)
    def speechChart(unit: SurfaceUnit, who: String, salt: String): PropositionEvidence =
      PropositionEvidence.of(
        ChartValidator
          .check(
            PropositionChart.unchecked(
              Some(id("s")),
              Map(
                id("s") -> Concept.predicate("say", frame("say-01")),
                id("h") -> Concept.entity(who),
                id("c") -> Concept.predicate("count", frame("count-01"))
              ),
              Vector(rel("s", agent, "h"), rel("s", theme, "c")),
              Map(id("s") -> ChartPolarity.Positive),
              Vector(EmbeddedProposition(id("s"), EmbeddingKind.Speech, id("c"))),
              Vector(
                alignIn(dialogueAtlas, unit, "said", "s"),
                alignIn(dialogueAtlas, unit, who.capitalize, "h")
              ),
              ChartProvenance(ChartOrigin.Parser(parser), Vector(chartCall(salt)), Vector.empty),
              Some(unit.id)
            )
          )
          .fold(v => fail(v.toString), identity)
      )
    val first = speechChart(dialogueAtlas.sentences(0), "he", "d0")
    val second = speechChart(dialogueAtlas.sentences(1), "she", "d1")
    val read = ContextPlacement.read(
      dialogue,
      dialogueAtlas,
      Vector(
        dialogueAtlas.sentences(0).id -> first.chart,
        dialogueAtlas.sentences(1).id -> second.chart
      )
    )
    assertEquals(read.quotations.size, 2)
    assertEquals(
      read.speakers(read.quotations(1)),
      HolderCandidate.Fillers(
        NonEmptyVector.one(ChartNodeRef(dialogueAtlas.sentences(1).id, id("h")))
      )
    )
  }

  // ---- nesting ------------------------------------------------------------------------------

  test("a nested embedding nests contexts, outermost first") {
    val nested = StorySource
      .titled(
        "He said he thought they were ghosts.",
        StoryTitle.callerSupplied("Nesting").fold(e => fail(e.message), identity)
      )
      .fold(e => fail(e.message), identity)
    val nestedAtlas = SurfaceAnalyzer.analyze(nested)
    val unit = nestedAtlas.sentences(0)
    val chart = PropositionEvidence.of(
      ChartValidator
        .check(
          PropositionChart.unchecked(
            Some(id("s")),
            Map(
              id("s") -> Concept.predicate("say", frame("say-01")),
              id("t") -> Concept.predicate("think", frame("think-01")),
              id("g") -> Concept.predicate("be-ghost", frame("be-located-at-91"))
            ),
            Vector(rel("s", theme, "t"), rel("t", theme, "g")),
            Map(id("s") -> ChartPolarity.Positive),
            Vector(
              EmbeddedProposition(id("s"), EmbeddingKind.Speech, id("t")),
              EmbeddedProposition(id("t"), EmbeddingKind.Belief, id("g"))
            ),
            Vector.empty,
            ChartProvenance(ChartOrigin.Parser(parser), Vector(chartCall("nested")), Vector.empty),
            Some(unit.id)
          )
        )
        .fold(v => fail(v.toString), identity)
    )
    val read = ContextPlacement.read(nested, nestedAtlas, Vector(unit.id -> chart.chart))
    ContextPlacement.place(chart.chart, id("g"), unit, read) match
      case Right(ContextAssignmentProposal.Held(path)) =>
        assertEquals(path.length, 2)
        assertEquals(path.toVector.map(_.placementKey.split(":").last), Vector("speech", "belief"))
      case other => fail(s"expected two nested steps, got $other")
  }

  test("a concept held two ways is refused rather than placed under one of them") {
    val ambiguous = PropositionChart.unchecked(
      Some(id("s")),
      Map(
        id("s") -> Concept.predicate("say", frame("say-01")),
        id("t") -> Concept.predicate("think", frame("think-01")),
        id("g") -> Concept.predicate("go", frame("go-02"))
      ),
      Vector(rel("s", theme, "g"), rel("t", theme, "g")),
      Map(id("s") -> ChartPolarity.Positive),
      Vector(
        EmbeddedProposition(id("s"), EmbeddingKind.Speech, id("g")),
        EmbeddedProposition(id("t"), EmbeddingKind.Belief, id("g"))
      ),
      Vector.empty,
      ChartProvenance(ChartOrigin.Parser(parser), Vector(chartCall("ambig")), Vector.empty),
      Some(atlas.sentences(0).id)
    )
    assertEquals(
      ContextPlacement.heldChain(ambiguous, id("g")),
      Left(PlacementRefusal.AmbiguousEmbedding(id("g")))
    )
  }

  // ---- identity ------------------------------------------------------------------------------

  test("one quotation is one context however many sentences it covers") {
    val model = compile(
      Vector(
        atlas.sentences(0).id -> saidChart,
        atlas.sentences(1).id -> foughtChart
      )
    ).draft
    val speech = model.graph.contexts.values.filter(_.kind != ContextKind.NarratedWorld)
    assertEquals(speech.size, 1)
    assertEquals(model.graph.contextRoots.size, 1)
  }
