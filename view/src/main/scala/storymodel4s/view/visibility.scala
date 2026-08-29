package storymodel4s.view

import storymodel4s.core.*

/** The one evidence-horizon computation shared by every view compiler (ADR 0002 D4, row 7).
  *
  * A claim is visible at reader offset `t` iff every evidence item is grounded (cites spans or
  * upstream claims), every cited span ends at or before `t`, and every upstream claim is itself
  * visible — the transitive closure over the ledger. Support is then clipped to spans ending at or
  * before `t`. Codex and Atlas must call this, never re-derive it, so they cannot diverge.
  */
object EvidenceVisibility:
  /** Validate a reader horizon against canonical UTF-16 text without cutting a code point. */
  def validateHorizon(
      text: String,
      horizon: EpistemicHorizon
  ): Either[DomainError, Unit] = horizon match
    case EpistemicHorizon.Omniscient       => Right(())
    case EpistemicHorizon.ReaderAt(offset) =>
      if offset < 0 || offset > text.length then
        Left(
          DomainError.InvalidSpan(
            offset,
            offset,
            s"reader horizon must lie inside canonical text of length ${text.length}"
          )
        )
      else if offset > 0 && offset < text.length &&
        Character.isHighSurrogate(text.charAt(offset - 1)) &&
        Character.isLowSurrogate(text.charAt(offset))
      then
        Left(
          DomainError.InvalidSpan(offset, offset, "reader horizon splits a UTF-16 surrogate pair")
        )
      else Right(())

  /** Compute the transitive evidence closure visible at one canonical UTF-16 source offset. */
  def visibleClaims(offset: Int, ledger: ClaimLedger): Set[ClaimId] =
    val memo = scala.collection.mutable.Map.empty[ClaimId, Boolean]

    def claimVisible(claim: ClaimId, visiting: Set[ClaimId]): Boolean =
      memo.get(claim) match
        case Some(value)                      => value
        case None if visiting.contains(claim) => false
        case None                             =>
          val result = ledger.lookup(claim).exists { meta =>
            meta.evidence.toVector.forall { evidence =>
              val grounded = evidence.spans.nonEmpty || evidence.upstream.nonEmpty
              val spansVisible =
                evidence.spans.forall(_.refs.toVector.forall(_.span.endExclusive <= offset))
              val upstreamVisible =
                evidence.upstream.forall(up => claimVisible(up, visiting + claim))
              grounded && spansVisible && upstreamVisible
            }
          }
          memo.update(claim, result)
          result

    ledger.all.iterator.map(_.id).filter(id => claimVisible(id, Set.empty)).toSet

  /** Claims visible under a horizon; `None` means "all" (omniscient). */
  def visibleUnder(horizon: EpistemicHorizon, ledger: ClaimLedger): Option[Set[ClaimId]] =
    horizon match
      case EpistemicHorizon.Omniscient       => None
      case EpistemicHorizon.ReaderAt(offset) => Some(visibleClaims(offset, ledger))

  /** Support restricted to the horizon: identity when omniscient; drops spans ending after `t`. */
  def clipSupport(support: SpanSet, horizon: EpistemicHorizon): Option[SpanSet] = horizon match
    case EpistemicHorizon.Omniscient       => Some(support)
    case EpistemicHorizon.ReaderAt(offset) =>
      SpanSet.of(support.refs.toVector.filter(_.span.endExclusive <= offset))

  /** Canonical, order-independent parts of shared view state for configuration checksums. */
  def stateParts(state: CommonViewState): Vector[String] =
    val horizon = state.horizon match
      case EpistemicHorizon.Omniscient       => "horizon:omniscient"
      case EpistemicHorizon.ReaderAt(offset) => s"horizon:reader:$offset"
    val selection = state.selection.toVector.sortBy(_.render).map(a => s"selection:${a.render}")
    val focus = Vector(state.focus.fold("focus:none")(a => s"focus:some:${a.render}"))
    val relations = state.relationLayers.toVector.sortBy(_.toString).map(r => s"relation:$r")
    val feature = Vector(state.feature.fold("feature:none") {
      case FeatureSelection.Raw(space)                => s"feature:raw:${space.value}"
      case FeatureSelection.Derived(derivation, None) =>
        s"feature:derived:${derivation.hex}"
      case FeatureSelection.Derived(derivation, Some(basisId)) =>
        s"feature:derived:${derivation.hex}:basis:${basisId.hex}"
    })
    Vector(horizon) ++ selection ++ focus ++ relations ++ feature
