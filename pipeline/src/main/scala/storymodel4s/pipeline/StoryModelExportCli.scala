package storymodel4s.pipeline

import io.circe.Json
import java.nio.file.{InvalidPathException, Path}
import storymodel4s.codec.StoryModelExport

/** Process adapter for exporting one `storymodel.json` as declared-lossy tables (ADR 0020). */
private[pipeline] object StoryModelExportCli:
  val ReceiptSchema = "storymodel-export-receipt/v0.1"
  val Usage = "storyModelExport MODEL_JSON OUTPUT"

  private enum Refusal:
    case Arguments, InvalidPath
    case Producer(error: StoryModelExportBuild.Error)

  private def request(args: List[String]): Either[Refusal, StoryModelExportBuild.Receipt] =
    args match
      case input :: output :: Nil =>
        for
          paths <-
            try
              if input.isEmpty || output.isEmpty then Left(Refusal.InvalidPath)
              else Right((Path.of(input), Path.of(output)))
            catch case _: InvalidPathException => Left(Refusal.InvalidPath)
          result <- StoryModelExportBuild
            .exportModel(paths._1, paths._2)
            .left
            .map(Refusal.Producer(_))
        yield result
      case _ => Left(Refusal.Arguments)

  private def failure(error: Refusal): Json =
    import StoryModelExportBuild.Error
    val (code, detail) = error match
      case Refusal.Arguments       => ("Arguments", Vector.empty)
      case Refusal.InvalidPath     => ("InvalidPath", Vector.empty)
      case Refusal.Producer(value) =>
        value match
          case Error.InputRead => ("InputRead", Vector.empty)
          case Error.Decode(e) => ("Decode", Vector("reason" -> Json.fromString(e.message)))
          case Error.NotValidated(errors, laws) =>
            (
              "NotValidated",
              Vector(
                "errors" -> Json.fromString(errors.toString),
                "laws" -> Json.fromValues(laws.map(Json.fromString))
              )
            )
          case Error.Export(StoryModelExport.Error.PlaybackSupport(claim)) =>
            ("PlaybackSupport", Vector("claim" -> Json.fromString(claim)))
          case Error.Export(StoryModelExport.Error.InvalidUtf16(file)) =>
            ("InvalidUtf16", Vector("file" -> Json.fromString(file)))
          case Error.OutputExists => ("OutputExists", Vector.empty)
          case Error.OutputWrite  => ("OutputWrite", Vector.empty)
    val fields = Vector(
      "schemaVersion" -> Json.fromString(ReceiptSchema),
      "status" -> Json.fromString("refused"),
      "error" -> Json.fromString(code)
    ) ++ detail ++ Option.when(error == Refusal.Arguments)("usage" -> Json.fromString(Usage))
    Json.obj(fields*)

  /** Returns the process status; output callbacks make stdout/stderr separation testable. */
  def run(args: List[String], out: String => Unit, err: String => Unit): Int =
    if args == List("--help") then
      out(Usage)
      0
    else
      request(args) match
        case Right(receipt) =>
          out(
            Json
              .obj(
                "schemaVersion" -> Json.fromString(ReceiptSchema),
                "status" -> Json.fromString("complete"),
                "input_sha256" -> Json.fromString(receipt.inputDigest.hex),
                "model_digest" -> Json.fromString(receipt.modelDigest.hex),
                "manifest_sha256" -> Json.fromString(receipt.manifestDigest.hex)
              )
              .noSpaces
          )
          0
        case Left(error) =>
          err(failure(error).noSpaces)
          2

object storyModelExport:
  def main(args: Array[String]): Unit =
    val status = StoryModelExportCli.run(args.toList, println, System.err.println)
    if status != 0 then sys.exit(status)
