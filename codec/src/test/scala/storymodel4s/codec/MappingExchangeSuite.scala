package storymodel4s.codec

import io.circe.Json
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.laws.MappingMiniature

class MappingExchangeSuite extends FunSuite:
  private lazy val record = MappingMiniature.record
  private def context(m: MappingResult): ExpectedMappingContext =
    ExpectedMappingContext(m.inventory, m.source)
  private def encoded(m: MappingResult = record): MappingExchange.Bundle =
    MappingExchange.encode(m).fold(e => fail(e.toString), identity)
  private def alter(files: Vector[(String, String)], name: String)(
      f: String => String
  ): Vector[(String, String)] =
    files.map((n, text) => n -> (if n == name then f(text) else text))

  test("all outcomes, unassigned words, exact support and original decisions roundtrip") {
    val b = encoded()
    val restored = MappingExchange.decode(b.files, context(record)).toOption.get
    assertEquals(restored.digest, record.digest)
    assertEquals(restored.outcomes.size, 8)
    assert(
      restored.inventory.words.exists(w =>
        restored.inventory
          .membership(w.id)
          .isInstanceOf[storymodel4s.recall.WordMembership.Unassigned]
      )
    )
    assertEquals(MappingExchange.encode(restored).toOption.get.files, b.files)
    val decisions = b.files.toMap.apply("decisions.tsv")
    assert(decisions.contains("p6"))
    assert(decisions.contains("provider-failure"))
    assert(decisions.contains("synthetic-authored-choice/v1"))
  }
  test("normalized mass and bound posterior keep their distinct contexts and quantities") {
    val normalized = TemporalQueryFixture.mapping
    assertEquals(
      MappingExchange.decode(encoded(normalized).files, context(normalized)).toOption.get.digest,
      normalized.digest
    )
    val posterior = MappingCodecFixture.record()
    val b = encoded(posterior)
    assertEquals(
      MappingExchange.decode(b.files, MappingCodecFixture.context()).toOption.get.digest,
      posterior.digest
    )
    assert(MappingExchange.decode(b.files, context(posterior)).isLeft)
    assert(
      MappingExchange.decode(encoded().files, context(MappingCompositionFixture.record)).isLeft
    )
  }
  test("missing, duplicate and extra files or duplicate manifest keys refuse") {
    val b = encoded()
    assert(
      MappingExchange.decode(b.files.filterNot(_._1 == "decisions.tsv"), context(record)).isLeft
    )
    assert(MappingExchange.decode(b.files :+ b.files.head, context(record)).isLeft)
    assert(MappingExchange.decode(b.files :+ ("unknown.tsv" -> ""), context(record)).isLeft)
    assert(
      MappingExchange
        .decode(
          alter(b.files, "manifest.json")(s =>
            "{\"schemaVersion\":\"mapping-exchange/v0.1\"," + s.drop(1)
          ),
          context(record)
        )
        .isLeft
    )
    assert(
      MappingExchange
        .decode(
          alter(b.files, "manifest.json")(
            _.replace("mapping-exchange/v0.1", "mapping-exchange/v9")
          ),
          context(record)
        )
        .isLeft
    )
  }
  test("rehashed table corruption still refuses against the checked record") {
    val b = encoded()
    val changed = alter(b.files, "decisions.tsv")(_.replace("p6", "omitted-failure"))
    val content = changed.toMap.apply("decisions.tsv")
    val manifest = Canonical.parse(b.manifest).toOption.get.mapObject { obj =>
      obj.add(
        "files",
        Json.fromValues(obj("files").get.asArray.get.map { f =>
          if f.hcursor.get[String]("name").contains("decisions.tsv") then
            f.mapObject(
              _.add("sha256", Json.fromString(Checksum.ofText(content).hex))
                .add("bytes", Json.fromString(content.getBytes("UTF-8").length.toString))
            )
          else f
        })
      )
    }
    assert(
      MappingExchange
        .decode(alter(changed, "manifest.json")(_ => MappingJson.print(manifest)), context(record))
        .isLeft
    )
  }
  test("structured channels retain multiline, non-BMP and escaped unmatched UTF16") {
    def channel(name: String): MappingResult =
      val outcomes = record.outcomes.map { o =>
        if o.processing != ProcessingStatus.Complete then o
        else
          val old = o.measures.raw.head
          val raw = RawScores.of(name, old.direction, old.scale, old.values, old.stage).toOption.get
          val measures = UnitMeasures.of(Vector(raw), None, None, None).toOption.get
          UnitOutcome
            .computed(
              o.unit,
              measures,
              o.links,
              DecisionBasis.of(MeasureKind.RawScore, Some(name)).toOption.get,
              o.decision.get.request,
              o.stages.get
            )
            .toOption
            .get
      }
      MappingResult
        .checked(
          record.inventory,
          record.source,
          record.policies,
          record.roles,
          record.ledger,
          outcomes
        )
        .toOption
        .get
    // Channels reside in structured JSON cells, whose ASCII escaping is lossless even for lone surrogates.
    Vector("quoted\"\tline\r\n\ud83d\ude80", "\ud800").foreach { name =>
      val m = channel(name)
      val b = encoded(m)
      assertEquals(MappingExchange.decode(b.files, context(m)).toOption.get.digest, m.digest)
    }

  }
  test(
    "direct quoted Unicode identifiers roundtrip and unmatched UTF16 refuses before byte hashing"
  ) {
    def extraTarget(label: String): MappingResult =
      val ref = SourceNodeRef.Situation(SituationId.unsafe(label))
      val nodes = MappingCompositionFixture.nodes :+ MappingCompositionFixture.nodes.head
        .copy(ref = ref, parent = None)
      val view = InMemorySourceView(nodes, Map.empty, None, 8)
      val physical = record.source.targets
        .map(t => t.ref -> t.sourceSupport)
        .toMap
        .updated(ref, SourceSupportStatus.unlocated(UnlocatedReason.NoLocusInSource))
      val source = SourceRepresentation.of(view, record.source.bundles, None, physical).toOption.get
      MappingResult
        .checked(
          record.inventory,
          source,
          record.policies,
          record.roles,
          record.ledger,
          record.outcomes
        )
        .toOption
        .get
    val m = extraTarget("quoted\"\ud83d\ude80")
    val b = encoded(m)
    assert(b.files.toMap.apply("targets.tsv").contains("quoted\"\"\ud83d\ude80"))
    assertEquals(MappingExchange.decode(b.files, context(m)).toOption.get.digest, m.digest)
    assertEquals(
      MappingExchange.encode(extraTarget("invalid\ud800")).left.toOption,
      Some(MappingExchange.Error.InvalidUtf16("targets.tsv"))
    )
  }
  test("ticks beyond binary64 exact-integer range stay decimal strings") {
    val m = MappingCompositionFixture.largeCoordinates
    val b = encoded(m)
    assert(b.files.toMap.apply("target-support.tsv").contains("9007199254740993"))
    assertEquals(MappingExchange.decode(b.files, context(m)).toOption.get.digest, m.digest)
  }

object MappingExchangeFixture:
  def main(args: Array[String]): Unit =
    require(args.isEmpty, "no arguments")
    Vector(
      "raw" -> MappingMiniature.record,
      "normalized" -> TemporalQueryFixture.mapping,
      "large-ticks" -> MappingCompositionFixture.largeCoordinates
    ).foreach { (name, record) =>
      val b = MappingExchange.encode(record).toOption.get
      println(
        "MAPPING_EXCHANGE_FIXTURE\t" + name + "\t" + MappingJson.print(
          Json.obj(b.files.map((n, text) => n -> Json.fromString(text))*)
        )
      )
    }
