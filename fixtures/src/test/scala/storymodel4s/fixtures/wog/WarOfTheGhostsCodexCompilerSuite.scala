package storymodel4s.fixtures.wog

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import storymodel4s.core.*
import storymodel4s.features.{Dtype, FeatureAddress, Layout}
import storymodel4s.story.*
import storymodel4s.view.*

class WarOfTheGhostsCodexCompilerSuite extends ScalaCheckSuite:
  private val model = WarOfTheGhostsModel.model
  private val featureSpaceId = FeatureSpaceId.unsafe("wog:feature:scale-selector")
  private val featureSpace = FeatureSpace[Double](
    featureSpaceId,
    "scale-selector fixture",
    FeatureValueSchema.Scalar(None),
    None,
    Fingerprint.unsafe("fixture:scale-selector:1"),
    normalized = false
  )
  private val clauseUnit = SurfaceUnit(
    SurfaceUnitId.unsafe("wog:scale-selector:clause:0"),
    SurfaceUnitKind.Clause,
    model.atlas.sentences.head.span,
    ordinal = 0,
    parent = Some(model.atlas.sentences.head.id)
  )
  private val scaleAtlas = SurfaceAtlas
    .of(model.atlas.source, model.atlas.units :+ clauseUnit)
    .fold(error => fail(error.message), identity)
  private val tokenTarget: FeatureTarget = FeatureTarget.Token(TokenIndex.Zero)
  private val sentenceTarget: FeatureTarget = FeatureTarget.Sentence(model.atlas.sentences.head.id)
  private val clauseTarget: FeatureTarget = FeatureTarget.SurfaceUnit(clauseUnit.id)
  private val paragraphTarget: FeatureTarget =
    FeatureTarget.SurfaceUnit(model.atlas.paragraphs.head.id)
  private val situationTarget: FeatureTarget =
    FeatureTarget.Situation(WarOfTheGhostsModel.S.battle)
  private val sceneTarget: FeatureTarget = FeatureTarget.Segment(WarOfTheGhostsModel.G.sc2c)
  private val episodeTarget: FeatureTarget = FeatureTarget.Segment(WarOfTheGhostsModel.G.ep2)
  private val storyTarget: FeatureTarget = FeatureTarget.Segment(WarOfTheGhostsModel.G.story)
  private val featureRefs = Vector(
    tokenTarget,
    sentenceTarget,
    clauseTarget,
    paragraphTarget,
    situationTarget,
    sceneTarget,
    episodeTarget,
    storyTarget
  ).zipWithIndex.map((target, row) => FeatureRef.unsafe(target, featureSpaceId, row))

  private val scalesAndTargets = Vector(
    FeatureScale.SurfaceUnit(SurfaceUnitKind.Token) -> tokenTarget,
    FeatureScale.SurfaceUnit(SurfaceUnitKind.Sentence) -> sentenceTarget,
    FeatureScale.SurfaceUnit(SurfaceUnitKind.Clause) -> clauseTarget,
    FeatureScale.SurfaceUnit(SurfaceUnitKind.Paragraph) -> paragraphTarget,
    FeatureScale.NarrativeUnit(NarrativeUnitBasis.Situations) -> situationTarget,
    FeatureScale.NarrativeUnit(NarrativeUnitBasis.Segments(SegmentKind.Scene)) -> sceneTarget,
    FeatureScale.NarrativeUnit(NarrativeUnitBasis.Segments(SegmentKind.Episode)) -> episodeTarget,
    FeatureScale.NarrativeUnit(NarrativeUnitBasis.Segments(SegmentKind.Story)) -> storyTarget
  )

  private def modelWithFeatureRefs(
      refs: Vector[FeatureRef],
      spaceId: FeatureSpaceId = featureSpaceId,
      atlas: SurfaceAtlas = scaleAtlas
  ): TextModel[ModelStatus.Validated] =
    val manifest = SidecarManifest.unsafe(
      spaceId,
      dimension = 1,
      rowCount = refs.size,
      dtype = Dtype.Float64,
      checksum = Checksum.ofText("wog-scale-selector-sidecar:" + spaceId.value),
      layout = Layout.RowMajor
    )
    val draft = StoryModel
      .draftText(
        atlas,
        model.graph,
        model.hierarchy,
        model.trajectory,
        model.featureSpaces.updated(spaceId, featureSpace.copy(id = spaceId)),
        model.sidecars.updated(spaceId, manifest),
        refs,
        model.descriptors,
        model.hypotheses,
        model.sensoryProfiles,
        model.receipt,
        model.schemaVersion
      )
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    val outcome = StoryValidator.validate(draft)
    outcome.validated.getOrElse(fail(outcome.report.render))

  private val featureModel = modelWithFeatureRefs(featureRefs)

  private def featureSupport(target: FeatureTarget): SpanSet = target match
    case FeatureTarget.Token(index) =>
      SpanSet.one(featureModel.atlas.tokens(index.value).span)
    case FeatureTarget.Sentence(id) =>
      SpanSet.one(featureModel.atlas.byId(id).span)
    case FeatureTarget.SurfaceUnit(id) =>
      SpanSet.one(featureModel.atlas.byId(id).span)
    case FeatureTarget.Situation(id) => featureModel.graph.situations(id).support.textSpans.get
    case FeatureTarget.Segment(id)   => featureModel.graph.segments(id).support.textSpans.get
    case other                       => fail(s"unexpected target $other")

  private val relationLayers = Set(
    RelationLayer.Participant,
    RelationLayer.WorldTime,
    RelationLayer.Causal,
    RelationLayer.Goal,
    RelationLayer.StateChange,
    RelationLayer.Reference,
    RelationLayer.EntityContinuity
  )

  private def compile(
      sourceModel: TextModel[ModelStatus.Validated],
      state: CommonViewState,
      spec: CodexSpec
  ): CodexFlow =
    val config = CodexCompiler.configurationChecksum(state, spec)
    val provenance = ViewProvenance
      .fixture(sourceModel.source.canonicalChecksum, "wog-codex-compiler-test", config)
      .fold(error => fail(error.message), identity)
    CodexCompiler(provenance)
      .compile(sourceModel, state, spec)
      .fold(error => fail(error.message), identity)

  private def compile(state: CommonViewState, spec: CodexSpec): CodexFlow =
    compile(model, state, spec)

  private def compileAtlas(
      sourceModel: TextModel[ModelStatus.Validated],
      state: CommonViewState,
      scale: FeatureScale,
      level: NarrativeLevel = NarrativeLevel.Scene
  ): NarrativeScene =
    val spec = AtlasSpec(
      ZoomLevel(level, SurfaceDetail.Hidden),
      ThreadPolicy.Selected,
      scale
    )
    val provenance = ViewProvenance
      .fixture(
        sourceModel.source.canonicalChecksum,
        "wog-atlas-feature-test",
        AtlasCompiler.configurationChecksum(state, spec)
      )
      .fold(error => fail(error.message), identity)
    AtlasCompiler(provenance)
      .compile(sourceModel, state, spec)
      .fold(error => fail(error.message), identity)

  private def rebuilt(
      graph: NarrativeGraph,
      hierarchy: NarrativeHierarchy
  ): TextModel[ModelStatus.Validated] =
    val draft = StoryModel
      .draftText(
        model.atlas,
        graph,
        hierarchy,
        DiscourseTrajectory
          .derive(graph, hierarchy, model.atlas)
          .fold(error => throw new IllegalArgumentException(error.message), identity),
        model.featureSpaces,
        model.sidecars,
        model.featureRefs,
        model.descriptors,
        model.hypotheses,
        model.sensoryProfiles,
        model.receipt,
        model.schemaVersion
      )
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    val outcome = StoryValidator.validate(draft)
    outcome.validated.getOrElse(fail(outcome.report.render))

  private def groundedHierarchy(): NarrativeHierarchy =
    val containment = model.hierarchy.containment.zipWithIndex.map { (edge, index) =>
      edge.copy(
        meta = WarOfTheGhostsModel.meta(
          s"selection:grounded-containment:$index",
          EpistemicStatus.HumanAdjudicated,
          Some(WarOfTheGhostsModel.sp(0))
        )
      )
    }
    NarrativeHierarchy(containment, model.hierarchy.boundaryBeliefs)

  private enum NormalizedPlacement:
    case OnMark
    case ViaAncestor(ancestor: Address)
    case OffProjection

  private def normalize[Mark](placement: SelectionPlacement[Mark]): NormalizedPlacement =
    placement match
      case SelectionPlacement.OnMark(_)             => NormalizedPlacement.OnMark
      case SelectionPlacement.ViaAncestor(ancestor) =>
        NormalizedPlacement.ViaAncestor(ancestor)
      case SelectionPlacement.OffProjection => NormalizedPlacement.OffProjection

  private def featureSpec(scale: FeatureScale): CodexSpec =
    CodexSpec
      .of(
        Vector(AnnotationChannel(AnnotationKind.Feature, AnnotationPriority.unsafe(700))),
        scale = scale
      )
      .fold(error => fail(error.message), identity)

  private def codexPlacements(flow: CodexFlow): Vector[(Address, SpanSet)] =
    flow.annotations
      .filter(_.kind == AnnotationKind.Feature)
      .map(annotation => annotation.target -> annotation.support)
      .sortBy(_._1.render)

  private def atlasPlacements(scene: NarrativeScene): Vector[(Address, SpanSet)] =
    scene.featureLayer.observations
      .map(observation => observation.address -> observation.support)
      .sortBy(_._1.render)

  test("the WOG compiler emits only evidence-backed model objects and deterministic lanes"):
    val state = CommonViewState
      .of(relationLayers = relationLayers)
      .fold(error => fail(error.message), identity)
    val channels = CodexLens.Overview.channels :+
      AnnotationChannel(AnnotationKind.Hierarchy, AnnotationPriority.unsafe(900))
    val lanePolicy = LanePolicy.of(2).fold(error => fail(error.message), identity)
    val spec = CodexSpec
      .of(channels, ChannelBudget.All, lanePolicy)
      .fold(error => fail(error.message), identity)
    val first = compile(state, spec)
    val second = compile(state, spec)

    assertEquals(first, second)
    assert(first.annotations.nonEmpty)
    assertEquals(first.lanes.placements.size, first.annotations.size)
    assertEquals(first.lanes.receipt.algorithm, LaneAlgorithm.IntervalFirstFit)
    assertEquals(
      first.runs.map(_.span),
      Vector(TextSpan.unsafe(0, model.source.canonicalText.length))
    )
    assert(
      first.annotations
        .filter(_.kind == AnnotationKind.Hierarchy)
        .forall(
          _.priority == AnnotationPriority.unsafe(900)
        )
    )

    first.annotations.foreach { annotation =>
      val storyRef = ViewRef.parse(annotation.target) match
        case Some(ViewRef.Story(ref)) => ref
        case other                    => fail(s"expected a StoryRef target, found $other")
      assertEquals(model.supporting(storyRef), Some(annotation.support))
      annotation.support.refs.toVector.foreach { support =>
        assert(model.covering(support.span).contains(storyRef))
      }
      val coreRefs = annotation.audit.upstream.flatMap(Addressable[CoreRef].parse)
      assert(coreRefs.exists {
        case CoreRef.Claim(_) => true
        case _                => false
      })
      assert(coreRefs.exists {
        case CoreRef.Evidence(_) => true
        case _                   => false
      })
    }

  test("V-L2: Codex selections resolve on-mark, through a visible ancestor, or off-projection"):
    val storyAddress = Addressable[StoryRef]
    val battle = storyAddress.address(StoryRef.Situation(WarOfTheGhostsModel.S.battle))
    val scene = storyAddress.address(StoryRef.Segment(WarOfTheGhostsModel.G.sc2c))
    val offProjection =
      Addressable[CoreRef].address(CoreRef.Claim(ClaimId.unsafe("wog:selection:off-projection")))
    val state = CommonViewState
      .of(selection = Set(battle), focus = Some(offProjection))
      .fold(error => fail(error.message), identity)
    val hierarchyOnly = CodexSpec
      .of(Vector(AnnotationChannel(AnnotationKind.Hierarchy, AnnotationPriority.Default)))
      .fold(error => fail(error.message), identity)
    val ancestorFlow = compile(state, hierarchyOnly)
    val sceneAnnotations = ancestorFlow.navigation.annotationsFor(scene)

    assert(sceneAnnotations.nonEmpty)
    assertEquals(sceneAnnotations.flatMap(ancestorFlow.navigation.targetOf).distinct, Vector(scene))
    assertEquals(
      ancestorFlow.navigation.annotationsFor(battle),
      sceneAnnotations
    )
    assertEquals(
      ancestorFlow.selectionPlacements.get(battle),
      Some(SelectionPlacement.ViaAncestor(scene))
    )
    assertEquals(
      ancestorFlow.selectionPlacements.get(offProjection),
      Some(SelectionPlacement.OffProjection)
    )
    assertEquals(ancestorFlow.selectionPlacements.keySet, state.selection ++ state.focus)
    assert(ancestorFlow.textualTwin.contains(s"${battle.render} -> via-ancestor(${scene.render})"))
    assert(ancestorFlow.textualTwin.contains(s"${offProjection.render} -> off-projection"))

    val claims = CodexSpec
      .of(Vector(AnnotationChannel(AnnotationKind.Claim, AnnotationPriority.Default)))
      .fold(error => fail(error.message), identity)
    val exactFlow = compile(state, claims)
    exactFlow.selectionPlacements.get(battle) match
      case Some(SelectionPlacement.OnMark(annotations)) =>
        assertEquals(annotations.toVector, exactFlow.navigation.annotationsFor(battle))
      case other => fail(s"expected on-mark placement, found $other")

    val hiddenState = CommonViewState
      .of(
        selection = Set(battle),
        horizon = EpistemicHorizon.ReaderAt(
          model.graph.situations(WarOfTheGhostsModel.S.battle).support.textSpans.get.minSpan.start
        )
      )
      .fold(error => fail(error.message), identity)
    val hiddenFlow = compile(hiddenState, hierarchyOnly)
    assertEquals(
      hiddenFlow.selectionPlacements.get(battle),
      Some(SelectionPlacement.OffProjection)
    )
    assertEquals(hiddenFlow.navigation.annotationsFor(battle), Vector.empty)

  property("V-L2: Codex and Atlas placements agree for generated selections and horizons"):
    val addresses = model.graph.situations.keys.toVector.sorted.map(id =>
      Addressable[StoryRef].address(StoryRef.Situation(id))
    )
    val selectionGen = Gen.nonEmptyListOf(Gen.oneOf(addresses)).map(_.toSet)
    val horizonGen = Gen.frequency(
      1 -> Gen.const(EpistemicHorizon.Omniscient),
      4 -> Gen
        .chooseNum(0, model.source.canonicalText.length)
        .map(EpistemicHorizon.ReaderAt.apply)
    )
    val hierarchyOnly = CodexSpec
      .of(Vector(AnnotationChannel(AnnotationKind.Hierarchy, AnnotationPriority.Default)))
      .fold(error => fail(error.message), identity)

    forAll(selectionGen, horizonGen) { (selection, horizon) =>
      val state = CommonViewState
        .of(selection = selection, horizon = horizon)
        .fold(error => fail(error.message), identity)
      val codex = compile(state, hierarchyOnly)
      val atlas = compileAtlas(
        model,
        state,
        FeatureScale.Default,
        NarrativeLevel.Episode
      )

      selection.foreach { address =>
        assertEquals(
          codex.selectionPlacements.get(address).map(normalize),
          atlas.selectionPlacements.get(address).map(normalize),
          clues(address.render, horizon)
        )
      }
    }

  test("V-L2: a hidden parent cannot reveal a visible marked grandparent"):
    val selected = Addressable[StoryRef]
      .address(StoryRef.Situation(WarOfTheGhostsModel.S.battle))
    val hiddenParentId = WarOfTheGhostsModel.G.sc2c
    val markedGrandparentId = WarOfTheGhostsModel.G.ep2
    val hiddenParent = model.graph.segments(hiddenParentId)
    val hiddenMeta = WarOfTheGhostsModel.meta(
      "selection:hidden-intermediate",
      EpistemicStatus.Hypothesized,
      None
    )
    val graph = model.graph.copy(
      segments = model.graph.segments.updated(
        hiddenParentId,
        hiddenParent.copy(meta = hiddenMeta)
      )
    )
    val sourceModel = rebuilt(graph, groundedHierarchy())
    val state = CommonViewState
      .of(
        selection = Set(selected),
        horizon = EpistemicHorizon.ReaderAt(sourceModel.source.canonicalText.length)
      )
      .fold(error => fail(error.message), identity)
    val hierarchyOnly = CodexSpec
      .of(Vector(AnnotationChannel(AnnotationKind.Hierarchy, AnnotationPriority.Default)))
      .fold(error => fail(error.message), identity)
    val codex = compile(sourceModel, state, hierarchyOnly)
    val atlas = compileAtlas(
      sourceModel,
      state,
      FeatureScale.Default,
      NarrativeLevel.Story
    )
    val hiddenParentAddress =
      Addressable[StoryRef].address(StoryRef.Segment(hiddenParentId))
    val markedGrandparentAddress =
      Addressable[StoryRef].address(StoryRef.Segment(markedGrandparentId))

    assertEquals(codex.navigation.exactAnnotationsFor(hiddenParentAddress), Vector.empty)
    assert(codex.navigation.exactAnnotationsFor(markedGrandparentAddress).nonEmpty)
    assertEquals(atlas.navigation.marksFor(hiddenParentAddress), Vector.empty)
    assert(atlas.navigation.marksFor(markedGrandparentAddress).nonEmpty)
    assertEquals(
      codex.selectionPlacements.get(selected),
      Some(SelectionPlacement.OffProjection)
    )
    assertEquals(
      atlas.selectionPlacements.get(selected),
      Some(SelectionPlacement.OffProjection)
    )

  test("a missing feature request remains explicit missingness rather than a zero-valued mark"):
    val derivation = Checksum.ofText("feature:missing-from-wog")
    val missing = FeatureSelection.Derived(derivation)
    val resolvedSpace = FeatureSpaceId.unsafe("derived:" + derivation.short(32))
    val state = CommonViewState
      .of(feature = Some(missing))
      .fold(error => fail(error.message), identity)
    val spec = CodexSpec
      .forLens(CodexLens.Structure)
      .fold(error => fail(error.message), identity)
    val flow = compile(state, spec)
    val atlas = compileAtlas(model, state, FeatureScale.Default)

    assertEquals(flow.contract.feature, FeatureChannelState.Missing(missing, resolvedSpace))
    assertEquals(atlas.featureLayer.state, flow.contract.feature)
    assertEquals(atlas.featureLayer.observations, Vector.empty)
    assert(!flow.annotations.exists(_.kind == AnnotationKind.Feature))

  test("a basis-aware selection resolves its exact space and reports absent data"):
    val derivation = Checksum.ofText("feature:derived")
    val basis = Checksum.ofText("feature:ordered-basis")
    val selection = FeatureSelection.Derived(derivation, Some(basis))
    val state = CommonViewState
      .of(feature = Some(selection))
      .fold(error => fail(error.message), identity)
    val spec = CodexSpec
      .of(
        Vector(AnnotationChannel(AnnotationKind.Feature, AnnotationPriority.unsafe(700))),
        scale = FeatureScale.NarrativeUnit(NarrativeUnitBasis.Situations)
      )
      .fold(error => fail(error.message), identity)
    val flow = compile(state, spec)
    val atlas = compileAtlas(
      model,
      state,
      FeatureScale.NarrativeUnit(NarrativeUnitBasis.Situations)
    )

    assertEquals(
      flow.contract.feature,
      FeatureChannelState.Missing(
        selection,
        storymodel4s.features.FeatureDerivation
          .outputSpaceId(derivation, Some(storymodel4s.features.BasisId.fromChecksum(basis)))
      )
    )
    assertEquals(atlas.featureLayer.state, flow.contract.feature)
    assertEquals(atlas.featureLayer.observations, Vector.empty)
    assert(!flow.annotations.exists(_.kind == AnnotationKind.Feature))
    assert(flow.textualTwin.contains("Resolved feature space: derived:"))
    assert(!flow.textualTwin.contains("basis-identity-unavailable:"))

  test("the scale selects exact feature targets and places them through ordinary lanes"):
    val selection = FeatureSelection.Raw(featureSpaceId)
    val state = CommonViewState
      .of(feature = Some(selection))
      .fold(error => fail(error.message), identity)
    scalesAndTargets.foreach { (scale, expectedTarget) =>
      val spec = CodexSpec
        .of(
          Vector(AnnotationChannel(AnnotationKind.Feature, AnnotationPriority.unsafe(700))),
          ChannelBudget.All,
          LanePolicy.Default,
          scale
        )
        .fold(error => fail(error.message), identity)
      val flow = compile(featureModel, state, spec)
      val annotations = flow.annotations.filter(_.kind == AnnotationKind.Feature)

      assertEquals(
        flow.contract.feature,
        FeatureChannelState.SidecarRequired(selection, featureSpaceId, 1)
      )
      assertEquals(annotations.size, 1, scale.canonicalString)
      assertEquals(
        Addressable[FeatureAddress].parse(annotations.head.target),
        Some(FeatureAddress.Observation(featureSpaceId, expectedTarget))
      )
      assertEquals(annotations.head.support, featureSupport(expectedTarget))
      assert(flow.lanes.slotOf(annotations.head.id).isDefined)
      assert(
        annotations.head.audit.upstream.contains(
          Addressable[FeatureAddress].address(FeatureAddress.Space(featureSpaceId))
        )
      )
      assert(flow.textualTwin.contains("Scale: " + scale.label))
      assert(flow.textualTwin.contains("Resolved feature space: " + featureSpaceId.value))
    }

  test("Codex and Atlas plan identical observations at every scale and horizon"):
    val selection = FeatureSelection.Raw(featureSpaceId)
    scalesAndTargets.foreach { (scale, expectedTarget) =>
      val support = featureSupport(expectedTarget)
      val horizons = Vector(
        EpistemicHorizon.Omniscient,
        EpistemicHorizon.ReaderAt(support.spans.head.endExclusive)
      )
      horizons.foreach { horizon =>
        val state = CommonViewState
          .of(horizon = horizon, feature = Some(selection))
          .fold(error => fail(error.message), identity)
        val codex = compile(featureModel, state, featureSpec(scale))
        val atlas = compileAtlas(featureModel, state, scale)
        val expected = EvidenceVisibility
          .clipSupport(support, horizon)
          .toVector
          .map(visible =>
            Addressable[FeatureAddress]
              .address(FeatureAddress.Observation(featureSpaceId, expectedTarget)) -> visible
          )

        assertEquals(atlas.featureLayer.scale, scale)
        assertEquals(atlas.featureLayer.state, codex.contract.feature)
        assertEquals(codexPlacements(codex), expected, scale.canonicalString)
        assertEquals(atlasPlacements(atlas), expected, scale.canonicalString)
      }
    }

  test("Atlas feature extents preserve discontinuous situation support without a hull"):
    val selection = FeatureSelection.Raw(featureSpaceId)
    val state = CommonViewState
      .of(feature = Some(selection))
      .fold(error => fail(error.message), identity)
    val scale = FeatureScale.NarrativeUnit(NarrativeUnitBasis.Situations)
    val scene = compileAtlas(featureModel, state, scale)
    val placement = scene.featureLayer.observations
      .find(_.target == situationTarget)
      .getOrElse(fail("missing battle feature placement"))
    val support = featureSupport(situationTarget)

    assert(support.size > 1)
    assert(!support.isContiguous)
    assertEquals(placement.support, support)
    assertEquals(placement.xExtents, support.spans)
    assertNotEquals(placement.xExtents.toVector, Vector(support.minSpan))
    assert(!scene.marks.exists(_.address == placement.address))
    assert(scene.textualTwin.contains(placement.address.render))

  test("a legacy derived selection resolves its output space and audits the derivation"):
    val derivation = Checksum.ofText("wog:derived:scale-selector")
    val derivedSpace = FeatureSpaceId.unsafe("derived:" + derivation.short(32))
    val refs = featureRefs.map(r => FeatureRef.unsafe(r.target, derivedSpace, r.row))
    val derivedModel = modelWithFeatureRefs(refs, derivedSpace)
    val selection = FeatureSelection.Derived(derivation)
    val state = CommonViewState
      .of(feature = Some(selection))
      .fold(error => fail(error.message), identity)
    val spec = CodexSpec
      .of(
        Vector(AnnotationChannel(AnnotationKind.Feature, AnnotationPriority.unsafe(700))),
        scale = FeatureScale.SurfaceUnit(SurfaceUnitKind.Paragraph)
      )
      .fold(error => fail(error.message), identity)
    val flow = compile(derivedModel, state, spec)
    val atlas = compileAtlas(
      derivedModel,
      state,
      FeatureScale.SurfaceUnit(SurfaceUnitKind.Paragraph)
    )
    val annotation =
      flow.annotations.find(_.kind == AnnotationKind.Feature).getOrElse(fail("feature"))
    val placement = atlas.featureLayer.observations.headOption.getOrElse(fail("atlas feature"))

    assertEquals(
      flow.contract.feature,
      FeatureChannelState.SidecarRequired(selection, derivedSpace, 1)
    )
    assertEquals(
      annotation.audit.upstream,
      Vector(
        Addressable[FeatureAddress].address(FeatureAddress.Space(derivedSpace)),
        Addressable[FeatureAddress].address(FeatureAddress.Derivation(derivation))
      ).sorted
    )
    assertEquals(atlas.featureLayer.state, flow.contract.feature)
    assertEquals(placement.address, annotation.target)
    assertEquals(placement.support, annotation.support)
    assertEquals(placement.audit.upstream, annotation.audit.upstream)

  test("surface target cases are not coerced across sentence, clause, and paragraph scales"):
    val state = CommonViewState
      .of(feature = Some(FeatureSelection.Raw(featureSpaceId)))
      .fold(error => fail(error.message), identity)
    val paragraphOnly = modelWithFeatureRefs(
      Vector(FeatureRef.unsafe(paragraphTarget, featureSpaceId, row = 0))
    )
    Vector(SurfaceUnitKind.Clause, SurfaceUnitKind.Sentence).foreach { kind =>
      val spec = CodexSpec
        .of(
          Vector(AnnotationChannel(AnnotationKind.Feature, AnnotationPriority.unsafe(700))),
          scale = FeatureScale.SurfaceUnit(kind)
        )
        .fold(error => fail(error.message), identity)
      val flow = compile(paragraphOnly, state, spec)
      assertEquals(
        flow.contract.feature,
        FeatureChannelState.Missing(FeatureSelection.Raw(featureSpaceId), featureSpaceId)
      )
      assert(!flow.annotations.exists(_.kind == AnnotationKind.Feature))
    }

    val sentenceTaggedParagraph = modelWithFeatureRefs(
      Vector(
        FeatureRef.unsafe(
          FeatureTarget.Sentence(model.atlas.paragraphs.head.id),
          featureSpaceId,
          row = 0
        )
      )
    )
    val sentenceSpec = CodexSpec
      .of(
        Vector(AnnotationChannel(AnnotationKind.Feature, AnnotationPriority.unsafe(700))),
        scale = FeatureScale.SurfaceUnit(SurfaceUnitKind.Sentence)
      )
      .fold(error => fail(error.message), identity)
    assertEquals(
      compile(sentenceTaggedParagraph, state, sentenceSpec).contract.feature,
      FeatureChannelState.Missing(FeatureSelection.Raw(featureSpaceId), featureSpaceId)
    )

  test("duplicate targets in one feature space fail closed instead of coalescing rows"):
    val duplicateModel = modelWithFeatureRefs(
      Vector(
        FeatureRef.unsafe(paragraphTarget, featureSpaceId, row = 0),
        FeatureRef.unsafe(paragraphTarget, featureSpaceId, row = 1)
      )
    )
    val state = CommonViewState
      .of(feature = Some(FeatureSelection.Raw(featureSpaceId)))
      .fold(error => fail(error.message), identity)
    val spec = CodexSpec
      .of(
        Vector(AnnotationChannel(AnnotationKind.Feature, AnnotationPriority.unsafe(700))),
        scale = FeatureScale.SurfaceUnit(SurfaceUnitKind.Paragraph)
      )
      .fold(error => fail(error.message), identity)
    val provenance = ViewProvenance
      .fixture(
        duplicateModel.source.canonicalChecksum,
        "wog-codex-compiler-test",
        CodexCompiler.configurationChecksum(state, spec)
      )
      .fold(error => fail(error.message), identity)
    val result = CodexCompiler(provenance).compile(duplicateModel, state, spec)

    assert(result.isLeft)
    assert(result.swap.toOption.exists(_.message.contains("duplicate target")))

  test("feature supports obey shared horizon clipping at every available scale"):
    val selection = FeatureSelection.Raw(featureSpaceId)
    scalesAndTargets.foreach { (scale, target) =>
      val support = featureSupport(target)
      val offset = support.minSpan.endExclusive
      val horizon = EpistemicHorizon.ReaderAt(offset)
      val expected = EvidenceVisibility.clipSupport(support, horizon)
      val state = CommonViewState
        .of(horizon = horizon, feature = Some(selection))
        .fold(error => fail(error.message), identity)
      val spec = CodexSpec
        .of(
          Vector(AnnotationChannel(AnnotationKind.Feature, AnnotationPriority.unsafe(700))),
          scale = scale
        )
        .fold(error => fail(error.message), identity)
      val flow = compile(featureModel, state, spec)
      val visible = flow.annotations.filter(_.kind == AnnotationKind.Feature)

      expected match
        case None          => assertEquals(visible, Vector.empty)
        case Some(clipped) =>
          assertEquals(visible.size, 1)
          assertEquals(visible.head.support, clipped)
          assert(visible.head.support.refs.toVector.forall(_.span.endExclusive <= offset))
    }

    val paragraph = featureModel.atlas.byId(model.atlas.paragraphs.head.id)
    val midParagraph = paragraph.span.start + paragraph.span.length / 2
    assert(midParagraph > paragraph.span.start)
    assert(midParagraph < paragraph.span.endExclusive)
    val horizon = EpistemicHorizon.ReaderAt(midParagraph)
    val state = CommonViewState
      .of(horizon = horizon, feature = Some(selection))
      .fold(error => fail(error.message), identity)
    val spec = CodexSpec
      .of(
        Vector(AnnotationChannel(AnnotationKind.Feature, AnnotationPriority.unsafe(700))),
        scale = FeatureScale.SurfaceUnit(SurfaceUnitKind.Paragraph)
      )
      .fold(error => fail(error.message), identity)
    val flow = compile(featureModel, state, spec)
    val atlas = compileAtlas(
      featureModel,
      state,
      FeatureScale.SurfaceUnit(SurfaceUnitKind.Paragraph)
    )

    assertEquals(
      EvidenceVisibility.clipSupport(featureSupport(paragraphTarget), horizon),
      None
    )
    assert(!flow.annotations.exists(_.kind == AnnotationKind.Feature))
    assertEquals(atlas.featureLayer.state, flow.contract.feature)
    assertEquals(atlas.featureLayer.observations, Vector.empty)

  test("feature-ref input order cannot change compiled annotations or lanes"):
    val selection = FeatureSelection.Raw(featureSpaceId)
    val state = CommonViewState
      .of(feature = Some(selection))
      .fold(error => fail(error.message), identity)
    val spec = CodexSpec
      .of(
        Vector(AnnotationChannel(AnnotationKind.Feature, AnnotationPriority.unsafe(700))),
        scale = FeatureScale.NarrativeUnit(NarrativeUnitBasis.Situations)
      )
      .fold(error => fail(error.message), identity)

    assertEquals(
      compile(featureModel, state, spec),
      compile(modelWithFeatureRefs(featureRefs.reverse), state, spec)
    )
    assertEquals(
      compileAtlas(
        featureModel,
        state,
        FeatureScale.NarrativeUnit(NarrativeUnitBasis.Situations)
      ),
      compileAtlas(
        modelWithFeatureRefs(featureRefs.reverse),
        state,
        FeatureScale.NarrativeUnit(NarrativeUnitBasis.Situations)
      )
    )

  test("changing feature scale does not hide non-feature narrative annotations"):
    val state = CommonViewState
      .of(
        relationLayers = relationLayers,
        feature = Some(FeatureSelection.Raw(featureSpaceId))
      )
      .fold(error => fail(error.message), identity)
    val channels = CodexLens.Overview.channels :+
      AnnotationChannel(AnnotationKind.Feature, AnnotationPriority.unsafe(700))
    def at(scale: FeatureScale): CodexFlow =
      val spec = CodexSpec
        .of(channels, ChannelBudget.All, scale = scale)
        .fold(error => fail(error.message), identity)
      compile(featureModel, state, spec)

    val paragraph = at(FeatureScale.SurfaceUnit(SurfaceUnitKind.Paragraph))
    val situations = at(FeatureScale.NarrativeUnit(NarrativeUnitBasis.Situations))
    assertEquals(
      paragraph.annotations.filterNot(_.kind == AnnotationKind.Feature),
      situations.annotations.filterNot(_.kind == AnnotationKind.Feature)
    )
    val paragraphAtlas =
      compileAtlas(featureModel, state, FeatureScale.SurfaceUnit(SurfaceUnitKind.Paragraph))
    val situationAtlas = compileAtlas(
      featureModel,
      state,
      FeatureScale.NarrativeUnit(NarrativeUnitBasis.Situations)
    )
    assertEquals(paragraphAtlas.marks, situationAtlas.marks)
    assertNotEquals(paragraphAtlas.featureLayer, situationAtlas.featureLayer)

  test("a zero-lane policy overflows a feature annotation without dropping it"):
    val selection = FeatureSelection.Raw(featureSpaceId)
    val state = CommonViewState
      .of(feature = Some(selection))
      .fold(error => fail(error.message), identity)
    val noLanes = LanePolicy.of(0).fold(error => fail(error.message), identity)
    val spec = CodexSpec
      .of(
        Vector(AnnotationChannel(AnnotationKind.Feature, AnnotationPriority.unsafe(700))),
        lanePolicy = noLanes,
        scale = FeatureScale.SurfaceUnit(SurfaceUnitKind.Paragraph)
      )
      .fold(error => fail(error.message), identity)
    val flow = compile(featureModel, state, spec)
    val featureAnnotations = flow.annotations.filter(_.kind == AnnotationKind.Feature)

    assertEquals(featureAnnotations.size, 1)
    assertEquals(flow.lanes.overflow, Vector(featureAnnotations.head.id))

  test("Codex rejects reader horizons outside the canonical source"):
    val state = CommonViewState
      .of(horizon = EpistemicHorizon.ReaderAt(model.source.canonicalText.length + 1))
      .fold(error => fail(error.message), identity)
    val spec = CodexSpec.forLens(CodexLens.Structure).fold(error => fail(error.message), identity)
    val provenance = ViewProvenance
      .fixture(
        model.source.canonicalChecksum,
        "wog-codex-compiler-test",
        CodexCompiler.configurationChecksum(state, spec)
      )
      .fold(error => fail(error.message), identity)

    assert(CodexCompiler(provenance).compile(model, state, spec).isLeft)

  test("reader-time compilation uses transitive evidence closure and never leaks future spans"):
    val offset = model.source.canonicalText.length / 2
    val readerState = CommonViewState
      .of(
        horizon = EpistemicHorizon.ReaderAt(offset),
        relationLayers = relationLayers
      )
      .fold(error => fail(error.message), identity)
    val omniscientState = CommonViewState
      .of(relationLayers = relationLayers)
      .fold(error => fail(error.message), identity)
    val spec = CodexSpec
      .forLens(CodexLens.Overview, ChannelBudget.All)
      .fold(error => fail(error.message), identity)
    val reader = compile(readerState, spec)
    val omniscient = compile(omniscientState, spec)
    val ledger = model.ledger.fold(error => fail(error.message), identity)

    assert(reader.annotations.nonEmpty)
    assert(reader.annotations.size < omniscient.annotations.size)
    assert(
      reader.annotations.forall(
        _.support.refs.toVector.forall(_.span.endExclusive <= offset)
      )
    )
    reader.annotations.foreach { annotation =>
      val storyRef = ViewRef.parse(annotation.target) match
        case Some(ViewRef.Story(ref)) => ref
        case other                    => fail(s"expected a StoryRef target, found $other")
      val expectedSupport = model
        .supporting(storyRef)
        .flatMap(support => SpanSet.of(support.refs.toVector.filter(_.span.endExclusive <= offset)))
      assertEquals(expectedSupport, Some(annotation.support))
      val claimIds = annotation.audit.upstream.flatMap(Addressable[CoreRef].parse).collect {
        case CoreRef.Claim(id) => id
      }
      assert(claimIds.nonEmpty)
      assert(claimIds.forall(id => availableAt(id, offset, ledger, Set.empty)))
    }

    val startState = CommonViewState
      .of(horizon = EpistemicHorizon.ReaderAt(0), relationLayers = relationLayers)
      .fold(error => fail(error.message), identity)
    assertEquals(compile(startState, spec).annotations, Vector.empty)

  private def availableAt(
      id: ClaimId,
      offset: Int,
      ledger: ClaimLedger,
      visiting: Set[ClaimId]
  ): Boolean =
    if visiting.contains(id) then false
    else
      ledger.lookup(id).exists { meta =>
        meta.evidence.toVector.forall { evidence =>
          val grounded = evidence.spans.nonEmpty || evidence.upstream.nonEmpty
          val spansVisible = evidence.spans.forall(
            _.refs.toVector.forall(_.span.endExclusive <= offset)
          )
          val upstreamVisible = evidence.upstream
            .forall(upstream => availableAt(upstream, offset, ledger, visiting + id))
          grounded && spansVisible && upstreamVisible
        }
      }
