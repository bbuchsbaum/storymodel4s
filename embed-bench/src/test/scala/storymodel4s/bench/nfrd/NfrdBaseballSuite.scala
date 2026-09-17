package storymodel4s.bench.nfrd

import storymodel4s.corpus.{ReadOperation, RelativeArtifactPath, RootIssue}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path}
import scala.jdk.CollectionConverters.*

import munit.FunSuite

import storymodel4s.bench.Origin
import storymodel4s.core.{Checksum, ContentAddress, StoryId, StorySource}

/** Synthetic courts for the NFRD intake boundary; no released participant language enters git. */
class NfrdBaseballSuite extends FunSuite:
  private val Utf8 = StandardCharsets.UTF_8
  private val Tokens = Vector("P001", "P002", "P003")
  private val ParticipantDomain = "synthetic/oregontrail-baseball/participant/v1"
  private val RankingDomain = "synthetic/oregontrail-baseball/split/v1"

  private final case class Fixture(
      spec: NfrdBaseballSpec,
      files: Map[String, Array[Byte]]
  )

  private def bytes(value: String): Array[Byte] = value.getBytes(Utf8)

  private def path(value: String, label: String): RelativeArtifactPath =
    RelativeArtifactPath.from(value).fold(_ => fail(s"unsafe path for $label"), identity)

  private def manifest(
      order: Vector[String],
      kind: ParticipantArtifactKind,
      contents: Map[String, Array[Byte]]
  ): Array[Byte] =
    val rows = order.map { token =>
      val filename = kind match
        case ParticipantArtifactKind.Transcript => s"${token}_baseball.txt"
        case ParticipantArtifactKind.TextGrid   => s"${token}_baseball.TextGrid"
      val value = contents(token)
      s"$filename\t${value.length}\t${Checksum.ofBytes(value).hex}"
    }
    bytes(rows.mkString("\n") + "\n")

  private def splitChecksum(
      tokens: Vector[String],
      transcriptManifestChecksum: Checksum,
      development: Int,
      calibration: Int
  ): Checksum =
    val ranked = tokens.distinct
      .map { token =>
        val rank = ContentAddress.digest(
          Vector(RankingDomain, transcriptManifestChecksum.hex, token)
        )
        val participant = ContentAddress.digest(
          Vector(ParticipantDomain, transcriptManifestChecksum.hex, token)
        )
        (rank.hex, token, participant)
      }
      .sortBy((rank, token, _) => (rank, token))
    val assignments = ranked.zipWithIndex.map { case ((_, _, participant), index) =>
      val partition =
        if index < development then "development"
        else if index < development + calibration then "calibration"
        else "untouched-test"
      s"${participant.hex}=$partition"
    }
    ContentAddress.digest(
      Vector(RankingDomain, transcriptManifestChecksum.hex) ++ assignments
    )

  private def fixture(
      transcriptOrder: Vector[String] = Tokens,
      textGridOrder: Vector[String] = Tokens
  ): Fixture =
    val allTokens = (transcriptOrder ++ textGridOrder).distinct
    val transcriptContents =
      allTokens.map(token => token -> bytes(s"synthetic recall $token")).toMap
    val textGridContents = allTokens.map(token => token -> bytes(s"synthetic grid $token")).toMap
    val transcriptManifestBytes = manifest(
      transcriptOrder,
      ParticipantArtifactKind.Transcript,
      transcriptContents
    )
    val textGridManifestBytes = manifest(
      textGridOrder,
      ParticipantArtifactKind.TextGrid,
      textGridContents
    )
    val transcriptManifestPath = path("metadata/transcripts.tsv", "transcript-manifest")
    val textGridManifestPath = path("metadata/textgrids.tsv", "textgrid-manifest")
    val transcriptDirectory = path("recall/transcripts", "transcript-directory")
    val textGridDirectory = path("recall/textgrids", "textgrid-directory")
    val transcriptSpec = new ManifestSpec(
      ArtifactLabel.TranscriptManifest,
      transcriptManifestPath,
      transcriptDirectory,
      transcriptOrder.size,
      Checksum.ofBytes(transcriptManifestBytes),
      ParticipantArtifactKind.Transcript
    )
    val textGridSpec = new ManifestSpec(
      ArtifactLabel.TextGridManifest,
      textGridManifestPath,
      textGridDirectory,
      textGridOrder.size,
      Checksum.ofBytes(textGridManifestBytes),
      ParticipantArtifactKind.TextGrid
    )
    val sourceBytes = bytes("Alpha beta.\n")
    val source = StorySource
      .fromText(new String(sourceBytes, Utf8), Some("Baseball Joe in the Big League — Chapter I"))
      .fold(error => fail(error.message), identity)
    val stimulusTranscript = new FixedArtifactSpec(
      ArtifactLabel.StimulusTranscript,
      path("stimulus/source.txt", "stimulus-transcript"),
      sourceBytes.length.toLong,
      Checksum.ofBytes(sourceBytes)
    )
    val stimulusTextGridBytes = bytes("synthetic stimulus grid")
    val stimulusTextGrid = new FixedArtifactSpec(
      ArtifactLabel.StimulusTextGrid,
      path("stimulus/source.TextGrid", "stimulus-textgrid"),
      stimulusTextGridBytes.length.toLong,
      Checksum.ofBytes(stimulusTextGridBytes)
    )
    val stimulusAudioBytes = bytes("synthetic stimulus audio")
    val stimulusAudio = new FixedArtifactSpec(
      ArtifactLabel.StimulusAudio,
      path("stimulus/source.mp3", "stimulus-audio"),
      stimulusAudioBytes.length.toLong,
      Checksum.ofBytes(stimulusAudioBytes)
    )
    val lineage = NfrdBaseballSpec.production.lineage
    val transcriptManifestChecksum = Checksum.ofBytes(transcriptManifestBytes)
    val split = new SplitSpec(
      ParticipantDomain,
      RankingDomain,
      1,
      1,
      1,
      splitChecksum(transcriptOrder, transcriptManifestChecksum, 1, 1)
    )
    val spec = NfrdBaseballSpec.testing(
      transcriptSpec,
      textGridSpec,
      stimulusTranscript,
      stimulusTextGrid,
      stimulusAudio,
      2,
      Checksum.ofText("alpha\nbeta"),
      source.canonicalText.length,
      source.canonicalChecksum,
      source.id,
      lineage,
      NfrdBaseballVerifier.lineageChecksum(lineage),
      split
    )
    val fixedFiles = Map(
      transcriptManifestPath.disclose -> transcriptManifestBytes,
      textGridManifestPath.disclose -> textGridManifestBytes,
      stimulusTranscript.path.disclose -> sourceBytes,
      stimulusTextGrid.path.disclose -> stimulusTextGridBytes,
      stimulusAudio.path.disclose -> stimulusAudioBytes
    )
    val transcriptFiles = transcriptOrder.distinct.map { token =>
      s"${transcriptDirectory.disclose}/${token}_baseball.txt" -> transcriptContents(token)
    }.toMap
    val textGridFiles = textGridOrder.distinct.map { token =>
      s"${textGridDirectory.disclose}/${token}_baseball.TextGrid" -> textGridContents(token)
    }.toMap
    Fixture(spec, fixedFiles ++ transcriptFiles ++ textGridFiles)

  private def rebuild(
      original: Fixture,
      transcriptManifest: ManifestSpec,
      stimulusTranscript: FixedArtifactSpec,
      lineage: Vector[FieldLineage],
      lineageChecksum: Checksum,
      split: SplitSpec,
      expectedLexicalTokens: Int = 2,
      expectedLexicalChecksum: Checksum = Checksum.ofText("alpha\nbeta")
  ): NfrdBaseballSpec =
    val spec = original.spec
    NfrdBaseballSpec.testing(
      transcriptManifest,
      spec.textGridManifest,
      stimulusTranscript,
      spec.stimulusTextGrid,
      spec.stimulusAudio,
      expectedLexicalTokens,
      expectedLexicalChecksum,
      spec.expectedCanonicalUtf16Units,
      spec.expectedCanonicalChecksum,
      spec.expectedStoryId,
      lineage,
      lineageChecksum,
      split
    )

  private def verify(fixture: Fixture): Either[NfrdIntakeError, VerifiedNfrdBaseball] =
    NfrdBaseballVerifier.verify(
      fixture.spec,
      request =>
        fixture.files
          .get(request.path.disclose)
          .map(_.clone())
          .toRight(NfrdIntakeError.MissingArtifact(request.label))
    )

  private def refusal(fixture: Fixture): NfrdIntakeError =
    verify(fixture).fold(identity, verified => fail(s"unexpected verification: $verified"))

  private def accepted(fixture: Fixture): VerifiedNfrdBaseball =
    verify(fixture).fold(error => fail(error.message), identity)

  private def withTranscriptManifest(
      original: Fixture,
      replacement: Array[Byte]
  ): Fixture =
    val old = original.spec.transcriptManifest
    val next = new ManifestSpec(
      old.label,
      old.path,
      old.directory,
      old.expectedCount,
      Checksum.ofBytes(replacement),
      old.artifactKind
    )
    val spec = rebuild(
      original,
      next,
      original.spec.stimulusTranscript,
      original.spec.lineage,
      original.spec.expectedLineageChecksum,
      original.spec.split
    )
    Fixture(spec, original.files.updated(old.path.disclose, replacement))

  private def withTextGridManifest(
      original: Fixture,
      replacement: Array[Byte]
  ): Fixture =
    val old = original.spec.textGridManifest
    val next = new ManifestSpec(
      old.label,
      old.path,
      old.directory,
      old.expectedCount,
      Checksum.ofBytes(replacement),
      old.artifactKind
    )
    val spec = NfrdBaseballSpec.testing(
      original.spec.transcriptManifest,
      next,
      original.spec.stimulusTranscript,
      original.spec.stimulusTextGrid,
      original.spec.stimulusAudio,
      original.spec.expectedLexicalTokens,
      original.spec.expectedLexicalChecksum,
      original.spec.expectedCanonicalUtf16Units,
      original.spec.expectedCanonicalChecksum,
      original.spec.expectedStoryId,
      original.spec.lineage,
      original.spec.expectedLineageChecksum,
      original.spec.split
    )
    Fixture(spec, original.files.updated(old.path.disclose, replacement))

  private def rewriteManifestLine(
      manifestBytes: Array[Byte],
      filename: String
  )(rewrite: Vector[String] => Vector[String]): Array[Byte] =
    val lines = new String(manifestBytes, Utf8).stripSuffix("\n").split("\n").toVector
    val updated = lines.map { line =>
      val fields = line.split("\t", -1).toVector
      if fields.headOption.contains(filename) then rewrite(fields) else fields
    }
    bytes(updated.map(_.mkString("\t")).mkString("\n") + "\n")

  private def materialize(fixture: Fixture): Path =
    val root = Files.createTempDirectory("nfrd-synthetic-")
    fixture.files.foreach { case (relative, content) =>
      val target = root.resolve(relative)
      Files.createDirectories(target.getParent)
      Files.write(target, content)
    }
    root

  private def deleteTree(root: Path): Unit =
    if Files.exists(root, LinkOption.NOFOLLOW_LINKS) then
      val stream = Files.walk(root)
      try
        stream
          .iterator()
          .asScala
          .toVector
          .sortBy(_.getNameCount)
          .reverse
          .foreach(path => Files.deleteIfExists(path))
      finally stream.close()

  test("the production receipt pins source, manifests, lineage, and the seedless cohort split") {
    val spec = NfrdBaseballSpec.production
    assertEquals(spec.transcriptManifest.expectedCount, 113)
    assertEquals(spec.textGridManifest.expectedCount, 113)
    assertEquals(spec.expectedLexicalTokens, 2187)
    assertEquals(spec.expectedStoryId, StoryId.unsafe("story:86498fdb3920"))
    assertEquals(
      spec.expectedLineageChecksum.hex,
      "bcfca613502ec24740e0746d6ff12eeb2e2ad45bf3542a6d14d2242bf80ba1f3"
    )
    assertEquals(spec.split.seed, None)
    assertEquals(spec.split.development, 57)
    assertEquals(spec.split.calibration, 28)
    assertEquals(spec.split.untouchedTest, 28)
    assertEquals(
      spec.split.expectedChecksum.hex,
      "45d879a8c201d8b4424ecf208969d4339295f2a48394248ff130a94d48f5ebd9"
    )
  }

  test("a complete synthetic snapshot verifies without retaining participant language") {
    val verified = accepted(fixture())
    assertEquals(verified.participants.size, 3)
    assertEquals(verified.participants.map(_.key).distinct.size, 3)
    assertEquals(verified.receipt.developmentCount, 1)
    assertEquals(verified.receipt.calibrationCount, 1)
    assertEquals(verified.receipt.untouchedTestCount, 1)
    verified.origin match
      case Origin.Diagnostic(_, _) => ()
    assert(!verified.toString.contains("synthetic recall"))
    assert(!verified.receipt.render.contains("P001"))
  }

  test("the aggregate input receipt binds both non-text stimulus artifacts") {
    val original = fixture()
    val first = accepted(original)
    val replacement = bytes("changed synthetic stimulus audio")
    val old = original.spec.stimulusAudio
    val changedAudio = new FixedArtifactSpec(
      old.label,
      old.path,
      replacement.length.toLong,
      Checksum.ofBytes(replacement)
    )
    val spec = NfrdBaseballSpec.testing(
      original.spec.transcriptManifest,
      original.spec.textGridManifest,
      original.spec.stimulusTranscript,
      original.spec.stimulusTextGrid,
      changedAudio,
      original.spec.expectedLexicalTokens,
      original.spec.expectedLexicalChecksum,
      original.spec.expectedCanonicalUtf16Units,
      original.spec.expectedCanonicalChecksum,
      original.spec.expectedStoryId,
      original.spec.lineage,
      original.spec.expectedLineageChecksum,
      original.spec.split
    )
    val second = accepted(Fixture(spec, original.files.updated(old.path.disclose, replacement)))
    assertEquals(second.receipt.stimulusAudioChecksum, Checksum.ofBytes(replacement))
    assertNotEquals(first.receipt.contentChecksum, second.receipt.contentChecksum)
  }

  test(
    "a same-size participant mutation fails checksum verification without disclosing its token"
  ) {
    val original = fixture()
    val target = "recall/transcripts/P001_baseball.txt"
    val changed = original.files(target).clone()
    changed(0) = (changed(0) ^ 1).toByte
    val error = refusal(original.copy(files = original.files.updated(target, changed)))
    error match
      case NfrdIntakeError.ChecksumMismatch(ArtifactLabel.RecallTranscript(_), _, _) => ()
      case other => fail(s"expected participant checksum refusal, got ${other.message}")
    assert(!error.message.contains("P001"))
    assert(!error.message.contains("synthetic recall"))
    assert(!error.toString.contains("P001"))
  }

  test("a false declared size fails even when the artifact checksum matches") {
    val original = fixture()
    val manifestPath = original.spec.textGridManifest.path.disclose
    val changed = rewriteManifestLine(
      original.files(manifestPath),
      "P002_baseball.TextGrid"
    )(fields => fields.updated(1, (fields(1).toLong + 1L).toString))
    refusal(withTextGridManifest(original, changed)) match
      case NfrdIntakeError.SizeMismatch(ArtifactLabel.RecallTextGrid(_), _, _) => ()
      case other => fail(s"expected size refusal, got ${other.message}")
  }

  test("participant artifacts pair by token rather than row position") {
    val error = refusal(fixture(textGridOrder = Vector("P001", "P002", "P004")))
    assertEquals(error, NfrdIntakeError.ParticipantPairMismatch(1, 1))
  }

  test("unsorted and duplicate manifest rows are refused even when their digest is pinned") {
    refusal(fixture(transcriptOrder = Tokens.reverse)) match
      case NfrdIntakeError.ManifestFormat(ArtifactLabel.TranscriptManifest, 0, reason) =>
        assertEquals(reason, "filenames are not sorted")
      case other => fail(s"expected unsorted refusal, got ${other.message}")

    refusal(fixture(transcriptOrder = Vector("P001", "P001", "P003"))) match
      case NfrdIntakeError.ManifestFormat(ArtifactLabel.TranscriptManifest, 0, reason) =>
        assertEquals(reason, "duplicate filename")
      case other => fail(s"expected duplicate refusal, got ${other.message}")
  }

  test("manifest aggregate identity and strict UTF-8 are separate courts") {
    val original = fixture()
    val manifestPath = original.spec.transcriptManifest.path.disclose
    val changed = original.files(manifestPath).clone()
    changed(0) = (changed(0) ^ 1).toByte
    refusal(original.copy(files = original.files.updated(manifestPath, changed))) match
      case NfrdIntakeError.ManifestChecksumMismatch(ArtifactLabel.TranscriptManifest, _, _) => ()
      case other => fail(s"expected aggregate checksum refusal, got ${other.message}")

    val invalidUtf8 = Array(0xc3.toByte, 0x28.toByte)
    refusal(withTranscriptManifest(original, invalidUtf8)) match
      case NfrdIntakeError.ManifestEncoding(ArtifactLabel.TranscriptManifest) => ()
      case other => fail(s"expected UTF-8 refusal, got ${other.message}")
  }

  test("an empty participant artifact is refused even when its checksum is declared") {
    val original = fixture()
    val manifestPath = original.spec.transcriptManifest.path.disclose
    val changed = rewriteManifestLine(
      original.files(manifestPath),
      "P001_baseball.txt"
    )(fields => fields.updated(1, "0").updated(2, Checksum.ofBytes(Array.emptyByteArray).hex))
    val target = "recall/transcripts/P001_baseball.txt"
    val altered = withTranscriptManifest(original, changed)
    refusal(altered.copy(files = altered.files.updated(target, Array.emptyByteArray))) match
      case NfrdIntakeError.ManifestFormat(
            ArtifactLabel.TranscriptManifest,
            1,
            "positive decimal size"
          ) =>
        ()
      case other => fail(s"expected empty-artifact refusal, got ${other.message}")
  }

  test("checksum-valid malformed source UTF-8 never reaches StorySource") {
    val original = fixture()
    val replacement = Array(0xc3.toByte, 0x28.toByte)
    val old = original.spec.stimulusTranscript
    val fixed = new FixedArtifactSpec(
      old.label,
      old.path,
      replacement.length.toLong,
      Checksum.ofBytes(replacement)
    )
    val spec = rebuild(
      original,
      original.spec.transcriptManifest,
      fixed,
      original.spec.lineage,
      original.spec.expectedLineageChecksum,
      original.spec.split
    )
    assertEquals(
      refusal(Fixture(spec, original.files.updated(old.path.disclose, replacement))),
      NfrdIntakeError.SourceEncoding
    )
  }

  test("source lexical and canonical identities remain independent of artifact identity") {
    val original = fixture()
    val replacement = bytes("Alpha zeta.\n")
    val old = original.spec.stimulusTranscript
    val fixed = new FixedArtifactSpec(
      old.label,
      old.path,
      replacement.length.toLong,
      Checksum.ofBytes(replacement)
    )
    val spec = rebuild(
      original,
      original.spec.transcriptManifest,
      fixed,
      original.spec.lineage,
      original.spec.expectedLineageChecksum,
      original.spec.split
    )
    refusal(Fixture(spec, original.files.updated(old.path.disclose, replacement))) match
      case NfrdIntakeError.SourceLexicalChecksum(_, _) => ()
      case other => fail(s"expected lexical refusal, got ${other.message}")
  }

  test("the lineage ledger is total and cannot relabel an unknown as observation") {
    val original = fixture()
    val missing = original.spec.lineage.filterNot(
      _.field == BaseballField.UpstreamTechnicalLoss
    )
    val missingSpec = rebuild(
      original,
      original.spec.transcriptManifest,
      original.spec.stimulusTranscript,
      missing,
      NfrdBaseballVerifier.lineageChecksum(missing),
      original.spec.split
    )
    refusal(original.copy(spec = missingSpec)) match
      case NfrdIntakeError.MissingLineage(fields) =>
        assertEquals(fields, Vector(BaseballField.UpstreamTechnicalLoss))
      case other => fail(s"expected missing-lineage refusal, got ${other.message}")

    val relabelled = original.spec.lineage.map { row =>
      if row.field == BaseballField.UpstreamTechnicalLoss then
        FieldLineage
          .of(
            row.field,
            IntakeClass.Observation,
            row.reversibility,
            row.procedure,
            row.evidenceRef
          )
          .fold(error => fail(error.message), identity)
      else row
    }
    val relabelledSpec = rebuild(
      original,
      original.spec.transcriptManifest,
      original.spec.stimulusTranscript,
      relabelled,
      NfrdBaseballVerifier.lineageChecksum(relabelled),
      original.spec.split
    )
    refusal(original.copy(spec = relabelledSpec)) match
      case NfrdIntakeError.FieldClassMismatch(
            BaseballField.UpstreamTechnicalLoss,
            IntakeClass.Unknown,
            IntakeClass.Observation
          ) =>
        ()
      case other => fail(s"expected class refusal, got ${other.message}")
  }

  test("changing the frozen participant split receipt fails closed") {
    val original = fixture()
    val old = original.spec.split
    val changes = Vector(
      new SplitSpec(
        old.participantDomain,
        s"${old.rankingDomain}/changed",
        old.development,
        old.calibration,
        old.untouchedTest,
        old.expectedChecksum
      ),
      new SplitSpec(
        old.participantDomain,
        old.rankingDomain,
        2,
        0,
        1,
        old.expectedChecksum
      ),
      new SplitSpec(
        old.participantDomain,
        old.rankingDomain,
        old.development,
        old.calibration,
        old.untouchedTest,
        Checksum.unsafe("0" * 64)
      )
    )
    changes.foreach { changed =>
      val spec = rebuild(
        original,
        original.spec.transcriptManifest,
        original.spec.stimulusTranscript,
        original.spec.lineage,
        original.spec.expectedLineageChecksum,
        changed
      )
      refusal(original.copy(spec = spec)) match
        case NfrdIntakeError.SplitChecksumMismatch(_, _) => ()
        case other => fail(s"expected split refusal, got ${other.message}")
    }
  }

  test("relative paths refuse traversal, platform roots, and ambiguous segments") {
    Vector("../x", "/x", "a//b", "a/./b", "C:/x", "a\\b").foreach { raw =>
      assert(
        RelativeArtifactPath.from(raw).isLeft,
        s"unsafe path unexpectedly admitted: $raw"
      )
    }
  }

  test("an invalid CLI path is a typed root refusal rather than an exception") {
    assertEquals(
      NfrdBaseballFiles.verify(0.toChar.toString),
      Left(NfrdIntakeError.RootUnavailable(RootIssue.InvalidPath))
    )
  }

  test("filesystem reads refuse a symlink whose real target escapes the admitted root") {
    val synthetic = fixture()
    val root = materialize(synthetic)
    val outside = Files.createTempFile("nfrd-outside-", ".txt")
    try
      Files.write(outside, bytes("synthetic recall P001"))
      val victim = root.resolve("recall/transcripts/P001_baseball.txt")
      Files.delete(victim)
      Files.createSymbolicLink(victim, outside)
      NfrdBaseballFiles.verify(root, synthetic.spec) match
        case Left(NfrdIntakeError.PathEscapesRoot(ArtifactLabel.RecallTranscript(_))) => ()
        case Left(other) => fail(s"expected symlink refusal, got ${other.message}")
        case Right(_)    => fail("out-of-root symlink unexpectedly verified")
    finally
      deleteTree(root)
      val _ = Files.deleteIfExists(outside)
  }
