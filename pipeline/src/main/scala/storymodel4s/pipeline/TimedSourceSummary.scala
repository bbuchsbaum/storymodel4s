package storymodel4s.pipeline

import java.nio.file.{Files, Path}
import storymodel4s.align.bridge.TimedSourceView
import storymodel4s.codec.TimedSourceArtifactCodec

/** What a timed-annotation file declares once it is checked: counts, target sets and identities.
  *
  * It exists so that an exporter's output can be compared with the exporter's own counts without
  * running a mapping. It prints collection labels and no row text.
  */
object TimedSourceReport:
  /** One `key=value` line per fact, in a fixed order. */
  def lines(view: TimedSourceView): Vector[String] =
    def ordinals(refs: Vector[storymodel4s.align.SourceNodeRef], of: Map[Int, ?]): String =
      val byRef = of.map((ordinal, ref) => ref -> ordinal)
      refs.flatMap(byRef.get).sorted.mkString(",")
    val labels = view.segments.flatMap(_.collection).distinct.sortBy(_.ordinal)
    Vector(
      s"artifact=${view.artifact.hex}",
      s"representation=${view.representation.digest.hex}",
      s"rows=${view.segments.size}",
      s"targetRows=${view.segments.count(_.target)}",
      s"groups=${view.groupRefs.size}",
      s"collections=${view.collectionRefs.size}",
      s"levels=${view.nodes.map(_.level).distinct.sorted.mkString(",")}",
      s"targetGroupCount=${view.targetGroups.size}",
      s"targetCollectionCount=${view.targetCollections.size}",
      s"targetGroups=${ordinals(view.targetGroups, view.groupRefs)}",
      s"targetCollections=${ordinals(view.targetCollections, view.collectionRefs)}",
      s"collectionLabels=${labels.map(c => s"${c.ordinal}:${c.label}").mkString("|")}"
    )

/** Prints the summary of an annotation CSV and its parts file; exit 2 means the file refused. */
@main def timedSourceSummary(csv: String, parts: String): Unit =
  val view = TimedSourceArtifactCodec
    .prepareChecked(Files.readAllBytes(Path.of(csv)), Files.readAllBytes(Path.of(parts)))
  view match
    case Right(value)  => TimedSourceReport.lines(value).foreach(println)
    case Left(refusal) =>
      System.err.println(s"refused: $refusal")
      sys.exit(2)
