package storymodel4s.pipeline

import cats.data.NonEmptyVector
import io.circe.Json
import io.circe.parser.parse
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import munit.FunSuite
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*
import scala.util.Using
import storymodel4s.codec.StoryModelCodec
import storymodel4s.core.*
import storymodel4s.document.{
  ChartProposalProvider,
  CoverageCounts,
  NarrativeCompiler,
  NarrativeCompilerError
}
import storymodel4s.fixtures.wog.WarOfTheGhostsText
import storymodel4s.provider.agent.*

/** The replay-mode War of the Ghosts court for the story-build orchestrator (ADR 0009).
  *
  * Every run here is `replay`, so `liveCalls == 0` by construction. Three recording sets live under
  * this module's test resources. Two are hand-written (`origin: authored`) and carry the same three
  * PENMAN replies `provider-agent` commits:
  *
  *   - `recordings/three/`: byte-for-byte copies of `provider-agent/src/test/resources/recordings`,
  *     keyed to the three-sentence text those replies were written for;
  *   - `recordings/wog/`: the same three files re-keyed to the full fixture text. A recording key
  *     digests the token identities of its sentence, and a token id names its story, so a reply
  *     recorded for the three-sentence story cannot serve the fifty-sentence one; the re-keyed copy
  *     is what lets the full text replay its first three sentences without a model call.
  *
  * The third is captured, not authored:
  *
  *   - `recordings/wog-captured/`: fifty replies from the first live run, one per sentence of the
  *     whole fixture story. It is the only record this repository has of what real machine charts
  *     look like at story scale, and the fifty-sentence court below is what reads it.
  *
  * Every run here replays `WarOfTheGhostsText.text`, the 50-sentence fixture literal, written to a
  * temp file; the pipeline reads it as-is. That is not the admitted text on disk
  * (`docs/design/war-of-the-ghosts-boas1901.txt`, which carries a provenance header and cuts into
  * 58 sentences); the two are different strings with different story ids, and every committed
  * recording is keyed against the literal (ADR 0008). The suite pins counts, ids, and checksums as
  * literals, and a change in the recordings, the provider, the compiler, or the rules text must
  * move them.
  */
class StoryBuildSuite extends FunSuite:
  private val Now = 1700000000000L

  /** The model every recording names; `provider-agent`'s replay suite pins the same literal. */
  private val Model = "claude-sonnet-5"

  /** The fixture story's id is its canonical text checksum, so it is the same under any file name.
    */
  private val WogStory = "story:e4b036101a7a"

  /** `Checksum.ofText(ChartProposalProvider.RulesText)`; a rules change must move this literal. */
  private val RulesChecksum =
    "d1c144cffe9f4563ef30669116232d511f2d5e82da2a00a4bec296015b1de1ea"

  private val wogRecordings: Path = Paths.get(getClass.getResource("/recordings/wog").toURI)

  /** The fifty captured replies for the full fixture text: one per sentence, `origin: captured`.
    * Replaying them is the only court that measures what the provider does with a whole real story.
    */
  private val capturedRecordings: Path =
    Paths.get(getClass.getResource("/recordings/wog-captured").toURI)
  private val threeRecordings: Path = Paths.get(getClass.getResource("/recordings/three").toURI)

  private val threeText: String =
    "There were people at Egulac. One night two young men went to hunt seals. " +
      "They came down the river."

  /** The three authored replies, as `provider-agent`'s fixture pins them. */
  private val penman: Vector[String] = Vector(
    "(b / be-located-at-91~e.1 :ARG1 (p / person~e.2) " +
      ":ARG2 (c / city~e.4 :name (n / name~e.4 :op1 \"Egulac\")))",
    "(g / go-02~e.5 :ARG0 (m / man~e.4 :quant 2 :mod (y / young~e.3)) " +
      ":purpose (h / hunt-01~e.7 :ARG0 m :ARG1 (s / seal~e.8)) " +
      ":time (n / night~e.1 :quant 1))",
    "(c / come-01~e.1 :ARG1 (t / they~e.0) :direction (d / down~e.2) :path (r / river~e.4))"
  )

  private val workDirs = ListBuffer.empty[Path]

  override def afterAll(): Unit =
    workDirs.foreach { dir =>
      val paths = Using.resource(Files.walk(dir))(walk => walk.iterator().asScala.toVector)
      paths.sorted(using Ordering[Path].reverse).foreach { path =>
        val _ = Files.deleteIfExists(path)
      }
    }
    workDirs.clear()

  private def work(name: String): Path =
    val dir = Files.createTempDirectory(s"pipeline-$name")
    workDirs += dir
    dir

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def writeText(work: Path, name: String, text: String): Path =
    val path = work.resolve(name)
    Files.write(path, text.getBytes(StandardCharsets.UTF_8))
    path

  private def wogText(work: Path): Path =
    writeText(work, "war-of-the-ghosts.txt", WarOfTheGhostsText.text)

  private def entries(dir: Path): Vector[Path] =
    Using.resource(Files.list(dir))(stream => stream.iterator().asScala.toVector.sortBy(_.toString))

  /** The title a caller states for the fixture story. Not derived from anything: the pipeline no
    * longer reads the input file's name, and this is the only way a title reaches a build.
    */
  private val wogTitle: StoryTitle =
    StoryTitle.callerSupplied(WarOfTheGhostsText.title).fold(e => fail(e.message), identity)

  private def buildAt(
      textPath: Path,
      recordings: Path,
      outDir: Path,
      now: Long,
      title: Option[StoryTitle] = None
  ): BuildSummary =
    StoryPipeline
      .run(DriverMode.Replay, textPath, recordings, outDir, Map.empty, now, title = title)
      .fold(error => fail(error.message), identity)

  /** A build with no title: what the command line does when a caller states none. */
  private def build(textPath: Path, recordings: Path, outDir: Path): BuildSummary =
    buildAt(textPath, recordings, outDir, Now)

  /** A build whose title a caller stated. Every court below whose subject is the compilation uses
    * this: before slice 1.7 those courts were green on a title derived from the input file's name,
    * so `validated == true` there rested on the model asserting the story was called
    * `war-of-the-ghosts.txt`. The title is now the caller's, which is a claim someone made.
    */
  private def buildTitled(
      textPath: Path,
      recordings: Path,
      outDir: Path,
      title: StoryTitle = wogTitle
  ): BuildSummary =
    buildAt(textPath, recordings, outDir, Now, Some(title))

  private def parsed(textPath: Path, recordings: Path): ParseOutcome =
    ClaudeParseDriver
      .parse(DriverMode.Replay, textPath, recordings, Map.empty, Now)
      .fold(error => fail(error.message), identity)

  private def json(path: Path): Json = parse(read(path)).fold(e => fail(e.message), identity)

  private def keys(value: Json): Set[String] =
    value.asObject.map(_.keys.toSet).getOrElse(fail("not a JSON object"))

  private def rows(value: Json, path: String*): Vector[Json] =
    path
      .foldLeft(value.hcursor: io.circe.ACursor)((cursor, field) => cursor.downField(field))
      .as[Vector[Json]]
      .fold(e => fail(e.message), identity)

  private def coverageRows(report: Json): Vector[Json] = rows(report, "coverage", "rows")

  private def field(row: Json, name: String): String =
    row.hcursor.downField(name).as[String].fold(e => fail(s"$name: ${e.message}"), identity)

  private def kindOf(row: Json): String = field(row, "kind")

  private def stringAt(value: Json, path: String*): String =
    path
      .foldLeft(value.hcursor: io.circe.ACursor)((cursor, name) => cursor.downField(name))
      .as[String]
      .fold(e => fail(s"${path.mkString(".")}: ${e.message}"), identity)

  private def intField(value: Json, path: String*): Int =
    path
      .foldLeft(value.hcursor: io.circe.ACursor)((cursor, name) => cursor.downField(name))
      .as[Int]
      .fold(e => fail(s"${path.mkString(".")}: ${e.message}"), identity)

  /** One `params` entry of a rendered provider call, absent when the call does not carry it. */
  private def param(call: Json, name: String): Option[String] =
    call.hcursor.downField("params").downField(name).as[String].toOption

  /** How many members a JSON collection has, whether the codec wrote it as an array or a map. */
  private def size(cursor: io.circe.ACursor, field: String): Int =
    cursor.downField(field).focus match
      case Some(value) if value.isArray  => value.asArray.fold(0)(_.size)
      case Some(value) if value.isObject => value.asObject.fold(0)(_.keys.size)
      case other => fail(s"$field is neither an array nor an object: $other")

  private def copyRecordings(from: Path, into: Path): Path =
    Files.createDirectories(into)
    entries(from).foreach(source => Files.copy(source, into.resolve(source.getFileName.toString)))
    into

  /** The fifty captured replies are named by their recording keys, and the key schema moved to
    * `agent-recording/v2`, which renamed all fifty. The set equality below is the falsifier for
    * that rename -- a single wrong name fails it, in either direction, and no assertion about their
    * content is made.
    *
    * Why this stays separate from the fifty-sentence court: that court fails if a recording is
    * misnamed too, but it fails as a coverage number and would send a reader looking at the
    * provider. This one fails as a name, which is where the fault is.
    */
  test("the fifty captured replies are named by exactly the keys the driver derives") {
    val dir = work("captured")
    val outcome = parsed(wogText(dir), capturedRecordings)
    val derived = outcome.recordingKeys.map(_.checksum.hex).toSet
    val onDisk = entries(capturedRecordings)
      .map(_.getFileName.toString.stripSuffix(".json"))
      .toSet
    assertEquals(outcome.recordingKeys.size, 50)
    assertEquals(derived.size, 50, "two sentences derived the same recording key")
    assertEquals(onDisk.size, 50)
    assertEquals(onDisk, derived)
  }

  test("both recording sets are the three authored replies, keyed to their own source") {
    val dir = work("recordings")
    val cases = Vector(
      (wogText(dir), wogRecordings, 50),
      (writeText(dir, "three.txt", threeText), threeRecordings, 3)
    )
    cases.foreach { (textPath, recordings, sentences) =>
      val outcome = parsed(textPath, recordings)
      val store = Recordings.open(recordings).fold(e => fail(e.message), identity)
      assertEquals(entries(recordings).size, 3)
      assertEquals(outcome.recordingKeys.size, sentences)
      assert(outcome.recordingKeys.size >= penman.size)
      outcome.recordingKeys.take(penman.size).zip(penman).foreach { (key, expected) =>
        assert(store.contains(key), s"no recording under $recordings for ${key.checksum.hex}")
        val reply = store.read(key, Model).fold(e => fail(e.toString), identity)
        assertEquals(reply.text, expected)
        assertEquals(reply.evidence, ReplyEvidence.Authored)
      }
      outcome.recordingKeys.drop(penman.size).foreach(key => assert(!store.contains(key)))
    }
  }

  /** The fifty-sentence replay court: what fifty real machine charts produce.
    *
    * Why it exists: `recordings/wog-captured` holds one captured reply per sentence of the whole
    * fixture story, and until this court nothing replayed them. A green pipeline suite therefore
    * said only that three authored replies still work; it said nothing about what the transport,
    * the provider, and the compiler do with fifty charts a model actually wrote. Replay mode means
    * `liveCalls == 0` by construction, so the court costs no spend and no new text.
    *
    * Which text: two War of the Ghosts strings live in this repository and they are not the same
    * string (ADR 0008). The admitted text on disk, `docs/design/war-of-the-ghosts-boas1901.txt`,
    * carries a provenance header and cuts into 58 sentences under `story:2c4b62fd655f`; the fixture
    * literal `WarOfTheGhostsText.text` cuts into 50 under `story:e4b036101a7a`, and every committed
    * recording is keyed against the literal. This court replays the literal, and pins the story id
    * and the sentence count, so a court that quietly changed texts fails here rather than silently
    * finding no recordings.
    *
    * What is deliberately not pinned: any recording's content — no reply text, no usage, no
    * duration. A content pin would make the fixture its own expectation and would go on passing
    * over a corrupted one. What is pinned is what the fifty replies *produce*: the served-from
    * ledger, the coverage ledger with its abstention reasons, the closed rule that admitted each
    * root, the gaps, the violations, and the shape of the draft that was built. The last of those
    * is what stops a run that regressed to proposing nothing from passing on zeroes.
    *
    * These are the numbers of the combined state, not of any intermediate one. Four mechanical
    * classes were closed together: markers on non-concepts (the transport now mirrors every decoded
    * marker into the sidecar), coordinated predicates, predicative roots, and existential roots.
    * Pinning an intermediate state would pin a pipeline that never ran.
    */
  test("the fifty-sentence captured court: 50 charts, 65 situations, one named abstention") {
    val dir = work("wog-captured")
    val outDir = dir.resolve("out")
    val summary = buildTitled(wogText(dir), capturedRecordings, outDir)

    // The text this court used, stated and pinned: the 50-sentence fixture literal.
    assertEquals(summary.storyId.value, WogStory)
    assertEquals(summary.sentences, 50)

    // The served-from ledger: every sentence was answered from a committed captured recording,
    // and nothing was authored, live, unrecorded, foreign, or corrupt.
    assertEquals(summary.parser.sentences, 50)
    assertEquals(summary.parser.replayedCaptured, 50)
    assertEquals(summary.parser.replayedAuthored, 0)
    assertEquals(summary.parser.capturedLive, 0)
    assertEquals(summary.parser.unrecorded, 0)
    assertEquals(summary.parser.foreign, 0)
    assertEquals(summary.parser.corrupt, 0)
    assertEquals(summary.parser.transportFailures, 0)
    assertEquals(summary.liveCalls, 0)

    // Every reply now yields a chart. Seven did not until the transport stopped refusing a marker
    // that sits on a role, a reentrancy, or a constant (`:quant many~e.2`, `:poss h~e.4`) and
    // began mirroring every decoded marker into the sidecar instead.
    assertEquals(summary.parser.proposed, 50)
    assertEquals(summary.parser.failed, 0)
    assertEquals(summary.parser.abstained, 0)
    assertEquals(summary.charts, 50)
    assertEquals(summary.coverage, CoverageCounts(33, 16, 1, 0, 0))
    assertEquals(summary.coverage.sentences, 50)

    val files = StoryPipeline.files(outDir)
    val report = json(files.report)
    val ledger = coverageRows(report)
    assertEquals(ledger.size, 50)
    assertEquals(
      ledger.groupBy(kindOf).view.mapValues(_.size).toMap,
      Map("proposed" -> 33, "coordinated" -> 16, "abstained" -> 1)
    )

    // The abstention-reason histogram, at sentence grain and at branch grain. One sentence
    // abstains, and it names a class we chose not to admit rather than one we failed to notice:
    // "It was nearly daylight when he became quiet" focuses `daylight`, an entity with a
    // `:degree` and a `:time` and no place, so no existential reading is licensed.
    val reasons = ledger.filter(row => kindOf(row) == "abstained").map(row => field(row, "reason"))
    assertEquals(
      reasons.groupBy(identity).view.mapValues(_.size).toMap,
      Map("focus-not-predicate:Entity" -> 1)
    )
    val coordinated = ledger.filter(row => kindOf(row) == "coordinated")
    assertEquals(coordinated.size, 16)
    // Every coordinating focus in this story is a two-branch one and every branch is a predicate,
    // so no branch reason appears. A branch that stopped being admitted would show up here.
    assertEquals(coordinated.map(row => intField(row, "admitted")), Vector.fill(16)(2))
    val branches = coordinated.flatMap(row => rows(row, "branches"))
    assertEquals(branches.size, 32)
    assertEquals(branches.groupBy(kindOf).view.mapValues(_.size).toMap, Map("admitted" -> 32))
    assertEquals(branches.flatMap(_.hcursor.downField("reason").as[String].toOption), Vector.empty)
    assertEquals(
      branches.map(row => field(row, "role")).groupBy(identity).view.mapValues(_.size).toMap,
      Map("op1" -> 15, "op2" -> 15, "snt1" -> 1, "snt2" -> 1)
    )

    // The four gaps and the three errors are the one abstained anchor and nothing else: the
    // situation, context, membership, and coverage families have no proposal there, and the first
    // three of those are required derivations.
    assertEquals(summary.gaps, 4)
    assertEquals(
      rows(report, "gaps").map(row => field(row, "family") -> field(row, "reason")).sorted,
      Vector(
        "ContextAssignment" -> "unresolved:NoProposal",
        "ParticipantCoverage" -> "unresolved:NoProposal",
        "SegmentMembership" -> "unresolved:NoProposal",
        "SituationMention" -> "unresolved:NoProposal"
      )
    )
    assertEquals(summary.errors, 3)
    assertEquals(summary.warnings, 0)
    assertEquals(summary.validated, false)
    assertEquals(
      rows(report, "validation", "violations")
        .map(row => field(row, "law") -> field(row, "severity"))
        .groupBy(identity)
        .view
        .mapValues(_.size)
        .toMap,
      Map(("compiler.required-derivation", "error") -> 3)
    )
    // Complete, not Incomplete: the exit status reports whether every sentence reached the court,
    // and every one of the fifty did. That the draft does not validate is what `validated` and the
    // three required-derivation errors say.
    assertEquals(ExitStatus.of(Right(summary)), ExitStatus.Complete)

    // Which closed admission rule produced each root. This is the one assertion that names the
    // rules rather than counting their effects: 32 of the predicates are coordination branches,
    // and the single predicative root ("He was dead") and the two existential roots ("There were
    // people at Egulac", "There were five men in the canoe") are the whole of what the predicative
    // and existential rules add to this story. Deleting either rule moves a named number here.
    val rootRules = rows(json(files.receipts), "calls")
      .filter(call => param(call, "rule").contains(ChartProposalProvider.SituationRule))
      .map(call => param(call, "root-rule").getOrElse(fail("a situation call has no root-rule")))
    assertEquals(rootRules.size, 65)
    assertEquals(
      rootRules.groupBy(identity).view.mapValues(_.size).toMap,
      Map("predicate" -> 61, "state-roleset" -> 1, "predicative" -> 1, "existential" -> 2)
    )

    // What the fifty charts actually built. 33 focus roots and 32 coordination branches are 65
    // situations; each is a segment member and sits in the one narrated-world context; the
    // temporal rule pairs the 65 roots into 64 adjacent `Unclear` values, and the trajectory has
    // one step per pair. A run that regressed to proposing nothing would still satisfy every
    // count above that only reads a ledger, and would fail here.
    val built = json(files.model)
    val graph = built.hcursor.downField("graph")
    assertEquals(size(graph, "situations"), 65)
    assertEquals(size(graph, "entities"), 29)
    assertEquals(size(graph, "contexts"), 1)
    assertEquals(size(graph, "segments"), 1)
    val relations = graph.downField("relations")
    assertEquals(size(relations, "participants"), 57)
    assertEquals(size(relations, "circumstances"), 11)
    assertEquals(size(relations, "temporal"), 64)
    assertEquals(size(relations, "causal"), 0)
    assertEquals(size(built.hcursor.downField("trajectory"), "steps"), 64)
    assertEquals(size(built.hcursor.downField("hierarchy"), "containment"), 65)
    assertEquals(intField(report, "model", "situations"), 65)
    assertEquals(intField(report, "model", "entities"), 29)
    assertEquals(intField(report, "model", "claims"), 386)

    // Slice 1.7's referentiality rule, measured on the same fifty charts. 35 entities and 68
    // participant edges became 29 and 57: the eleven fillers that moved are the nine `:time` and
    // two `:manner` ones, and the six entities that went with them are `then`, `now`, `midnight`,
    // `night`, `thus` and `together` - a time is not a participant and an adverb is not a cast
    // member. Nothing was discarded to get there: the circumstance layer holds all eleven with
    // their own spans, and no entity that names a referent left the model.
    val entityLabels = graph
      .downField("entities")
      .focus
      .flatMap(_.asObject)
      .map(
        _.values.toVector
          .flatMap(_.hcursor.downField("label").downField("value").as[String].toOption)
      )
      .getOrElse(fail("no entities object"))
      .sorted
    assertEquals(entityLabels.size, 29)
    assertEquals(
      entityLabels.toSet.intersect(Set("then", "now", "midnight", "night", "thus", "together")),
      Set.empty[String]
    )
    val circumstances = rows(built, "graph", "relations", "circumstances")
    assertEquals(
      circumstances
        .map(row => field(row, "kind") -> field(row, "label"))
        .groupBy(identity)
        .view
        .mapValues(_.size)
        .toMap,
      Map(
        ("Time", "then") -> 4,
        ("Time", "now") -> 3,
        ("Time", "midnight") -> 1,
        ("Time", "night") -> 1,
        ("Manner", "thus") -> 1,
        ("Manner", "together") -> 1
      )
    )
    // Every circumstance carries its own words, never the whole situation by default.
    assert(
      circumstances.forall(row => rows(row, "support").nonEmpty),
      "a circumstance was recorded with no span"
    )

    // The bundle still carries no source prose, on a fifty-sentence run as on a three.
    assert(!read(files.report).contains("Egulac"), "the report carries source prose")
    assert(!read(files.receipts).contains("Egulac"), "the receipts carry source prose")
  }

  test("the WOG replay court: 50 sentences, three charts, two situations, a partial draft") {
    val dir = work("wog")
    val outDir = dir.resolve("out")
    val summary = buildTitled(wogText(dir), wogRecordings, outDir)

    assertEquals(summary.storyId.value, WogStory)
    assertEquals(summary.sentences, 50)
    assertEquals(summary.charts, 3)
    assertEquals(summary.parser.sentences, 50)
    assertEquals(summary.parser.proposed, 3)
    assertEquals(summary.parser.failed, 47)
    assertEquals(summary.parser.abstained, 0)
    assertEquals(summary.parser.transportFailures, 47)
    assertEquals(summary.parser.replayedAuthored, 3)
    assertEquals(summary.parser.replayedCaptured, 0)
    assertEquals(summary.parser.capturedLive, 0)
    assertEquals(summary.parser.unrecorded, 47)
    assertEquals(summary.parser.foreign, 0)
    assertEquals(summary.parser.corrupt, 0)
    assertEquals(summary.liveCalls, 0)

    // Three charts reach the provider. "One night two young men went to hunt seals" (go-02) and
    // "They came down the river" (come-01) have predicate roots and are proposed. "There were
    // people at Egulac" parses to be-located-at-91, which the AMR adapter classes as
    // ConceptKind.Special, so the provider abstains with focus-not-predicate:Special even though
    // its StateFrames set names that frame (the 1.3 court's hand chart gave it Predicate kind and
    // never met the adapter's classification). The other 47 sentences have no chart. The 1.3
    // court's 4/2/1/43 came from seven hand charts and does not transfer.
    assertEquals(summary.coverage, CoverageCounts(3, 0, 0, 0, 47))
    // Gaps: three NoProposal gaps at the abstained anchor (situation, context, membership) and one
    // trajectory step between the two emitted situations that lacks participant and temporal
    // inputs until phase 1.4 lands.
    assertEquals(summary.gaps, 0)
    assertEquals(summary.errors, 0)
    assertEquals(summary.warnings, 0)
    assertEquals(summary.validated, true)
    assertEquals(ExitStatus.of(Right(summary)), ExitStatus.Incomplete)

    val files = StoryPipeline.files(outDir)
    assertEquals(summary.files, files)
    files.all.foreach(path => assert(Files.isRegularFile(path), s"$path was not written"))
    assertEquals(
      entries(outDir).map(_.getFileName.toString),
      files.all.map(_.getFileName.toString).sorted
    )

    val report = json(files.report)
    assertEquals(
      keys(report),
      Set(
        "schemaVersion",
        "mode",
        "source",
        "parser",
        "coverage",
        "gaps",
        "validation",
        "compilation",
        "model"
      )
    )
    val receipts = json(files.receipts)
    assertEquals(
      keys(receipts),
      Set(
        "schemaVersion",
        "mode",
        "parser",
        "sentences",
        "calls",
        "provenance",
        "buildReceipt",
        "buildReceiptContentChecksum"
      )
    )
    val model = json(files.model)
    assert(keys(model).contains("source"), "storymodel.json lacks the model's source")
    assert(keys(model).contains("graph"), "storymodel.json lacks the model's graph")

    val reportText = read(files.report)
    val receiptsText = read(files.receipts)
    assert(!reportText.contains("Egulac"), "the report carries source prose")
    assert(!receiptsText.contains("Egulac"), "the receipts carry source prose")
    assert(!StoryPipeline.render(summary, outDir).contains("Egulac"))

    val cursor = report.hcursor
    assertEquals(cursor.downField("source").downField("storyId").as[String], Right(WogStory))
    assertEquals(cursor.downField("validation").downField("validated").as[Boolean], Right(true))
    assertEquals(cursor.downField("validation").downField("errors").as[Int], Right(0))
    assertEquals(cursor.downField("validation").downField("warnings").as[Int], Right(0))
    val laws =
      rows(report, "validation", "violations").map(v => field(v, "law") -> field(v, "severity"))
    // Since phase 1.4 every one of the three charted sentences is proposed (the be-located-at-91
    // root is admitted as a State), so no family is left unresolved and the trajectory is derived
    // from accepted participant coverage and temporal values: nothing blocks promotion.
    assertEquals(laws.sorted, Vector.empty[(String, String)])
    assertEquals(
      cursor.downField("coverage").downField("counts").downField("noCharts").as[Int],
      Right(47)
    )
    assertEquals(cursor.downField("parser").downField("liveCalls").as[Int], Right(0))
    assertEquals(
      cursor.downField("parser").downField("served").downField("replayedAuthored").as[Int],
      Right(3)
    )
    val gaps = rows(report, "gaps")
    assertEquals(gaps.size, 0)

    // What the three charted sentences actually built: one situation each, the entities their
    // roles filled, a participant edge per filler, a temporal value per adjacent pair, and a
    // trajectory step per pair. A draft that validated on nothing would show zeroes here.
    val built = json(files.model)
    val graph = built.hcursor.downField("graph")
    assertEquals(size(graph, "situations"), 3)
    // Two entities and two participant edges, not three: the third filler of these authored charts
    // is a `:time`, which slice 1.7's referentiality rule records as a circumstance rather than
    // minting an entity for it.
    assertEquals(size(graph, "entities"), 2)
    val relations = graph.downField("relations")
    assertEquals(size(relations, "participants"), 2)
    assertEquals(size(relations, "circumstances"), 1)
    assertEquals(size(relations, "temporal"), 2)
    assertEquals(size(built.hcursor.downField("trajectory"), "steps"), 2)
    assertEquals(
      cursor.downField("compilation").downField("fingerprint").as[String],
      Right(summary.fingerprint.hex)
    )
    assertEquals(
      cursor.downField("compilation").downField("candidateSet").as[String],
      Right(summary.candidateSet.hex)
    )
    assertEquals(
      cursor.downField("model").downField("encodingDigest").downField("checksum").as[String],
      Right(summary.encodingDigest.hex)
    )
    assertEquals(
      cursor
        .downField("model")
        .downField("encodingDigest")
        .downField("timestampBearing")
        .as[Boolean],
      Right(true)
    )
    assertEquals(cursor.downField("model").downField("situations").as[Int], Right(3))
    assertEquals(cursor.downField("model").downField("segments").as[Int], Right(1))

    val coverage = coverageRows(report)
    assertEquals(coverage.size, 50)
    assertEquals(
      coverage(0),
      Json.obj(
        "sentence" -> Json.fromString(s"$WogStory:s0"),
        "ordinal" -> Json.fromInt(0),
        // Since phase 1.4 a -91 reification root is admitted as a State even though the AMR
        // adapter classes it ConceptKind.Special. Its two roles carry no normalized participant
        // role, so they are counted unlicensed rather than proposed as participants.
        "kind" -> Json.fromString("proposed"),
        "root" -> Json.fromString(s"$WogStory:s0#b"),
        // Since slice 1.7 the row divides every filler the scan saw, and the four counters sum to
        // `seen`: a filler cannot leave the provider without a row saying where it went.
        "fillers" -> Json.fromInt(0),
        "circumstances" -> Json.fromInt(0),
        "nonReferential" -> Json.fromInt(0),
        "unlicensed" -> Json.fromInt(2),
        "seen" -> Json.fromInt(2)
      )
    )
    assertEquals(
      coverage(1),
      Json.obj(
        "sentence" -> Json.fromString(s"$WogStory:s1"),
        "ordinal" -> Json.fromInt(1),
        "kind" -> Json.fromString("proposed"),
        "root" -> Json.fromString(s"$WogStory:s1#g"),
        "fillers" -> Json.fromInt(1),
        "circumstances" -> Json.fromInt(1),
        "nonReferential" -> Json.fromInt(0),
        "unlicensed" -> Json.fromInt(0),
        "seen" -> Json.fromInt(2)
      )
    )
    assertEquals(kindOf(coverage(2)), "proposed")
    assertEquals(coverage.map(kindOf).drop(3).distinct, Vector("no-chart"))

    val receiptCursor = receipts.hcursor
    val sentences = rows(receipts, "sentences")
    assertEquals(sentences.size, 50)
    assertEquals(sentences.count(row => field(row, "served") == "replayed-authored"), 3)
    assertEquals(sentences.count(row => field(row, "served") == "unrecorded"), 47)
    assertEquals(sentences.count(row => field(row, "status") == "proposed"), 3)
    assertEquals(
      receiptCursor.downField("buildReceiptContentChecksum").as[String],
      Right(summary.receiptChecksum.hex)
    )
    assertEquals(
      receiptCursor.downField("provenance").downField("configHash").as[String],
      Right(RulesChecksum)
    )
    assertEquals(Checksum.ofText(ChartProposalProvider.RulesText).hex, RulesChecksum)
    val stages = rows(receipts, "buildReceipt", "stages").map(s => field(s, "stage"))
    assertEquals(stages, Vector(ClaudeParseDriver.Stage.value, "chart-proposal-provider"))
    // The parser's call, the AMR adapter's PENMAN conversion receipt on every chart, and the
    // proposal provider's rule applications.
    val providers = rows(receipts, "calls").map(c => field(c, "provider")).toSet
    assertEquals(providers, Set("amr-interop", "anthropic", "chart-proposal-provider"))
    val parserStages = rows(receipts, "parser", "stages").map(s => field(s, "stage"))
    assertEquals(parserStages, Vector(ClaudeParseDriver.Stage.value))
  }

  test("removing one recording makes exactly that sentence NoChart and moves nothing else") {
    val dir = work("isolation")
    val textPath = wogText(dir)
    val full = buildTitled(textPath, wogRecordings, dir.resolve("full"))
    val fullRows = coverageRows(json(full.files.report))

    val outcome = parsed(textPath, wogRecordings)
    assertEquals(outcome.batch.inputs.size, 50)
    val river = outcome.batch.inputs(2)
    assertEquals(river.id.value, "s0002")
    val riverKey = outcome.recordingKeys(2)

    val copy = copyRecordings(wogRecordings, dir.resolve("recordings"))
    val store = Recordings.open(copy).fold(error => fail(error.message), identity)
    assert(store.contains(riverKey), "the third recording is not the river sentence's")
    Files.delete(store.path(riverKey))

    val mutated = buildTitled(textPath, copy, dir.resolve("mutated"))
    assertEquals(mutated.coverage, CoverageCounts(2, 0, 0, 0, 48))
    assertEquals(mutated.charts, 2)
    assertEquals(mutated.parser.replayedAuthored, 2)
    assertEquals(mutated.parser.unrecorded, 48)
    assertEquals(mutated.parser.transportFailures, 48)
    assertEquals(mutated.liveCalls, 0)
    // The abstained anchor's three gaps survive; the trajectory gap needed two situations.
    assertEquals(mutated.gaps, 0)
    assertEquals(mutated.validated, true)

    val mutatedRows = coverageRows(json(mutated.files.report))
    assertEquals(mutatedRows.size, 50)
    val changed = fullRows.zip(mutatedRows).zipWithIndex.collect {
      case ((before, after), index) if before != after => (index, before, after)
    }
    assertEquals(changed.map(_._1), Vector(2), s"rows other than the river changed: $changed")
    val (_, before, after) = changed.head
    assertEquals(kindOf(before), "proposed")
    assertEquals(kindOf(after), "no-chart")
    assertEquals(field(after, "sentence"), river.sentenceId.value)
  }

  test("a missing recordings directory is refused before any file is written") {
    val dir = work("missing")
    val absent = dir.resolve("absent")
    val outDir = dir.resolve("out")
    val outcome =
      StoryPipeline.run(DriverMode.Replay, wogText(dir), absent, outDir, Map.empty, Now)
    assertEquals(
      outcome,
      Left(
        PipelineError.NotStarted(
          DriverError.RecordingsUnavailable(RecordingsError.Missing(absent.toString))
        )
      )
    )
    assertEquals(ExitStatus.of(outcome), ExitStatus.CouldNotStart)
    assert(!Files.exists(outDir), "the output directory was created despite the refusal")
    assert(!Files.exists(absent), "replay created the recordings directory")
  }

  test("record mode without the environment opt-in is refused before touching disk") {
    val dir = work("record")
    val recordingsDir = dir.resolve("recordings")
    val outDir = dir.resolve("out")
    val outcome = StoryPipeline.run(
      DriverMode.Record,
      wogText(dir),
      recordingsDir,
      outDir,
      Map(AgentCredentials.PrimaryKeyVariable -> "not-a-real-key"),
      Now
    )
    assertEquals(
      outcome,
      Left(
        PipelineError.NotStarted(
          DriverError.LiveRefused(
            LiveRefusal.LiveFlagAbsent(AgentCredentials.LiveVariable, AgentCredentials.LiveValue)
          )
        )
      )
    )
    assertEquals(ExitStatus.of(outcome), ExitStatus.CouldNotStart)
    assert(!Files.exists(outDir), "the output directory was created despite the refusal")
    assert(!Files.exists(recordingsDir), "the recordings directory was created")
  }

  test("a refused build receipt comes after the court and before any write") {
    val dir = work("receipt-refused")
    val outDir = dir.resolve("out")
    val outcome =
      StoryPipeline.run(DriverMode.Replay, wogText(dir), wogRecordings, outDir, Map.empty, -1L)
    outcome match
      case Left(PipelineError.NotStarted(DriverError.ReceiptInvalid(detail))) =>
        assertEquals(
          detail,
          "invariant violated at acquire/build-receipt/timestamp: " +
            "build receipt timestamp must be nonnegative"
        )
      case other => fail(s"expected a refused receipt, got $other")
    assertEquals(ExitStatus.of(outcome), ExitStatus.Incomplete)
    assert(!Files.exists(outDir), "the output directory was created before the receipt court")
    assertEquals(entries(wogRecordings).size, 3, "the refusal touched the recordings")
  }

  test("the summary refuses a compilation whose receipt does not carry the parse it was fed") {
    val dir = work("receipt-mismatch")
    val textPath = wogText(dir)
    val wog = parsed(textPath, wogRecordings)
    val files = StoryPipeline.files(dir.resolve("out"))
    val proposals = ChartProposalProvider
      .propose(wog.story, wog.atlas, wog.charts)
      .fold(e => fail(e.message), identity)
    def compile(outcome: ParseOutcome, parserStage: Option[StageId]) =
      ChartProposalProvider
        .input(outcome.story, outcome.atlas, outcome.charts, parserStage, Now)
        .flatMap(NarrativeCompiler.compile)
        .fold(e => fail(e.message), identity)

    val noStage = BuildSummary.derive(wog, proposals, compile(wog, None), files)
    noStage match
      case Left(PipelineError.ReceiptMismatch(detail)) =>
        assert(detail.startsWith("receipt lacks parser stage provider-agent/claude-parse="), detail)
      case other => fail(s"expected a stage mismatch, got $other")

    val three = parsed(writeText(dir, "three.txt", threeText), threeRecordings)
    val otherSource = BuildSummary.derive(
      wog,
      proposals,
      compile(three, Some(wog.parserStage._1)),
      files
    )
    // A compilation of another story is refused, but by the stage check rather than the source
    // check: the parser stage now carries the digest of the charts that were compiled, and those
    // charts name their sentences, which name their story. The source-checksum branch below it is
    // defence in depth and is no longer reachable through this path.
    otherSource match
      case Left(PipelineError.ReceiptMismatch(detail)) =>
        assert(detail.startsWith("receipt lacks parser stage"), detail)
      case other => fail(s"expected a receipt mismatch, got $other")

    val matching =
      BuildSummary.derive(wog, proposals, compile(wog, Some(wog.parserStage._1)), files)
    assert(matching.isRight, matching.toString)
    assert(!Files.exists(dir.resolve("out")), "derive wrote something")
  }

  test("two replay runs write byte-identical files with one fingerprint") {
    val dir = work("determinism")
    val textPath = wogText(dir)
    val first = build(textPath, wogRecordings, dir.resolve("one"))
    val second = build(textPath, wogRecordings, dir.resolve("two"))
    assertEquals(second.fingerprint, first.fingerprint)
    assertEquals(second.encodingDigest, first.encodingDigest)
    assertEquals(second.receiptChecksum, first.receiptChecksum)
    assert(
      java.util.Arrays.equals(
        Files.readAllBytes(first.files.model),
        Files.readAllBytes(second.files.model)
      ),
      "storymodel.json differs between two replays of the same inputs"
    )
    assertEquals(read(second.files.report), read(first.files.report))
    assertEquals(read(second.files.receipts), read(first.files.receipts))
  }

  test("the clock moves only the receipt timestamp, the model bytes, and the encoding digest") {
    val dir = work("clock")
    val textPath = wogText(dir)
    val first = buildAt(textPath, wogRecordings, dir.resolve("one"), Now)
    val later = buildAt(textPath, wogRecordings, dir.resolve("two"), Now + 1L)
    assertEquals(later.fingerprint, first.fingerprint)
    assertEquals(later.candidateSet, first.candidateSet)
    assertEquals(later.receiptChecksum, first.receiptChecksum)
    assertEquals(later.coverage, first.coverage)
    assertNotEquals(later.encodingDigest, first.encodingDigest)
    assert(
      !java.util.Arrays.equals(
        Files.readAllBytes(first.files.model),
        Files.readAllBytes(later.files.model)
      ),
      "storymodel.json does not carry the receipt timestamp"
    )
    val decoded = StoryModelCodec
      .decode(read(later.files.model))
      .fold(error => fail(error.toString), identity)
    assertEquals(decoded.receipt.map(_.createdAtEpochMillis), Some(Now + 1L))
  }

  test("the written storymodel.json decodes to the encoding digest and the receipt") {
    val dir = work("roundtrip")
    val summary = build(wogText(dir), wogRecordings, dir.resolve("out"))
    val decoded = StoryModelCodec
      .decode(read(summary.files.model))
      .fold(error => fail(error.toString), identity)
    assertEquals(StoryModelCodec.contentChecksum(decoded), summary.encodingDigest)
    assertEquals(decoded.graph.situations.size, 3)
    assertEquals(decoded.receipt.map(_.contentChecksum), Some(summary.receiptChecksum))
    assertEquals(decoded.receipt.map(_.createdAtEpochMillis), Some(Now))
  }

  /** The title court. Before slice 1.7 the pipeline handed `StorySource` the input file's name, so
    * this same build published a summary claim at credence 1.0, under calibration model
    * `title-rule-v1`, asserting the narrative was called `war-of-the-ghosts.txt`. A filename is not
    * a title, and the fix is to stop deriving one, not to derive a better one.
    */
  test("with no title the summary is a gap and no claim in the model names the input file") {
    val dir = work("untitled")
    val textPath = wogText(dir)
    val summary = build(textPath, capturedRecordings, dir.resolve("out"))
    val report = json(summary.files.report)

    assertEquals(stringAt(report, "coverage", "summary", "kind"), "no-title")
    assertEquals(
      rows(report, "gaps").map(row => field(row, "family")).count(_ == "Summary"),
      1
    )
    // The accepted consequence, stated rather than worked around: with no summary there is no
    // story segment, so no situation is under a primary root and the draft does not promote. That
    // is true of a model built from a bare text file, and the fix for it is a summary rule that
    // reads the story.
    assertEquals(summary.validated, false)
    assert(summary.errors > 0, "an unresolved summary left no violation")

    // The filename reaches no artifact. `war-of-the-ghosts` is the stem of the file this court
    // wrote, and nothing the pipeline publishes may carry it.
    val fileStem = textPath.getFileName.toString
    summary.files.all.foreach { path =>
      assert(!read(path).contains(fileStem), s"${path.getFileName} names the input file")
    }
  }

  test("a caller-supplied title is carried as the caller's claim, with its provenance recorded") {
    val dir = work("titled")
    val summary = buildTitled(wogText(dir), capturedRecordings, dir.resolve("out"))
    val report = json(summary.files.report)

    assertEquals(stringAt(report, "coverage", "summary", "kind"), "proposed")
    assertEquals(stringAt(report, "coverage", "summary", "titleProvenance"), "caller-supplied")
    assertEquals(
      rows(report, "gaps").map(row => field(row, "family")).count(_ == "Summary"),
      0
    )
    val decoded = StoryModelCodec
      .decode(read(summary.files.model))
      .fold(error => fail(error.toString), identity)
    assertEquals(decoded.source.title, Some(WarOfTheGhostsText.title))
    assertEquals(decoded.source.titleProvenance, Some(TitleProvenance.CallerSupplied))
    assertEquals(decoded.source.establishedTitle.map(_.value), Some(WarOfTheGhostsText.title))
  }

  test("a title the title court refuses stops the run before it convenes") {
    assertEquals(
      ClaudeParseDriver.titleArgument(Vector.empty),
      Right(None)
    )
    assert(ClaudeParseDriver.titleArgument(Vector("  ")).isLeft, "a blank title was admitted")
    assert(
      ClaudeParseDriver.titleArgument(Vector("stories/wog.txt")).isLeft,
      "a path was admitted as a title"
    )
    assert(
      ClaudeParseDriver.titleArgument(Vector("one", "two")).isLeft,
      "two titles were admitted"
    )
    assertEquals(
      ClaudeParseDriver.titleArgument(Vector("The War of the Ghosts")).map(_.map(_.value)),
      Right(Some("The War of the Ghosts"))
    )
  }

  test("exit status: a court that never convened is 2, every other refusal is 1, a clean run 0") {
    assertEquals(ExitStatus.CouldNotStart.code, 2)
    assertEquals(ExitStatus.Incomplete.code, 1)
    assertEquals(ExitStatus.Complete.code, 0)
    val notStarted = PipelineError.NotStarted(DriverError.UnknownMode("nope"))
    assertEquals(ExitStatus.of(Left(notStarted)), ExitStatus.CouldNotStart)
    val receiptRefused = PipelineError.NotStarted(DriverError.ReceiptInvalid("synthetic"))
    assertEquals(ExitStatus.of(Left(receiptRefused)), ExitStatus.Incomplete)
    val invariant = DomainError.InvariantViolation("pipeline-test", "synthetic")
    assertEquals(
      ExitStatus.of(Left(PipelineError.ProposalRefused(invariant))),
      ExitStatus.Incomplete
    )
    val compilerError = NarrativeCompilerError.InvalidInput(NonEmptyVector.one(invariant))
    assertEquals(
      ExitStatus.of(Left(PipelineError.InputRefused(compilerError))),
      ExitStatus.Incomplete
    )
    assertEquals(
      ExitStatus.of(Left(PipelineError.CompileRefused(compilerError))),
      ExitStatus.Incomplete
    )
    assertEquals(
      ExitStatus.of(Left(PipelineError.ReceiptMismatch("synthetic"))),
      ExitStatus.Incomplete
    )
    assertEquals(
      ExitStatus.of(Left(PipelineError.OutputUnwritable("x", Checksum.ofText("y")))),
      ExitStatus.Incomplete
    )
    assertEquals(DriverMode.parse("nope"), Left(DriverError.UnknownMode("nope")))
  }

  test("a run whose every sentence reached the court exits 0") {
    val dir = work("complete")
    val textPath = writeText(dir, "three.txt", threeText)
    val outDir = dir.resolve("out")
    val summary = buildTitled(textPath, threeRecordings, outDir)
    assertEquals(summary.sentences, 3)
    assertEquals(summary.charts, 3)
    assertEquals(summary.parser.transportFailures, 0)
    assertEquals(summary.parser.replayedAuthored, 3)
    assertEquals(summary.coverage, CoverageCounts(3, 0, 0, 0, 0))
    assertEquals(summary.gaps, 0)
    assertEquals(summary.validated, true)
    assertEquals(ExitStatus.of(Right(summary)), ExitStatus.Complete)
    val line = StoryPipeline.render(summary, outDir)
    assert(line.contains("sentences=3 charts=3 proposed=3 coordinated=0 abstained=0"), line)
    assert(line.contains("transportFailures=0"), line)
    assert(line.contains("liveCalls=0"), line)
    assert(line.contains("encodingDigest(timestamp-bearing)="), line)
    assert(!line.contains("Egulac"), line)
  }
