package storymodel4s.pipeline.agentmapping

import io.circe.Json
import io.circe.jawn.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import storymodel4s.core.Checksum
import storymodel4s.provider.agent.{ModelReply, ModelStopReason, Recordings, ReplyEvidence, TextExchange}

/** An authored synthetic third study, unrelated to either research corpus. Its text is invented
  * test data, with no participant or licensed story content. The actual command entry points must
  * preserve zero-based, gapped unit numbers and non-contiguous scene numbers through disk replay.
  */
class StudyCliSuite extends FunSuite:
  private def right[E, A](v: Either[E, A]): A = v.fold(e => fail(e.toString), identity)
  private def str(s: String) = Json.fromString(s)
  private def read(p: Path): Json = right(parse(Files.readString(p)))

  test("a third study crosses the real replay and bundle commands with its original unit keys"):
    val dir = Files.createTempDirectory("third-study-")
    try
      val exportDir = Files.createDirectory(dir.resolve("export"))
      val recordingDir = Files.createDirectory(dir.resolve("recordings"))
      val recordings = right(Recordings.open(recordingDir))
      val numbers = Vector(0, 7, 12)
      val texts = Vector("first", "second", "third")
      val units = numbers.zip(texts).map { (n, t) =>
        Json.obj("u" -> Json.fromInt(n), "row" -> str(s"row-$n"), "text" -> str(t))
      }
      val shown = numbers.zip(texts).map((n, t) => s"[$n] $t").mkString("\n")
      val prompts = Vector(s"$shown\nmap 0-7", s"$shown\nmap 12-12")
      val chunks = Vector((0, 7), (12, 12)).zip(prompts).map { case ((lo, hi), p) =>
        Json.obj("lo" -> Json.fromInt(lo), "hi" -> Json.fromInt(hi), "prompt" -> str(p))
      }
      def write(name: String, text: String): Unit =
        Files.writeString(exportDir.resolve(name), text): Unit
      write("source.csv", "ordinal,text,part,onset,end,group,group_label,locations,characters\n" +
        "11,first source,part,0,10,10,first,,\n22,second source,part,10,20,30,second,,\n")
      write("parts.json", """[{"part":"part","start":"0","end":"20","numerator":"1","denominator":"1"}]""")
      write("session.json", Json.obj("participant" -> str("p"), "session" -> str("trial"),
        "units" -> Json.fromValues(units), "chunks" -> Json.fromValues(chunks)).noSpaces)
      write("index.json", Json.obj(
        "schema" -> str(StudyExport.Schema), "model" -> str("model"), "condition" -> str("third"),
        "system" -> str("instructions"), "systemSha256" -> str(Checksum.ofText("instructions").hex),
        "replyProtocol" -> str("coercing-open/v1"), "attempts" -> Json.fromInt(3),
        "retrySuffix" -> str(" retry {attempt}"), "unitLine" -> str("[{u}] {text}"),
        "chunkLine" -> str("map {lo}-{hi}"), "manifestSha256" -> str("0" * 64),
        "extras" -> Json.arr(str("alts"), str("rows"), str("conf")),
        "sessions" -> Json.arr(Json.obj("participant" -> str("p"), "session" -> str("trial"),
          "file" -> str("session.json"),
          "promptSha256" -> Json.fromValues(prompts.map(p => str(Checksum.ofText(p).hex)))))
      ).noSpaces)
      val first = """{"units":[{"u":0,"narrative":10,"referent":30,"alts":[[10,0.8]],"rows":[22],"conf":0.8},{"u":7,"narrative":null,"conf":null}]}"""
      val last = """{"units":[{"u":12,"narrative":30,"referent":30}]}"""
      Vector(prompts.head -> "not json", (prompts.head + " retry 1") -> first, prompts.last -> last)
        .foreach { (prompt, answer) =>
          right(recordings.write(TextExchange.key("provider", "model", "instructions", prompt),
            ModelReply("model", answer, ModelStopReason.EndTurn, ReplyEvidence.Authored)))
        }
      val replyDir = dir.resolve("replies")
      agentStudyReplay(exportDir.toString, recordingDir.toString, "provider", replyDir.toString)
      val bundleDir = dir.resolve("bundles")
      agentStudyBundle(exportDir.resolve("source.csv").toString, exportDir.resolve("parts.json").toString,
        exportDir.toString, recordingDir.toString, "provider", bundleDir.toString)
      val replies = read(replyDir.resolve("p_trial.json")).hcursor.get[Vector[Json]]("units").toOption.get
      val bundle = read(bundleDir.resolve("p_trial.bundle.json"))
      Files.createDirectories(Path.of("target")): Unit
      Files.writeString(Path.of("target/synthetic-third-study.bundle.json"), bundle.noSpaces + "\n"): Unit
      val rows = bundle.hcursor.get[Vector[Json]]("units").toOption.get
      assertEquals(rows.map(_.hcursor.get[Int]("u").toOption.get), numbers)
      assertEquals(rows.map(_.hcursor.get[String]("row").toOption.get), numbers.map(n => s"row-$n"))
      StudyBundle.Fields.foreach { field =>
        assertEquals(rows.map(_.hcursor.downField(field).downField("stated").focus.get),
          replies.map(_.hcursor.downField(field).focus.get))
      }
      assertEquals(rows.head.hcursor.downField("narrative").downField("judgement").get[Int]("scene"), Right(10))
      assertEquals(rows(1).hcursor.downField("narrative").downField("judgement").get[String]("kind"), Right("none"))
      assertEquals(rows(1).hcursor.downField("referent").downField("judgement").get[String]("kind"), Right("invalid"))
      assertEquals(rows(1).hcursor.downField("extras").focus, Some(Json.obj("conf" -> Json.Null)))
      assertEquals(rows.last.hcursor.downField("extras").focus, Some(Json.obj()))
      assertEquals(bundle.hcursor.downField("provenance").get[Int]("modelCalls"), Right(0))
    finally
      val files = Files.walk(dir)
      try files.iterator.asScala.toVector.reverse.foreach(p => Files.delete(p))
      finally files.close()
