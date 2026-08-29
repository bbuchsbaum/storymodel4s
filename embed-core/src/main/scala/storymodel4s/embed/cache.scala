package storymodel4s.embed

import cats.{Applicative, Monad}
import cats.syntax.all.*

import storymodel4s.features.Estimate

/** Cache identity: the recipe plus the exact rendered provider material (keyed for non-public
  * inputs). Two requests with the same text but different instructions or roles never collide
  * because role/instruction live in the material and the `GeometryId`. The constructor is
  * `private[embed]`: a key can only be minted through [[CacheKey.of]], which routes the digest
  * through [[ReceiptDigest.of]] (Plain iff Public).
  */
final class CacheKey private[embed] (val space: GeometryId, val digest: ReceiptDigest):
  def render: String = s"${space.value}|${digest.render}"

  override def equals(other: Any): Boolean = other match
    case that: CacheKey => space == that.space && digest == that.digest
    case _              => false

  override def hashCode(): Int = (space, digest).hashCode

  override def toString: String =
    s"CacheKey(space=${space.value}, digestKind=${digest.kind.render})"

object CacheKey:
  def of(
      space: EmbeddingSpace,
      payload: EmbedPayload,
      keys: SensitiveKeyProvider
  ): Either[EmbedError, CacheKey] =
    ReceiptDigest
      .of(payload.sensitivityOf, Material.render(space, payload), keys)
      .map(digest => new CacheKey(space.id, digest))

/** A cache of validated vectors. Values are only ever `Observed` vectors; misses are absence, not
  * `Missing` estimates (abstention is a provider outcome, not a cache outcome).
  */
trait EmbeddingCache[F[_]]:
  def get(key: CacheKey): F[Option[ValidatedVector]]
  def put(key: CacheKey, value: ValidatedVector): F[Unit]

object EmbeddingCache:
  /** Portable in-memory cache. Not thread-safe by design (immutable map behind a var); JVM callers
    * that need concurrency wrap it or use a JVM implementation behind `EncryptedStore`.
    */
  final class InMemory[F[_]: Applicative] extends EmbeddingCache[F]:
    private var entries: Map[String, ValidatedVector] = Map.empty
    def get(key: CacheKey): F[Option[ValidatedVector]] =
      Applicative[F].pure(entries.get(key.render))
    def put(key: CacheKey, value: ValidatedVector): F[Unit] =
      entries = entries.updated(key.render, value)
      Applicative[F].pure(())
    def size: Int = entries.size

  def inMemory[F[_]: Applicative]: InMemory[F] = new InMemory[F]

/** Renders the exact material a provider embeds for a request (canonical rendering, ADR 0001 D6):
  * role marker, instruction digest, and escaped text. Used both for cache identity and for
  * `ProviderCall.inputChecksum`.
  */
object Material:
  def render(space: EmbeddingSpace, payload: EmbedPayload): String =
    ReceiptRendering.material(space.role, space.instruction, payload.materialText)

/** Wraps an embedder with a cache. Hits produce outcomes with no provider call; every decision is
  * recorded in the receipt. Non-public inputs are keyed with the store-local HMAC key; a request
  * whose key is unavailable fails closed as `PolicyDenied(KeyUnavailable)` with a recorded policy
  * decision and is neither cached nor embedded — never a plain key.
  */
final class CachingEmbedder[F[_]: Monad](
    underlying: Embedder[F],
    cache: EmbeddingCache[F],
    keys: SensitiveKeyProvider
) extends Embedder[F]:
  def info: EmbedderInfo = underlying.info
  def spaces: Vector[EmbeddingSpace] = underlying.spaces

  private enum Keyed:
    case Ok(key: CacheKey)
    case NoKey(decision: PolicyDecision.KeyUnavailable)
    case Other(error: EmbedError)

  /** REQUIRED (chief re-review): the store key is resolved ONCE at batch start. A non-public batch
    * without a key fails closed here — before any delegation, so `Withheld` never describes a
    * provider call and nothing is ever cached for a denied outcome. When the key is present it is
    * snapshotted for the whole batch, so a key withdrawn mid-batch cannot desynchronize item
    * identities from the attempt identity.
    */
  def embed(batch: EmbedBatch): F[BatchResult] =
    val nonPublic = batch.itemSensitivity.exists { case (_, s) =>
      !ReceiptDigest.plainAdmissible.contains(s)
    }
    val keyId = keys.currentKeyId
    if !nonPublic then embedWith(batch, None)
    else
      SensitiveKeySnapshot.capture(keyId, keys) match
        case Right(snapshot)                        => embedWith(batch, Some(snapshot))
        case Left(error @ EmbedError.InvalidKey(_)) =>
          Monad[F].pure(BatchResult.invalidKey(batch, keyId, error))
        case Left(_) => Monad[F].pure(BatchResult.cacheKeyUnavailable(batch, keyId))

  private def embedWith(
      batch: EmbedBatch,
      snapshot: Option[SensitiveKeySnapshot]
  ): F[BatchResult] =
    val batchKeys = snapshot.fold[SensitiveKeyProvider](SensitiveKeyProvider.none)(_.provider)
    val keyed: Vector[(EmbedRequest, Keyed)] = batch.requests.map { r =>
      val key = underlying.space(r.space) match
        case None    => Keyed.Other(EmbedError.UnknownSpace(r.space.value))
        case Some(s) =>
          CacheKey.of(s, r.payload, batchKeys) match
            case Right(k)                   => Keyed.Ok(k)
            case Left(EmbedError.NoKey(id)) =>
              Keyed.NoKey(new PolicyDecision.KeyUnavailable(Some(r.id), KeyId.unsafe(id)))
            case Left(e) => Keyed.Other(e)
      (r, key)
    }
    keyed
      .traverse {
        case (r, Keyed.Ok(key)) => cache.get(key).map(v => (r, Keyed.Ok(key), v))
        case (r, other)         => Monad[F].pure((r, other, Option.empty[ValidatedVector]))
      }
      .flatMap { looked =>
        val decisions = looked.map {
          case (r, Keyed.Ok(k), Some(_)) => CacheDecision.Hit(r.id, k.digest)
          case (r, Keyed.Ok(k), None)    => CacheDecision.Miss(r.id, k.digest)
          case (r, Keyed.NoKey(d), _)    => CacheDecision.Denied(r.id, d)
          case (r, Keyed.Other(e), _)    => CacheDecision.Bypassed(r.id, e.message)
        }
        val denials = looked.collect { case (_, Keyed.NoKey(d), _) => d: PolicyDecision }
        val misses = looked.collect { case (r, Keyed.Ok(_), None) => r }
        val run: F[BatchResult] =
          if misses.isEmpty then Monad[F].pure(BatchResult(Vector.empty, AttemptReceipt.empty))
          else
            EmbedBatch.validated(misses, underlying.spaceIds) match
              case Left(e) =>
                Monad[F].pure(
                  BatchResult(
                    misses.map(m => EmbedOutcome(m.id, m.space, Left(ExecutionFailure.Invalid(e)))),
                    AttemptReceipt.empty
                  )
                )
              case Right(b) => underlying.embed(b)
        run.flatMap { fresh =>
          val freshById = fresh.outcomes.map(o => o.id -> o).toMap
          val outcomes = looked.map {
            case (r, Keyed.Ok(_), Some(v)) =>
              EmbedOutcome(r.id, r.space, Right(Estimate.observed(v)))
            case (r, Keyed.Ok(_), None) =>
              freshById.getOrElse(
                r.id,
                EmbedOutcome(
                  r.id,
                  r.space,
                  Left(ExecutionFailure.Invalid(EmbedError.InvalidResult("no outcome")))
                )
              )
            case (r, Keyed.NoKey(d), _) =>
              EmbedOutcome(r.id, r.space, Left(ExecutionFailure.PolicyDenied(d)))
            case (r, Keyed.Other(e), _) =>
              EmbedOutcome(r.id, r.space, Left(ExecutionFailure.Invalid(e)))
          }
          val cacheDecisions = decisions ++ fresh.receipt.cacheDecisions
          val policy = fresh.receipt.policyDecisions ++ denials
          // The receipt is minted BEFORE any put: a withheld attempt caches nothing.
          val receipted = snapshot match
            case Some(authority) =>
              AttemptReceipt.ofSnapshot(
                fresh.receipt.providerCalls,
                fresh.receipt.embeddingReceipts,
                cacheDecisions,
                policy,
                fresh.receipt.resultDecisions,
                batch.itemSensitivity,
                authority
              )
            case None =>
              AttemptReceipt.of(
                fresh.receipt.providerCalls,
                fresh.receipt.embeddingReceipts,
                cacheDecisions,
                policy,
                fresh.receipt.resultDecisions,
                batch.itemSensitivity,
                batchKeys
              )
          val receipt = receipted.fold(
            error =>
              val rejection = Vector(ResultDecision.BatchRejected(error))
              val rebuilt = snapshot match
                case Some(authority) =>
                  AttemptReceipt.ofSnapshot(
                    Vector.empty,
                    Vector.empty,
                    decisions,
                    denials,
                    rejection,
                    batch.itemSensitivity,
                    authority
                  )
                case None =>
                  AttemptReceipt.of(
                    Vector.empty,
                    Vector.empty,
                    decisions,
                    denials,
                    rejection,
                    batch.itemSensitivity,
                    SensitiveKeyProvider.none
                  )
              rebuilt.getOrElse(
                AttemptReceipt.rejectProviderResult(
                  Vector.empty,
                  batch.itemSensitivity,
                  error,
                  snapshot
                )
              )
            ,
            identity
          )
          val finalOutcomes = receipted match
            case Left(error) =>
              outcomes.map(o => o.copy(value = Left(ExecutionFailure.Invalid(error))))
            case Right(_) => receipt.enforceWithholding(outcomes)
          val puts =
            if receipted.isLeft || receipt.kind == DigestKind.Withheld then Vector.empty
            else
              looked.collect { case (r, Keyed.Ok(key), None) =>
                finalOutcomes
                  .find(_.id == r.id)
                  .filter(_.space == r.space)
                  .flatMap(_.value.toOption)
                  .flatMap(_.toOption)
                  .map(v => cache.put(key, v))
              }.flatten
          puts.sequence_.map(_ => BatchResult(finalOutcomes, receipt))
        }
      }
