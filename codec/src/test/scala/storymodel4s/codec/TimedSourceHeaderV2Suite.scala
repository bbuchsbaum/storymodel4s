package storymodel4s.codec

import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import storymodel4s.align.MappingRefusal
import storymodel4s.align.bridge.TimedSourceView
import storymodel4s.codec.TimedSourceArtifactCodec.Refusal
import storymodel4s.core.{Checksum, ContentAddress, RationalTimebase, SourceBundle}

/** The second annotation header: what it adds, what it refuses, and that the first is unmoved. */
class TimedSourceHeaderV2Suite extends FunSuite:
  private val v1 = TimedSourceArtifactCodec.Header.mkString(",")
  private val v2 = TimedSourceArtifactCodec.HeaderV2.mkString(",")
  private val parts =
    """[{"part":"a","start":"0","end":"100","numerator":"1","denominator":"10"}]"""

  /** The fixture of `TimedSourceArtifactSuite`, byte for byte. */
  private val firstHeaderCsv =
    v1 + "\n1,Anna arrived.,a,10,20,1,Scene,,Anna\n2,Bob left.,a,30,40,1,Scene,,Bob\n"

  /** Scene 1 mixes a target and a non-target row; scene 2 and the whole cartoon are not targets. */
  private val rows = Vector(
    "1,Anna arrived.,a,10,20,1,scene 1,,Anna,1,Harbour film,",
    "2,A title card.,a,20,30,1,scene 1,,,1,Harbour film,no",
    "3,Another title card.,a,30,40,2,scene 2,,,1,Harbour film,no",
    "4,A mouse dances.,a,40,50,3,scene 3,,,2,Cartoon,no",
    "5,Bob left.,a,50,60,4,scene 4,,Bob,3,Station film,"
  )
  private def csv(lines: Vector[String] = rows, header: String = v2): String =
    (header +: lines).mkString("", "\n", "\n")
  private def prepare(text: String): Either[Refusal, TimedSourceView] =
    TimedSourceArtifactCodec.prepareChecked(text.getBytes(UTF_8), parts.getBytes(UTF_8))
  private def tagged(tag: String, text: String): Checksum =
    ContentAddress.digest(Vector(tag, Checksum.ofText(text).hex, Checksum.ofText(parts).hex))
  private def changed(row: Int, cell: Int, value: String): String =
    csv(rows.updated(row, rows(row).split(",", -1).updated(cell, value).mkString(",")))
  private lazy val view = prepare(csv()).fold(e => fail(e.toString), identity)

  test("a first-header file keeps the artifact and representation it had before the second") {
    val prepared = prepare(firstHeaderCsv).fold(e => fail(e.toString), identity)
    assertEquals(
      prepared.artifact.hex,
      "22355ba1dba2ad4eb184be3ed103cac78867783232f45b1675d6e2dba87b5b09"
    )
    assertEquals(
      prepared.representation.digest.hex,
      "d9a06f4cffc4f2a64aae400b12564a0cff6835e0c23ec55dc25e220b49742b44"
    )
    assertEquals(prepared.artifact, tagged("timed-annotations/v1", firstHeaderCsv))
    assertEquals(prepared.collectionRefs, Map.empty)
    assertEquals(prepared.targetGroups, prepared.groupRefs.values.toVector)
  }

  test("the second header reads collections and target marks") {
    assertEquals(view.segments.size, 5)
    assertEquals(view.groupRefs.keySet, Set(1, 2, 3, 4))
    assertEquals(view.collectionRefs.keySet, Set(1, 2, 3))
    assertEquals(view.segments.map(_.target), Vector(true, false, false, false, true))
    assertEquals(view.segments.flatMap(_.collection.map(_.label)).distinct.size, 3)
    assertEquals(view.targetGroups, Vector(1, 4).map(view.groupRefs))
    assertEquals(view.targetCollections, Vector(1, 3).map(view.collectionRefs))
    assertEquals(view.nodes.map(_.level).distinct.sorted, Vector(0, 1, 2))
  }

  test("the header a file carries is part of its identity") {
    val text = csv()
    assertEquals(view.artifact, tagged("timed-annotations/v2", text))
    assertNotEquals(view.artifact, tagged("timed-annotations/v1", text))
  }

  test("the same nine columns under the two headers are two artifacts with disjoint node ids") {
    val shared = rows.map(_.split(",", -1).take(9).mkString(","))
    val first = prepare(csv(shared, v1)).fold(e => fail(e.toString), identity)
    val second = prepare(csv(shared.map(_ + ",,,"))).fold(e => fail(e.toString), identity)
    assertEquals(second.collectionRefs, Map.empty)
    def content(v: TimedSourceView) = v.segments.map(x => (x.ordinal, x.text, x.group, x.target))
    assertEquals(content(second), content(first))
    assertNotEquals(second.artifact, first.artifact)
    assertEquals(second.nodes.map(_.ref).toSet.intersect(first.nodes.map(_.ref).toSet), Set.empty)
    assertNotEquals(second.representation.digest, first.representation.digest)
  }

  test("a part's checksum covers the collection and target cells") {
    val moved = prepare(changed(0, 11, "no")).fold(e => fail(e.toString), identity)
    assertNotEquals(moved.parts("a").identity, view.parts("a").identity)
  }

  test("a part of a second-header file is hashed under that header") {
    def cells(values: Vector[String]) = Json.fromValues(values.map(Json.fromString))
    val table = Canonical.print(
      Json.obj(
        "part" -> Json.fromString("a"),
        "header" -> cells(TimedSourceArtifactCodec.HeaderV2),
        "rows" -> Json.fromValues(rows.map(row => cells(row.split(",", -1).toVector)))
      )
    )
    val expected = SourceBundle
      .annotationTable(
        Checksum.ofText(table),
        0L,
        100L,
        RationalTimebase.of(1L, 10L).toOption.get
      )
      .toOption
      .get
    assertEquals(view.parts("a").identity, expected.identity)
  }

  test("prepared-timed-source round-trips a second-header file") {
    val encoded = TimedSourceArtifactCodec
      .encode(csv().getBytes(UTF_8), parts.getBytes(UTF_8))
      .fold(fail(_), identity)
    val decoded = TimedSourceArtifactCodec.decode(encoded).fold(fail(_), identity)
    assertEquals(decoded, view)
    assertEquals(decoded.representation.digest, view.representation.digest)
    assertEquals(decoded.targetGroups, view.targetGroups)
    assertEquals(decoded.targetCollections, view.targetCollections)
  }

  test("an unknown header refuses") {
    assertEquals(prepare(csv(rows, v2 + ",extra")), Left(Refusal.UnknownHeader))
    assertEquals(prepare(csv(rows, v2.replace("target", "Target"))), Left(Refusal.UnknownHeader))
    val two = v1 + ",collection,collection_label"
    assertEquals(prepare(csv(rows, two)), Left(Refusal.UnknownHeader))
    assertEquals(prepare(""), Left(Refusal.UnknownHeader))
  }

  test("a first-header file cannot carry the second header's cells") {
    assertEquals(prepare(csv(rows, v1)), Left(Refusal.RowWidth(1)))
  }

  test("a second-header row without the three cells refuses") {
    val short = rows.updated(2, rows(2).split(",", -1).take(9).mkString(","))
    assertEquals(prepare(csv(short)), Left(Refusal.RowWidth(3)))
  }

  test("a target cell other than empty or no refuses") {
    Vector("yes", "No", " no", "false", "0").foreach { cell =>
      assertEquals(prepare(changed(4, 11, cell)), Left(Refusal.InvalidTarget(5, cell)))
    }
  }

  test("a collection needs both an integer ordinal and a label") {
    assertEquals(prepare(changed(3, 10, "")), Left(Refusal.InvalidCollection(4)))
    assertEquals(prepare(changed(3, 10, "  ")), Left(Refusal.InvalidCollection(4)))
    assertEquals(prepare(changed(3, 9, "")), Left(Refusal.InvalidCollection(4)))
    assertEquals(prepare(changed(3, 9, "two")), Left(Refusal.InvalidCollection(4)))
  }

  test("a row the shared columns cannot read names its record") {
    assertEquals(prepare(changed(1, 0, "x")), Left(Refusal.InvalidRow(2, "invalid ordinal")))
    assertEquals(prepare(changed(1, 2, "z")), Left(Refusal.InvalidRow(2, "foreign part")))
  }

  test("relations among rows refuse as the checked source's own refusals") {
    def source(detail: String) =
      Left(Refusal.Source(MappingRefusal.InvalidValue("timed-source", detail)))
    assertEquals(
      prepare(changed(1, 9, "2").replace("2,Harbour film,no", "2,Cartoon,no")),
      source(TimedSourceView.GroupInSeveralCollections)
    )
    val unowned = rows(2).split(",", -1).updated(9, "").updated(10, "").mkString(",")
    assertEquals(prepare(csv(rows.updated(2, unowned))), source(TimedSourceView.MixedCollections))
    val ungrouped = rows(4).split(",", -1).updated(5, "").updated(6, "").mkString(",")
    assertEquals(
      prepare(csv(rows.updated(4, ungrouped))),
      source(TimedSourceView.CollectionWithoutGroup)
    )
    assertEquals(prepare(changed(4, 9, "0")), source(TimedSourceView.CollectionInvalid))
  }

  test("the string entry point reports the same refusals as text") {
    assertEquals(
      TimedSourceArtifactCodec.prepare(csv(rows, v1).getBytes(UTF_8), parts.getBytes(UTF_8)),
      Left("invalid annotation header or row")
    )
    assertEquals(
      TimedSourceArtifactCodec
        .prepare(changed(4, 11, "yes").getBytes(UTF_8), parts.getBytes(UTF_8)),
      Left("row 5: target must be empty or `no`")
    )
  }
