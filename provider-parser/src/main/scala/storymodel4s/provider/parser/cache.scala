package storymodel4s.provider.parser

import cats.Monad
import cats.syntax.all.*
import storymodel4s.core.*

/** Content-addressed identity of one sentence under one pinned runtime and parser configuration. */
object ParserCacheKey:
  opaque type ParserCacheKey = Checksum

  def of(
      input: ParserSentenceInput,
      runtime: PinnedRuntime,
      config: ParserConfig
  ): ParserCacheKey =
    ParserIdentity.digest(
      "parser-cache/v2",
      Vector(input.checksum.hex, runtime.checksum.hex, config.checksum.hex)
    )

  extension (key: ParserCacheKey) def checksum: Checksum = key

type ParserCacheKey = ParserCacheKey.ParserCacheKey

/** Cached successful evidence bound to the request and receipt that admitted it. */
final class CachedParserProposal private (
    val key: ParserCacheKey,
    val requestChecksum: Checksum,
    val proposal: ParserProposal,
    val sourceReceipt: Checksum,
    val checksum: Checksum
):
  private[parser] def admits(
      input: ParserSentenceInput,
      candidate: ParserProposal
  ): Boolean =
    requestChecksum == input.checksum && proposal == candidate

  override def equals(other: Any): Boolean = other match
    case that: CachedParserProposal =>
      key == that.key && requestChecksum == that.requestChecksum && proposal == that.proposal &&
      sourceReceipt == that.sourceReceipt && checksum == that.checksum
    case _ => false

  override def hashCode(): Int =
    (key, requestChecksum, proposal, sourceReceipt, checksum).hashCode

  override def toString: String =
    s"CachedParserProposal(${key.checksum.short()}, checksum=${checksum.short()})"

object CachedParserProposal:
  /** Mint a cache witness only from one already-admitted successful attempt.
    *
    * Possession authorizes replay only under this witness's visible original key. The caching
    * provider separately compares that key with the key derived from its current runtime and
    * configuration before it constructs a successful attempt.
    */
  private[parser] def from(
      key: ParserCacheKey,
      attempt: ParserAttempt
  ): Option[CachedParserProposal] = attempt.result.toOption.map { proposal =>
    val requestChecksum = attempt.receipt.requestChecksum
    val sourceReceipt = attempt.receipt.digest
    val checksum = ParserIdentity.digest(
      "cached-parser-proposal/v1",
      Vector(
        key.checksum.hex,
        requestChecksum.hex,
        proposal.canonicalDigest.hex,
        sourceReceipt.hex
      )
    )
    new CachedParserProposal(key, requestChecksum, proposal, sourceReceipt, checksum)
  }

/** Effect-polymorphic cache used by the parser adapter; implementations may be memory or disk. */
trait ParserCache[F[_]]:
  def get(key: ParserCacheKey): F[Option[CachedParserProposal]]
  def put(key: ParserCacheKey, value: CachedParserProposal): F[Unit]

/** Cache wrapper that revalidates every hit and calls its delegate only for misses. */
final class CachingParserProvider[F[_]: Monad] private (
    delegate: AmrCandidateProvider[F],
    pinned: PinnedRuntime,
    cache: ParserCache[F]
) extends AmrCandidateProvider[F]:
  val runtime: ParserRuntime = delegate.runtime
  val config: ParserConfig = delegate.config

  def parse(batch: ParserBatch): F[ParserBatchResult] =
    batch.inputs
      .traverse { input =>
        val key = ParserCacheKey.of(input, pinned, config)
        cache.get(key).map(input -> key -> _)
      }
      .flatMap { lookups =>
        val cached = lookups.collect { case ((input, key), Some(value)) =>
          input.id -> fromCache(input, key, value)
        }.toMap
        val misses = lookups.collect { case ((input, key), None) => input -> key }
        if misses.isEmpty then
          Monad[F].pure(
            ParserBatchResult.unsafe(batch.inputs.map(input => cached(input.id)))
          )
        else
          val missBatch = ParserBatch.unsafe(misses.map(_._1))
          delegate.parse(missBatch).flatMap { raw =>
            raw.conforms(missBatch) match
              case Left(error) =>
                val missAttempts = misses.map { (input, key) =>
                  val failure = ParserFailure.CacheCorrupt(error.toString)
                  val receipt = ParserAttemptReceipt.of(
                    input,
                    None,
                    Vector(
                      ParserAttemptDecision.CacheMiss(key),
                      ParserAttemptDecision.ResultRejected(failure)
                    )
                  )
                  input.id -> ParserAttempt.failed(input, failure, receipt, Some(key))
                }.toMap
                val ordered =
                  batch.inputs.map(input => cached.getOrElse(input.id, missAttempts(input.id)))
                Monad[F].pure(ParserBatchResult.unsafe(ordered, raw.decisions))
              case Right(valid) =>
                val byId = valid.attempts.map(attempt => attempt.id -> attempt).toMap
                val amended = misses.map { (input, key) =>
                  val original = byId(input.id)
                  val receipt = ParserAttemptReceipt.of(
                    input,
                    original.receipt.call,
                    ParserAttemptDecision.CacheMiss(key) +: original.receipt.decisions
                  )
                  val attempt = original.result match
                    case Left(failure) =>
                      ParserAttempt.failed(input, failure, receipt, Some(key))
                    case Right(proposal) =>
                      ParserAttempt
                        .proposed(input, proposal, receipt, Some(key))
                        .fold(
                          failure => ParserAttempt.failed(input, failure, receipt, Some(key)),
                          identity
                        )
                  input.id -> (key, attempt)
                }.toMap
                val puts = amended.values.toVector.traverse_ { (key, attempt) =>
                  CachedParserProposal
                    .from(key, attempt)
                    .traverse_(value => cache.put(key, value))
                }
                puts.map { _ =>
                  val ordered = batch.inputs.map { input =>
                    cached.getOrElse(input.id, amended(input.id)._2)
                  }
                  ParserBatchResult.unsafe(ordered, valid.decisions)
                }
          }
      }

  private def fromCache(
      input: ParserSentenceInput,
      key: ParserCacheKey,
      cached: CachedParserProposal
  ): ParserAttempt =
    val validation =
      if cached.key != key then Left("cache key mismatch")
      else if cached.requestChecksum != input.checksum then Left("request checksum mismatch")
      else if ParserProposal
          .of(cached.proposal.evidence)
          .canonicalDigest != cached.proposal.canonicalDigest
      then Left("canonical digest mismatch")
      else ParserAdmission.validate(input, cached.proposal.evidence).left.map(_.render)
    validation match
      case Left(reason) =>
        val failure = ParserFailure.CacheCorrupt(reason)
        val receipt = ParserAttemptReceipt.of(
          input,
          None,
          Vector(
            ParserAttemptDecision.CacheHit(cached),
            ParserAttemptDecision.ResultRejected(failure)
          )
        )
        ParserAttempt.failed(input, failure, receipt, Some(key))
      case Right(_) =>
        val receipt = ParserAttemptReceipt.of(
          input,
          None,
          Vector(ParserAttemptDecision.CacheHit(cached))
        )
        ParserAttempt
          .proposed(input, cached.proposal, receipt, Some(key))
          .fold(
            failure => ParserAttempt.failed(input, failure, receipt, Some(key)),
            identity
          )

object CachingParserProvider:
  /** Construct only around a ready delegate, deriving cache identity from that delegate. */
  def from[F[_]: Monad](
      delegate: AmrCandidateProvider[F],
      cache: ParserCache[F]
  ): Either[ParserSetupFailure, CachingParserProvider[F]] = delegate.runtime match
    case ParserRuntime.Ready(pinned) =>
      Right(new CachingParserProvider(delegate, pinned, cache))
    case ParserRuntime.Unavailable(reason) => Left(reason)
