package storymodel4s.pipeline

import io.circe.Json
import java.nio.file.{InvalidPathException, Path}
import storymodel4s.view.ArtifactId

/** Process adapter for exporting one explicitly selected, already-checked workspace mapping. */
private[pipeline] object MappingExchangeCli:
  val ReceiptSchema = "workspace-mapping-export-receipt/v0.1"
  val Usage = "mappingExchangeExport WORKSPACE POLICY OUTPUT"

  private enum Refusal:
    case Arguments, InvalidPolicy, InvalidPath
    case Producer(error: MappingExchangeBuild.Error)

  private def request(
      args: List[String]
  ): Either[Refusal, MappingExchangeBuild.WorkspaceExportReceipt] = args match
    case input :: policy :: output :: Nil =>
      for
        id <- ArtifactId.from(policy).left.map(_ => Refusal.InvalidPolicy)
        paths <-
          try
            if input.isEmpty || output.isEmpty then Left(Refusal.InvalidPath)
            else Right((Path.of(input), Path.of(output)))
          catch case _: InvalidPathException => Left(Refusal.InvalidPath)
        result <- MappingExchangeBuild
          .exportWorkspace(paths._1, id, paths._2)
          .left
          .map(Refusal.Producer(_))
      yield result
    case _ => Left(Refusal.Arguments)

  private def failure(error: Refusal): Json =
    val (code, reason) = error match
      case Refusal.Arguments       => ("Arguments", None)
      case Refusal.InvalidPolicy   => ("InvalidPolicy", None)
      case Refusal.InvalidPath     => ("InvalidPath", None)
      case Refusal.Producer(value) =>
        value match
          case MappingExchangeBuild.Error.Workspace(value) =>
            ("WorkspaceRefused", Some(value.toString))
          case MappingExchangeBuild.Error.Exchange(_)  => ("ExchangeRefused", None)
          case MappingExchangeBuild.Error.InputRead    => ("InputRead", None)
          case MappingExchangeBuild.Error.OutputExists => ("OutputExists", None)
          case MappingExchangeBuild.Error.OutputWrite  => ("OutputWrite", None)
    val fields = Vector(
      "schemaVersion" -> Json.fromString(ReceiptSchema),
      "status" -> Json.fromString("refused"),
      "error" -> Json.fromString(code)
    ) ++ reason.map(value => "reason" -> Json.fromString(value)) ++
      Option.when(error == Refusal.Arguments)("usage" -> Json.fromString(Usage))
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
                "policy_id" -> Json.fromString(receipt.policyId.value),
                "mapping_digest" -> Json.fromString(receipt.recordDigest.hex),
                "manifest_sha256" -> Json.fromString(receipt.manifestDigest.hex)
              )
              .noSpaces
          )
          0
        case Left(error) =>
          err(failure(error).noSpaces)
          2

object mappingExchangeExport:
  def main(args: Array[String]): Unit =
    val status = MappingExchangeCli.run(args.toList, println, System.err.println)
    if status != 0 then sys.exit(status)
