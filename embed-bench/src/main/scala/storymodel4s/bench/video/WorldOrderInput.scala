package storymodel4s.bench.video

import java.time.LocalDate

import storymodel4s.core.Checksum

/** Who asserts a story-world clock for an edition, and on what basis.
  *
  * A declaration, not a measurement. It exists so that a claim about story-world order — that the
  * edition presents its events in that order, or that a supplied rank is that order — is written
  * where a reader can find it, date it and dispute it, instead of being the builder's silent
  * default. It is rendered into run provenance, never into the view.
  */
final case class WorldOrderWitness(assertedBy: String, basis: String, asserted: LocalDate)

/** Why a source carries no story-world order. */
enum WorldOrderAbsence:
  /** Nobody supplied one and nothing is claimed either way. */
  case NotSupplied

  /** The edition is known to reorder story time and no explicit rank has been supplied. */
  case EditionNonlinear

/** Why an explicit rank was refused by [[TimedSourceView.build]]. */
enum WorldOrderRefusal:
  case EmptyRank
  case RankMissingLeaves(ordinals: Vector[Int])
  case RankNamesUnknownLeaves(ordinals: Vector[Int])

  def message: String = this match
    case EmptyRank                   => "explicit world order is empty"
    case RankMissingLeaves(ordinals) =>
      s"explicit world order ranks no leaf for segment ordinals ${ordinals.mkString(",")}"
    case RankNamesUnknownLeaves(ordinals) =>
      s"explicit world order names segment ordinals that do not exist: ${ordinals.mkString(",")}"

/** The story-world clock a timed source is built under. Required by [[TimedSourceView.build]];
  * there is no default, because the default this replaces was the discourse clock wearing the world
  * clock's name (mission commitment 5; ADR 0007, "missing alignment is a typed refusal"; ADR 0013).
  *
  *   - `Explicit`: a rank over leaf segments keyed by `TimedSegment.ordinal`, ties allowed, with a
  *     witness saying who supplied it and from what. World-time succession runs from every node at
  *     one rank to every node at the next distinct rank; a tie is never broken by presentation
  *     order, so an explicit rank cannot smuggle the discourse clock back in. The rank is checked
  *     against the segments it will order when the view is built.
  *   - `SameAsPresentation`: the edition is declared to present its events in story order, and the
  *     world-time layer is the discourse succession under that declaration.
  *   - `Unknown`: no world-time layer and no world order. Downstream that is absence, not zero:
  *     `RecallSignature.worldChronology` has no value, `worldBackwardMass` is `None`, and a
  *     projection that weights either refuses.
  *
  * Every witness is part of the run's provenance.
  */
enum WorldOrderInput:
  case Explicit(byOrdinal: Map[Int, Int], witness: WorldOrderWitness)
  case SameAsPresentation(witness: WorldOrderWitness)
  case Unknown(reason: WorldOrderAbsence)

  /** Canonical one-line rendering for run provenance. Independent of map ordering. A change to any
    * rendering here moves every provenance checksum produced after it; that is deliberate, so the
    * match is spelled out rather than left to `toString`.
    */
  def render: String = this match
    case Explicit(byOrdinal, w) =>
      val listing = byOrdinal.toVector.sorted.map((o, r) => s"$o:$r").mkString(",")
      s"explicit(n=${byOrdinal.size}; sha256=${Checksum.ofText(listing).hex}; ${witnessRendering(w)})"
    case SameAsPresentation(w)                  => s"sameAsPresentation(${witnessRendering(w)})"
    case Unknown(WorldOrderAbsence.NotSupplied) => "unknown(NotSupplied)"
    case Unknown(WorldOrderAbsence.EditionNonlinear) => "unknown(EditionNonlinear)"

  private def witnessRendering(w: WorldOrderWitness): String =
    s"by=${w.assertedBy}; on=${w.asserted}; basis=${w.basis}"
