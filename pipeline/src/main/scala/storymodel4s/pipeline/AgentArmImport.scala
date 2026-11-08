package storymodel4s.pipeline

import io.circe.{Json, Printer}
import io.circe.jawn.JawnParser
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal
import storymodel4s.core.Checksum

/** Imports recorded agent (LLM) mapper outputs as a frozen-scorer scene-outcome arm.
  *
  * Why: the LLM mapper is evaluated by the same frozen scorer as every other producer, so its
  * recorded replies must enter through checked unit identity (manifest id, ordinal, text hash and
  * onset) and every manifest unit must receive an explicit outcome. A missing reply is a provider
  * failure with a reason, never a silently dropped unit; a null choice is an abstention; anything
  * else that is not a scene in range is invalid. No scores, probabilities or calibration are
  * carried: the arm records categorical choices only.
  */
object AgentArmImport:
  val ArmSchema: String = "storymodel4s.bench.scene-outcomes/v1"
  val ManifestSchema: String = "storymodel4s.bench.recall-unit-manifest/v1"
  val SceneCount: Int = 50
  private val parser = new JawnParser(maxValueSize = None, allowDuplicateKeys = false)
  private val printer = Printer.spaces2.copy(sortKeys = true)

  /** Why the import refused; each case names the violated identity or input condition. */
  enum Refusal:
    case ManifestRead, InvalidManifest, UnknownPartition, OutputExists, OutputWrite
    case ReplyRead(participant: String)
    case InvalidReply(participant: String)
    case UnknownUnit(participant: String, id: String)
    case OrdinalMismatch(participant: String, id: String)
    case DuplicateUnit(participant: String, id: String)

  /** Counts of each outcome kind written, plus the arm's content hash. */
  final case class Written(
      armSha256: String,
      participants: Int,
      labels: Int,
      abstentions: Int,
      invalid: Int,
      providerFailures: Int
  )

  private final case class ManifestUnit(
      id: String,
      ordinal: Int,
      textSha256: String,
      onset: Json
  )

  private def readJson(path: Path, refusal: Refusal): Either[Refusal, (Array[Byte], Json)] =
    try
      val bytes = Files.readAllBytes(path)
      parser.parse(new String(bytes, StandardCharsets.UTF_8)).left.map(_ => refusal).map(bytes -> _)
    catch case NonFatal(_) => Left(refusal)

  private def manifestUnits(p: Json): Option[Vector[ManifestUnit]] =
    p.hcursor
      .get[Vector[Json]]("units")
      .toOption
      .flatMap { us =>
        val parsed = us.map { u =>
          val c = u.hcursor
          for
            id <- c.get[String]("id").toOption
            ordinal <- c.get[Int]("ordinal").toOption
            text <- c.get[String]("textSha256").toOption
            onset <- c.downField("onsetSeconds").focus
          yield ManifestUnit(id, ordinal, text, onset)
        }
        Option.when(parsed.forall(_.isDefined))(parsed.flatten)
      }

  /** The outcome for one recorded reply value, by the frozen scorer's outcome grammar. */
  private[pipeline] def outcomeOf(reply: Option[Json], field: String): Json =
    reply match
      case None =>
        Json.obj(
          "kind" -> Json.fromString("provider-failure"),
          "reason" -> Json.fromString("no-reply-for-unit")
        )
      case Some(u) =>
        u.hcursor.downField(field).focus match
          case None | Some(Json.Null) => Json.obj("kind" -> Json.fromString("abstention"))
          case Some(v)                =>
            v.asNumber.flatMap(_.toInt) match
              case Some(scene) if scene >= 1 && scene <= SceneCount =>
                Json.obj("kind" -> Json.fromString("label"), "scene" -> Json.fromInt(scene))
              case _ =>
                Json.obj(
                  "kind" -> Json.fromString("invalid"),
                  "reason" -> Json.fromString("unparseable-or-out-of-range-scene")
                )

  /** Build the arm from `manifest`, replies under `replies/<participant>.json`, and `field`. */
  def run(
      manifest: Path,
      replies: Path,
      field: String,
      partition: String,
      output: Path
  ): Either[Refusal, Written] =
    for
      _ <- Either.cond(!Files.exists(output), (), Refusal.OutputExists)
      _ <- Either.cond(
        Set("development", "untouchedTest", "all").contains(partition),
        (),
        Refusal.UnknownPartition
      )
      read <- readJson(manifest, Refusal.ManifestRead)
      (manifestBytes, doc) = read
      _ <- Either.cond(
        doc.hcursor.get[String]("schema").contains(ManifestSchema),
        (),
        Refusal.InvalidManifest
      )
      sourceSha <- doc.hcursor
        .get[String]("sourceInputSha256")
        .left
        .map(_ => Refusal.InvalidManifest)
      people <- doc.hcursor.get[Vector[Json]]("participants").left.map(_ => Refusal.InvalidManifest)
      selected = people.filter(p =>
        partition == "all" || p.hcursor.get[String]("partition").contains(partition)
      )
      _ <- Either.cond(selected.nonEmpty, (), Refusal.InvalidManifest)
      built <- selected
        .foldLeft[Either[Refusal, Vector[(Json, Vector[Json])]]](Right(Vector.empty)) { (acc, p) =>
          acc.flatMap { done =>
            participant(p, replies, field).map(done :+ _)
          }
        }
      arm = Json.obj(
        "schema" -> Json.fromString(ArmSchema),
        "unitManifestSha256" -> Json.fromString(Checksum.ofBytes(manifestBytes).hex),
        "sourceInputSha256" -> Json.fromString(sourceSha),
        "identityPolicy" -> Json.fromString("manifest-id-ordinal-text-onset"),
        "producer" -> Json.obj(
          "kind" -> Json.fromString("agent-recorded-replies"),
          "field" -> Json.fromString(field),
          "authority" -> Json.fromString("model-dependent; uncalibrated; categorical choice only")
        ),
        "participants" -> Json.fromValues(built.map(_._1))
      )
      bytes = (printer.print(arm) + "\n").getBytes(StandardCharsets.UTF_8)
      _ <-
        try Right(Files.write(output, bytes, java.nio.file.StandardOpenOption.CREATE_NEW): Unit)
        catch case NonFatal(_) => Left(Refusal.OutputWrite)
    yield
      val kinds = built.flatMap(_._2).flatMap(_.hcursor.get[String]("kind").toOption)
      Written(
        Checksum.ofBytes(bytes).hex,
        built.size,
        kinds.count(_ == "label"),
        kinds.count(_ == "abstention"),
        kinds.count(_ == "invalid"),
        kinds.count(_ == "provider-failure")
      )

  private def participant(
      p: Json,
      replies: Path,
      field: String
  ): Either[Refusal, (Json, Vector[Json])] =
    for
      name <- p.hcursor.get[String]("participant").left.map(_ => Refusal.InvalidManifest)
      units <- manifestUnits(p).toRight(Refusal.InvalidManifest)
      file = replies.resolve(s"$name.json")
      recorded <-
        if !Files.exists(file) then Right(Vector.empty[Json])
        else
          readJson(file, Refusal.ReplyRead(name)).flatMap { case (_, j) =>
            j.hcursor.get[Vector[Json]]("units").left.map(_ => Refusal.InvalidReply(name))
          }
      byId <- recorded.foldLeft[Either[Refusal, Map[String, Json]]](Right(Map.empty)) { (acc, u) =>
        acc.flatMap { m =>
          u.hcursor.get[String]("id") match
            case Left(_)                     => Left(Refusal.InvalidReply(name))
            case Right(id) if m.contains(id) => Left(Refusal.DuplicateUnit(name, id))
            case Right(id)                   => Right(m.updated(id, u))
        }
      }
      known = units.map(_.id).toSet
      _ <- byId.keys.find(id => !known.contains(id)).map(Refusal.UnknownUnit(name, _)).toLeft(())
      _ <- units
        .find(mu =>
          byId.get(mu.id).exists(_.hcursor.get[Int]("index").toOption != Some(mu.ordinal))
        )
        .map(mu => Refusal.OrdinalMismatch(name, mu.id))
        .toLeft(())
    yield
      val outcomes = units.map(mu => outcomeOf(byId.get(mu.id), field))
      val rows = units.zip(outcomes).map { case (mu, o) =>
        Json.obj(
          "id" -> Json.fromString(mu.id),
          "ordinal" -> Json.fromInt(mu.ordinal),
          "textSha256" -> Json.fromString(mu.textSha256),
          "onsetSeconds" -> mu.onset,
          "outcome" -> o
        )
      }
      (
        Json.obj("participant" -> Json.fromString(name), "units" -> Json.fromValues(rows)),
        outcomes
      )

/** CLI: `agentMappingArm MANIFEST REPLIES_DIR FIELD PARTITION OUTPUT`. */
@main def agentMappingArm(
    manifest: String,
    replies: String,
    field: String,
    partition: String,
    output: String
): Unit =
  AgentArmImport.run(Path.of(manifest), Path.of(replies), field, partition, Path.of(output)) match
    case Right(w) =>
      println(
        s"arm ${w.armSha256} participants=${w.participants} labels=${w.labels} " +
          s"abstentions=${w.abstentions} invalid=${w.invalid} providerFailures=${w.providerFailures}"
      )
    case Left(r) =>
      System.err.println(s"refused: $r")
      sys.exit(2)
