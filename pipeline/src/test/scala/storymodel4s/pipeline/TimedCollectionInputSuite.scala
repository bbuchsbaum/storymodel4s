package storymodel4s.pipeline

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite

/** A second-header annotation file enters through the ordinary `timed-annotations` source kind. The
  * films, scenes and recall are invented.
  */
class TimedCollectionInputSuite extends FunSuite:
  import StudyPreparation.*
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private val parts =
    """[{"part":"a","start":"0","end":"100","numerator":"1","denominator":"10"}]"""
  private val csv = (TimedAnnotationInput.HeaderV2.mkString(",") +: Vector(
    "1,Anna arrived.,a,10,20,1,scene 1,,Anna,1,Harbour film,",
    "2,A title card.,a,20,30,2,scene 2,,,1,Harbour film,no",
    "3,A mouse dances.,a,40,50,3,scene 3,,,2,Cartoon,no",
    "4,Bob left.,a,50,,4,scene 4,,Bob,3,Station film,"
  )).mkString("", "\n", "\n")

  private def study(): (Path, Prepared) =
    val root = Files.createTempDirectory("timed-collection-input-")
    Files.writeString(root.resolve("parts.json"), parts): Unit
    Files.writeString(root.resolve("annotations.csv"), csv): Unit
    Files.writeString(root.resolve("recall.txt"), "Anna arrived."): Unit
    val config = Config(
      SourceInput.TimedAnnotations("annotations.csv", "parts.json"),
      Vector(RecallInput("p1", "s1", "recall.txt")),
      RecallProfile
    )
    root -> right(prepare(root, config))

  test("a second-header file prepares, and its stored artifact replays the same source") {
    val (_, prepared) = study()
    val direct = right(TimedAnnotationInput.prepare(csv.getBytes(UTF_8), parts.getBytes(UTF_8)))
    assertEquals(prepared.source.representation.digest, direct.representation.digest)
    assertEquals(prepared.source.nodes.map(_.level).distinct.sorted, Vector(0, 1, 2))
    assert(prepared.outcomes.forall(_.result.isRight))
    val replayed = right(TimedAnnotationInput.decode(prepared.source.artifact))
    assertEquals(replayed, direct)
    assertEquals(replayed.targetGroups, Vector(1, 4).map(direct.groupRefs))
    assertEquals(replayed.targetCollections, Vector(1, 3).map(direct.collectionRefs))
  }

  test("the stored artifact is accepted as a checked-timed source with the same representation") {
    val (root, prepared) = study()
    Files.writeString(root.resolve("prepared-source.json"), prepared.source.artifact): Unit
    val config = Config(
      SourceInput.CheckedTimed("prepared-source.json"),
      Vector(RecallInput("p1", "s1", "recall.txt")),
      RecallProfile
    )
    val again = right(prepare(root, config))
    assertEquals(again.source.representation.digest, prepared.source.representation.digest)
  }

  test("the summary states counts, target sets and identities, and no annotation text") {
    val view = right(TimedAnnotationInput.prepare(csv.getBytes(UTF_8), parts.getBytes(UTF_8)))
    val lines = TimedSourceReport.lines(view)
    assertEquals(
      lines.drop(2),
      Vector(
        "rows=4",
        "targetRows=2",
        "groups=4",
        "collections=3",
        "levels=0,1,2",
        "targetGroupCount=2",
        "targetCollectionCount=2",
        "targetGroups=1,4",
        "targetCollections=1,3",
        "collectionLabels=1:Harbour film|2:Cartoon|3:Station film"
      )
    )
    assertEquals(
      lines.take(2),
      Vector(
        s"artifact=${view.artifact.hex}",
        s"representation=${view.representation.digest.hex}"
      )
    )
    assert(!lines.exists(_.contains("Anna")))
  }
