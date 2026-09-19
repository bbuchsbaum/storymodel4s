package storymodel4s.laws

import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.*
import org.typelevel.discipline.Laws
import storymodel4s.core.*

/** Discipline courts for the C1 portable source contract. */
object SourceLaws extends Laws:
  val reducedRational: Gen[ExactRational] =
    for
      n <- Gen.chooseNum(-1000L, 1000L)
      d <- Gen.chooseNum(1L, 1000L)
    yield ExactRational.of(n, d).toOption.get

  val positiveTimebase: Gen[RationalTimebase] =
    for
      n <- Gen.chooseNum(1L, 1000L)
      d <- Gen.chooseNum(1L, 1000L)
    yield RationalTimebase.of(n, d).toOption.get

  given Arbitrary[ExactRational] = Arbitrary(reducedRational)
  given Arbitrary[RationalTimebase] = Arbitrary(positiveTimebase)

  def rationals: RuleSet =
    new DefaultRuleSet(
      "source.rationals",
      None,
      "reduction is unique" -> forAll { (n: Long, d: Long) =>
        (d != 0L) ==> {
          val a = ExactRational.of(n, d)
          val b = ExactRational.of(n, d)
          a == b
        }
      },
      "zero denominator refuses" -> forAll { (n: Long) =>
        ExactRational.of(n, 0L).isLeft
      },
      "timebase scale is positive" -> forAll { (tb: RationalTimebase) =>
        tb.scale.isPositive
      }
    )

  def rescale: RuleSet =
    new DefaultRuleSet(
      "source.rescale",
      None,
      "identity rescale is exact" -> forAll { (tick: Long, tb: RationalTimebase) =>
        ExactRescaler.rescaleExact(tick, tb, tb) match
          case RescaleOutcome.Exact(out, target, _)               => out == tick && target == tb
          case RescaleOutcome.Refused(RescaleRefusal.Overflow, _) =>
            tick == Long.MinValue || tick == Long.MaxValue
          case _ => false
      },
      "millisecond to microsecond is exact on small ticks" -> forAll(Gen.chooseNum(0L, 10000L)) {
        tick =>
          val us = RationalTimebase.of(1L, 1_000_000L).toOption.get
          ExactRescaler.rescaleExact(tick, RationalTimebase.Millisecond, us) match
            case RescaleOutcome.Exact(out, _, _) => out == tick * 1000L
            case _                               => false
      }
    )

  def timestamps: RuleSet =
    new DefaultRuleSet(
      "source.timestamps",
      None,
      "independent missingness is lawful" -> forAll { (p: Long, d: Long) =>
        PacketTimeFields.of(TimestampField.missing, TimestampField.missing).isRight &&
        PacketTimeFields.of(TimestampField.present(p), TimestampField.missing).isRight &&
        PacketTimeFields.of(TimestampField.missing, TimestampField.present(d)).isRight
      },
      "present PTS < DTS refuses" -> forAll { (low: Long, high: Long) =>
        (low < high) ==>
          PacketTimeFields.of(TimestampField.present(low), TimestampField.present(high)).isLeft
      },
      "fixture INT64_MIN is missing, never a present tick" -> {
        val classified = FixturePacketNormalizer
          .classify(FixturePacketWitness.synthetic(Long.MinValue, 4L, 0L))
          .toOption
          .get
        (classified.fields.pts == TimestampField.missing &&
          classified.fields.dts == TimestampField.Present(4L)) :| "INT64_MIN classified as missing"
      }
    )

  def bundles: RuleSet =
    new DefaultRuleSet(
      "source.bundles",
      None,
      "written-text identity is stable" -> forAll(CoreGens.storyText) { text =>
        StorySource.fromText(text).toOption.exists { src =>
          val a = SourceBundle.writtenText(src).toOption.get
          val b = SourceBundle.writtenText(src).toOption.get
          a.id == b.id && a.primaryAxis.kind == AxisKind.TextCharacter
        }
      },
      "empty support refuses" -> forAll(CoreGens.storyText) { text =>
        StorySource.fromText(text).toOption.exists { src =>
          val bundle = SourceBundle.writtenText(src).toOption.get
          EvidenceSupport.of(bundle, Vector.empty).isLeft
        }
      }
    )

  def supports: RuleSet =
    val film = SourceBundle
      .filmEdition(
        EditionId.unsafe("law-film"),
        Checksum.ofText("film"),
        0L,
        1000L,
        RationalTimebase.Millisecond
      )
      .toOption
      .get
    val foreign = SourceBundle
      .filmEdition(
        EditionId.unsafe("law-foreign"),
        Checksum.ofText("foreign"),
        0L,
        1000L,
        RationalTimebase.Millisecond
      )
      .toOption
      .get
    val textSource = StorySource.fromText("Alpha beta.").toOption.get
    val text = SourceBundle.writtenText(textSource).toOption.get
    def intervals(start: Long, end: Long): PlaybackIntervalSet =
      PlaybackIntervalSet.one(PlaybackInterval.on(film.primaryAxis, start, end).toOption.get)
    def anchor(start: Long, end: Long): EvidenceAnchor =
      EvidenceAnchor.MediaTime(
        film.id,
        film.streams.head.id,
        film.primaryAxis.id,
        intervals(start, end)
      )
    new DefaultRuleSet(
      "source.supports",
      None,
      "anchor kind matches stream" -> forAll(Gen.chooseNum(1, 10)) { end =>
        EvidenceSupport
          .text(film, film.streams.head.id, SpanSet.one(TextSpan.unsafe(0, end)))
          .isLeft
      },
      "MediaTime axis agrees with intervals" -> forAll(Gen.chooseNum(1L, 999L)) { end =>
        val foreignIntervals = PlaybackIntervalSet.one(
          PlaybackInterval.on(foreign.primaryAxis, 0L, end).toOption.get
        )
        EvidenceSupport
          .of(
            film,
            Vector(
              EvidenceAnchor.MediaTime(
                film.id,
                film.streams.head.id,
                film.primaryAxis.id,
                foreignIntervals
              )
            )
          )
          .isLeft
      },
      "foreign axis refuses" -> forAll(Gen.chooseNum(1L, 999L)) { end =>
        val foreignIntervals =
          PlaybackIntervalSet.one(PlaybackInterval.on(foreign.primaryAxis, 0L, end).toOption.get)
        EvidenceSupport
          .of(
            film,
            Vector(
              EvidenceAnchor
                .MediaTime(film.id, film.streams.head.id, foreign.primaryAxis.id, foreignIntervals)
            )
          )
          .isLeft
      },
      "text extent refuses overflow" -> forAll(Gen.chooseNum(1, 100)) { excess =>
        EvidenceSupport
          .text(
            text,
            text.streams.head.id,
            SpanSet.one(TextSpan.unsafe(0, textSource.canonicalText.length + excess))
          )
          .isLeft
      },
      "foreign bundle refuses" -> forAll(Gen.chooseNum(1L, 999L)) { end =>
        EvidenceSupport
          .of(
            film,
            Vector(
              EvidenceAnchor.MediaTime(
                foreign.id,
                film.streams.head.id,
                film.primaryAxis.id,
                intervals(0L, end)
              )
            )
          )
          .isLeft
      },
      "union is order-independent and idempotent" -> forAll(Gen.chooseNum(1L, 400L)) { start =>
        val anchors = Vector(
          anchor(start, start + 4L),
          anchor(start + 3L, start + 6L),
          anchor(start + 6L, start + 8L),
          anchor(start + 10L, start + 12L)
        )
        val forward =
          EvidenceSupport.of(film, anchors).toOption.get.intervalsOn(film.primaryAxis.id)
        val backward =
          EvidenceSupport.of(film, anchors.reverse).toOption.get.intervalsOn(film.primaryAxis.id)
        val expected = Vector((start, start + 8L), (start + 10L, start + 12L))
        forward == backward && forward.toOption.exists { result =>
          result.intervals.toVector.map(i => (i.start, i.endExclusive)) == expected &&
          EvidenceSupport
            .media(film, film.streams.head.id, result)
            .toOption
            .get
            .intervalsOn(film.primaryAxis.id) == forward
        }
      }
    )
