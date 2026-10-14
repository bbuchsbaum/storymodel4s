package storymodel4s.pipeline

import io.circe.Json
import io.circe.jawn.JawnParser
import java.nio.file.{Files, LinkOption, Path}
import scala.util.control.NonFatal
import storymodel4s.embed.{KeyId, SensitiveKeyProvider}
import storymodel4s.embed.onnx.{OnnxSentenceArtifacts, OnnxSentenceEmbedder, OnnxSentenceModel}

/** Local resource configuration stays separate from the scientific profile and published receipts.
  */
private[pipeline] object MappingSettings:
  private val parser = new JawnParser(maxValueSize = None, allowDuplicateKeys = false)
  private final case class LocalOnnx(model: Path, tokenizer: Path, key: Path, keyId: KeyId)

  final class Resolved private[MappingSettings] (
      val config: MappingPipeline.Config,
      private val local: Option[LocalOnnx]
  ):
    override def toString: String = s"MappingSettings(${config.profile})"

    /** Checked catalog models own artifact admission; the file never supplies hashes or topology.
      */
    def execute(
        prepared: StudyPreparation.Prepared,
        model: OnnxSentenceModel = OnnxSentenceModel.AllMiniLmL6V2
    ): Either[FacadeFailure, MappingPipeline.Batch] = local match
      case None        => MappingPipeline.batch(prepared, config).left.map(FacadeFailure.Mapping(_))
      case Some(files) =>
        FacadeFailure.read(files.key).flatMap { secretBytes =>
          try
            if secretBytes.length != 32 then Left(FacadeFailure.InvalidConfiguration)
            else
              val keys = new SensitiveKeyProvider:
                def currentKeyId: KeyId = files.keyId
                def key(id: KeyId): Option[Array[Byte]] =
                  Option.when(id == files.keyId)(secretBytes.clone())
              OnnxSentenceEmbedder
                .open(model, OnnxSentenceArtifacts(files.model, files.tokenizer), keys)
                .left
                .map(error =>
                  FacadeFailure.Mapping(
                    MappingPipeline.Refusal.MissingCapability("onnx-startup:" + error.productPrefix)
                  )
                )
                .flatMap { encoder =>
                  try
                    MappingPipeline
                      .batchOnnx(prepared, config, encoder)
                      .left
                      .map(FacadeFailure.Mapping(_))
                  finally encoder.close()
                }
          finally java.util.Arrays.fill(secretBytes, 0.toByte)
        }

  private def file(root: Path, name: String): Either[FacadeFailure, Path] =
    try
      val base = root.toRealPath()
      val relative = Path.of(name)
      val path = base.resolve(relative).normalize()
      Either.cond(
        !relative.isAbsolute && path.startsWith(base) &&
          Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
          path.toRealPath().startsWith(base),
        path,
        FacadeFailure.InvalidConfiguration
      )
    catch case NonFatal(_) => Left(FacadeFailure.InvalidConfiguration)

  def decode(text: String, root: Path): Either[FacadeFailure, Resolved] =
    def invalid[A](value: Either[?, A]): Either[FacadeFailure, A] =
      value.left.map(_ => FacadeFailure.InvalidConfiguration)
    for
      json <- invalid(parser.parse(text))
      obj <- json.asObject.toRight(FacadeFailure.InvalidConfiguration)
      config <- invalid(MappingCli.config(Json.fromJsonObject(obj.remove("onnx")).noSpaces))
      local <- obj("onnx") match
        case None =>
          Either.cond(
            config.profile != MappingPipeline.OnnxProfile,
            None,
            FacadeFailure.InvalidConfiguration
          )
        case Some(settings) =>
          for
            _ <- Either.cond(
              config.profile == MappingPipeline.OnnxProfile &&
                settings.asObject.exists(
                  _.keys.toSet == Set("model", "tokenizer", "keyFile", "keyId")
                ),
              (),
              FacadeFailure.InvalidConfiguration
            )
            modelName <- invalid(settings.hcursor.get[String]("model"))
            tokenizerName <- invalid(settings.hcursor.get[String]("tokenizer"))
            keyName <- invalid(settings.hcursor.get[String]("keyFile"))
            keyNameId <- invalid(settings.hcursor.get[String]("keyId"))
            keyId <- invalid(KeyId.from(keyNameId))
            model <- file(root, modelName)
            tokenizer <- file(root, tokenizerName)
            key <- file(root, keyName)
          yield Some(LocalOnnx(model, tokenizer, key, keyId))
    yield new Resolved(config, local)

  def load(path: Path): Either[FacadeFailure, Resolved] =
    val absolute = path.toAbsolutePath
    for
      bytes <- FacadeFailure.read(absolute)
      text <- StudyPreparation.utf8(bytes).left.map(FacadeFailure.Preparation(_))
      settings <- decode(text, absolute.getParent)
    yield settings
