package storymodel4s.acquire

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import storymodel4s.core.*

class CacheSuite extends ScalaCheckSuite:
  private val text: Gen[String] = Gen.alphaNumStr
  private val key = (i: String, s: String, c: String, f: String) =>
    StageCacheKey.of(Checksum.ofText(i), s, Checksum.ofText(c), Fingerprint.unsafe(s"f$f"))

  property("identical inputs give identical keys"):
    forAll(text, text, text, text) { (i, s, c, f) => key(i, s, c, f) == key(i, s, c, f) }

  property("any single field change gives a different key"):
    forAll(text, text, text, text, Gen.alphaNumStr.suchThat(_.nonEmpty)) { (i, s, c, f, d) =>
      val base = key(i, s, c, f)
      key(i + d, s, c, f) != base && key(i, s + d, c, f) != base &&
      key(i, s, c + d, f) != base && key(i, s, c, f + d) != base
    }

  test("stage-local prompt packages, parameters, and seed change the key"):
    val base = key("i", "s", "c", "f")
    val local = StageLocalConfig(Vector(Fixtures.promptRef), Map("temperature" -> "0"), Some(1L))
    val withLocal = StageCacheKey.of(
      Checksum.ofText("i"),
      "s",
      Checksum.ofText("c"),
      Fingerprint.unsafe("ff"),
      local
    )
    assertNotEquals(withLocal, base)
    val bumped = local.copy(promptPackages =
      Vector(Fixtures.manifest.replace(version = "1.0.1").toOption.get.ref)
    )
    val withBumped = StageCacheKey.of(
      Checksum.ofText("i"),
      "s",
      Checksum.ofText("c"),
      Fingerprint.unsafe("ff"),
      local = bumped
    )
    assertNotEquals(withBumped, withLocal)
    val seeded = local.copy(seed = Some(2L))
    assertNotEquals(
      StageCacheKey
        .of(Checksum.ofText("i"), "s", Checksum.ofText("c"), Fingerprint.unsafe("ff"), seeded),
      withLocal
    )
    val reordered = local.copy(providerParams = Map("temperature" -> "0"))
    assertEquals(
      StageCacheKey
        .of(Checksum.ofText("i"), "s", Checksum.ofText("c"), Fingerprint.unsafe("ff"), reordered),
      withLocal
    )
    assertEquals(
      StageCacheKey.of(
        Checksum.ofText("i"),
        "s",
        Checksum.ofText("c"),
        Fingerprint.unsafe("ff"),
        StageLocalConfig.empty
      ),
      base
    )

  test("receipt builder records stages in order and layer coverage"):
    val story = StoryId.unsafe("story-1")
    val src = Checksum.ofText("source")
    val r1 = StageRecord(
      StageId.unsafe("surface"),
      key("a", "1", "c", "x"),
      Vector(src),
      Vector(Checksum.ofText("atlas")),
      Vector.empty,
      cached = false
    )
    val r2 = r1.copy(stage = StageId.unsafe("local"), outputs = Vector(Checksum.ofText("charts")))
    val layer = LayerId.unsafe("goals")
    val ext = BuildReceiptBuilder
      .start(story, src, "0.1")
      .record(r1)
      .record(r2)
      .cover(layer, LayerCoverage.NotAttempted)
      .build(createdAtEpochMillis = 42L)
    assertEquals(ext.receipt.stages.map(_._1), Vector(r1.stage, r2.stage))
    assertEquals(ext.receipt.stages.map(_._2), Vector(r1.outputChecksum, r2.outputChecksum))
    assertEquals(ext.layerCoverage(layer), LayerCoverage.NotAttempted)
    // the content checksum ignores the timestamp
    val again = BuildReceiptBuilder.start(story, src, "0.1").record(r1).record(r2).build(43L)
    assertEquals(ext.receipt.contentChecksum, again.receipt.contentChecksum)
