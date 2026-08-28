package storymodel4s.codec

import io.circe.{Decoder, Encoder, Json}
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import storymodel4s.core.*
import storymodel4s.features.*
import storymodel4s.proposition.{Checked, PropositionChart}
import CodecGens.given
import CanonicalPrimitives.given
import CoreCodecs.given
import FeatureCodecs.given
import PropositionCodecs.given

class CodecSuite extends ScalaCheckSuite:

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

  lawsFor[TextSpan]("TextSpan")
  lawsFor[SpanSet]("SpanSet")
  lawsFor[Credence]("Credence")
  lawsFor[ClaimMeta]("ClaimMeta")
  lawsFor[Resolved[String]]("Resolved")
  lawsFor[Estimate[Double]]("Estimate[Double]")
  lawsFor[WorldTimeTransition]("WorldTimeTransition")
  lawsFor[FeatureTrack[FeatureTarget, Double]]("FeatureTrack[Double]")
  lawsFor[FeatureTrack[FeatureTarget, String]]("FeatureTrack[String]")

  property("PropositionChart: decode(encode(x)) is structurally equal and Checked") {
    forAll { (ch: PropositionChart[Checked]) =>
      val back = Canonical.decode[PropositionChart[Checked]](Canonical.encode(ch))
      assertEquals(back.map(_.unchecked), Right(ch.unchecked))
      assert(Canonical.isFixedPoint(ch))
    }
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

  test("Address seam placeholder round-trips as a string") {
    val a = AddressString("story/situation/sit:0")
    assertEquals(Canonical.decode[AddressString](Canonical.encode(a)), Right(a))
  }
