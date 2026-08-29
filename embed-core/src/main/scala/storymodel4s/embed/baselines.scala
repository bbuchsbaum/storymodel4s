package storymodel4s.embed

import cats.Applicative

import storymodel4s.core.{Checksum, ContentAddress, ProviderCall, TextNorm}
import storymodel4s.features.{CanonicalDouble, Estimate}

/** Shared plumbing for the free, deterministic, portable baselines: preflight, per-item outcomes,
  * one receipted "call" per batch (cached=false, no remote).
  *
  * Identity discipline (ADR 0001 D6): a non-public item without a key is denied as
  * `PolicyDenied(KeyUnavailable)` with a recorded decision; if the outputs of a keyed batch cannot
  * be keyed (key vanished between items and outputs) the WHOLE batch fails closed the same way —
  * there is no path on which a plain identity of non-public material reaches a receipt.
  */
abstract class LocalBaseline[F[_]: Applicative] extends Embedder[F]:
  protected def vectorFor(
      space: EmbeddingSpace,
      text: String
  ): Either[EmbedError, Estimate[ValidatedVector]]

  /** Store-local keys for non-public material. `SensitiveKeyProvider.none` makes every non-public
    * request fail closed (typed) — never a plain identity of sensitive text.
    */
  protected def keys: SensitiveKeyProvider

  def embed(batch: EmbedBatch): F[BatchResult] =
    Applicative[F].pure(embedNow(batch))

  private def denied(r: EmbedRequest, keyId: KeyId): (EmbedOutcome, PolicyDecision.KeyUnavailable) =
    val d = new PolicyDecision.KeyUnavailable(Some(r.id), keyId)
    (EmbedOutcome(r.id, r.space, Left(ExecutionFailure.PolicyDenied(d))), d)

  private def embedNow(batch: EmbedBatch): BatchResult =
    val nonPublic = batch.itemSensitivity.exists { case (_, sensitivity) =>
      !ReceiptDigest.plainAdmissible.contains(sensitivity)
    }
    val keyId = keys.currentKeyId
    SensitiveKeySnapshot.capture(keyId, keys) match
      case Right(snapshot) => embedNowWith(batch, snapshot.provider)
      case Left(error @ EmbedError.InvalidKey(_)) if nonPublic =>
        BatchResult.invalidKey(batch, keyId, error)
      case Left(_) if nonPublic => BatchResult.keyUnavailable(batch, keyId)
      case Left(_)              => embedNowWith(batch, SensitiveKeyProvider.none)

  private def embedNowWith(
      batch: EmbedBatch,
      batchKeys: SensitiveKeyProvider
  ): BatchResult =
    val policy = Vector.newBuilder[PolicyDecision]
    val perItem: Vector[(EmbedOutcome, Option[ItemDigest])] =
      batch.requests.map { r =>
        Embedder.preflight(info, r) match
          case Left(f) =>
            f match
              case ExecutionFailure.PolicyDenied(d) => policy += d
              case ExecutionFailure.LocalOnly(d)    => policy += d
              case _                                => ()
            (EmbedOutcome(r.id, r.space, Left(f)), None)
          case Right(()) =>
            space(r.space) match
              case None =>
                (
                  EmbedOutcome(
                    r.id,
                    r.space,
                    Left(ExecutionFailure.Invalid(EmbedError.UnknownSpace(r.space.value)))
                  ),
                  None
                )
              case Some(s) =>
                val sensitivity = r.payload.sensitivityOf
                val material = Material.render(s, r.payload)
                ReceiptDigest.of(sensitivity, material, batchKeys) match
                  case Left(EmbedError.NoKey(k)) =>
                    val (o, d) = denied(r, KeyId.unsafe(k))
                    policy += d
                    (o, None)
                  case Left(e) =>
                    (EmbedOutcome(r.id, r.space, Left(ExecutionFailure.Invalid(e))), None)
                  case Right(digest) =>
                    ItemDigest.of(r.id, sensitivity, digest) match
                      case Left(e) =>
                        (EmbedOutcome(r.id, r.space, Left(ExecutionFailure.Invalid(e))), None)
                      case Right(item) =>
                        val outcome = vectorFor(s, r.payload.materialText) match
                          case Left(e) =>
                            EmbedOutcome(r.id, r.space, Left(ExecutionFailure.Invalid(e)))
                          case Right(v) => EmbedOutcome(r.id, r.space, Right(v))
                        (outcome, Some(item))
      }
    val outcomes = perItem.map(_._1)
    val items = perItem.flatMap(_._2)
    val anyKeyed = items.exists(_.digest.kind == DigestKind.Keyed)
    val outputsMaterial = LocalBaseline.outputsRendering(outcomes)
    val base = ProviderCall(
      provider = info.name,
      model = info.version,
      version = info.provider.render,
      promptTemplateVersion = None,
      inputChecksum = Checksum.ofText(""),
      outputChecksum = Checksum.ofText(""),
      params = Map("locality" -> "local"),
      seed = None,
      cached = false
    )
    val outputs: Either[EmbedError, ReceiptDigest] =
      if anyKeyed then ReceiptDigest.keyed(outputsMaterial, batchKeys)
      else Right(ReceiptDigest.plainPublic(outputsMaterial))
    val receipted: Either[EmbedError, EmbeddingReceipt] =
      outputs.flatMap(o => EmbeddingReceipt.of(base, items, o))
    receipted match
      case Right(receipt) =>
        AttemptReceipt
          .of(
            Vector(receipt.call),
            Vector(receipt),
            Vector.empty,
            policy.result(),
            Vector.empty,
            batch.itemSensitivity,
            batchKeys
          )
          .fold(
            _ => BatchResult.keyUnavailable(batch, batchKeys.currentKeyId),
            r => BatchResult(outcomes, r)
          )
      case Left(_) =>
        // The owned non-empty batch key makes this unreachable; retain a conservative guard.
        BatchResult.keyUnavailable(batch, batchKeys.currentKeyId)

object LocalBaseline:
  /** Canonical rendering of a batch's outcomes (platform-stable doubles; no material). */
  private[embed] def outputsRendering(outcomes: Vector[EmbedOutcome]): String =
    (ReceiptRendering.OutputsVersion +: outcomes.map { o =>
      val v = o.value.fold(
        f => s"failure=${ReceiptRendering.esc(f.render)}",
        e => e.toOption.fold("missing")(_.values.map(CanonicalDouble.render).mkString(","))
      )
      s"${ReceiptRendering.esc(o.id.value)}|$v"
    }).mkString("\n")

/** Portable word scanner over code points (no `\p{L}` regexes, which Scala.js does not support in
  * `String.split`). Letters, digits and apostrophes form words; everything else separates.
  */
object BaselineTokens:
  def words(text: String): Vector[String] =
    val lower = TextNorm.lower(text)
    val out = Vector.newBuilder[String]
    val sb = new StringBuilder
    var i = 0
    while i < lower.length do
      val cp = lower.codePointAt(i)
      val n = Character.charCount(cp)
      if Character.isLetterOrDigit(cp) || cp == '\''.toInt then sb.appendAll(Character.toChars(cp))
      else if sb.nonEmpty then
        out += sb.result()
        sb.clear()
      i += n
    if sb.nonEmpty then out += sb.result()
    out.result()

/** Deterministic FNV-1a 64-bit hashing; portable, no `java.security`. */
object Fnv1a:
  private val Offset = 0xcbf29ce484222325L
  private val Prime = 0x100000001b3L
  def hash(s: String, seed: Long): Long =
    var h = Offset ^ seed
    var i = 0
    while i < s.length do
      h ^= s.charAt(i).toLong & 0xffffL
      h *= Prime
      i += 1
    h

/** Free portable baseline: hashed character 3–5-grams plus word uni/bigrams, sign-hashed into a
  * fixed dimension and L2-normalized. Its fingerprint names the hash function, seed and dimension
  * so two configurations never share a `GeometryId`.
  */
final class HashedNgramEmbedder[F[_]: Applicative](
    val dimension: Dimension,
    val seed: Long,
    protected val keys: SensitiveKeyProvider
) extends LocalBaseline[F]:
  val info: EmbedderInfo = EmbedderInfo(
    provider = ProviderFingerprint
      .of("hashed-ngram", "none", s"fnv1a64:seed=$seed:dim=${dimension.value}", "portable"),
    name = "hashed-ngram",
    version = "1",
    locality = Locality.Local,
    privacyClass = PrivacyClass.SensitiveOk,
    maxTokens = Int.MaxValue,
    supportsInstructions = false,
    tokenEmbeddings = false,
    matryoshkaDims = None
  )

  val spaces: Vector[EmbeddingSpace] =
    Vector(Role.Query, Role.Document).flatMap { role =>
      Vector(
        SemanticView.Surface,
        SemanticView.Gloss,
        SemanticView.ContextualTemplate,
        SemanticView.Segment
      ).map { view =>
        EmbeddingSpace
          .of(info.provider, role, view, None, dimension, Normalization.L2, TruncationPolicy.Reject)
          .toOption
          .get
      }
    }

  def features(text: String): Vector[String] =
    val words = BaselineTokens.words(text)
    val padded = words.map(w => s"#$w#")
    val chars = padded.flatMap(w => (3 to 5).flatMap(n => w.sliding(n).filter(_.length == n)))
    val bigrams = words.sliding(2).filter(_.size == 2).map(_.mkString("_")).toVector
    words.map("w:" + _) ++ bigrams.map("b:" + _) ++ chars.map("c:" + _)

  protected def vectorFor(
      space: EmbeddingSpace,
      text: String
  ): Either[EmbedError, Estimate[ValidatedVector]] =
    val fs = features(text)
    if fs.isEmpty then Right(Estimate.missing(storymodel4s.features.MissingReason.OutOfVocabulary))
    else
      val acc = new Array[Double](dimension.value)
      fs.foreach { f =>
        val h = Fnv1a.hash(f, seed)
        val bucket = ((h >>> 1) % dimension.value).toInt
        val sign = if (h & 1L) == 0L then 1.0 else -1.0
        acc(bucket) += sign
      }
      ValidatedVector.l2(dimension, acc.toVector).map(Estimate.observed)

object HashedNgramEmbedder:
  def apply[F[_]: Applicative](
      dimension: Int = 512,
      seed: Long = 0L,
      keys: SensitiveKeyProvider = SensitiveKeyProvider.none
  ): HashedNgramEmbedder[F] =
    new HashedNgramEmbedder[F](Dimension.unsafe(dimension), seed, keys)

/** TF-IDF over a fitted vocabulary, L2-normalized. The corpus fingerprint is part of the provider
  * identity, so vectors from different fits never share a space.
  */
final class TfIdfEmbedder[F[_]: Applicative] private (
    val vocabulary: Vector[String],
    val idf: Vector[Double],
    val corpusFingerprint: Checksum,
    protected val keys: SensitiveKeyProvider
) extends LocalBaseline[F]:
  val dimension: Dimension = Dimension.unsafe(vocabulary.length)
  private val index: Map[String, Int] = vocabulary.zipWithIndex.toMap

  val info: EmbedderInfo = EmbedderInfo(
    provider = ProviderFingerprint.of(
      "tfidf",
      "whitespace-lower",
      s"corpus=${corpusFingerprint.hex}:dim=${dimension.value}",
      "portable"
    ),
    name = "tfidf",
    version = "1",
    locality = Locality.Local,
    privacyClass = PrivacyClass.SensitiveOk,
    maxTokens = Int.MaxValue,
    supportsInstructions = false,
    tokenEmbeddings = false,
    matryoshkaDims = None
  )

  val spaces: Vector[EmbeddingSpace] =
    Vector(Role.Query, Role.Document).flatMap { role =>
      Vector(
        SemanticView.Surface,
        SemanticView.Gloss,
        SemanticView.ContextualTemplate,
        SemanticView.Segment
      ).map { view =>
        EmbeddingSpace
          .of(info.provider, role, view, None, dimension, Normalization.L2, TruncationPolicy.Reject)
          .toOption
          .get
      }
    }

  protected def vectorFor(
      space: EmbeddingSpace,
      text: String
  ): Either[EmbedError, Estimate[ValidatedVector]] =
    val counts = TfIdfEmbedder.tokens(text).groupBy(identity).view.mapValues(_.size.toDouble).toMap
    val acc = new Array[Double](dimension.value)
    var any = false
    counts.foreach { case (t, c) =>
      index.get(t).foreach { i =>
        acc(i) = c * idf(i)
        any = true
      }
    }
    if !any then Right(Estimate.missing(storymodel4s.features.MissingReason.OutOfVocabulary))
    else ValidatedVector.l2(dimension, acc.toVector).map(Estimate.observed)

object TfIdfEmbedder:
  def tokens(text: String): Vector[String] = BaselineTokens.words(text)

  /** Fit the vocabulary and idf on a corpus (sorted vocabulary ⇒ deterministic dimension order). */
  def fit[F[_]: Applicative](
      corpus: Vector[String],
      keys: SensitiveKeyProvider = SensitiveKeyProvider.none
  ): Either[EmbedError, TfIdfEmbedder[F]] =
    val docs = corpus.map(d => tokens(d).toSet)
    val vocab = docs.flatten.distinct.sorted
    if vocab.isEmpty then Left(EmbedError.InvalidRecipe("empty corpus"))
    else
      val n = docs.length.toDouble
      val idf = vocab.map(t => math.log((1.0 + n) / (1.0 + docs.count(_.contains(t)))) + 1.0)
      val fp = ContentAddress.digest(corpus)
      Right(new TfIdfEmbedder[F](vocab, idf, fp, keys))
