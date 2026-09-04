package storymodel4s.codec

import munit.FunSuite
import storymodel4s.view.*
import storymodel4s.core.*
import storymodel4s.features.*
import storymodel4s.story.StoryValidator

/** Materializing tracks onto the fixture model, the sidecar bytes and the `features.json` record:
  * round trip, binding, and refusals.
  */
class FeatureMaterializerSuite extends FunSuite:
  private val draft = CodecFixture.draft
  private val sequence = SurfaceSequence(draft.atlas)
  private val resolver =
    SupportResolver(sequence, situation = id => draft.graph.situations.get(id).map(_.support))

  private def tracks(m: LexicalMeasure): Vector[FeatureTrack[? <: FeatureTarget, Double]] =
    val raw = TokenTracks.measure(sequence, m).fold(e => fail(e.message), identity)
    val sentences = TokenTracks.perSentence(raw, sequence).fold(e => fail(e.message), identity)
    val situations = TokenTracks
      .perSituation(raw, resolver, draft.graph.discourseOrder)
      .fold(e => fail(e.message), identity)
    Vector(raw, sentences, situations)

  private val materialized =
    FeatureMaterializer.materialize(draft, tracks(TokenLength)).fold(e => fail(e.message), identity)

  test("the model gains the spaces, manifests and compact row refs of every observed value") {
    val model = materialized.model
    assertEquals(model.featureSpaces.size, draft.featureSpaces.size + 3)
    assertEquals(model.sidecars.size, draft.sidecars.size + 3)
    val raw = materialized.tracks.find(_.space.id == TokenLength.space.id).get
    assertEquals(raw.manifest.rowCount, sequence.lexicalSize)
    assertEquals(raw.manifest.dimension, 1)
    assertEquals(
      raw.manifest.layout match
        case Layout.BlockedRowMajor(rows, _) => rows.value
        case Layout.RowMajor                 => -1
      ,
      FeatureMaterializer.RowsPerBlock.value
    )
    val refs = model.featureRefs.filter(_.space == TokenLength.space.id)
    assertEquals(refs.map(_.row), refs.indices.toVector, "rows are compact and in target order")
    assertEquals(refs.size, sequence.lexicalSize)
    // A missing observation consumes no row and keeps its reason.
    val missing = raw.observations.filter(!_.estimate.isObserved)
    assertEquals(missing.size, sequence.size - sequence.lexicalSize)
    assert(missing.forall(_.estimate == Estimate.Missing(MissingReason.Excluded)))
    // The model's own feature laws hold on the augmented draft.
    val violations =
      StoryValidator.validate(model).report.violations.filter(_.law.startsWith("feature."))
    assertEquals(violations, Vector.empty)
  }

  test("the sidecar bytes verify against the manifest and hold the observed values in order") {
    val raw = materialized.tracks.find(_.space.id == TokenLength.space.id).get
    val bytes = materialized.sidecars(raw.space.id)
    assertEquals(raw.manifest.checksum, Checksum.ofBytes(bytes))
    val decoded = SidecarCodec.decodeRows(raw.manifest, bytes).fold(e => fail(e.message), identity)
    val expected = TokenTracks
      .measure(sequence, TokenLength)
      .fold(e => fail(e.message), identity)
      .observations
      .flatMap(_.estimate.toOption)
      .map(v => Vector(v))
    assertEquals(decoded, expected)
  }

  test("features.json round-trips, binds to the model, and refuses another model") {
    val artifact = FeaturesArtifact
      .of(materialized.model, materialized.tracks)
      .fold(e => fail(e.message), identity)
    val text = FeaturesRecordCodec.encode(artifact)
    assertEquals(FeaturesRecordCodec.decode(text), Right(artifact))
    assertEquals(FeaturesRecordCodec.decode(materialized.model, text), Right(artifact))
    assert(artifact.describes(materialized.model))
    assert(!artifact.describes(draft), "the record is for the augmented model, not the bare draft")
    FeaturesRecordCodec.decode(draft, text) match
      case Left(CodecError.Decode(path, _)) => assertEquals(path, "$.modelChecksum")
      case other                            => fail(s"accepted a record for another model: $other")
    assert(artifact.tracks.forall(e => e.file == FeaturesArtifact.fileName(e.track.manifest)))
    assert(!text.contains("null"))
    val widened =
      io.circe.parser.parse(text).toOption.get.mapObject(_.add("extra", io.circe.Json.True))
    assert(FeaturesRecordCodec.decode(Canonical.print(widened)).isLeft)
  }

  test("a record whose track is not the model's sidecar for that space is refused") {
    val other = FeatureMaterializer
      .materialize(draft, tracks(TypeFrequency))
      .fold(e => fail(e.message), identity)
    FeaturesArtifact.of(materialized.model, other.tracks) match
      case Left(DomainError.InvariantViolation(_, detail)) =>
        assert(detail.contains("not the model's sidecar"), detail)
      case r => fail(s"accepted $r")
  }

  test("two tracks on one space, or a space the model already has, are refused") {
    val raw = TokenTracks.measure(sequence, TokenLength).fold(e => fail(e.message), identity)
    assert(FeatureMaterializer.materialize(draft, Vector(raw, raw)).isLeft)
    assert(FeatureMaterializer.materialize(materialized.model, Vector(raw)).isLeft)
  }

  test("an empty request materializes nothing and records no tracks") {
    val none =
      FeatureMaterializer.materialize(draft, Vector.empty).fold(e => fail(e.message), identity)
    assertEquals(none.model.featureSpaces, draft.featureSpaces)
    val artifact =
      FeaturesArtifact.of(none.model, Vector.empty).fold(e => fail(e.message), identity)
    assertEquals(artifact.tracks, Vector.empty)
    assert(artifact.describes(none.model))
  }

  private def featureScene(
      bundle: MaterializedFeatures,
      index: Int,
      horizon: EpistemicHorizon = EpistemicHorizon.Omniscient,
      alter: FeatureTrack[FeatureTarget, Double] => FeatureTrack[FeatureTarget, Double] = identity
  ): Either[AtlasCompileError, NarrativeScene] =
    val entry = bundle.tracks(index)
    val track = alter(
      SidecarCodec
        .materializeScalarTrack(entry, bundle.sidecars(entry.space.id))
        .fold(e => fail(e.message), identity)
    )
    val scale = index match
      case 0 => FeatureScale.SurfaceUnit(SurfaceUnitKind.Token)
      case 1 => FeatureScale.SurfaceUnit(SurfaceUnitKind.Sentence)
      case _ => FeatureScale.NarrativeUnit(NarrativeUnitBasis.Situations)
    val state = CommonViewState
      .of(feature = Some(FeatureRendering.selection(track)), horizon = horizon)
      .toOption
      .get
    val spec =
      AtlasSpec(ZoomLevel(NarrativeLevel.Scene, SurfaceDetail.Tokens), ThreadPolicy.Selected, scale)
    val input = DraftModel.of(
      bundle.model,
      StoryValidator.validate(bundle.model),
      DerivationRecord.NotSupplied
    )
    val provenance = ViewProvenance
      .draftBuild(input, "feature-court/v1", AtlasCompiler.configurationChecksum(state, spec))
      .toOption
      .get
    AtlasCompiler(provenance).compileDraftFeatures(input, state, spec, track)

  test("all three produced grains compile their real sidecar values and preserve exact support") {
    (0 to 2).foreach { i =>
      val scene = featureScene(materialized, i).fold(e => fail(e.message), identity)
      val features = scene.marks.collect { case VisualPrimitive.Feature(_, value) => value }
      val track = materialized.tracks(i)
      assertEquals(features.size, track.observations.size)
      assertEquals(features.map(_.support), track.observations.map(_.support.get))
      assertEquals(features.map(_.coverage), track.observations.map(_.coverage))
      assert(features.forall(v => scene.navigation.marksFor(v.address).nonEmpty))
      assertEquals(
        features.map(_.circularity).distinct,
        Vector(if i == 0 then FeatureCircularity.NotAggregate else FeatureCircularity.NotAssessed)
      )
      assert(scene.textualTwin.contains("materialized:outcomes="))
    }
  }

  test("observed zero and missing or excluded words survive as different visible outcomes") {
    val word = sequence.lexicalTokens.flatMap(_.normalized).head
    val table = LexiconTable
      .of("partial-zero", "test values, not norms", Some("test-units"), Vector(word -> 0.0))
      .fold(e => fail(e.message), identity)
    val bundle =
      FeatureMaterializer.materialize(draft, tracks(new LexiconMeasure(table))).toOption.get
    val scene = featureScene(bundle, 0).fold(e => fail(e.message), identity)
    val values = scene.marks.collect { case VisualPrimitive.Feature(_, value) => value }
    assertEquals(values.size, sequence.size)
    assert(values.exists(_.estimate.toOption.contains(0.0)), "a measured zero disappeared")
    assert(
      values.exists(_.estimate == Estimate.Missing(MissingReason.NotInLexicon)),
      "missing lexical values disappeared"
    )
    assert(
      values.exists(_.estimate == Estimate.Missing(MissingReason.Excluded)),
      "excluded punctuation disappeared"
    )
    assert(scene.textualTwin.contains("value=0.0"))
    assert(scene.textualTwin.contains("missing=NotInLexicon"))
    assert(scene.textualTwin.contains("missing=Excluded"))
  }

  test("whole-story measurements refuse a reader horizon instead of leaking future evidence") {
    val result = featureScene(materialized, 0, EpistemicHorizon.ReaderAt(10))
    assert(result.left.toOption.exists(_.message.contains("omniscient horizon")))
  }

  test("a feature mark cannot borrow another source, target support or sidecar row") {
    val source = featureScene(
      materialized,
      0,
      alter = t =>
        t.copy(provenance =
          t.provenance.copy(storyChecksum = Some(Checksum.ofText("another source")))
        )
    )
    assert(source.left.toOption.exists(_.message.contains("another source")))
    val support = featureScene(
      materialized,
      0,
      alter = t =>
        t.copy(observations = t.observations.updated(0, t.observations.head.copy(support = None)))
    )
    assert(support.left.toOption.exists(_.message.contains("support differs")))
    val rows = featureScene(
      materialized,
      0,
      alter = t =>
        t.copy(observations =
          t.observations.map(o =>
            if o.estimate.isObserved then o.copy(estimate = Estimate.Missing(MissingReason.Unknown))
            else o
          )
        )
    )
    assert(rows.left.toOption.exists(_.message.contains("sidecar")))
  }

  test(
    "an entirely unmeasured track has visible absences and no numeric domain at all three grains"
  ) {
    val table = LexiconTable
      .of("absent", "test values only", Some("test"), Vector("zzzzmissingword" -> 1.0))
      .toOption
      .get
    val bundle =
      FeatureMaterializer.materialize(draft, tracks(new LexiconMeasure(table))).toOption.get
    (0 to 2).foreach { i =>
      val scene = featureScene(bundle, i).fold(e => fail(e.message), identity)
      val values = scene.marks.collect { case VisualPrimitive.Feature(_, value) => value }
      assert(values.nonEmpty)
      assert(values.forall(_.estimate.toOption.isEmpty))
      assert(values.forall(_.domain.isEmpty))
      if i > 0 then assert(values.forall(_.coverage.exists(_.observed == 0)))
    }
  }
