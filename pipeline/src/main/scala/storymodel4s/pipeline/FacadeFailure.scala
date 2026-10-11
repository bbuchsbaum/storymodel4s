package storymodel4s.pipeline

import io.circe.Json
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal
import storymodel4s.core.Checksum

/** Concrete facade failures retain actionable local context without logging input prose. */
enum FacadeFailure:
  case Io(operation: FacadeFailure.Operation, path: Path, exceptionClass: String)
  case InvalidConfiguration
  case Preparation(reason: StudyPreparation.Refusal)
  case Mapping(reason: MappingPipeline.Refusal)
  case Publication(code: String, path: Path)

  /** Paths are available to typed callers; logs use their digest because names can identify people.
    */
  def diagnostic: Json =
    val fields = this match
      case Io(operation, path, exceptionClass) =>
        Vector(
          "error" -> Json.fromString("Io"),
          "operation" -> Json.fromString(operation.toString),
          "path_sha256" -> Json.fromString(Checksum.ofText(path.toString).hex),
          "exceptionClass" -> Json.fromString(exceptionClass)
        )
      case InvalidConfiguration => Vector("error" -> Json.fromString("InvalidConfiguration"))
      case Preparation(reason)  =>
        Vector(
          "error" -> Json.fromString("Preparation"),
          "reason" -> Json.fromString(reason.productPrefix)
        )
      case Mapping(reason) =>
        Vector(
          "error" -> Json.fromString("Mapping"),
          "reason" -> Json.fromString(reason.productPrefix)
        )
      case Publication(code, path) =>
        Vector(
          "error" -> Json.fromString("Publication"),
          "reason" -> Json.fromString(code),
          "path_sha256" -> Json.fromString(Checksum.ofText(path.toString).hex)
        )
    Json.obj((Vector("status" -> Json.fromString("refused")) ++ fields)*)

object FacadeFailure:
  /** The failed boundary is explicit, rather than inferred from an exception message. */
  enum Operation:
    case Read, Write, List

  private[pipeline] def read(path: Path): Either[FacadeFailure, Array[Byte]] =
    try Right(Files.readAllBytes(path))
    catch case NonFatal(error) => Left(Io(Operation.Read, path, error.getClass.getName))
