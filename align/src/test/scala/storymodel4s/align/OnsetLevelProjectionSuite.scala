package storymodel4s.align

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.recall.RecallTiming.*

/** Discriminating and refusing controls for the onset-point level projection (ADR 0031 §3). */
class OnsetLevelProjectionSuite extends ScalaCheckSuite:
  import OnsetLevelProjection.*

  // w0..w5 lie in unit u; w6 and w7 lie outside every unit (unassigned).
  private val source =
    StorySource.fromText("alpha beta gamma delta epsilon zeta eta theta").toOption.get
  private val text = source.canonicalText
  private val wordSpans =
    "\\S+".r.findAllMatchIn(text).map(m => TextSpan.unsafe(m.start, m.end)).toVector
  private def graphOf(unitEnd: Int): RecallGraph[Checked] =
    val span = TextSpan.unsafe(0, wordSpans(unitEnd).endExclusive)
    val unit = RecallUnit(
      RecallUnitId.unsafe("u"),
      0,
      SpanSet.one(span),
      span.slice(text).toOption.get,
      DiscourseFunction.EpisodicAssertion,
      ExpressedUncertainty.Unmarked,
      PropositionSketch.empty,
      None
    )
    RecallGraph
      .validated(source, SurfaceAnalyzer.analyze(source), Vector(unit), RecallRelations.empty)
      .toOption
      .get
  private val graph = graphOf(5)
  private val inventory = RecallInventory
    .of(graph, wordSpans, WordIdPolicy.inputArtifact(Checksum.ofText("parser")))
    .toOption
    .get
  private val words = inventory.words
  private def clockNamed(key: String) = Clock.declared(
    Checksum.ofText("clock.csv"),
    ClockKey.unsafe(key),
    Checksum.ofText("column"),
    RecordingIdentity.Unestablished,
    Origin.Unestablished
  )
  private val clock = clockNamed("onset")
  private val basis = Basis.SourceReported(Checksum.ofText("source"))
  private def sec(n: Long): ExactRational = ExactRational.integer(n)
  private val observations = Vector(
    Observation.OnsetOnly(sec(0), basis),
    Observation.OnsetOnly(sec(1), basis),
    Observation.OnsetOnly(sec(2), basis),
    Observation.OnsetOnly(sec(3), basis),
    Observation.Interval(sec(4), sec(5), basis),
    Observation.Missing(MissingReason.BlankSourceCell),
    Observation.Interval(sec(5), sec(7), basis), // onset exactly at bin 0's end
    Observation.OnsetOnly(sec(20), basis)
  )
  private val timing = RecallTiming
    .checked(
      inventory,
      clock,
      words.zip(observations).map((w, o) => Entry(w.id, clock, o)),
      RecallTiming.Provenance(Checksum.ofText("correspondence"), RecordingLink.NotEstablished)
    )
    .toOption
    .get
  private val bins =
    Bins
      .checked(clock, Vector(sec(0) -> sec(5), sec(5) -> sec(10), sec(10) -> sec(15)))
      .toOption
      .get
  private val declaration =
    Declaration(Layer.Atom, Interpretation.Referent, Checksum.ofText("extent on part A"))

  /** Anchor over words `from` to `to` inclusive. */
  private def over(from: Int, to: Int): SpanSet =
    SpanSet.one(TextSpan.unsafe(wordSpans(from).start, wordSpans(to).endExclusive))
  private def level(from: Int, to: Int, value: Double): Sample[Double] =
    Sample(over(from, to), Level.Applicable(value))
  private def na(from: Int, to: Int): Sample[Double] =
    Sample(over(from, to), Level.NotApplicable(NotApplicableReason.Discourse))

  private def run[A, O](
      samples: Vector[Sample[A]],
      reducer: Reducer[A, O],
      nesting: Nesting = Nesting.All
  ): Projection[A, O] =
    project(
      Request
        .checked(graph, timing, declaration, nesting, bins, samples, reducer)
        .fold(e => fail(e.toString), identity)
    )

  test("distinct-reference counting: one reference over five words counts once") {
    // R covers w0..w4 (all five onsets in bin 0); S covers w4 only.
    val result = run(Vector(level(0, 4, 10.0), level(4, 4, 4.0)), Reducer.Mean)
    val bin = result.bins(0)
    assertEquals(bin.ledger.applicableReferences, 2)
    assertEquals(bin.ledger.coveredWords, 5)
    // Distinct references: (10 + 4) / 2. Counting words would give (5 * 10 + 4) / 6 = 9.
    assertEquals(bin.value, BinValue.Present(7.0))
  }

  test("Innermost and All differ on a nested frame and nucleus") {
    // Frame F covers w2..w5 (w5 has no timing); nucleus N covers w2..w4.
    val samples = Vector(level(2, 5, 10.0), level(2, 4, 2.0))
    val innermost = run(samples, Reducer.Mean, Nesting.Innermost).bins(0)
    val all = run(samples, Reducer.Mean, Nesting.All).bins(0)
    // Under Innermost every timed frame word is inside the nucleus, so only N is sampled.
    assertEquals(innermost.value, BinValue.Present(2.0))
    assertEquals(innermost.applicable, Vector(over(2, 4)))
    assertEquals(innermost.ledger.applicableReferences, 1)
    // Under All both references are distinct samples: (10 + 2) / 2.
    assertEquals(all.value, BinValue.Present(6.0))
    assertEquals(all.applicable.toSet, Set(over(2, 5), over(2, 4)))
    assertEquals(all.ledger.applicableReferences, 2)
    // The word partition is the same under both policies: w0, w1 uncovered members.
    Vector(innermost, all).foreach { b =>
      assertEquals(b.ledger.coveredWords, 3)
      assertEquals(b.ledger.uncoveredMemberWords, 2)
    }
  }

  test("not applicable never becomes zero and never enters the numeric reducer") {
    val mixed = run(Vector(level(0, 1, 8.0), na(2, 3)), Reducer.Mean).bins(0)
    assertEquals(mixed.ledger.applicableReferences, 1)
    assertEquals(mixed.ledger.notApplicableReferences, 1)
    // A zero-coded not-applicable reference would give (8 + 0) / 2 = 4.
    assertEquals(mixed.value, BinValue.Present(8.0))
    assertEquals(mixed.notApplicable, Vector(over(2, 3) -> NotApplicableReason.Discourse))
    val only = run(Vector(na(0, 4)), Reducer.Minimum).bins(0)
    assertEquals(only.value, BinValue.Absent(Absence.NotApplicableOnly))
    assertEquals(only.ledger.applicableReferences, 0)
    assertEquals(only.ledger.notApplicableReferences, 1)
  }

  test("an empty bin is no sample, not zero and not missing speech") {
    val result = run(Vector(level(0, 7, 3.0)), Reducer.Mean)
    val empty = result.bins(2)
    assertEquals(empty.value, BinValue.Absent(Absence.NoSample))
    assertEquals(empty.ledger.wordsWithOnset, 0)
    assertEquals(empty.ledger.applicableReferences, 0)
    // Bin 1 holds only the unassigned w6; uncovered words are a different absence.
    val uncovered = run(Vector(level(0, 1, 3.0)), Reducer.Mean).bins(1)
    assertEquals(uncovered.value, BinValue.Absent(Absence.UncoveredOnly))
    assertEquals(uncovered.ledger.uncoveredUnassignedWords, 1)
    assertEquals(uncovered.ledger.uncoveredMemberWords, 0)
  }

  test("missing-timing words are in the projection ledger, not in any bin") {
    // Reference M covers only w5, whose timing is missing.
    val result = run(Vector(level(5, 5, 1.0), level(0, 0, 2.0)), Reducer.Mean)
    assertEquals(result.missingTimingWords, Vector(words(5).id))
    assertEquals(result.outsideBinWords, Vector(words(7).id))
    assertEquals(result.unsampledReferences, Vector(over(5, 5)))
    assertEquals(result.wordlessReferences, Vector.empty)
    assertEquals(result.version, OnsetLevelProjection.Version)
    assertEquals(result.request.version, OnsetLevelProjection.Version)
    assert(result.bins.forall(b => !b.applicable.contains(over(5, 5))))
    assertEquals(result.bins.map(_.ledger.wordsWithOnset), Vector(5, 1, 0))
  }

  test("a categorical reducer reports shares and presence, never a mean of codes") {
    val samples = Vector(
      Sample(over(0, 1), Level.Applicable(1)),
      Sample(over(2, 2), Level.Applicable(3)),
      Sample(over(3, 4), Level.Applicable(3))
    )
    val shares = run(samples, Reducer.Share[Int]()).bins(0).value match
      case BinValue.Present(value) => value
      case other                   => fail(s"expected shares, got $other")
    assertEquals(shares.counts, Map(1 -> 1, 3 -> 2))
    assertEquals(shares.references, 3)
    assertEquals(shares.share(3), ExactRational.of(2, 3).toOption)
    // An absent category has share zero when references were sampled, not "unknown".
    assertEquals(shares.share(2), Some(ExactRational.Zero))
    assertEquals(run(samples, Reducer.Presence[Int]()).bins(0).value, BinValue.Present(Set(1, 3)))
  }

  test("a wordless reference is reported once and never sampled") {
    // A span over the single space between w0 and w1 intersects no word.
    val gap = SpanSet.one(TextSpan.unsafe(wordSpans(0).endExclusive, wordSpans(1).start))
    val result = run(Vector(Sample(gap, Level.Applicable(5.0))), Reducer.Maximum)
    assertEquals(result.wordlessReferences, Vector(gap))
    assertEquals(result.unsampledReferences, Vector.empty)
    assertEquals(result.bins(0).value, BinValue.Absent(Absence.UncoveredOnly))
  }

  test("an onset at a bin's end belongs to the next bin, or to none") {
    // w6 has its onset at 5, the end of bin 0 and the start of bin 1.
    val result = run(Vector(level(6, 6, 1.0)), Reducer.Mean)
    assertEquals(result.bins(0).ledger.wordsWithOnset, 5)
    assertEquals(result.bins(1).applicable, Vector(over(6, 6)))
    assertEquals(result.bins(1).value, BinValue.Present(1.0))
    val one = Bins.checked(clock, Vector(sec(0) -> sec(5))).toOption.get
    val alone = project(
      Request
        .checked(
          graph,
          timing,
          declaration,
          Nesting.All,
          one,
          Vector(level(6, 6, 1.0)),
          Reducer.Mean
        )
        .toOption
        .get
    )
    assertEquals(alone.outsideBinWords, Vector(words(6).id, words(7).id))
    assertEquals(alone.bins(0).value, BinValue.Absent(Absence.UncoveredOnly))
    assertEquals(alone.unsampledReferences, Vector(over(6, 6)))
  }

  test("a discontinuous anchor covers only the words its spans intersect") {
    val split = SpanSet.unsafe(SpanRef(wordSpans(0)), SpanRef(wordSpans(3)))
    val bin = run(Vector(Sample(split, Level.Applicable(4.0))), Reducer.Mean).bins(0)
    assertEquals(bin.ledger.coveredWords, 2)
    assertEquals(bin.ledger.uncoveredMemberWords, 3)
    assertEquals(bin.ledger.applicableReferences, 1)
    assertEquals(bin.value, BinValue.Present(4.0))
  }

  test("an empty span inside an anchor covers nothing and changes no identity") {
    val withEmpty =
      SpanSet.unsafe(
        SpanRef(TextSpan.unsafe(wordSpans(0).start, wordSpans(0).start)),
        SpanRef(wordSpans(2))
      )
    assertEquals(support(withEmpty), Vector(wordSpans(2)))
    val bin = run(Vector(Sample(withEmpty, Level.Applicable(1.0))), Reducer.Mean).bins(0)
    assertEquals(bin.ledger.coveredWords, 1)
  }

  test("duplicate anchor: an abutting split of the same support is one reference") {
    val split = SpanSet.unsafe(
      SpanRef(TextSpan.unsafe(wordSpans(0).start, wordSpans(1).start)),
      SpanRef(TextSpan.unsafe(wordSpans(1).start, wordSpans(1).endExclusive))
    )
    assertEquals(support(split), support(over(0, 1)))
    Vector(Nesting.All, Nesting.Innermost).foreach { nesting =>
      assertEquals(
        Request
          .checked(
            graph,
            timing,
            declaration,
            nesting,
            bins,
            Vector(level(0, 1, 1.0), Sample(split, Level.Applicable(2.0))),
            Reducer.Mean
          )
          .left
          .toOption,
        Some(Refusal.DuplicateAnchor(1))
      )
    }
  }

  test("duplicate anchor: a unit-tagged anchor is the same reference as an untagged one") {
    val tagged = SpanSet.one(SpanRef(Some(SurfaceUnitId.unsafe("s0")), over(0, 1).minSpan))
    assertNotEquals(tagged, over(0, 1))
    Vector(Nesting.All, Nesting.Innermost).foreach { nesting =>
      assertEquals(
        Request
          .checked(
            graph,
            timing,
            declaration,
            nesting,
            bins,
            Vector(Sample(tagged, Level.Applicable(2.0)), level(0, 1, 1.0)),
            Reducer.Mean
          )
          .left
          .toOption,
        Some(Refusal.DuplicateAnchor(1))
      )
    }
    // Alone, the tagged anchor covers its words like any other.
    assertEquals(
      run(Vector(Sample(tagged, Level.Applicable(2.0))), Reducer.Mean).bins(0).ledger.coveredWords,
      2
    )
  }

  test("the mean sums exact binary values before one declared rounding") {
    // A floating left fold gives 0.0 for (1e16, 1, -1e16) / 3; the exact sum is 1.
    val samples = Vector(level(0, 0, 1e16), level(1, 1, 1.0), level(2, 2, -1e16))
    assertEquals(run(samples, Reducer.Mean).bins(0).value, BinValue.Present(1.0 / 3.0))
    assertEquals(Reducer.Mean.MeanQuotient, java.math.MathContext.DECIMAL128)
  }

  // ---- refusals ----

  test("bins refuse empty, non-positive, unordered and overlapping declarations") {
    assertEquals(Bins.checked(clock, Vector.empty), Left(Bins.Refusal.Empty))
    assertEquals(
      Bins.checked(clock, Vector(sec(3) -> sec(3))),
      Left(Bins.Refusal.NonPositiveWidth(0))
    )
    assertEquals(
      Bins.checked(clock, Vector(sec(5) -> sec(10), sec(0) -> sec(5))),
      Left(Bins.Refusal.Unordered(1))
    )
    assertEquals(
      Bins.checked(clock, Vector(sec(0) -> sec(6), sec(5) -> sec(10))),
      Left(Bins.Refusal.Overlapping(1))
    )
    assert(Bins.checked(clock, Vector(sec(0) -> sec(5), sec(5) -> sec(10))).isRight)
  }

  test("a request refuses a foreign clock and a foreign transcript") {
    val foreign = Bins.checked(clockNamed("other"), Vector(sec(0) -> sec(5))).toOption.get
    assertEquals(
      Request.checked(graph, timing, declaration, Nesting.All, foreign, Vector.empty, Reducer.Mean),
      Left(Refusal.ForeignClock)
    )
    assertEquals(
      Request
        .checked(graphOf(4), timing, declaration, Nesting.All, bins, Vector.empty, Reducer.Mean),
      Left(Refusal.ForeignTranscript)
    )
  }

  test("a request refuses anchors outside the transcript, duplicates and non-finite levels") {
    def refusal(samples: Vector[Sample[Double]], nesting: Nesting = Nesting.All) =
      Request
        .checked(graph, timing, declaration, nesting, bins, samples, Reducer.Mean)
        .left
        .toOption
    val outside = Sample(SpanSet.one(TextSpan.unsafe(0, text.length + 1)), Level.Applicable(1.0))
    assertEquals(
      refusal(Vector(level(0, 0, 1.0), outside)),
      Some(Refusal.AnchorOutsideTranscript(1))
    )
    assertEquals(
      refusal(Vector(level(0, 1, 1.0), level(0, 1, 2.0))),
      Some(Refusal.DuplicateAnchor(1))
    )
    assertEquals(refusal(Vector(level(0, 1, Double.NaN))), Some(Refusal.NonFiniteValue(0)))
    assertEquals(
      refusal(Vector(level(0, 1, Double.PositiveInfinity))),
      Some(Refusal.NonFiniteValue(0))
    )
    // Two equally short anchors over one word leave Innermost undefined; All accepts them.
    val tie = Vector(level(0, 1, 1.0), level(1, 2, 2.0))
    assertEquals(refusal(tie, Nesting.Innermost), Some(Refusal.AmbiguousInnermost(words(1).id)))
    assertEquals(refusal(tie, Nesting.All), None)
  }

  // ---- invariants ----

  private val genSamples: Gen[Vector[Sample[Double]]] =
    for
      n <- Gen.choose(0, 6)
      raw <- Gen.listOfN(
        n,
        for
          a <- Gen.choose(0, 7)
          b <- Gen.choose(a, 7)
          applicable <- Gen.prob(0.8)
          v <- Gen.choose(-5, 5)
        yield if applicable then level(a, b, v.toDouble) else na(a, b)
      )
    yield raw.toVector.distinctBy(_.anchor)

  property("the result is independent of sample order") {
    forAll(genSamples, Gen.long) { (samples, seed) =>
      val shuffled = new scala.util.Random(seed).shuffle(samples)
      Prop.all(
        Vector(Nesting.All, Nesting.Innermost).map { nesting =>
          val a = Request.checked(graph, timing, declaration, nesting, bins, samples, Reducer.Mean)
          val b = Request.checked(graph, timing, declaration, nesting, bins, shuffled, Reducer.Mean)
          (a.left.toOption == b.left.toOption) :| s"refusal $nesting" &&
          (a.toOption.map(project) == b.toOption.map(project)) :| s"projection $nesting"
        }*
      )
    }
  }

  // Anchors at character (not word) offsets: several spans, empty spans and unit tags.
  private val genAnchor: Gen[SpanSet] =
    for
      n <- Gen.choose(1, 3)
      refs <- Gen.listOfN(
        n,
        for
          a <- Gen.choose(0, text.length)
          w <- Gen.choose(0, 12)
          tagged <- Gen.prob(0.3)
        yield SpanRef(
          Option.when(tagged)(SurfaceUnitId.unsafe("s")),
          TextSpan.unsafe(a, math.min(text.length, a + w))
        )
      )
    yield SpanSet.of(refs).get
  private val genRichSamples: Gen[Vector[Sample[Double]]] =
    for
      n <- Gen.choose(0, 6)
      raw <- Gen.listOfN(
        n,
        for
          anchor <- genAnchor
          applicable <- Gen.prob(0.8)
          v <- Gen.choose(-5, 5)
        yield
          if applicable then Sample(anchor, Level.Applicable(v.toDouble))
          else Sample(anchor, Level.NotApplicable(NotApplicableReason.NoSourceSupport))
      )
    yield raw.toVector.distinctBy(s => support(s.anchor))

  /** Naive oracle: every word against every raw span of every anchor. */
  private def naiveCover(samples: Vector[Sample[Double]], word: RecallWord): Vector[Int] =
    samples.indices.filter(i => samples(i).anchor.spans.exists(_.overlaps(word.span))).toVector

  property("coverage matches a naive scan of every raw span") {
    forAll(genRichSamples) { samples =>
      val ordered = Request
        .checked(graph, timing, declaration, Nesting.All, bins, samples, Reducer.Mean)
        .toOption
        .get
        .samples
      val expected = words.map(w => w.id -> naiveCover(ordered, w)).filter(_._2.nonEmpty).toMap
      coverage(inventory, ordered) == expected
    }
  }

  property("each ledger part matches an independent oracle in each bin") {
    val onsets = observations.map {
      case Observation.OnsetOnly(at, _)      => Some(at.numerator)
      case Observation.Interval(start, _, _) => Some(start.numerator)
      case Observation.Missing(_)            => None
    }
    val windows = Vector(0L -> 5L, 5L -> 10L, 10L -> 15L)
    forAll(genRichSamples) { samples =>
      Prop.all(
        Vector(Nesting.All, Nesting.Innermost).map { nesting =>
          Request
            .checked(graph, timing, declaration, nesting, bins, samples, Reducer.Mean)
            .toOption
            .map { request =>
              val ordered = request.samples
              val result = project(request)
              windows.indices.forall { b =>
                val (lo, hi) = windows(b)
                val inBin = words.indices.filter(j => onsets(j).exists(t => lo <= t && t < hi))
                val covering = inBin.map(j => naiveCover(ordered, words(j)))
                val selected = covering.flatMap { c =>
                  nesting match
                    case Nesting.All       => c
                    case Nesting.Innermost =>
                      c.sortBy(i => ordered(i).anchor.coveredLength).take(1)
                }.toSet
                def unassigned(j: Int) =
                  inventory.membership(words(j).id).isInstanceOf[WordMembership.Unassigned]
                val l = result.bins(b).ledger
                val applicable =
                  selected.filter(i => ordered(i).level.isInstanceOf[Level.Applicable[?]])
                l.coveredWords == covering.count(_.nonEmpty) &&
                l.uncoveredMemberWords ==
                  inBin.indices.count(k => covering(k).isEmpty && !unassigned(inBin(k))) &&
                  l.uncoveredUnassignedWords ==
                  inBin.indices.count(k => covering(k).isEmpty && unassigned(inBin(k))) &&
                  l.wordsWithOnset == inBin.size &&
                  l.applicableReferences == applicable.size &&
                  l.notApplicableReferences == selected.size - applicable.size &&
                  result.bins(b).applicable.toSet == applicable.map(ordered(_).anchor)
              }
            }
            .forall(identity) :| s"$nesting"
        }*
      )
    }
  }
