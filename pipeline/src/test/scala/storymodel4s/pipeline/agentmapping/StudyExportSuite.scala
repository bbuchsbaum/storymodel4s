package storymodel4s.pipeline.agentmapping

import io.circe.Json
import io.circe.jawn.parse
import munit.FunSuite
import storymodel4s.core.Checksum

/** The export reader on invented documents: what it binds (protocol, hashes, session names) and
  * what it refuses.
  */
class StudyExportSuite extends FunSuite:
  private val system = "SYSTEM TEXT"
  private val prompt = "P\n[1] one\n[2] two\nmap 1-2"

  private def indexDoc(change: Map[String, Json] = Map.empty, sessions: Option[Json] = None): Json =
    val entry = Json.obj(
      "participant" -> Json.fromString("p-01"),
      "session" -> Json.fromString("run-01"),
      "file" -> Json.fromString("sessions/p-01_run-01.json"),
      "promptSha256" -> Json.arr(Json.fromString(Checksum.ofText(prompt).hex))
    )
    val base = Map(
      "schema" -> Json.fromString(StudyExport.Schema),
      "model" -> Json.fromString("m"),
      "condition" -> Json.fromString("annotation"),
      "system" -> Json.fromString(system),
      "systemSha256" -> Json.fromString(Checksum.ofText(system).hex),
      "replyProtocol" -> Json.fromString("strict-chunked/v1"),
      "attempts" -> Json.fromInt(3),
      "retrySuffix" -> Json.fromString(" (attempt {attempt})"),
      "extras" -> Json.arr(Json.fromString("conf")),
      "unitLine" -> Json.fromString("[{u}] {text}"),
      "manifestSha256" -> Json.fromString("ab"),
      "sessions" -> sessions.getOrElse(Json.arr(entry))
    )
    Json.fromFields(base ++ change)

  private def sessionDoc(change: String => String = identity): Json =
    parse(
      change(
        s"""{"participant": "p-01", "session": "run-01",
           "units": [{"u": 1, "row": 5, "text": "one"}, {"u": 2, "row": 6, "text": "two"}],
           "chunks": [{"lo": 1, "hi": 2, "prompt": ${Json.fromString(prompt).noSpaces}}]}"""
      )
    ).toOption.get

  private lazy val entry = StudyExport.parseIndex(indexDoc()).toOption.get.sessions.head

  test("the reply protocol is the one the export records"):
    val index = StudyExport.parseIndex(indexDoc()).toOption.get
    assertEquals(index.protocol, StudyReplay.ReplyProtocol.StrictChunked)
    assertEquals(index.protocolName, "strict-chunked/v1")
    val open = indexDoc(Map("replyProtocol" -> Json.fromString("coercing-open/v1")))
    assertEquals(
      StudyExport.parseIndex(open).map(_.protocol),
      Right(StudyReplay.ReplyProtocol.CoercingOpen)
    )

  test("an export without a known reply protocol is refused"):
    val unknown = indexDoc(Map("replyProtocol" -> Json.fromString("strict")))
    assertEquals(StudyExport.parseIndex(unknown), Left("unknown reply protocol strict"))
    val missing = Json.fromJsonObject(indexDoc().asObject.get.remove("replyProtocol"))
    assertEquals(StudyExport.parseIndex(missing), Left("index.json is malformed"))

  test("a system prompt that does not have its recorded hash is refused"):
    val edited = indexDoc(Map("system" -> Json.fromString(system + "!")))
    assertEquals(
      StudyExport.parseIndex(edited),
      Left("the system prompt does not have its recorded hash")
    )

  test("unusable replay parameters and a foreign schema are refused"):
    def bad(key: String, value: Json) = StudyExport.parseIndex(indexDoc(Map(key -> value)))
    assertEquals(
      bad("attempts", Json.fromInt(0)),
      Left("index.json has unusable replay parameters")
    )
    assertEquals(
      bad("retrySuffix", Json.fromString(" again")),
      Left("index.json has unusable replay parameters")
    )
    assertEquals(
      bad("unitLine", Json.fromString("{text}")),
      Left("index.json has unusable replay parameters")
    )
    assertEquals(bad("schema", Json.fromString("other")), Left("not a study export"))

  test("session names must be plain, and a participant-session is listed once"):
    def entryJson(participant: String) = Json.obj(
      "participant" -> Json.fromString(participant),
      "session" -> Json.fromString("run-01"),
      "file" -> Json.fromString("sessions/x.json"),
      "promptSha256" -> Json.arr()
    )
    assertEquals(
      StudyExport.parseIndex(indexDoc(sessions = Some(Json.arr(entryJson("../x"))))),
      Left("a participant or session name is not a plain name")
    )
    assertEquals(
      StudyExport.parseIndex(
        indexDoc(sessions = Some(Json.arr(entryJson("p-01"), entryJson("p-01"))))
      ),
      Left("a participant-session is listed twice")
    )

  test("a session file is read against its index entry"):
    val read = StudyExport.parseSession(sessionDoc(), entry).toOption.get
    assertEquals(
      read.units.map(u => (u.u, u.row, u.text)),
      Vector((1, Json.fromInt(5), "one"), (2, Json.fromInt(6), "two"))
    )
    assertEquals(read.chunks, Vector(StudyReplay.Chunk(1, 2, prompt)))
    assertEquals(entry.name, "p-01_run-01")

  test("a session file that names another session, or whose prompt was edited, is refused"):
    val file = entry.file
    assertEquals(
      StudyExport.parseSession(sessionDoc(_.replace("p-01", "p-02")), entry),
      Left(s"$file is not the session the index names")
    )
    assertEquals(
      StudyExport.parseSession(sessionDoc(_.replace("map 1-2", "map 1-3")), entry),
      Left(s"$file: a prompt does not have its recorded hash")
    )
    assertEquals(
      StudyExport.parseSession(sessionDoc(), entry.copy(hashes = Vector.empty)),
      Left(s"$file: a prompt does not have its recorded hash")
    )

  test("a repeated unit number, an empty unit and a unit spanning lines are refused"):
    val file = entry.file
    assertEquals(
      StudyExport.parseSession(sessionDoc(_.replace("\"u\": 2", "\"u\": 1")), entry),
      Left(s"$file: a unit number repeats")
    )
    assertEquals(
      StudyExport.parseSession(sessionDoc(_.replace("\"text\": \"two\"", "\"text\": \"\"")), entry),
      Left(s"$file: a unit is empty or spans lines")
    )
    assertEquals(
      StudyExport
        .parseSession(sessionDoc(_.replace("\"text\": \"two\"", "\"text\": \"t\\nwo\"")), entry),
      Left(s"$file: a unit is empty or spans lines")
    )
