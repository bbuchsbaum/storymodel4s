package storymodel4s.provider.agent

import storymodel4s.amr.graph.{MarkerSite, TokenMarker}

/** One `explicit-index-list/v1` sidecar row mirroring one decoded marker. */
private[agent] final case class SidecarRow(
    ordinal: Int,
    providerNodeId: String,
    tokenIndices: Vector[Int]
)

/** Derive the sidecar the admission court requires from the markers the model wrote inline.
  *
  * Why derivation and not a second model output: two sources of truth for one alignment would let
  * them disagree silently. Rows mirror the markers verbatim in decoder order; indices are neither
  * sorted, deduplicated, nor range-filtered here, so the court's checks still bite.
  *
  * Why every site kind is mirrored rather than only concepts: a marker that sits on a role, on a
  * reentrant reference, or on a literal target is evidence, not noise. It says which token carried
  * a negation, where an entity is mentioned again, or which token a quantity came from, and
  * `AmrCandidates` already represents all three as relation, reentrancy and attribute alignments.
  * An earlier version refused them on the grounds that the prompt asks for concept markers only;
  * measured against fifty captured replies that refusal discarded seven whole sentences and every
  * mention-level alignment in the rest, which is the opposite of keeping an observation visible in
  * the part of the model that can hold it. The prompt still asks for concept markers, because they
  * are the ones every downstream rule uses; a model that volunteers more is not punished for it.
  *
  * Mirroring is total, so this function cannot refuse. A marker whose edge the decoder cannot
  * canonicalize is dropped downstream by `AmrCandidates`, and the resulting count mismatch is
  * refused by the admission court with its own typed issue: the judgment stays where the evidence
  * to make it lives.
  */
object SidecarDerivation:
  /** Name the site a row came from, so a reader of the sidecar can tell a concept alignment from a
    * relation or reentrancy one without re-parsing the PENMAN. The court validates this only as an
    * opaque provider node id, so the shape is ours to choose; it must stay blank-free and short.
    */
  private[agent] def siteId(site: MarkerSite): String = site match
    case MarkerSite.OnConcept(node) => node.value
    case MarkerSite.OnRole(edge)    => s"role:$edge"
    case MarkerSite.OnTarget(edge)  => s"target:$edge"

  private[agent] def derive(markers: Vector[TokenMarker]): Vector[SidecarRow] =
    markers.zipWithIndex.map { (marker, ordinal) =>
      SidecarRow(ordinal, siteId(marker.site), marker.marker.indices)
    }
