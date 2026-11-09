package storymodel4s.pipeline.agentmapping

import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal

/** The text exchange an agent mapper sends: a fixed system prompt plus, per chunk of target units,
  * the whole film packet, the participant's whole recall and the target range.
  *
  * Why: replay is keyed by the exact bytes sent, so the prompts are rendered here from typed
  * inputs, and the system prompt is a versioned resource rather than a string assembled at call
  * time. The recall text is read from the mapping arm's unit table and never enters the repository.
  */
object AgentPrompts:
  /** Prompt versions with a committed system text. */
  enum Version(val resource: String):
    case V2 extends Version("/storymodel4s/agentmapping/system-v2.txt")

  /** One recall unit as the prompt shows it. */
  final case class RecallUnit(index: Int, text: String)

  /** Why the unit table was refused. */
  enum Refusal:
    case MissingColumn(name: String)
    case QuotedCell(line: Int)
    case BadIndex(line: Int)
    case Empty

  def system(version: Version): String =
    val in = getClass.getResourceAsStream(version.resource)
    try new String(in.readAllBytes(), StandardCharsets.UTF_8)
    finally in.close()

  /** Units from a tab-separated unit table with `unit` and `recallText` columns, in file order. A
    * cell that opens with a quote is refused: a CSV reader would unquote it and the prompt bytes
    * would then depend on the reader.
    */
  def units(table: String): Either[Refusal, Vector[RecallUnit]] =
    val lines = table.split('\n').toVector.map(_.stripSuffix("\r")).filter(_.nonEmpty)
    lines.headOption.toRight(Refusal.Empty).flatMap { head =>
      val h = head.split("\t", -1).toVector
      for
        ui <- Some(h.indexOf("unit")).filter(_ >= 0).toRight(Refusal.MissingColumn("unit"))
        ti <- Some(h.indexOf("recallText"))
          .filter(_ >= 0)
          .toRight(Refusal.MissingColumn("recallText"))
        out <- lines.tail.zipWithIndex.foldLeft[Either[Refusal, Vector[RecallUnit]]](
          Right(Vector.empty)
        ) { case (acc, (line, i)) =>
          acc.flatMap { done =>
            val cells = line.split("\t", -1)
            if cells.exists(_.startsWith("\"")) then Left(Refusal.QuotedCell(i + 2))
            else
              val index =
                try Some(cells(ui).toInt)
                catch case NonFatal(_) => None
              index
                .zip(cells.lift(ti))
                .map((n, t) => done :+ RecallUnit(n, t))
                .toRight(Refusal.BadIndex(i + 2))
          }
        }
      yield out
    }

  /** The user prompt for target units `lo..hi` (unit indices, inclusive). */
  def prompt(outline: String, all: Vector[RecallUnit], lo: Int, hi: Int): String =
    val recall = all.map(u => s"[${u.index}] ${u.text}").mkString("\n")
    s"FILM ANNOTATION\n$outline\n\nPARTICIPANT RECALL (all units)\n$recall\n\n" +
      s"TARGET UNITS: map every unit numbered $lo through $hi inclusive."

  /** Every chunk's prompt, `chunk` units at a time in file order. */
  def chunks(outline: String, all: Vector[RecallUnit], chunk: Int): Vector[String] =
    require(chunk > 0, "chunk must be positive")
    all
      .grouped(chunk)
      .map(g => prompt(outline, all, g.head.index, g.last.index))
      .toVector
