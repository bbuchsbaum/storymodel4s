package storymodel4s.pipeline.agentmapping

import io.circe.jawn.parse
import java.nio.charset.StandardCharsets
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.core.{Checksum, SituationId}
import storymodel4s.corpus.intake.{SherlockAnnotations, TimebaseRepair}
import storymodel4s.recall.RecallUnit

/** The case-study reference conversion, its refusals and the part-by-part extent reducer, on the
  * synthetic two-part film of `AgentBundleSuite` and an invented recall. No real annotation, recall
  * or reply is used (rule 14d).
  */
class ReferenceConversionSuite extends FunSuite:
  import ProvisionalReference.{Declaration, Member, Shape}
  import ReferenceConversion.{IntervalProblem, Refusal}

  private val header =
    "Segment Number\tStart Time (s) \tEnd Time (s) \tStart TR\tEnd TR\tScene Segments\t" +
      "Scene Details - A Level \tSpace-In/Outdoor\tName - All\tName - Focus\tName - Speaking\t" +
      "Location\tCamera Angle\tMusic Presence \tWords on Screen "
  // Rows 1-4 are part A (row 3 is a zero-duration point, row 4 a scan break); rows 5-6 part B.
  private val annotation = Vector(
    header,
    "1\t0\t10\t1\t7\t1. Opening\tA man walks in the rain.\tOutdoor\tMan\tMan\t\tStreet\tLong\tNo\t",
    "2\t10\t20\t8\t14\t\tThe man knocks on a red door.\tOutdoor\tMan\t\tMan\tStreet\tMedium\tNo\t",
    "3\t20\t20\t14\t14\t\tSmash cut to black.\tIndoor\t\t\t\t\tClose\tNo\t",
    "4\t20\t30\t\t\t\tBlack screen between reels.\tIndoor\t\t\t\t\tLong\tNo\t",
    "5\t0\t5\t21\t24\t2. Cartoon\tPeople in costumes parade.\tIndoor\tSingers\t\t\tStage\tLong\tYes\t",
    "6\t5\t12\t25\t29\t\tPopcorn pops in a machine.\tIndoor\tSinger\t\tSinger\tStage\tMedium\tYes\t"
  ).mkString("\n").getBytes(StandardCharsets.UTF_8)

  private lazy val atlas: SherlockAnnotations.Atlas =
    val stream = getClass.getResourceAsStream("/agentmapping/sherlock-synthetic-repair.json")
    val json =
      try new String(stream.readAllBytes(), StandardCharsets.UTF_8)
      finally stream.close()
    val record = TimebaseRepair
      .parse(json.replace("0" * 64, Checksum.ofBytes(annotation).hex))
      .fold(e => fail(e.message), identity)
    SherlockAnnotations.parse(annotation, record).fold(e => fail(e.message), identity)

  private lazy val view =
    AgentMappingRecord.film(atlas, Checksum.ofBytes(annotation)).fold(e => fail(e), identity).view
  private lazy val rows = SourceRows.of(view)
  private def row(r: Int): SourceNodeRef =
    SourceNodeRef.Situation(SituationId.unsafe(s"timed:${view.artifact.hex}:$r"))
  private lazy val partA = TemporalSupport.Selection.Part(atlas.partABundle.identity)
  private lazy val partB = TemporalSupport.Selection.Part(atlas.partBBundle.identity)

  // Invented recall; each cell is one word, one cell holds a hyphenated word.
  private val cells = Vector(
    "A man walked in the rain the rain.",
    "Then he knocked on a well-known door.",
    "I think it was red."
  ).flatMap(_.split(' '))
  private val recallCsv =
    ("Words,Princeton,a,b,OpenNeuro,c,d" +: cells.zipWithIndex.map((w, i) =>
      s"$w,${i * 0.4},0,0,${if i == 3 then "" else (10 + i * 0.4).toString},0,0"
    )).mkString("\n").getBytes(StandardCharsets.UTF_8)

  private lazy val recalled = ReferenceParity.recall(recallCsv).fold(e => fail(e), identity)
  private lazy val graph = recalled._1
  private lazy val inventory = recalled._2
  private def unit(i: Int): RecallUnit = graph.ordered(i)

  private def reply(text: String): ReferenceAnswers.Reference =
    val item = parse(s"""{"talk":"episodic","hedge":false,"refs":[$text]}""").toOption.get
    ReferenceAnswers.answer(item, ReferenceAnswers.Schema.LocalV2, rows).refs match
      case AgentAnswers.Field.Value(Vector(r)) => r
      case other                               => fail(s"unexpected refs $other")

  private def convert(text: String, u: Int = 0) =
    ReferenceConversion.reference(unit(u), reply(text), row, rows, view.representation, partA)

  private def hypothesisOf(c: ReferenceConversion.Converted): ProvisionalReference.Hypothesis =
    c.declaration match
      case Declaration.Definite(h, _) => h
      case other                      => fail(s"not definite: $other")

  private def anchored(c: ReferenceConversion.Converted): String =
    val span = c.anchor.spans.head
    graph.transcript.canonicalText.substring(span.start, span.endExclusive)

  test("the recall keeps unit boundaries and makes one inventory word per cell") {
    assertEquals(inventory.words.size, cells.size)
    assert(graph.ordered.nonEmpty)
    val hyphenated = inventory.words(cells.indexOf("well-known"))
    assertEquals(
      graph.transcript.canonicalText.substring(hyphenated.span.start, hyphenated.span.endExclusive),
      "well-known"
    )
  }

  test("specific and extended are one single constituent over every listed row") {
    for form <- Vector("specific", "extended") do
      val c = convert(s"""{"words":"walked in","form":"$form","alts":[{"w":0.7,"iv":[[1,2]]}]}""")
        .fold(r => fail(s"$form refused $r"), identity)
      val h = hypothesisOf(c)
      assertEquals(h.shape, Shape.Single)
      assertEquals(h.constituents.toVector.map(_.toVector), Vector(Vector(row(1), row(2))))
      assertEquals(c.declaration, Declaration.Definite(h, Some(0.7)))
      assertEquals(anchored(c), "walked in")
  }

  test("summary is broad; repeated and joint keep one constituent per interval") {
    val summary =
      convert("""{"words":"A man","form":"summary","alts":[{"w":0.5,"iv":[[1,2]]}]}""").toOption.get
    assertEquals(hypothesisOf(summary).shape, Shape.Broad)
    val repeated = convert(
      """{"words":"A man","form":"repeated","alts":[{"w":0.5,"iv":[[1,1],[2,2]]}]}"""
    ).toOption.get
    assertEquals(hypothesisOf(repeated).shape, Shape.Single)
    assertEquals(hypothesisOf(repeated).constituents.length, 2)
    val joint =
      convert(
        """{"words":"A man","form":"joint","alts":[{"w":0.5,"iv":[[1,1],[5,6]]}]}"""
      ).toOption.get
    assertEquals(hypothesisOf(joint).shape, Shape.Joint)
    assertEquals(hypothesisOf(joint).constituents.toVector.map(_.length), Vector(1, 2))
  }

  test("several hypotheses are an alternative with weights as written") {
    val c = convert(
      """{"words":"man","form":"specific","alts":[{"w":0.5,"iv":[[1,1]]},{"w":0.25,"iv":[[2,2]]}]}"""
    ).fold(r => fail(s"refused $r"), identity)
    assert(c.alternativesPresent)
    c.declaration match
      case Declaration.Alternative(ms) =>
        assertEquals(ms.map(_.weight), Vector(Some(0.5), Some(0.25)))
        assert(ms.forall(_.member.isInstanceOf[Member.Located]))
      case other => fail(s"not an alternative: $other")
  }

  test("anchors normalize case and punctuation but must occur exactly once in the unit") {
    val c = convert("""{"words":"MAN, walked","form":"specific","alts":[{"w":0.5,"iv":[[1,1]]}]}""")
    assertEquals(c.map(anchored), Right("man walked"))
    assertEquals(
      convert("""{"words":"the rain","form":"specific","alts":[{"w":0.5,"iv":[[1,1]]}]}"""),
      Left(Refusal.AnchorAmbiguous(2))
    )
    assertEquals(
      convert("""{"words":"knocked","form":"specific","alts":[{"w":0.5,"iv":[[1,1]]}]}"""),
      Left(Refusal.AnchorNotFound)
    )
    assertEquals(
      convert("""{"words":" ,. ","form":"specific","alts":[{"w":0.5,"iv":[[1,1]]}]}"""),
      Left(Refusal.EmptyAnchor)
    )
  }

  test("weight, interval and meaning refusals are typed") {
    def alts(a: String) = convert(s"""{"words":"A man","form":"specific","alts":$a}""")
    assertEquals(alts("""[{"w":0,"iv":[[1,1]]}]"""), Left(Refusal.ZeroWeight(0)))
    assertEquals(alts("""[{"iv":[[1,1]]}]"""), Left(Refusal.WeightUnstated(0)))
    assertEquals(
      alts("""[{"w":0.5,"iv":[[1,99]]}]"""),
      Left(Refusal.AltsInvalid(IntervalProblem.UnknownRow))
    )
    assertEquals(
      alts("""[{"w":0.5,"iv":[[2,1]]}]"""),
      Left(Refusal.AltsInvalid(IntervalProblem.ReversedInterval))
    )
    assertEquals(
      alts("""[{"w":0.5,"iv":[[1]]}]"""),
      Left(Refusal.AltsInvalid(IntervalProblem.Malformed))
    )
    assertEquals(alts("""[]"""), Left(Refusal.NoHypotheses))
    assertEquals(alts("""[{"w":0.5,"iv":[]}]"""), Left(Refusal.NoIntervals(0)))
    assertEquals(
      alts("""[{"w":0.5,"iv":[[1,1]]},{"w":0.3,"iv":[[1,1]]}]"""),
      Left(Refusal.Library(ProvisionalReference.Refusal.DuplicateMember(0, 1)))
    )
    assertEquals(
      alts("""[{"w":0.75,"iv":[[1,1]]},{"w":0.5,"iv":[[2,2]]}]"""),
      Left(Refusal.Library(ProvisionalReference.Refusal.WeightSumExceedsOne(1.25)))
    )
    assertEquals(
      convert("""{"words":"A man","form":"joint","alts":[{"w":0.5,"iv":[[1,2]]}]}"""),
      Left(Refusal.Library(ProvisionalReference.Refusal.SingleMomentJoint))
    )
    assertEquals(
      convert("""{"form":"specific","alts":[{"w":0.5,"iv":[[1,1]]}]}"""),
      Left(Refusal.WordsUnstated)
    )
    assertEquals(
      convert("""{"words":"A man","alts":[{"w":0.5,"iv":[[1,1]]}]}"""),
      Left(Refusal.FormUnstated)
    )
  }

  test("duplicate normalized anchors and equal innermost ties are refused together") {
    val u = unit(0)
    val base = u.span.spans.head.start
    def at(a: Int, b: Int) =
      storymodel4s.core.SpanSet.one(storymodel4s.core.TextSpan.unsafe(base + a, base + b))
    val split = storymodel4s.core.SpanSet
      .of(
        Vector(
          storymodel4s.core.SpanRef(storymodel4s.core.TextSpan.unsafe(base, base + 2)),
          storymodel4s.core.SpanRef(storymodel4s.core.TextSpan.unsafe(base + 2, base + 5))
        )
      )
      .get
    // [0,5) written two ways is one reference; [6,12) and [9,15) tie over a shared word.
    val refused = ReferenceConversion.inadmissible(
      inventory,
      Vector(at(0, 5), split, at(6, 12), at(9, 15), at(0, 15))
    )
    assertEquals(refused.get(0), Some(Refusal.DuplicateAnchor))
    assertEquals(refused.get(1), Some(Refusal.DuplicateAnchor))
    assertEquals(refused.get(2), Some(Refusal.AmbiguousInnermost))
    assertEquals(refused.get(3), Some(Refusal.AmbiguousInnermost))
    assertEquals(refused.get(4), None)
  }

  private def extent(h: String, selections: Vector[TemporalSupport.Selection]) =
    val c = convert(h).fold(r => fail(s"refused $r"), identity)
    ReferenceExtent.of(view.representation, selections, c.declaration)

  test("extent is read part by part and weight-averaged over members") {
    val both = Vector(partA, partB)
    assertEquals(
      extent("""{"words":"A man","form":"specific","alts":[{"w":0.5,"iv":[[5,6]]}]}""", both)
        .map(_.seconds),
      Right(12.0)
    )
    // Members of 20 s (part A) and 5 s (part B) with weights 0.6 and 0.2: (12 + 1) / 0.8.
    assertEquals(
      extent(
        """{"words":"A man","form":"specific","alts":[{"w":0.6,"iv":[[1,2]]},{"w":0.2,"iv":[[5,5]]}]}""",
        both
      ).map(_.seconds),
      Right(16.25)
    )
    // A joint reference sums its moments, each on its own part.
    assertEquals(
      extent("""{"words":"A man","form":"joint","alts":[{"w":0.5,"iv":[[1,1],[6,6]]}]}""", both)
        .map(_.seconds),
      Right(17.0)
    )
  }

  test("an extent no part can measure is a typed unavailability, never a number") {
    val both = Vector(partA, partB)
    val crossing =
      extent("""{"words":"A man","form":"specific","alts":[{"w":0.5,"iv":[[2,5]]}]}""", both)
    crossing match
      case Left(u @ ReferenceExtent.Unavailable.Constituent(0, 0, _)) =>
        assert(u.code.contains("OffAxis"), u.code)
      case other => fail(s"expected an unavailable constituent, got $other")
    val point =
      extent("""{"words":"A man","form":"specific","alts":[{"w":0.5,"iv":[[3,3]]}]}""", both)
    point match
      case Left(u: ReferenceExtent.Unavailable.Constituent) =>
        assert(u.code.contains("PointSupport"), u.code)
      case other => fail(s"expected point support, got $other")
    // Read on part A alone, a part-B row has no measurable support.
    assert(
      extent(
        """{"words":"A man","form":"specific","alts":[{"w":0.5,"iv":[[5,5]]}]}""",
        Vector(partA)
      ).isLeft
    )
  }

  test("timing reads the OpenNeuro column and keeps a blank onset missing") {
    val (timing, recipe) =
      ReferenceParity.timing(recallCsv, graph, inventory).fold(e => fail(e), identity)
    assertEquals(recipe, storymodel4s.corpus.intake.RecallTimingIntake.Recipe)
    val observations = inventory.words.map(w => timing.entry(w.id).get.observation)
    assert(observations(3).isInstanceOf[storymodel4s.recall.RecallTiming.Observation.Missing])
    assertEquals(
      observations(0),
      storymodel4s.recall.RecallTiming.Observation.OnsetOnly(
        storymodel4s.core.ExactRational.integer(10),
        storymodel4s.recall.RecallTiming.Basis.SourceReported(Checksum.ofBytes(recallCsv))
      )
    )
    val bins = ReferenceParity.bins(timing).fold(e => fail(e), identity)
    // The last of 20 onsets is 10 + 19 * 0.4 = 17.6 s, so bins run through [16.5, 18).
    assertEquals(bins.bins.size, 12)
  }

  test("an unparsable reply field keeps its raw JSON out of the refusal") {
    val r = convert("""{"words":"A man","form":"specific","alts":"none"}""")
    assertEquals(r, Left(Refusal.AltsInvalid(IntervalProblem.Malformed)))
    assert(!r.left.toOption.get.toString.contains("none"))
  }
