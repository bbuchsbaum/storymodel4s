package storymodel4s.codec

import io.circe.{Decoder, Encoder, Json}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import storymodel4s.core.*
import storymodel4s.document.*
import storymodel4s.features.*
import storymodel4s.laws.AddressGens
import storymodel4s.proposition.{Checked, PropositionChart}
import storymodel4s.story.{SegmentSummary, SummaryGap}
import AddressGens.given
import CodecGens.given
import CanonicalPrimitives.given
import CoreCodecs.given
import FeatureCodecs.given
import DerivationCodecs.given
import DerivationRecordCodec.given
import PropositionCodecs.given
import StoryCodecs.given

class CodecSuite extends ScalaCheckSuite:

  private val addressWireMutation: Gen[String] = AddressGens.address.flatMap { address =>
    val rendered = address.render
    Gen.oneOf(
      rendered,
      s"$rendered/%2f",
      s"$rendered/%41",
      s"$rendered//",
      s"$rendered/",
      s"$rendered/%２F",
      s"$rendered/%٢F",
      s"$rendered/%FF",
      s"$rendered/%C3%A9",
      s"$rendered/e%CC%81"
    )
  }

  private def lawsFor[A: Encoder: Decoder](name: String)(using org.scalacheck.Arbitrary[A]): Unit =
    property(s"$name: decode(encode(x)) == x") {
      forAll { (a: A) =>
        val decoded = Canonical.decode[A](Canonical.encode(a))
        assertEquals(decoded, Right(a))
      }
    }
    property(s"$name: canonical form is a fixed point") {
      forAll((a: A) => Canonical.isFixedPoint(a))
    }
    property(s"$name: canonical text has sorted keys and no whitespace outside strings") {
      forAll { (a: A) =>
        val text = Canonical.encode(a)
        val parsed = io.circe.parser.parse(text).toOption.get
        sortedKeys(parsed) && text == Canonical.print(parsed)
      }
    }

  private def sortedKeys(j: Json): Boolean =
    j.fold(
      true,
      _ => true,
      _ => true,
      _ => true,
      arr => arr.forall(sortedKeys),
      obj =>
        val ks = obj.keys.toVector
        ks == ks.sorted && obj.values.forall(sortedKeys)
    )

  lawsFor[Address]("Address")
  lawsFor[TextSpan]("TextSpan")
  lawsFor[SpanSet]("SpanSet")
  lawsFor[Credence]("Credence")
  lawsFor[ClaimMeta]("ClaimMeta")
  lawsFor[Resolved[String]]("Resolved")
  lawsFor[SegmentSummary]("SegmentSummary")
  lawsFor[Estimate[Double]]("Estimate[Double]")
  lawsFor[WorldTimeTransition]("WorldTimeTransition")
  lawsFor[FeatureTrack[FeatureTarget, Double]]("FeatureTrack[Double]")
  lawsFor[FeatureTrack[FeatureTarget, String]]("FeatureTrack[String]")
  lawsFor[DerivationGap]("DerivationGap")
  lawsFor[DerivationAttempt]("DerivationAttempt")
  lawsFor[SentenceCoverage]("SentenceCoverage")
  lawsFor[SummaryCoverage]("SummaryCoverage")
  lawsFor[DerivationArtifact]("DerivationArtifact")

  property("PropositionChart: decode(encode(x)) is structurally equal and Checked") {
    forAll { (ch: PropositionChart[Checked]) =>
      val back = Canonical.decode[PropositionChart[Checked]](Canonical.encode(ch))
      assertEquals(back.map(_.unchecked), Right(ch.unchecked))
      assert(Canonical.isFixedPoint(ch))
    }
  }

  property("SegmentSummary: Unsummarized(gap) survives the round trip as Unsummarized(gap)") {
    forAll(CodecGens.summaryGap) { gap =>
      val s: SegmentSummary = SegmentSummary.Unsummarized(gap)
      val text = Canonical.encode(s)
      assert(text.contains(s""""gap":"${gap.render}""""), text)
      assertEquals(Canonical.decode[SegmentSummary](text), Right(s))
    }
  }

  test("SegmentSummary: an unknown gap, a missing gap, or an unknown tag is rejected on decode") {
    assert(Canonical.decode[SegmentSummary]("""{"gap":"lost","summary":"unsummarized"}""").isLeft)
    assert(Canonical.decode[SegmentSummary]("""{"summary":"unsummarized"}""").isLeft)
    assert(Canonical.decode[SegmentSummary]("""{"summary":"absent"}""").isLeft)
  }

  property("Missingness: Missing(reason) survives the round trip as Missing(reason)") {
    forAll(CodecGens.missingReason) { r =>
      val e: Estimate[Double] = Estimate.Missing(r)
      val text = Canonical.encode(e)
      assert(!text.contains("null") && !text.contains("NaN"), text)
      assertEquals(Canonical.decode[Estimate[Double]](text), Right(e))
    }
  }

  test("Doubles: canonical hex rendering is exact for every bit pattern, including NaN and -0.0") {
    val samples = Vector(
      0.0,
      -0.0,
      1.0,
      -1.0,
      0.1,
      1e-300,
      Double.MinPositiveValue,
      Double.MaxValue,
      Double.NaN,
      Double.PositiveInfinity,
      Double.NegativeInfinity,
      3.141592653589793
    )
    samples.foreach { d =>
      val text = Canonical.encode(d)
      assert(text.startsWith("\"0x") && text.length == 20, text)
      val back = Canonical.decode[Double](text).toOption.get
      assertEquals(java.lang.Double.doubleToLongBits(back), java.lang.Double.doubleToLongBits(d))
    }
    // a plain JSON number is accepted on input but re-encodes canonically
    assertEquals(Canonical.decode[Double]("0.5"), Right(0.5))
    assertEquals(Canonical.encode(0.5), "\"0x3fe0000000000000\"")
  }

  test("Doubles: rejected forms") {
    assert(Canonical.decode[Double]("\"0x3FE0000000000000\"").isLeft, "uppercase hex")
    assert(Canonical.decode[Double]("\"0x3fe\"").isLeft, "short")
    assert(Canonical.decode[Double]("\"nope\"").isLeft)
  }

  test("Credence: calibrated probability without a model is rejected on decode") {
    val text = """{"calibrated":"0x3fe0000000000000","rawScore":"0x3fe0000000000000"}"""
    assert(Canonical.decode[Credence](text).isLeft)
  }

  test("ClaimMeta: SurfaceExplicit without spans is rejected on decode") {
    val meta = CodecFixture.meta("x", EpistemicStatus.Hypothesized, None)
    val json = summon[Encoder[ClaimMeta]](meta)
      .mapObject(_.add("status", Json.fromString("SurfaceExplicit")))
    assert(Canonical.decodeJson[ClaimMeta](json).isLeft)
  }

  test("ProviderCall: prompt-template versions round trip and blank legacy JSON is rejected") {
    val call = ProviderCall(
      "provider",
      "model",
      "version",
      Some(PromptTemplateVersion.unsafe("  prompt-v1  ")),
      Checksum.ofText("input"),
      Checksum.ofText("output"),
      Map.empty,
      None,
      cached = false
    )
    val json = summon[Encoder[ProviderCall]](call)
    assertEquals(Canonical.decodeJson[ProviderCall](json), Right(call))
    val blank = json.mapObject(_.add("promptTemplateVersion", Json.fromString("\u2003")))
    assert(Canonical.decodeJson[ProviderCall](blank).isLeft)
  }

  test("TextSpan: end before start is rejected on decode") {
    assert(Canonical.decode[TextSpan]("""{"end":1,"start":4}""").isLeft)
  }

  test("Option fields are absent keys, never null") {
    val ref = SpanRef(None, TextSpan.unsafe(0, 3))
    assertEquals(Canonical.encode(ref), """{"span":{"end":3,"start":0}}""")
    assertEquals(Canonical.decode[SpanRef]("""{"span":{"end":3,"start":0}}"""), Right(ref))
  }

  test("Sets are sorted arrays") {
    val ev = Evidence(
      EvidenceId.unsafe("e:1"),
      None,
      Set(ClaimId.unsafe("c:b"), ClaimId.unsafe("c:a")),
      CodecGens.fingerprint,
      CodecGens.stage
    )
    assert(Canonical.encode(ev).contains("""["c:a","c:b"]"""))
  }

  test("Schema version: an unknown version is rejected with UnsupportedSchema") {
    val track = CodecGens.scalarTrack.sample.get
    val json = summon[Encoder[FeatureTrack[FeatureTarget, Double]]](track)
      .mapObject(_.add("schemaVersion", Json.fromString("9.9.9")))
    val r = Canonical.decodeJson[FeatureTrack[FeatureTarget, Double]](json)
    assert(r.isLeft)
    assert(Migration.toCurrent(json).left.exists(_.isInstanceOf[CodecError.UnsupportedSchema]))
    assert(Migration.toCurrent(summon[Encoder[FeatureTrack[FeatureTarget, Double]]](track)).isRight)
  }

  test("JSON Lines: append-only ledger round trip with line-numbered failures") {
    val metas = Vector(
      CodecFixture.meta("l1", EpistemicStatus.Hypothesized, None),
      CodecFixture.meta("l2", EpistemicStatus.Hypothesized, None)
    )
    val ledger = ClaimLedger.empty.addAll(metas).toOption.get
    val text = JsonLines.claims(ledger)
    assertEquals(text.count(_ == '\n'), 2)
    assertEquals(JsonLines.readClaims(text).map(_.all), Right(metas))
    val more =
      JsonLines.append(text, Vector(CodecFixture.meta("l3", EpistemicStatus.Hypothesized, None)))
    assertEquals(JsonLines.readClaims(more).map(_.size), Right(3))
    val broken = text + "{not json}\n"
    JsonLines.readClaims(broken) match
      case Left(CodecError.Parse(m)) => assert(m.startsWith("line 3"), m)
      case other                     => fail(s"expected a line-numbered parse error, got $other")
    // duplicate claim ids are a domain error, not silently merged
    assert(JsonLines.readClaims(text + text).isLeft)
  }

  test("Address wire form is the canonical rendered string") {
    val address = Address(
      ModuleTag.unsafe("story"),
      AddressKind.unsafe("situation"),
      AddressKey.of("sit/0", "é%")
    )
    val encoded = Canonical.encode(address)
    assertEquals(encoded, "\"story/situation/sit%2F0/%C3%A9%25\"")
    assertEquals(Canonical.decode[Address](encoded), Right(address))
    assertEquals(Canonical.decode[Address](encoded).map(Canonical.encode(_)), Right(encoded))
  }

  property("Every accepted mutated Address wire string is a canonical fixed point") {
    forAll(addressWireMutation) { rendered =>
      val wire = Json.fromString(rendered).noSpaces
      assert(Canonical.decode[Address](wire).forall(a => Canonical.encode(a) == wire), rendered)
    }
  }

  test("Address decoder rejects malformed and non-canonical wire strings") {
    val rejected = Vector(
      "story/situation/sit%2f0", // lowercase escape
      "story/situation/%41", // escaped unreserved character
      "story/situation/%２F", // non-ASCII hex digit
      "story/situation/%FF", // invalid UTF-8 decoded as a replacement character
      "story/situation/sit%", // truncated escape
      "story/situation/a b", // unescaped reserved character
      "story/situation" // missing key
    )
    rejected.foreach { rendered =>
      assert(Canonical.decode[Address](Json.fromString(rendered).noSpaces).isLeft, rendered)
    }
  }
