package storyatlas4s.privatecheck
import java.nio.file.{Files, Path}
import storymodel4s.codec.*
import storymodel4s.core.Checksum
object Identity:
  def main(args: Array[String]): Unit =
    def right[A](x: Either[?, A]): A = x.fold(e => throw new IllegalArgumentException(e.toString), identity)
    val session = right(TemporalInspectionCodecs.decode(Files.readString(Path.of(args(0)))))
    val text = Vector(session.workspace.source.digest.hex, session.workspace.inventory.digest.hex, session.digest.hex, Checksum.ofText(right(TemporalInspectionCodecs.encode(session))).hex).mkString("\n")
    val _ = Files.writeString(Path.of(args(1)), text)
