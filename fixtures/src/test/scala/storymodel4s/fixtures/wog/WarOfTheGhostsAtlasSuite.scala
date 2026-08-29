package storymodel4s.fixtures.wog

import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.story.*
import storymodel4s.view.*
import storymodel4s.fixtures.wog.WarOfTheGhostsModel as Wog

/** Discourse Atlas laws (ADR 0002 §5) on the War of the Ghosts acceptance fixture. */
class WarOfTheGhostsAtlasSuite extends FunSuite:
  val model = Wog.model
  val ev = Addressable[StoryRef]
  val all3 = ThreadPolicy.All(PositiveInt.unsafe(3))

  def spec(
      level: NarrativeLevel,
      threads: ThreadPolicy = all3,
      surface: SurfaceDetail = SurfaceDetail.Hidden
  ): AtlasSpec =
    AtlasSpec(ZoomLevel(level, surface), threads)

  def provenanceFor(
      sourceModel: StoryModel[ModelStatus.Validated],
      state: CommonViewState,
      s: AtlasSpec
  ): ViewProvenance =
    ViewProvenance
      .fixture(
        sourceModel.source.canonicalChecksum,
        "atlas-suite",
        AtlasCompiler.configurationChecksum(state, s)
      )
      .fold(e => fail(e.message), identity)

  def scene(
      level: NarrativeLevel,
      state: CommonViewState = CommonViewState.empty,
      threads: ThreadPolicy = all3,
      sourceModel: StoryModel[ModelStatus.Validated] = model,
      surface: SurfaceDetail = SurfaceDetail.Hidden
  ): NarrativeScene =
    val s = spec(level, threads, surface)
    AtlasCompiler(provenanceFor(sourceModel, state, s))
      .compile(sourceModel, state, s)
      .fold(e => fail(e.message), identity)

  def state(
      selection: Set[Address] = Set.empty,
      focus: Option[Address] = None,
      horizon: EpistemicHorizon = EpistemicHorizon.Omniscient,
      layers: Set[RelationLayer] = Set.empty
  ): CommonViewState =
    CommonViewState
      .of(selection = selection, focus = focus, horizon = horizon, relationLayers = layers)
      .fold(e => fail(e.message), identity)

  def rebuilt(
      atlas: SurfaceAtlas = model.atlas,
      graph: NarrativeGraph = model.graph,
      hierarchy: NarrativeHierarchy = model.hierarchy
  ): StoryModel[ModelStatus.Validated] =
    val draft = StoryModel.draft(
      model.source,
      atlas,
      graph,
      hierarchy,
      DiscourseTrajectory.derive(graph, hierarchy, atlas),
      model.featureSpaces,
      model.sidecars,
      model.featureRefs,
      model.descriptors,
      model.hypotheses,
      model.sensoryProfiles,
      model.receipt,
      model.schemaVersion
    )
    val outcome = StoryValidator.validate(draft)
    outcome.validated.getOrElse(fail(outcome.report.render))

  def validatedAtlas(units: Vector[storymodel4s.core.SurfaceUnit]): SurfaceAtlas =
    SurfaceAtlas
      .validated(SurfaceAtlas(model.source, units))
      .fold(error => fail(error.message), identity)

  def groundedHierarchy(evidenceSentence: Int = 0): NarrativeHierarchy =
    val containment = model.hierarchy.containment.zipWithIndex.map { (edge, index) =>
      edge.copy(
        meta = Wog.meta(
          s"atlas-test:containment-grounded:$evidenceSentence:$index",
          EpistemicStatus.HumanAdjudicated,
          Some(Wog.sp(evidenceSentence))
        )
      )
    }
    NarrativeHierarchy(containment, model.hierarchy.boundaryBeliefs)

  def regions(s: NarrativeScene) = s.marks.collect { case r: VisualPrimitive.Region => r }
  def landmarks(s: NarrativeScene) = s.marks.collect { case l: VisualPrimitive.Landmark => l }
  def surfaceUnits(s: NarrativeScene) =
    s.marks.collect { case unit: VisualPrimitive.SurfaceUnit => unit }

  test("surface detail changes marks and preserves a selected token through its sentence"):
    assert(model.atlas.sentences.size > 1, "fixture must contain multiple sentences")
    assert(
      model.atlas.tokens.size > model.atlas.sentences.size,
      "fixture must contain more tokens than sentences"
    )
    val token = model.atlas.tokens.find(_.parent.nonEmpty).getOrElse(fail("missing parented token"))
    val sentence = token.parent
      .flatMap(model.atlas.byId.get)
      .getOrElse(fail("token parent is absent from the surface atlas"))
    assertEquals(sentence.kind, SurfaceUnitKind.Sentence)
    val coreRef = Addressable[CoreRef]
    val tokenAddress = coreRef.address(CoreRef.SurfaceUnit(token.id))
    val sentenceAddress = coreRef.address(CoreRef.SurfaceUnit(sentence.id))
    val selected = state(selection = Set(tokenAddress))

    val hidden = scene(NarrativeLevel.Scene, selected)
    val sentences = scene(
      NarrativeLevel.Scene,
      selected,
      surface = SurfaceDetail.Sentences
    )
    val tokens = scene(NarrativeLevel.Scene, selected, surface = SurfaceDetail.Tokens)

    assertNotEquals(hidden.marks.map(_.identity.mark), sentences.marks.map(_.identity.mark))
    assertNotEquals(sentences.marks.map(_.identity.mark), tokens.marks.map(_.identity.mark))
    assertEquals(surfaceUnits(hidden), Vector.empty)
    assertEquals(surfaceUnits(sentences).size, model.atlas.sentences.size)
    assertEquals(
      surfaceUnits(tokens).size,
      model.atlas.sentences.size + model.atlas.tokens.size
    )
    assertEquals(surfaceUnits(sentences).map(_.kind).toSet, Set(SurfaceUnitKind.Sentence))
    assertEquals(
      surfaceUnits(tokens).map(_.kind).toSet,
      Set(SurfaceUnitKind.Sentence, SurfaceUnitKind.Token)
    )
    val sentenceMarkIds = surfaceUnits(sentences).map(u => u.address -> u.identity.mark).toMap
    val tokenDetailSentenceMarkIds = surfaceUnits(tokens)
      .filter(_.kind == SurfaceUnitKind.Sentence)
      .map(u => u.address -> u.identity.mark)
      .toMap
    assertEquals(tokenDetailSentenceMarkIds, sentenceMarkIds)
    assertEquals(
      hidden.selectionPlacements.get(tokenAddress),
      Some(SelectionPlacement.OffProjection)
    )
    assertEquals(
      sentences.selectionPlacements.get(tokenAddress),
      Some(SelectionPlacement.ViaAncestor(sentenceAddress))
    )
    tokens.selectionPlacements.get(tokenAddress) match
      case Some(SelectionPlacement.OnMark(marks)) =>
        assertEquals(marks.toVector, tokens.navigation.marksFor(tokenAddress))
      case other => fail(s"expected selected token on its token mark, found $other")
    assert(
      tokens.navigation.marksFor(sentenceAddress).nonEmpty,
      "token detail must retain sentence marks as the coarser visible ancestors"
    )
    model.atlas.sentences.foreach(unit =>
      assert(!tokens.textualTwin.contains(model.atlas.text(unit)))
    )

  test("mid-sentence horizon keeps a complete token but not its incomplete sentence"):
    val (token, sentence) = model.atlas.tokens
      .flatMap(token =>
        token.parent
          .flatMap(model.atlas.byId.get)
          .filter(parent =>
            parent.kind == SurfaceUnitKind.Sentence &&
              token.span.endExclusive < parent.span.endExclusive
          )
          .map(token -> _)
      )
      .headOption
      .getOrElse(fail("fixture has no token ending strictly inside its sentence"))
    val coreRef = Addressable[CoreRef]
    val tokenAddress = coreRef.address(CoreRef.SurfaceUnit(token.id))
    val sentenceAddress = coreRef.address(CoreRef.SurfaceUnit(sentence.id))
    val horizon = token.span.endExclusive
    val before = scene(
      NarrativeLevel.Scene,
      state(
        selection = Set(tokenAddress),
        horizon = EpistemicHorizon.ReaderAt(horizon - 1)
      ),
      surface = SurfaceDetail.Tokens
    )
    val tokens = scene(
      NarrativeLevel.Scene,
      state(
        selection = Set(tokenAddress),
        horizon = EpistemicHorizon.ReaderAt(horizon)
      ),
      surface = SurfaceDetail.Tokens
    )
    val sentences = scene(
      NarrativeLevel.Scene,
      state(
        selection = Set(tokenAddress),
        horizon = EpistemicHorizon.ReaderAt(horizon)
      ),
      surface = SurfaceDetail.Sentences
    )

    assertEquals(before.navigation.marksFor(tokenAddress), Vector.empty)
    assertEquals(
      before.selectionPlacements.get(tokenAddress),
      Some(SelectionPlacement.OffProjection)
    )
    assert(tokens.navigation.marksFor(tokenAddress).nonEmpty)
    tokens.selectionPlacements.get(tokenAddress) match
      case Some(SelectionPlacement.OnMark(marks)) =>
        assertEquals(marks.toVector, tokens.navigation.marksFor(tokenAddress))
      case other => fail(s"expected completed token on its mark, found $other")
    assertEquals(sentences.navigation.marksFor(sentenceAddress), Vector.empty)
    assertEquals(
      sentences.selectionPlacements.get(tokenAddress),
      Some(SelectionPlacement.OffProjection)
    )

  test("missing sentence refinement is preflightable and compilation refuses it"):
    val atlas = validatedAtlas(
      model.atlas.units.map {
        case unit if unit.kind == SurfaceUnitKind.Sentence =>
          unit.copy(kind = SurfaceUnitKind.Clause)
        case unit => unit
      }
    )
    val sourceModel = rebuilt(atlas = atlas)
    val support = SurfaceDetailSupport.inspect(atlas)
    assert(!support.availableKinds.contains(SurfaceUnitKind.Sentence))
    assertEquals(support.supportedDetails, Set(SurfaceDetail.Hidden))
    val requested = spec(NarrativeLevel.Scene, surface = SurfaceDetail.Sentences)

    AtlasCompiler(provenanceFor(sourceModel, CommonViewState.empty, requested))
      .compile(sourceModel, CommonViewState.empty, requested) match
      case Left(AtlasCompileError.UnsupportedSurfaceDetail(SurfaceDetail.Sentences, found)) =>
        assertEquals(found, support)
      case other => fail(s"expected typed missing-sentence refusal, found $other")

  test("token detail refuses mixed ancestry even when sentence and token kinds both exist"):
    val token = model.atlas.tokens.headOption.getOrElse(fail("fixture has no token"))
    val paragraph = model.atlas
      .unitAt(token.span.start, SurfaceUnitKind.Paragraph)
      .getOrElse(fail("token has no containing paragraph"))
    val atlas = validatedAtlas(
      model.atlas.units.map {
        case unit if unit.id == token.id => unit.copy(parent = Some(paragraph.id))
        case unit                        => unit
      }
    )
    val sourceModel = rebuilt(atlas = atlas)
    val support = SurfaceDetailSupport.inspect(atlas)
    assert(support.availableKinds.contains(SurfaceUnitKind.Sentence))
    assert(support.availableKinds.contains(SurfaceUnitKind.Token))
    assertEquals(support.unsupportedTokenIds, Vector(token.id))
    assert(!support.supports(SurfaceDetail.Sentences))
    assert(!support.supports(SurfaceDetail.Tokens))
    val requested = spec(NarrativeLevel.Scene, surface = SurfaceDetail.Tokens)

    AtlasCompiler(provenanceFor(sourceModel, CommonViewState.empty, requested))
      .compile(sourceModel, CommonViewState.empty, requested) match
      case Left(AtlasCompileError.UnsupportedSurfaceDetail(SurfaceDetail.Tokens, found)) =>
        assertEquals(found, support)
      case other => fail(s"expected typed mixed-ancestry refusal, found $other")

  test("story level shows the three episode regions and no landmarks; area is undeclared"):
    val s = scene(NarrativeLevel.Story)
    assertEquals(
      regions(s).map(_.address).toSet,
      Set(Wog.G.ep1, Wog.G.ep2, Wog.G.ep3).map(g => ev.address(StoryRef.Segment(g)))
    )
    assertEquals(landmarks(s), Vector.empty)
    assertEquals(s.contract, ProjectionContract.discourseAtlas)
    assertEquals(s.contract.area, None)
    assert(s.contract.invariants.contains(VisualInvariant.HorizonShared))

  test("V-P1: landmarks are ordered by exact discourse offset consistent with discourse order"):
    val s = scene(NarrativeLevel.Scene)
    val xs = model.graph.discourseOrder.map(id =>
      landmarks(s)
        .find(_.address == ev.address(StoryRef.Situation(id)))
        .getOrElse(fail(s"missing ${id.value}"))
        .at
        .x
    )
    assert(xs.zip(xs.tail).forall((a, b) => a <= b), xs.toString)

  test("V-P2: every child region lies inside its parent region, and landmarks inside their scene"):
    val ep = scene(NarrativeLevel.Episode)
    val byAddr = regions(ep).map(r => r.address -> r).toMap
    regions(ep).foreach { r =>
      r.parent.foreach(p =>
        assert(byAddr(p).extent.contains(r.extent), s"${r.address} not inside ${p.render}")
      )
    }
    val sc = scene(NarrativeLevel.Scene)
    val sceneRegions = regions(sc).map(r => r.address -> r).toMap
    landmarks(sc).foreach { l =>
      val sit = ev.parse(l.address).collect { case StoryRef.Situation(id) => id }.get
      val parent = model.hierarchy.primaryParent(NarrativeMember.Situation(sit))
      val region = sceneRegions(ev.address(StoryRef.Segment(parent)))
      assert(region.extent.containsX(l.at.x), s"${l.address} at ${l.at.x} outside ${region.extent}")
    }

  test("V-I1: the same address is used at every level; mark ids differ per level"):
    val a = ev.address(StoryRef.Segment(Wog.G.ep2))
    val s1 = scene(NarrativeLevel.Story)
    val s2 = scene(NarrativeLevel.Episode)
    val m1 = s1.navigation.marksFor(a)
    val m2 = s2.navigation.marksFor(a)
    assert(m1.nonEmpty && m2.nonEmpty)
    assertNotEquals(m1, m2)
    assertEquals(s1.navigation.addressOf(m1.head), a)

  test("V-L2: selection and focus are preserved on-mark, through an ancestor, or off-projection"):
    val battle = ev.address(StoryRef.Situation(Wog.S.battle))
    val focus = Addressable[CoreRef].address(CoreRef.Claim(model.claims.head.id))
    val st = state(selection = Set(battle), focus = Some(focus))
    val story = scene(NarrativeLevel.Story, st)
    assertEquals(story.state, st)
    assertEquals(story.navigation.marksFor(battle), Vector.empty)
    assertEquals(
      story.selectionPlacements.get(battle),
      Some(SelectionPlacement.ViaAncestor(ev.address(StoryRef.Segment(Wog.G.ep2))))
    )
    assertEquals(
      story.selectionPlacements.get(focus),
      Some(SelectionPlacement.OffProjection)
    )
    assertEquals(story.selectionPlacements.keySet, st.selection ++ st.focus)
    assert(story.textualTwin.contains(s"${battle.render} -> via-ancestor"))
    assert(story.textualTwin.contains(s"${focus.render} -> off-projection"))
    val sc = scene(NarrativeLevel.Scene, st)
    assert(sc.navigation.marksFor(battle).nonEmpty)
    sc.selectionPlacements.get(battle) match
      case Some(SelectionPlacement.OnMark(marks)) =>
        assertEquals(marks.toVector, sc.navigation.marksFor(battle))
      case other => fail(s"expected on-mark placement, found $other")
    assertEquals(sc.selectionPlacements.get(focus), Some(SelectionPlacement.OffProjection))

  test("a horizon-hidden selected object stays off-projection instead of revealing its ancestor"):
    val hiddenMeta = Wog.meta(
      "atlas-test:hidden-selected-situation",
      EpistemicStatus.Hypothesized,
      None
    )
    val hidden = model.graph.situations(Wog.S.huntSeals) match
      case SituationNode.Event(node) => SituationNode.Event(node.copy(meta = hiddenMeta))
      case SituationNode.State(node) => SituationNode.State(node.copy(meta = hiddenMeta))
    val graph = model.graph.copy(
      situations = model.graph.situations.updated(Wog.S.huntSeals, hidden)
    )
    val sourceModel = rebuilt(graph = graph, hierarchy = groundedHierarchy())
    val selected = ev.address(StoryRef.Situation(Wog.S.huntSeals))
    val st = state(
      selection = Set(selected),
      horizon = EpistemicHorizon.ReaderAt(sourceModel.source.canonicalText.length)
    )
    val s = scene(NarrativeLevel.Story, st, sourceModel = sourceModel)

    assert(s.navigation.marksFor(ev.address(StoryRef.Segment(Wog.G.ep1))).nonEmpty)
    assertEquals(s.selectionPlacements.get(selected), Some(SelectionPlacement.OffProjection))

  test("V-E3: every mark resolves to model evidence; threads connect participations only"):
    val s = scene(
      NarrativeLevel.Scene,
      threads = ThreadPolicy.All(PositiveInt.unsafe(5)),
      surface = SurfaceDetail.Tokens
    )
    s.marks.foreach {
      case unit: VisualPrimitive.SurfaceUnit =>
        val id = Addressable[CoreRef].parse(unit.address) match
          case Some(CoreRef.SurfaceUnit(id)) => id
          case other                         => fail(s"not a surface-unit address: $other")
        val exact = model.atlas.byId.getOrElse(id, fail(s"unknown surface unit ${id.value}"))
        assertEquals(unit.span, exact.span)
        assertEquals(unit.kind, exact.kind)
        assertEquals(unit.unitOrdinal, exact.ordinal)
        assertEquals(
          unit.parent,
          exact.parent.map(parent => Addressable[CoreRef].address(CoreRef.SurfaceUnit(parent)))
        )
      case mark =>
        assert(ev.parse(mark.address).flatMap(model.supporting).isDefined, mark.address.render)
    }
    val threads = s.marks.collect { case t: VisualPrimitive.Thread => t }
    assert(threads.nonEmpty)
    threads.foreach { t =>
      val e = ev.parse(t.address).collect { case StoryRef.Entity(id) => id }.get
      val expected = model.graph
        .situationsByEntity(e)
        .map(id => model.graph.situations(id).support.minSpan.start)
      assertEquals(t.points.map(_.x), expected)
    }

  test("routes and portals appear only for active relation layers, with epistemic status"):
    val none = scene(NarrativeLevel.Scene)
    assertEquals(none.marks.collect { case r: VisualPrimitive.Route => r }, Vector.empty)
    assertEquals(none.marks.collect { case p: VisualPrimitive.Portal => p }, Vector.empty)
    val causal = scene(NarrativeLevel.Scene, state(layers = Set(RelationLayer.Causal))).marks
      .collect { case r: VisualPrimitive.Route => r }
    assertEquals(causal.size, model.graph.relations.causal.size)
    assert(causal.forall(_.layer == RelationLayer.Causal))
    assertEquals(causal.map(_.status).toSet, model.graph.relations.causal.map(_.meta.status).toSet)
    val portals = scene(NarrativeLevel.Scene, state(layers = Set(RelationLayer.Reference))).marks
      .collect { case p: VisualPrimitive.Portal => p }
    val nonAdjacent = model.graph.relations.references.count(r =>
      math.abs(model.graph.discoursePosition(r.from) - model.graph.discoursePosition(r.to)) > 1
    )
    assertEquals(portals.size, nonAdjacent)

  test("reader horizon uses the shared evidence closure and clips regions to visible children"):
    val sourceModel = rebuilt(hierarchy = groundedHierarchy())
    val mid = sourceModel.graph.situations(Wog.S.battle).support.minSpan.start
    val st = state(horizon = EpistemicHorizon.ReaderAt(mid))
    val s = scene(NarrativeLevel.Scene, st, sourceModel = sourceModel)
    val ledger = sourceModel.ledger.toOption.get
    val visible = EvidenceVisibility.visibleClaims(mid, ledger)
    val expected = sourceModel.graph.discourseOrder.filter { id =>
      val n = sourceModel.graph.situations(id)
      visible.contains(n.meta.id) &&
      EvidenceVisibility.clipSupport(n.support, st.horizon).isDefined
    }
    assertEquals(
      landmarks(s)
        .map(l => ev.parse(l.address).collect { case StoryRef.Situation(id) => id }.get)
        .toSet,
      expected.toSet
    )
    assert(landmarks(s).nonEmpty)
    assert(landmarks(s).forall(_.at.x < mid))
    assert(regions(s).nonEmpty)
    assert(regions(s).forall(_.extent.x1Exclusive <= mid))
    assert(
      landmarks(s).size < landmarks(scene(NarrativeLevel.Scene, sourceModel = sourceModel)).size
    )

    val visibleContexts = sourceModel.graph.contexts.valuesIterator
      .filter(context => visible.contains(context.meta.id))
      .map(_.id)
      .toSet
    val root = sourceModel.graph.rootContext.filter(visibleContexts.contains).toVector
    val contextLanes =
      (root ++ visibleContexts.toVector.filterNot(root.contains).sorted).zipWithIndex.toMap
    landmarks(s).foreach { landmark =>
      val id = ev.parse(landmark.address).collect { case StoryRef.Situation(value) => value }.get
      val context = sourceModel.graph.situations(id).context
      assertEquals(landmark.at.lane, contextLanes.getOrElse(context, 0))
    }

  test("reader regions exclude a segment whose summary claim is still beyond the horizon"):
    val hierarchy = groundedHierarchy()
    val sourceModel = rebuilt(hierarchy = hierarchy)
    val offset = Wog.sent(0).span.endExclusive
    val st = state(horizon = EpistemicHorizon.ReaderAt(offset))
    val s = scene(NarrativeLevel.Scene, st, sourceModel = sourceModel)
    val ledger = sourceModel.ledger.toOption.get
    val visible = EvidenceVisibility.visibleClaims(offset, ledger)
    val target = sourceModel.graph.segments(Wog.G.sc1a)
    val firstSituation = sourceModel.graph.situations(Wog.S.peopleAtEgulac)
    val firstContainment = hierarchy.primary
      .find(
        _.member == NarrativeMember.Situation(Wog.S.peopleAtEgulac)
      )
      .getOrElse(fail("missing first-situation containment"))

    assert(visible.contains(firstSituation.meta.id))
    assert(visible.contains(firstContainment.meta.id))
    assert(!visible.contains(target.summary.meta.id))
    assert(landmarks(s).exists(_.address == ev.address(StoryRef.Situation(firstSituation.id))))
    assert(!regions(s).exists(_.address == ev.address(StoryRef.Segment(target.id))))

  test("reader region hulls exclude containment claims without horizon-visible evidence"):
    val offset = model.source.canonicalText.length
    val st = state(horizon = EpistemicHorizon.ReaderAt(offset))
    val s = scene(NarrativeLevel.Scene, st)
    val visible = EvidenceVisibility.visibleClaims(offset, model.ledger.toOption.get)

    assert(visible.contains(model.graph.segments(Wog.G.sc1a).summary.meta.id))
    assert(model.hierarchy.primary.forall(edge => !visible.contains(edge.meta.id)))
    assert(landmarks(s).nonEmpty)
    assertEquals(regions(s), Vector.empty)

  test("reader entity threads exclude participations whose relation claim is still future"):
    val hidden = model.graph.relations.participants
      .find(edge => edge.situation == Wog.S.huntSeals && edge.entity == Wog.E.youngMen)
      .getOrElse(fail("missing young-men hunt participation"))
    val lateMeta = Wog.meta(
      "atlas-test:participant-late",
      EpistemicStatus.SurfaceExplicit,
      Some(Wog.sp(49))
    )
    val participants = model.graph.relations.participants.map { edge =>
      if edge == hidden then edge.copy(meta = lateMeta) else edge
    }
    val graph = model.graph.copy(
      relations = model.graph.relations.copy(participants = participants)
    )
    val sourceModel = rebuilt(graph = graph)
    val offset = Wog.sent(6).span.endExclusive
    val entityAddress = ev.address(StoryRef.Entity(Wog.E.youngMen))
    val st = state(
      selection = Set(entityAddress),
      horizon = EpistemicHorizon.ReaderAt(offset)
    )
    val s = scene(
      NarrativeLevel.Scene,
      st,
      threads = ThreadPolicy.Selected,
      sourceModel = sourceModel
    )
    val visible = EvidenceVisibility.visibleClaims(offset, sourceModel.ledger.toOption.get)
    val thread = s.marks
      .collectFirst {
        case value: VisualPrimitive.Thread if value.address == entityAddress => value
      }
      .getOrElse(fail("missing selected young-men thread"))
    val hiddenX = sourceModel.graph.situations(Wog.S.huntSeals).support.minSpan.start

    assert(visible.contains(sourceModel.graph.entities(Wog.E.youngMen).meta.id))
    assert(visible.contains(sourceModel.graph.situations(Wog.S.huntSeals).meta.id))
    assert(!visible.contains(lateMeta.id))
    assert(thread.points.size >= 2)
    assert(!thread.points.map(_.x).contains(hiddenX))

  test("Atlas rejects reader horizons outside the canonical source"):
    val st = state(horizon = EpistemicHorizon.ReaderAt(-1))
    val s = spec(NarrativeLevel.Story)
    val result = AtlasCompiler(provenanceFor(model, st, s)).compile(model, st, s)
    assert(result.isLeft)

  test("V-D1/V-D2: compilation and the textual twin are deterministic and carry receipts"):
    val a = scene(NarrativeLevel.Scene)
    val b = scene(NarrativeLevel.Scene)
    assertEquals(a, b)
    assertEquals(a.textualTwin, b.textualTwin)
    assert(a.textualTwin.contains("X: exact discourse offset"))
    assert(a.textualTwin.contains("researcher-reviewed narrative acceptance fixture"))
    assert(a.textualTwin.contains(s"Source checksum: ${model.source.canonicalChecksum.hex}"))
    assert(a.textualTwin.contains(s"Configuration: ${a.provenance.configChecksum.hex}"))
    assert(a.textualTwin.contains("Model receipt checksum: not available"))

  test("provenance must match source, configuration, and model receipt basis"):
    val s = spec(NarrativeLevel.Story)
    val wrongSource = ViewProvenance
      .fixture(
        Checksum.ofText("other"),
        "atlas-suite",
        AtlasCompiler.configurationChecksum(CommonViewState.empty, s)
      )
      .toOption
      .get
    assert(AtlasCompiler(wrongSource).compile(model, CommonViewState.empty, s).isLeft)
    val wrongConfig = ViewProvenance
      .fixture(model.source.canonicalChecksum, "atlas-suite", Checksum.ofText("stale"))
      .toOption
      .get
    assert(AtlasCompiler(wrongConfig).compile(model, CommonViewState.empty, s).isLeft)
    val claimsBuild = ViewProvenance
      .of(
        model.source.canonicalChecksum,
        Some(Checksum.ofText("receipt")),
        ViewBasis.ValidatedBuild,
        "atlas-suite",
        AtlasCompiler.configurationChecksum(CommonViewState.empty, s)
      )
      .toOption
      .get
    // The fixture has no build receipt: a "validated build" claim over it must be rejected.
    assert(AtlasCompiler(claimsBuild).compile(model, CommonViewState.empty, s).isLeft)
