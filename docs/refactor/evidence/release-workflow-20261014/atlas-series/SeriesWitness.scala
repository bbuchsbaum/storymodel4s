package storyatlas4s.independentreview
import java.nio.file.{Files, Path}
import io.circe.Json
import storyatlas4s.edition.TemporalTestData.*
import storyatlas4s.shell.*
import storymodel4s.align.*
import storymodel4s.codec.*
import storymodel4s.core.Checksum
import storymodel4s.recall.*
object SeriesWitness:
  def main(args: Array[String]): Unit =
    val out = Path.of(args(0))
    val entries = timing.entries.zipWithIndex.map { (entry, i) =>
      val observed = i match
        case 2 => RecallTiming.Observation.Interval(sec(5), sec(7), RecallTiming.Basis.SourceReported(Checksum.ofText("series-review-source")))
        case 3 => RecallTiming.Observation.OnsetOnly(sec(8), RecallTiming.Basis.Estimated(Checksum.ofText("series-review-estimate")))
        case _ => entry.observation
      entry.copy(observation = observed)
    }
    val both = right(RecallTiming.checked(inventory, clock, entries, timing.provenance))
    val paired = right(TemporalInspectionCodecs.checked(workspace, Some(both)))
    val _ = Files.writeString(out.resolve("series.json"), right(TemporalInspectionCodecs.encode(paired)))
    val original = right(TemporalWorkspaceController.open(paired, reference))
    val controller = right(original.dispatch(TemporalWorkspaceAction.Allocation(TemporalQuery.Allocation.UniformIntervals)))
    val projection = controller.projection
    val full = right(TemporalQuery.Region.onTimeAxis(projection.coordinate.axis, Vector(0L -> 100L), Vector.empty))
    val partial = right(TemporalQuery.Region.onTimeAxis(projection.coordinate.axis, Vector(10L -> 15L), Vector.empty))
    val empty = right(TemporalQuery.Region.onTimeAxis(projection.coordinate.axis, Vector.empty, Vector.empty))
    val rows = projection.rows.map { row =>
      val q = right(projection.query(row.unit, full))
      val components = q.prepared.components.flatMap { component => component.location match
        case TemporalQuery.Location.Resolved(domain) => domain.availability.toOption.map { support =>
          Json.obj("weight" -> Json.fromDoubleOrNull(component.weight), "allocation" -> Json.fromString(domain.allocation.toString), "intervals" -> Json.fromValues(support.intervals.map(i => Json.arr(Json.fromLong(i.start), Json.fromLong(i.endExclusive)))))
        }
        case _ => None
      }
      val width = right(TemporalConcentration.shortestInspectionWindow(q.prepared, TemporalConcentration.Target.ShareOfAllocatedSource(0.5))).width
      Json.obj("unit" -> Json.fromString(row.unit.value), "fullMass" -> Json.fromDoubleOrNull(q.allocatedRegionMass), "partialMass" -> Json.fromDoubleOrNull(right(projection.query(row.unit, partial)).allocatedRegionMass), "emptyMass" -> Json.fromDoubleOrNull(right(projection.query(row.unit, empty)).allocatedRegionMass), "sourceAllocated" -> Json.fromDoubleOrNull(q.prepared.ledger.allocatedSource), "width" -> Json.fromString(width.toString), "components" -> Json.fromValues(components))
    }
    val _ = Files.writeString(out.resolve("provider-series.json"), Json.fromValues(rows).spaces2)
    println(s"Exported ${rows.size} synthetic timed rows and direct provider quantities.")
