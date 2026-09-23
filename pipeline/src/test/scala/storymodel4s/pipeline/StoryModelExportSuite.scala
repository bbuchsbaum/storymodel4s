package storymodel4s.pipeline

import io.circe.Json
import io.circe.parser.parse
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite
import storymodel4s.codec.{StoryModelCodec, StoryModelExport}
import storymodel4s.core.*
import storymodel4s.fixtures.wog.WarOfTheGhostsModel
import storymodel4s.story.*

/** ADR 0020: declared-lossy StoryModel tables. Expected values are recomputed from the model's own
  * fields here, never read back from the exporter.
  */
class StoryModelExportSuite extends FunSuite:
  private val wog = WarOfTheGhostsModel.model
  private lazy val bundle = StoryModelExport.encode(wog).fold(e => fail(e.toString), identity)
  private lazy val files: Map[String, String] = bundle.files.toMap
  private lazy val manifest: Json = parse(bundle.manifest).fold(e => fail(e.message), identity)

  /** Quoted-TSV reader written against the documented wire, not the exporter's code. */
  private def rows(name: String): Vector[Map[String, String]] =
    val text = files(name)
    val records = Vector.newBuilder[Vector[String]]
    var record = Vector.newBuilder[String]
    val cell = new StringBuilder
    var i = 0
    while i < text.length do
      assertEquals(text.charAt(i), '"', s"$name: unquoted cell at $i")
      i += 1
      var open = true
      while open do
        if text.charAt(i) == '"' then
          if i + 1 < text.length && text.charAt(i + 1) == '"' then
            cell += '"'; i += 2
          else
            open = false; i += 1
        else
          cell += text.charAt(i); i += 1
      record += cell.result(); cell.clear()
      text.charAt(i) match
        case '\t' => i += 1
        case '\n' =>
          records += record.result(); record = Vector.newBuilder[String]; i += 1
        case other => fail(s"$name: unexpected $other at $i")
    val all = records.result()
    val header = all.head
    all.tail.map { r =>
      assertEquals(r.size, header.size, s"$name: ragged row")
      header.zip(r).toMap
    }

  private def losses: Map[String, Json] =
    manifest.hcursor
      .downField("losses")
      .as[Vector[Json]]
      .toOption
      .get
      .map(l => l.hcursor.get[String]("structure").toOption.get -> l)
      .toMap
  private def dropped(structure: String): Option[Int] =
    losses(structure).hcursor.get[String]("dropped").toOption.map(_.toInt)
  private def lostClaims(structure: String): Int =
    losses(structure).hcursor.get[String]("claims").toOption.get.toInt

  private val g = wog.graph

  test("capacity: the fixture exercises every table the export claims to carry") {
    assert(g.entities.nonEmpty && g.situations.nonEmpty && g.contexts.nonEmpty)
    assert(g.segments.nonEmpty && wog.hierarchy.containment.nonEmpty)
    assert(g.relations.participants.nonEmpty && g.relations.temporal.nonEmpty)
    assert(g.situations.values.exists(_.isState) && g.situations.values.exists(_.isEvent))
  }

  test("nodes: one row per entity, situation, context and segment, with its own claim") {
    val nodes = rows("nodes.tsv")
    val expected =
      g.entities.values.map(e => (e.id.value, "entity", e.meta.id.value)) ++
        g.situations.values.map(s => (s.id.value, s.kindName, s.meta.id.value)) ++
        g.contexts.values.map(c => (c.id.value, "context", c.meta.id.value)) ++
        g.segments.values.map(s => (s.id.value, "segment", s.meta.id.value))
    assertEquals(
      nodes.map(r => (r("node_id"), r("node_kind"), r("claim_id"))).toSet,
      expected.toSet
    )
    assertEquals(nodes.size, expected.size)
    assertEquals(rows("entities.tsv").size, g.entities.size)
    assertEquals(rows("situations.tsv").size, g.situations.size)
    assertEquals(rows("contexts.tsv").size, g.contexts.size)
    assertEquals(rows("segments.tsv").size, g.segments.size)
  }

  test("a situation's context is present; an entity's is not-applicable, not empty") {
    val nodes = rows("nodes.tsv").map(r => r("node_id") -> r).toMap
    val s = g.situations.values.head
    assertEquals(
      nodes(s.id.value)("context"),
      s"""{"status":"present","value":"${s.context.value}"}"""
    )
    assertEquals(nodes(g.entities.values.head.id.value)("context"), """{"status":"not-applicable"}""")
  }

  test("absent differs from zero: the first situation in discourse order has position 0 present") {
    val first = wog.discourseOrder.head
    val row = rows("situations.tsv").find(_("node_id") == first.value).get
    assertEquals(row("discourse_position"), """{"status":"present","value":0}""")
    val stateRow = rows("situations.tsv").find(r => r("situation_kind") == "state").get
    assertEquals(stateRow("aspect"), """{"status":"not-applicable"}""")
  }

  test("relations: every edge of the seven target layers, keyed by its claim") {
    val r = g.relations
    val expected =
      r.participants.map(e => (e.meta.id.value, "participant")) ++
        r.temporal.map(e => (e.meta.id.value, "temporal")) ++
        r.causal.map(e => (e.meta.id.value, "causal")) ++
        r.goals.map(e => (e.meta.id.value, "goal")) ++
        r.stateChanges.map(e => (e.meta.id.value, "state-change")) ++
        r.references.map(e => (e.meta.id.value, "reference")) ++
        r.entityRelations.map(e => (e.meta.id.value, "entity-relation"))
    val got = rows("relations.tsv").map(x => (x("claim_id"), x("layer")))
    assertEquals(got.sortBy(identity), expected.sortBy(identity))
    assertEquals(rows("circumstances.tsv").size, r.circumstances.size)
    assertEquals(rows("hierarchy.tsv").size, wog.hierarchy.containment.size)
  }

  test("vocabulary cells are tagged terms, never an ns:label string") {
    val roles = rows("relations.tsv").filter(_("layer") == "participant").map(_("relation"))
    assert(roles.nonEmpty)
    assert(roles.forall(_.startsWith("""{"status":""")), roles.take(3).toString)
  }

  test("spans: node support and claim evidence, as exact UTF-16 offsets") {
    val spans = rows("spans.tsv")
    val exportedClaims = rows("nodes.tsv").map(_("claim_id")) ++
      rows("relations.tsv").map(_("claim_id")) ++ rows("circumstances.tsv").map(_("claim_id")) ++
      rows("hierarchy.tsv").map(_("claim_id"))
    val metas = wog.claims.map(m => m.id.value -> m).toMap
    val evidenceSpans = exportedClaims.map(c =>
      metas(c).evidence.toVector.flatMap(_.spans.toVector.flatMap(_.refs.toVector)).size
    ).sum
    val supportSpans =
      g.entities.values.toVector.map(_.support) ++ g.situations.values.toVector.map(_.support) ++
        g.contexts.values.toVector.map(_.support) ++ g.segments.values.toVector.map(_.support) ++
        g.relations.circumstances.map(_.support)
    val supportCount = supportSpans.map(_.textSpans.get.refs.length).sum
    assertEquals(spans.count(_("origin") == "claim-evidence"), evidenceSpans)
    assertEquals(spans.count(_("origin") == "node-support"), supportCount)
    val text = wog.source.canonicalText
    spans.foreach { r =>
      val (s, e) = (r("utf16_start").toInt, r("utf16_end_exclusive").toInt)
      assert(0 <= s && s < e && e <= text.length, r.toString)
    }
    val evidence = rows("evidence.tsv")
    assertEquals(
      evidence.size,
      exportedClaims.map(c => metas(c).evidence.length).sum
    )
  }

  test("claim accounting: exported claims plus claims in loss records equal model.claims") {
    val exported = manifest.hcursor.get[String]("exported_claims").toOption.get.toInt
    val lost = losses.keys.toVector.map(lostClaims).sum
    assertEquals(exported + lost, wog.claims.size)
    val tableClaims = (rows("nodes.tsv").map(_("claim_id")) ++
      rows("relations.tsv").map(_("claim_id")) ++ rows("circumstances.tsv").map(_("claim_id")) ++
      rows("hierarchy.tsv").map(_("claim_id"))).toSet
    assertEquals(tableClaims.size, exported)
    val labelClaims = g.entities.values.map(_.label.meta.id.value) ++
      g.segments.values.flatMap(_.summary.stated.map(_.meta.id.value))
    val unexported = wog.claims.map(_.id.value).toSet -- tableClaims
    assertEquals(
      unexported,
      (labelClaims ++ g.entities.values.flatMap(_.attributes.map(_.meta.id.value)) ++
        wog.trajectory.allMeta.map(_.id.value) ++ wog.descriptors.map(_.meta.id.value) ++
        wog.hypotheses.map(_.meta.id.value)).toSet
    )
  }

  // One test per loss record, so deleting any one record fails a test that names it.
  private def lossTest(structure: String, expected: => Option[Int]): Unit =
    test(s"loss record $structure counts what the input carries") {
      assert(losses.contains(structure), s"$structure missing from the manifest")
      assertEquals(dropped(structure), expected)
    }
  lossTest("trajectory-steps", Some(wog.trajectory.steps.size))
  lossTest("boundary-beliefs", Some(wog.hierarchy.boundaryBeliefs.size))
  lossTest("feature-spaces", Some(wog.featureSpaces.size))
  lossTest("sidecars", Some(wog.sidecars.size))
  lossTest("feature-refs", Some(wog.featureRefs.size))
  lossTest("descriptors", Some(wog.descriptors.size))
  lossTest("hypotheses", Some(wog.hypotheses.size))
  lossTest("sensory-profiles", Some(wog.sensoryProfiles.values.map(_.size).sum))
  lossTest("scoped-attributes", Some(g.entities.values.map(_.attributes.size).sum))
  lossTest(
    "mentions",
    Some(g.entities.values.map(_.mentions.length).sum + g.situations.values.map(_.mentions.length).sum)
  )
  lossTest(
    "resolved-alternatives",
    Some(
      g.entities.values.map(_.label.alternatives.size).sum +
        g.segments.values.flatMap(_.summary.stated).map(_.alternatives.size).sum
    )
  )
  lossTest(
    "resolved-value-claims",
    Some(g.entities.size + g.segments.values.count(_.summary.stated.isDefined))
  )
  lossTest("claim-credence-provenance", Some(rows("nodes.tsv").size + rows("relations.tsv").size +
    rows("circumstances.tsv").size + rows("hierarchy.tsv").size))
  lossTest("evidence-extractors", Some(rows("evidence.tsv").size))
  lossTest("build-receipt", None)
  lossTest("source-text", Some(1))

  test("loss records are exactly the declared set; nothing is silently added or dropped") {
    assertEquals(
      losses.keySet,
      Set(
        "trajectory-steps", "boundary-beliefs", "feature-spaces", "sidecars", "feature-refs",
        "descriptors", "hypotheses", "sensory-profiles", "scoped-attributes", "mentions",
        "resolved-alternatives", "resolved-value-claims", "claim-credence-provenance",
        "evidence-extractors", "build-receipt", "source-text"
      )
    )
  }

  test("a supplied build receipt is counted as dropped; an absent one is not-supplied") {
    assertEquals(losses("build-receipt").hcursor.get[String]("status").toOption, Some("not-supplied"))
    val receipt = BuildReceipt(wog.storyId, wog.sourceChecksum, wog.schemaVersion, Vector.empty, 0L)
    val withReceipt = StoryModel
      .draftText(
        WarOfTheGhostsModel.atlas,
        WarOfTheGhostsModel.graph,
        WarOfTheGhostsModel.hierarchy,
        WarOfTheGhostsModel.trajectory,
        descriptors = WarOfTheGhostsModel.descriptors,
        hypotheses = WarOfTheGhostsModel.hypotheses,
        receipt = Some(receipt)
      )
      .toOption
      .flatMap(StoryValidator.validate(_).validated)
      .get
    val l = StoryModelExport.losses(withReceipt, 0, 0).find(_.structure == "build-receipt").get
    assertEquals(l.dropped, Some(1))
  }

  test("manifest binds model digest, source checksum and every file's bytes") {
    val c = manifest.hcursor
    assertEquals(c.get[String]("schemaVersion").toOption, Some(StoryModelExport.SchemaVersion))
    assertEquals(
      c.get[String]("model_digest").toOption,
      Some(StoryModelCodec.contentChecksum(wog).hex)
    )
    assertEquals(c.get[String]("source_checksum").toOption, Some(wog.sourceChecksum.hex))
    assertEquals(
      c.downField("capabilities").downField("canonical_model").get[String]("status").toOption,
      Some("unavailable")
    )
    val described = c.downField("files").as[Vector[Json]].toOption.get
    assertEquals(described.size, files.size - 1)
    described.foreach { f =>
      val name = f.hcursor.get[String]("name").toOption.get
      val bytes = files(name).getBytes(StandardCharsets.UTF_8)
      assertEquals(f.hcursor.get[String]("sha256").toOption, Some(Checksum.ofBytes(bytes).hex))
      assertEquals(f.hcursor.get[String]("bytes").toOption, Some(bytes.length.toString))
    }
    assert(rows("nodes.tsv").forall(_("model_digest") == StoryModelCodec.contentChecksum(wog).hex))
  }

  test("encoding is deterministic") {
    assertEquals(StoryModelExport.encode(wog).map(_.files), Right(bundle.files))
  }

  test("a draft is refused by type") {
    assert(compiletime.testing.typeChecks("StoryModelExport.encode(WarOfTheGhostsModel.model)"))
    assert(!compiletime.testing.typeChecks("StoryModelExport.encode(WarOfTheGhostsModel.draft)"))
  }

  // ---- publication and command line ----------------------------------------------------------
  private def withDir[A](body: Path => A): A =
    val root = Files.createTempDirectory("storymodel-export-test-")
    try body(root)
    finally
      Files
        .walk(root)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(p => { Files.deleteIfExists(p): Unit })

  test("the command exports a validated storymodel.json and refuses an existing output") {
    withDir { root =>
      val input = root.resolve("storymodel.json")
      Files.writeString(input, StoryModelCodec.encode(wog)): Unit
      val out = root.resolve("export")
      val (o1, e1) = (Vector.newBuilder[String], Vector.newBuilder[String])
      val status = StoryModelExportCli.run(List(input.toString, out.toString), o1 += _, e1 += _)
      assertEquals(status, 0, e1.result().mkString)
      val receipt = parse(o1.result().head).toOption.get.hcursor
      assertEquals(receipt.get[String]("status").toOption, Some("complete"))
      assertEquals(
        receipt.get[String]("manifest_sha256").toOption,
        Some(Checksum.ofText(Files.readString(out.resolve("manifest.json"))).hex)
      )
      assertEquals(Files.readString(out.resolve("nodes.tsv")), files("nodes.tsv"))
      val e2 = Vector.newBuilder[String]
      assertEquals(StoryModelExportCli.run(List(input.toString, out.toString), _ => (), e2 += _), 2)
      assert(e2.result().head.contains("\"OutputExists\""))
    }
  }

  test("a model that fails validation is refused and nothing is written") {
    withDir { root =>
      val json = StoryModelCodec.encode(wog)
      val before = "\"relation\":\"Before\""
      assert(json.contains(before), "fixture must carry a Before edge for this probe")
      val broken = json.replaceFirst(before, "\"relation\":\"After\"")
      val input = root.resolve("storymodel.json")
      Files.writeString(input, broken): Unit
      val out = root.resolve("export")
      val result = StoryModelExportBuild.exportModel(input, out)
      assert(
        result match
          case Left(StoryModelExportBuild.Error.NotValidated(n, laws)) => n > 0 && laws.nonEmpty
          case _                                                    => false
        ,
        result.toString
      )
      assert(!Files.exists(out))
    }
  }

  test("an interrupted payload write leaves no completion marker") {
    withDir { root =>
      val out = root.resolve("export")
      val result = StoryModelExportBuild.publish(
        bundle,
        out,
        (_, _) => throw new java.io.IOException("injected failure")
      )
      assertEquals(result, Left(StoryModelExportBuild.Error.OutputWrite))
      assert(!Files.exists(out.resolve("manifest.json")))
    }
  }

/** Writes the War of the Ghosts export used by `examples/storymodel-export`. */
object StoryModelExportExample:
  def main(args: Array[String]): Unit =
    StoryModelExportBuild.write(WarOfTheGhostsModel.model, Path.of(args(0))) match
      case Right(digest) => println(digest.hex)
      case Left(error)   => sys.error(error.toString)
