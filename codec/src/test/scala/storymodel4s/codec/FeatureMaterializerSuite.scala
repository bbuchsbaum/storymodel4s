package storymodel4s.codec

import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.features.*
import storymodel4s.story.{ModelStatus, StoryModel, StoryValidator}

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
