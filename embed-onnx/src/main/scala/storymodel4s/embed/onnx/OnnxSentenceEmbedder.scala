package storymodel4s.embed.onnx

import java.nio.LongBuffer
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.{Map as JMap, Set as JSet}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import ai.djl.huggingface.tokenizers.{Encoding, HuggingFaceTokenizer}
import ai.onnxruntime.{OnnxJavaType, OnnxTensor, OrtEnvironment, OrtSession, TensorInfo}
import cats.Id

import storymodel4s.core.{Checksum, ProviderCall}
import storymodel4s.embed.*
import storymodel4s.features.Estimate

/** A sentence encoder admitted by the first local ONNX adapter slice.
  *
  * Why construction is closed: model topology, pooling, checksums, and context length are one
  * reviewed contract. A new encoder must add an explicit catalog entry and golden evidence instead
  * of borrowing MiniLM's execution assumptions by name.
  */
final class OnnxSentenceModel private[onnx] (
    val modelId: String,
    val revision: String,
    val modelChecksum: Checksum,
    val tokenizerChecksum: Checksum,
    val dimension: Dimension,
    val maxTokens: Int,
    val license: String
):
  def coordinate: String = s"$modelId@$revision"

object OnnxSentenceModel:
  /** Apache-2.0 MiniLM sentence encoder pinned to one Hugging Face revision and artifact pair. */
  val AllMiniLmL6V2: OnnxSentenceModel =
    new OnnxSentenceModel(
      "sentence-transformers/all-MiniLM-L6-v2",
      "1110a243fdf4706b3f48f1d95db1a4f5529b4d41",
      Checksum.unsafe("6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452"),
      Checksum.unsafe("be50c3628f2bf5bb5e3a7f17b1f74611b2561a3a27eeab05e5aa30f411572037"),
      Dimension.unsafe(384),
      256,
      "Apache-2.0"
    )

  private[onnx] def testFixture(
      modelChecksum: Checksum,
      tokenizerChecksum: Checksum,
      dimension: Int,
      maxTokens: Int
  ): OnnxSentenceModel =
    new OnnxSentenceModel(
      "storymodel4s/test-sentence-encoder",
      "fixture-v1",
      modelChecksum,
      tokenizerChecksum,
      Dimension.unsafe(dimension),
      maxTokens,
      "test-only"
    )

/** Exact local files supplied to a pinned [[OnnxSentenceModel]].
  *
  * Why paths are separate from model identity: moving identical bytes must not change an embedding
  * geometry, while substituting different bytes must fail before native code loads them.
  */
final case class OnnxSentenceArtifacts(model: Path, tokenizer: Path)

/** Safe startup failures for the local ONNX adapter; messages name artifact roles, never paths or
  * source text.
  */
enum OnnxEmbedderError:
  case TelemetryNotDisabled
  case MissingArtifact(kind: OnnxArtifactKind)
  case ArtifactChecksumMismatch(kind: OnnxArtifactKind, expected: Checksum, actual: Checksum)
  case InvalidModelSchema(code: String)
  case LoadFailure(stage: OnnxLoadStage)

  def message: String = this match
    case TelemetryNotDisabled =>
      "ORT_DISABLE_TELEMETRY=1 must be set before the JVM starts"
    case MissingArtifact(kind)                            => s"missing ${kind.render} artifact"
    case ArtifactChecksumMismatch(kind, expected, actual) =>
      s"${kind.render} checksum mismatch: expected ${expected.hex}, got ${actual.hex}"
    case InvalidModelSchema(code) => s"invalid ONNX model schema: $code"
    case LoadFailure(stage)       => s"failed to load ${stage.render}"

/** Artifact roles used in typed startup evidence. */
enum OnnxArtifactKind:
  case Model
  case Tokenizer

  def render: String = this match
    case Model     => "model"
    case Tokenizer => "tokenizer"

/** Startup stages exposed without exception messages or local paths. */
enum OnnxLoadStage:
  case Checksum
  case Tokenizer
  case Runtime
  case Session

  def render: String = this match
    case Checksum  => "artifact checksum"
    case Tokenizer => "tokenizer"
    case Runtime   => "ONNX runtime"
    case Session   => "ONNX session"

/** JVM-local sentence encoder behind the portable [[Embedder]] contract.
  *
  * Why it owns native resources: callers get one checked model/tokenizer pair, one fingerprint, and
  * one close boundary; no ONNX or DJL type crosses into a portable module.
  */
final class OnnxSentenceEmbedder private (
    val model: OnnxSentenceModel,
    val info: EmbedderInfo,
    val spaces: Vector[EmbeddingSpace],
    val runtimeIdentity: String,
    tokenizer: HuggingFaceTokenizer,
    environment: OrtEnvironment,
    session: OrtSession,
    keys: SensitiveKeyProvider
) extends Embedder[Id],
      AutoCloseable:
  import OnnxSentenceEmbedder.*

  private final case class Admitted(
      request: EmbedRequest,
      item: ItemDigest,
      encoding: Either[ExecutionFailure, Encoding]
  )

  def embed(batch: EmbedBatch): BatchResult = this.synchronized {
    val nonPublic = batch.itemSensitivity.exists { case (_, sensitivity) =>
      !ReceiptDigest.plainAdmissible.contains(sensitivity)
    }
    val keyId = keys.currentKeyId
    SensitiveKeySnapshot.capture(keyId, keys) match
      case Right(snapshot) => embedWith(batch, snapshot.provider, Some(snapshot))
      case Left(error @ EmbedError.InvalidKey(_)) if nonPublic =>
        BatchResult.invalidKey(batch, keyId, error)
      case Left(_) if nonPublic => BatchResult.keyUnavailable(batch, keyId)
      case Left(_)              => embedWith(batch, SensitiveKeyProvider.none, None)
  }

  private def embedWith(
      batch: EmbedBatch,
      batchKeys: SensitiveKeyProvider,
      snapshot: Option[SensitiveKeySnapshot]
  ): BatchResult =
    val policy = Vector.newBuilder[PolicyDecision]
    val admitted = Vector.newBuilder[Admitted]
    val immediate = scala.collection.mutable.Map.empty[RequestId, EmbedOutcome]

    batch.requests.foreach { request =>
      Embedder.preflight(info, request) match
        case Left(failure) =>
          failure match
            case ExecutionFailure.PolicyDenied(decision) => policy += decision
            case ExecutionFailure.LocalOnly(decision)    => policy += decision
            case _                                       => ()
          immediate += request.id -> EmbedOutcome(request.id, request.space, Left(failure))
        case Right(()) =>
          space(request.space) match
            case None =>
              immediate += request.id -> EmbedOutcome(
                request.id,
                request.space,
                Left(ExecutionFailure.Invalid(EmbedError.UnknownSpace(request.space.value)))
              )
            case Some(space) =>
              val sensitivity = request.payload.sensitivityOf
              val material = Material.render(space, request.payload)
              val item = for
                digest <- ReceiptDigest.of(sensitivity, material, batchKeys)
                result <- ItemDigest.of(request.id, sensitivity, digest)
              yield result
              item match
                case Left(EmbedError.NoKey(missing)) =>
                  val decision = new PolicyDecision.KeyUnavailable(
                    Some(request.id),
                    KeyId.unsafe(missing)
                  )
                  policy += decision
                  immediate += request.id -> EmbedOutcome(
                    request.id,
                    request.space,
                    Left(ExecutionFailure.PolicyDenied(decision))
                  )
                case Left(error) =>
                  immediate += request.id -> EmbedOutcome(
                    request.id,
                    request.space,
                    Left(ExecutionFailure.Invalid(error))
                  )
                case Right(itemDigest) =>
                  val encoded = tokenize(request.payload.materialText)
                  admitted += Admitted(request, itemDigest, encoded)
    }

    val admittedItems = admitted.result()
    val encodable = admittedItems.collect { case a @ Admitted(_, _, Right(_)) => a }
    val inferred: Map[RequestId, Either[ExecutionFailure, Estimate[ValidatedVector]]] =
      if encodable.isEmpty then Map.empty
      else
        run(encodable.map(_.encoding.toOption.get)) match
          case Left(code) =>
            encodable.iterator
              .map(a => a.request.id -> Left(ExecutionFailure.ProviderError(code, 1)))
              .toMap
          case Right(vectors) =>
            encodable
              .zip(vectors)
              .iterator
              .map { case (a, vector) =>
                val value = vector.left.map(ExecutionFailure.Invalid(_)).map(Estimate.observed)
                a.request.id -> value
              }
              .toMap

    val providerOutcomes = admittedItems.map { a =>
      val value = a.encoding match
        case Left(failure) => Left(failure)
        case Right(_)      =>
          inferred.getOrElse(
            a.request.id,
            Left(ExecutionFailure.ProviderError("missing-output", 1))
          )
      EmbedOutcome(a.request.id, a.request.space, value)
    }
    val providerById = providerOutcomes.iterator.map(o => o.id -> o).toMap
    val outcomes = batch.requests.map { request =>
      immediate
        .get(request.id)
        .orElse(providerById.get(request.id))
        .getOrElse(
          EmbedOutcome(
            request.id,
            request.space,
            Left(ExecutionFailure.ProviderError("missing-outcome", 1))
          )
        )
    }

    if admittedItems.isEmpty then
      val receipt = AttemptReceipt
        .of(
          Vector.empty,
          Vector.empty,
          Vector.empty,
          policy.result(),
          Vector.empty,
          batch.itemSensitivity,
          batchKeys
        )
        .getOrElse(AttemptReceipt.empty)
      BatchResult(outcomes, receipt)
    else
      receiptBatch(
        batch,
        admittedItems.map(_.item),
        providerOutcomes,
        outcomes,
        policy.result(),
        batchKeys,
        snapshot
      )

  private def receiptBatch(
      batch: EmbedBatch,
      items: Vector[ItemDigest],
      providerOutcomes: Vector[EmbedOutcome],
      outcomes: Vector[EmbedOutcome],
      policy: Vector[PolicyDecision],
      batchKeys: SensitiveKeyProvider,
      snapshot: Option[SensitiveKeySnapshot]
  ): BatchResult =
    val base = ProviderCall(
      provider = info.name,
      model = model.coordinate,
      version = info.provider.render,
      promptTemplateVersion = None,
      inputChecksum = Checksum.ofText(""),
      outputChecksum = Checksum.ofText(""),
      params = Map(
        "locality" -> "local",
        "semantic-kind" -> "neural-encoder",
        "pooling" -> "attention-mask-mean+l2",
        "runtime" -> runtimeIdentity,
        "license" -> model.license
      ),
      seed = None,
      cached = false
    )
    val outputMaterial = LocalBaseline.outputsRendering(providerOutcomes)
    val outputDigest =
      if items.exists(_.digest.kind == DigestKind.Keyed) then
        ReceiptDigest.keyed(outputMaterial, batchKeys)
      else Right(ReceiptDigest.plainPublic(outputMaterial))
    val receipt = for
      output <- outputDigest
      embedding <- EmbeddingReceipt.of(base, items, output)
      attempt <- AttemptReceipt.of(
        Vector(embedding.call),
        Vector(embedding),
        Vector.empty,
        policy,
        Vector.empty,
        batch.itemSensitivity,
        batchKeys
      )
    yield (embedding.call, attempt)
    receipt match
      case Right((_, attempt)) => BatchResult(outcomes, attempt)
      case Left(error)         =>
        val failed = batch.requests.map(request =>
          EmbedOutcome(request.id, request.space, Left(ExecutionFailure.Invalid(error)))
        )
        BatchResult(
          failed,
          AttemptReceipt.rejectProviderResult(Vector(base), batch.itemSensitivity, error, snapshot)
        )

  private def tokenize(text: String): Either[ExecutionFailure, Encoding] =
    try
      val encoding = tokenizer.encode(text)
      val count = encoding.getIds.length
      if count > info.maxTokens then Left(ExecutionFailure.TooLong(count, info.maxTokens))
      else Right(encoding)
    catch case NonFatal(_) => Left(ExecutionFailure.ProviderError("tokenizer", 1))

  private[onnx] def tokenIds(text: String): Either[String, Vector[Long]] = this.synchronized {
    try Right(tokenizer.encode(text).getIds.toVector)
    catch case NonFatal(_) => Left("tokenizer")
  }

  private def run(
      encodings: Vector[Encoding]
  ): Either[String, Vector[Either[EmbedError, ValidatedVector]]] =
    val width = encodings.map(_.getIds.length).max
    val rows = encodings.size
    def padded(values: Encoding => Array[Long], pad: Long): Array[Long] =
      val flat = Array.fill(rows * width)(pad)
      encodings.zipWithIndex.foreach { case (encoding, row) =>
        val source = values(encoding)
        System.arraycopy(source, 0, flat, row * width, source.length)
      }
      flat

    var opened = List.empty[OnnxTensor]
    def tensor(values: Array[Long]): OnnxTensor =
      val created = OnnxTensor.createTensor(
        environment,
        LongBuffer.wrap(values),
        Array(rows.toLong, width.toLong)
      )
      opened = created :: opened
      created
    try
      val ids = tensor(padded(_.getIds, PadTokenId))
      val masks = tensor(padded(_.getAttentionMask, 0L))
      val types = tensor(padded(_.getTypeIds, 0L))
      val inputs: JMap[String, OnnxTensor] = Map(
        InputIds -> ids,
        AttentionMask -> masks,
        TokenTypeIds -> types
      ).asJava
      val requested: JSet[String] = Set(Output).asJava
      val result = session.run(inputs, requested)
      try
        val value = result.get(Output).orElseThrow()
        value match
          case tensor: OnnxTensor => pool(tensor, encodings, rows, width)
          case _                  => Left("output-not-tensor")
      finally result.close()
    catch case NonFatal(_) => Left("onnx-run")
    finally opened.foreach(closeQuietly)

  private def pool(
      tensor: OnnxTensor,
      encodings: Vector[Encoding],
      rows: Int,
      width: Int
  ): Either[String, Vector[Either[EmbedError, ValidatedVector]]] =
    val shape = tensor.getInfo.getShape
    if shape.toVector != Vector(rows.toLong, width.toLong, model.dimension.value.toLong) then
      Left("unexpected-output-shape")
    else
      val values = tensor.getFloatBuffer
      val dimension = model.dimension.value
      val vectors = encodings.indices.toVector.map { row =>
        val mask = encodings(row).getAttentionMask
        val sums = Array.fill(dimension)(0.0)
        var count = 0
        var token = 0
        while token < width do
          val active = token < mask.length && mask(token) != 0L
          var coordinate = 0
          while coordinate < dimension do
            val value = values.get((row * width + token) * dimension + coordinate).toDouble
            if active then sums(coordinate) += value
            coordinate += 1
          if active then count += 1
          token += 1
        if count == 0 then Left(EmbedError.InvalidResult("encoder returned no attended tokens"))
        else ValidatedVector.l2(model.dimension, sums.iterator.map(_ / count.toDouble).toVector)
      }
      Right(vectors)

  override def close(): Unit =
    try session.close()
    finally tokenizer.close()

object OnnxSentenceEmbedder:
  private val ImplementationVersion = "storymodel4s-embed-onnx/v1"
  private val InputIds = "input_ids"
  private val AttentionMask = "attention_mask"
  private val TokenTypeIds = "token_type_ids"
  private val Output = "last_hidden_state"
  private val PadTokenId = 0L

  /** Open and validate a local encoder before it can receive an embedding request. */
  def open(
      model: OnnxSentenceModel,
      artifacts: OnnxSentenceArtifacts,
      keys: SensitiveKeyProvider = SensitiveKeyProvider.none
  ): Either[OnnxEmbedderError, OnnxSentenceEmbedder] =
    for
      _ <- validateEnvironment(sys.env)
      _ <- verify(OnnxArtifactKind.Model, artifacts.model, model.modelChecksum)
      _ <- verify(OnnxArtifactKind.Tokenizer, artifacts.tokenizer, model.tokenizerChecksum)
      tokenizer <- loadTokenizer(artifacts.tokenizer, model.maxTokens)
      embedder <- loadRuntime(model, artifacts.model, tokenizer, keys)
    yield embedder

  private[onnx] def validateEnvironment(
      environment: collection.Map[String, String]
  ): Either[OnnxEmbedderError, Unit] =
    Either.cond(
      environment.get("ORT_DISABLE_TELEMETRY").contains("1"),
      (),
      OnnxEmbedderError.TelemetryNotDisabled
    )

  private def verify(
      kind: OnnxArtifactKind,
      path: Path,
      expected: Checksum
  ): Either[OnnxEmbedderError, Unit] =
    if !Files.isRegularFile(path) then Left(OnnxEmbedderError.MissingArtifact(kind))
    else
      try
        val digest = MessageDigest.getInstance("SHA-256")
        val input = Files.newInputStream(path)
        try
          val buffer = new Array[Byte](64 * 1024)
          var read = input.read(buffer)
          while read >= 0 do
            if read > 0 then digest.update(buffer, 0, read)
            read = input.read(buffer)
        finally input.close()
        val hex = digest.digest().iterator.map(byte => f"${byte & 0xff}%02x").mkString
        val actual = Checksum.unsafe(hex)
        Either.cond(
          actual == expected,
          (),
          OnnxEmbedderError.ArtifactChecksumMismatch(kind, expected, actual)
        )
      catch case NonFatal(_) => Left(OnnxEmbedderError.LoadFailure(OnnxLoadStage.Checksum))

  private def loadTokenizer(
      path: Path,
      maxTokens: Int
  ): Either[OnnxEmbedderError, HuggingFaceTokenizer] =
    try
      Right(
        HuggingFaceTokenizer.newInstance(
          path,
          Map(
            "addSpecialTokens" -> "true",
            "withOverflowingTokens" -> "false",
            "truncation" -> "DO_NOT_TRUNCATE",
            "padding" -> "DO_NOT_PAD",
            "modelMaxLength" -> maxTokens.toString
          ).asJava
        )
      )
    catch case NonFatal(_) => Left(OnnxEmbedderError.LoadFailure(OnnxLoadStage.Tokenizer))

  private def loadRuntime(
      model: OnnxSentenceModel,
      modelPath: Path,
      tokenizer: HuggingFaceTokenizer,
      keys: SensitiveKeyProvider
  ): Either[OnnxEmbedderError, OnnxSentenceEmbedder] =
    val environment =
      try
        val loaded = OrtEnvironment.getEnvironment("storymodel4s-embed-onnx")
        loaded.setTelemetry(false)
        Right(loaded)
      catch case NonFatal(_) => Left(OnnxEmbedderError.LoadFailure(OnnxLoadStage.Runtime))
    environment match
      case Left(error) =>
        closeQuietly(tokenizer)
        Left(error)
      case Right(loadedEnvironment) =>
        var openedSession = Option.empty[OrtSession]
        try
          val options = new OrtSession.SessionOptions()
          val session =
            try loadedEnvironment.createSession(modelPath.toString, options)
            finally options.close()
          openedSession = Some(session)
          validateSchema(session, model) match
            case Left(error) =>
              closeQuietly(session)
              closeQuietly(tokenizer)
              Left(error)
            case Right(()) =>
              val runtime =
                s"onnx:${loadedEnvironment.getVersion}|djl-tokenizers:${tokenizer.getVersion}"
              val fingerprint = ProviderFingerprint.of(
                s"${model.coordinate}:${model.modelChecksum.hex}",
                model.tokenizerChecksum.hex,
                ImplementationVersion,
                runtime
              )
              val spaces = Vector(Role.Query, Role.Document).map { role =>
                EmbeddingSpace
                  .of(
                    fingerprint,
                    role,
                    SemanticView.Surface,
                    None,
                    model.dimension,
                    Normalization.L2,
                    TruncationPolicy.Reject
                  )
                  .fold(error => throw new IllegalStateException(error.message), identity)
              }
              val info = EmbedderInfo(
                fingerprint,
                "onnx-sentence-encoder",
                s"${model.coordinate}|${fingerprint.render}",
                Locality.Local,
                PrivacyClass.SensitiveOk,
                model.maxTokens,
                supportsInstructions = false,
                tokenEmbeddings = false,
                matryoshkaDims = None
              )
              Right(
                new OnnxSentenceEmbedder(
                  model,
                  info,
                  spaces,
                  runtime,
                  tokenizer,
                  loadedEnvironment,
                  session,
                  keys
                )
              )
        catch
          case NonFatal(_) =>
            openedSession.foreach(closeQuietly)
            closeQuietly(tokenizer)
            Left(OnnxEmbedderError.LoadFailure(OnnxLoadStage.Session))

  private def closeQuietly(resource: AutoCloseable): Unit =
    try resource.close()
    catch case NonFatal(_) => ()

  private def validateSchema(
      session: OrtSession,
      model: OnnxSentenceModel
  ): Either[OnnxEmbedderError, Unit] =
    val inputs = session.getInputInfo.asScala.toMap
    val expectedInputs = Set(InputIds, AttentionMask, TokenTypeIds)
    if inputs.keySet != expectedInputs then
      Left(OnnxEmbedderError.InvalidModelSchema("input-names"))
    else
      inputs.valuesIterator
        .map(_.getInfo)
        .find {
          case tensor: TensorInfo =>
            tensor.`type` != OnnxJavaType.INT64 || tensor.getShape.length != 2
          case _ => true
        }
        .map(_ => OnnxEmbedderError.InvalidModelSchema("input-type-or-rank"))
        .orElse {
          session.getOutputInfo.asScala.get(Output) match
            case None       => Some(OnnxEmbedderError.InvalidModelSchema("output-name"))
            case Some(node) =>
              node.getInfo match
                case tensor: TensorInfo
                    if tensor.`type` == OnnxJavaType.FLOAT &&
                      tensor.getShape.length == 3 &&
                      tensor.getShape.last == model.dimension.value.toLong =>
                  None
                case _ =>
                  Some(OnnxEmbedderError.InvalidModelSchema("output-type-rank-or-dimension"))
        }
        .toLeft(())
