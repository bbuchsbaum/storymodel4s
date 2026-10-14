package storyatlas4s.privatecheck
import java.nio.file.{Files, Path}
import io.circe.Json
import storyatlas4s.shell.*
import storymodel4s.codec.*
import storymodel4s.align.*
import storymodel4s.view.*
object Readback:
  def main(args: Array[String]): Unit =
    def right[A](x: Either[?, A]): A = x.fold(e => throw new IllegalArgumentException(e.toString), identity)
    val session = right(TemporalInspectionCodecs.decode(Files.readString(Path.of(args(0)))))
    val controller = right(TemporalWorkspaceController.open(session, ArtifactId.unsafe("reference")))
    val uniform = right(controller.dispatch(TemporalWorkspaceAction.Allocation(TemporalQuery.Allocation.UniformIntervals)))
    val projection = uniform.projection
    val rows = projection.rows.flatMap { row =>
      projection.regions.zipWithIndex.map { (region, index) =>
        val geometry = region.geometry.get
        val grid = row.grid.toOption
        Json.obj(
          "unit" -> Json.fromString(row.unit.value),
          "bin" -> Json.fromString((geometry.intervals.map(i => s"${i.start}:${i.endExclusive}") ++ geometry.points.map(p => s"@${p.at}")).mkString(", ")),
          "allocated" -> grid.flatMap(_.allocatedAt(index)).fold(Json.Null)(Json.fromDoubleOrNull),
          "lower" -> grid.flatMap(_.boundsAt(index)).fold(Json.Null)(v => Json.fromDoubleOrNull(v._1)),
          "upper" -> grid.flatMap(_.boundsAt(index)).fold(Json.Null)(v => Json.fromDoubleOrNull(v._2))
        )
      }
    }
    val text = Json.obj("units" -> Json.fromInt(session.workspace.inventory.units.size), "words" -> Json.fromInt(session.workspace.inventory.words.size), "cells" -> Json.fromValues(rows)).noSpaces
    val _ = Files.writeString(Path.of(args(1)), text)
    println(s"units=${projection.rows.size}; cells=${rows.size}")
