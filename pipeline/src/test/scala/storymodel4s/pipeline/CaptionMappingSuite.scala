package storymodel4s.pipeline

import cats.data.NonEmptyVector
import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.align.bridge.TextSourceView
import storymodel4s.core.*
import storymodel4s.media.*
import storymodel4s.recall.*

/** Reuses admitted F0 replay artifacts and Anna/Bob synthetic controls. Modified captions below are
  * explicitly synthetic replay payloads, not fresh provider execution or efficacy evidence.
  */
class CaptionMappingSuite extends FunSuite:
  private def right[E, A](e: Either[E, A]): A = e.fold(x => fail(x.toString), identity)
  private def bytes(name: String): Array[Byte] =
    val in = getClass.getResourceAsStream("/f0/" + name)
    assert(in != null, s"missing admitted F0 resource $name")
    try in.readAllBytes()
    finally in.close()
  private def text(name: String): String = new String(bytes(name), UTF_8)
  private lazy val manifest = right(FixtureManifest.parse(text("f0-v1.manifest.json")))
  private lazy val probeEnvelope = right(ProbeEnvelope.parse(text("f0-v1.ffprobe-envelope.json")))
  private lazy val probe = right(
    MediaProbe.join(
      manifest,
      right(manifest.verify(bytes("f0-v1.mov"))),
      probeEnvelope.tool,
      probeEnvelope.args,
      right(FfprobeJson.parse(right(probeEnvelope.verifyStdout(bytes(probeEnvelope.stdoutFile)))))
    )
  )
  private lazy val frameEnvelope = right(FramesEnvelope.parse(text("f0-v1.frames-envelope.json")))
  private lazy val frames = right(
    FrameSet.join(
      probe,
      frameEnvelope.streamIndex,
      frameEnvelope.geometry,
      frameEnvelope.tool,
      frameEnvelope.args,
      frameEnvelope.byteLength,
      frameEnvelope.framesSha256
    )
  )
  private lazy val envelope = right(CaptionEnvelope.parse(text("f0-v1.caption-envelope.json")))
  private lazy val request = right(CaptionRequest.parse(text(envelope.requestFile)))
  private lazy val edition = EditionId.unsafe(manifest.fixtureId)
  private lazy val media = right(
    CaptionSearch.join(
      frames,
      request,
      right(CaptionOutcome.parse(right(envelope.verifyOutcome(bytes(envelope.outcomeFile))))),
      envelope.worker,
      edition
    )
  )
  private lazy val annotation = right(
    TextSourceView.of(
      right(TextSourcePackage.fromText("Anna arrived.\nAnna arrived.\nBob left.")),
      SurfaceUnitKind.Sentence
    )
  )
  private lazy val view =
    InMemorySourceView(annotation.nodes, Map.empty, None, annotation.scoringLength)
  private lazy val representation = right(
    SourceRepresentation.of(
      view,
      NonEmptyVector.one(BundleEntry.media(media.bundle)),
      None,
      view.nodes
        .zip(media.proposals)
        .map { (n, c) =>
          val anchors = right(
            EvidenceSupport.of(
              media.bundle,
              Vector(
                EvidenceAnchor.MediaTime(
                  media.bundle.id,
                  media.bundle.streams.head.id,
                  media.bundle.primaryAxis.id,
                  PlaybackIntervalSet.one(c.support)
                )
              )
            )
          )
          n.ref -> SourceSupportStatus.located(TypedSupport.Anchored(anchors))
        }
        .toMap
    )
  )
  private lazy val recall = RecallSegmenter.segment(right(StorySource.fromText("Anna arrived.")))
  private lazy val inventory = right(
    RecallInventory.of(
      recall,
      SurfaceSequence(recall.atlas).lexicalTokens.map(_.span),
      WordIdPolicy.inputArtifact(Checksum.ofText("caption-isolation-control"))
    )
  )
  private lazy val config = right(
    StrictCandidateConfig.of(3, TieBudgetRequest.AtMost(3), false, None)
  )
  private lazy val pins =
    CaptionMapping.Expected(edition, frames.identity, frames.framesSha256, envelope.outcomeSha256)
  private def replay(
      payload: Array[Byte] = bytes(envelope.outcomeFile),
      env: CaptionEnvelope = envelope,
      expected: CaptionMapping.Expected = pins,
      source: SourceRepresentation = representation
  ) =
    CaptionMapping.replay(
      frames,
      request,
      env,
      payload,
      expected,
      recall,
      view,
      source,
      inventory,
      config,
      0.8
    )
  private def annotationEvidence(): LocalEvidence =
    val candidates = right(
      StrictCandidateGenerator.canonical(StrictSemanticChannel.Lexical, config, recall, view)
    )
    val costs = right(
      StrictCostModel.of(
        StrictSemanticChannel.Lexical,
        weights = CostWeights.semanticOnly,
        functionPrior = FunctionPrior.none,
        externalMismatch = 0.0,
        distortionPenalty = 0.0
      )
    )
    right(LocalEvidence.compute(recall, view, candidates, costs))
  private def pair(e: LocalEvidence): Unit =
    val universe = right(DeclaredUniverse.of(view.nodes.map(_.ref), TargetGrain.SingleLevel(0)))
    val local = right(
      LocalReference.compute(recall, view, e, right(LocalReference.Profile.of(universe, 1.0)))
    )
    val grouping = right(
      StructuredReconstruction.Grouping.of(view, view.nodes.zipWithIndex.map((n, i) => n.ref -> i))
    )
    val reconstruction = right(
      StructuredReconstruction.compute(
        recall,
        view,
        e,
        StructuredReconstruction.Profile.sherlock(),
        grouping
      )
    )
    assert(local.evidence eq e)
    assert(reconstruction.evidence eq e)
    assertEquals(reconstruction.inference.costs, e.units.zip(e.breakdowns).toMap)

  test("verified replay retains draft authority and both consumers retain each profile evidence") {
    val av = right(replay())
    assertEquals(av.authority, ObservationAuthority.Draft)
    assertEquals(av.media.identity, media.identity)
    assertEquals(av, right(replay()))
    pair(annotationEvidence())
    pair(av.evidence)
  }

  test(
    "changed caption alters only AV prices over the same units cut and candidate configuration"
  ) {
    val before = annotationEvidence()
    val original = right(replay())
    val raw = right(io.circe.parser.parse(text(envelope.outcomeFile)))
    val extents = right(raw.hcursor.get[Vector[Json]]("extents"))
    val altered = raw.mapObject(
      _.add(
        "extents",
        Json.fromValues(extents.zipWithIndex.map { (e, i) =>
          e.mapObject(
            _.add("text", Json.fromString(if i == 0 then "Anna arrived." else "Bob left."))
          )
        })
      )
    )
    val payload = altered.noSpaces.getBytes(UTF_8)
    val env = envelope.copy(outcomeSha256 = Checksum.ofBytes(payload))
    val changed = right(replay(payload, env, pins.copy(outcomeChecksum = env.outcomeSha256)))
    assertNotEquals(changed.produced.identity, original.produced.identity)
    assertNotEquals(changed.evidence.breakdowns, original.evidence.breakdowns)
    assertEquals(annotationEvidence(), before)
    assertEquals(changed.evidence.units, before.units)
    assertEquals(changed.evidence.nominated, before.nominated)
    pair(changed.evidence)
  }

  test("frame and receipt identity stays distinct even when numerical scores match") {
    val original = right(replay()).produced
    val old = original.session.scoring.evidence
    val frameId = Checksum.ofText("synthetic distinct declared frame identity")
    val receipt = right(
      SourceDerivationReceipt.of(
        old.receipt.algorithm,
        old.receipt.parameters,
        Vector(frameId, old.framesChecksum)
      )
    )
    val declared =
      right(CaptionEvidence.of(old.bundle, frameId, old.framesChecksum, receipt, old.captions))
    val session =
      right(right(CaptionScoring.of(0.8, declared)).session(recall, view, representation))
    val changed = right(CaptionScoring.produce(session, inventory, config))
    assertEquals(changed.evidence.identity, original.evidence.identity)
    assertNotEquals(changed.identity, original.identity)
  }

  test("wrong outcome bytes independent frame pins and source support refuse") {
    assert(replay(bytes(envelope.outcomeFile).dropRight(1)).isLeft)
    assertEquals(
      replay(expected = pins.copy(framesIdentity = Checksum.ofText("foreign"))),
      Left(CaptionMapping.Refusal.ArtifactMismatch)
    )
    assertEquals(
      replay(expected = pins.copy(framesChecksum = Checksum.ofText("foreign"))),
      Left(CaptionMapping.Refusal.ArtifactMismatch)
    )
    val unlocated = right(
      SourceRepresentation.of(
        view,
        NonEmptyVector.one(BundleEntry.media(media.bundle)),
        None,
        view.nodes
          .map(n => n.ref -> SourceSupportStatus.unlocated(UnlocatedReason.NoLocusInSource))
          .toMap
      )
    )
    assert(replay(source = unlocated).isLeft)
  }

  test("missing captions remain unavailable and cannot substitute annotation scores") {
    val old = right(replay()).produced.session.scoring.evidence
    val empty = right(
      CaptionEvidence.of(
        old.bundle,
        old.framesIdentity,
        old.framesChecksum,
        old.receipt,
        Vector.empty
      )
    )
    val session = right(right(CaptionScoring.of(0.8, empty)).session(recall, view, representation))
    val produced = right(CaptionScoring.produce(session, inventory, config))
    assert(produced.evidence.provenance.isInstanceOf[CandidateProvenance.Strict])
    assert(
      produced.evidence.states.flatten.forall(_ == AlignState.External(ExternalState.Unranked))
    )
    assert(annotationEvidence().states.flatten.exists(_.isSource))
    pair(produced.evidence)
  }

  test("nomination and pricing cannot combine different caption sessions") {
    val first = right(replay()).produced.session
    val e = first.scoring.evidence
    val empty = right(
      CaptionEvidence.of(e.bundle, e.framesIdentity, e.framesChecksum, e.receipt, Vector.empty)
    )
    val other = right(
      right(CaptionScoring.of(first.scoring.blend, empty)).session(recall, view, representation)
    )
    val candidates = right(StrictCandidateGenerator.caption(first, config))
    val pricing = right(StrictCostModel.of(other.channel, captionSession = Some(other)))
    assert(LocalEvidence.compute(recall, view, candidates, pricing).isLeft)
    assert(StrictCandidateGenerator.canonical(first.channel, config, recall, view).isLeft)
  }

  test("facade binds a changed replay worker even when all numeric evidence is equal") {
    val first = right(replay())
    val worker = right(
      ToolRealization.of(
        envelope.worker.name,
        envelope.worker.versionLine,
        Checksum.ofText("synthetic changed worker bytes")
      )
    )
    val second = right(replay(env = envelope.copy(worker = worker)))
    assertEquals(first.evidence.breakdowns, second.evidence.breakdowns)
    val config = MappingPipeline.Config(
      MappingPipeline.CaptionProfile,
      1.0,
      3,
      3,
      Vector(MappingPipeline.Capability.Mapping)
    )
    val a = right(MappingPipeline.captions(first, config))
    val b = right(MappingPipeline.captions(second, config))
    assert(a.execution.reference.evidence eq first.evidence)
    assert(b.execution.reference.evidence eq second.evidence)
    assertNotEquals(a.registration, b.registration)
    assertNotEquals(a.identity, b.identity)
    assertEquals(a.caption, Some(first))
    assertEquals(b.caption, Some(second))
  }
