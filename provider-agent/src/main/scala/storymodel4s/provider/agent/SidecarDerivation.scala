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
  */
object SidecarDerivation:
  private[agent] def rows(markers: Vector[TokenMarker]): Vector[SidecarRow] =
    markers.zipWithIndex.map { (marker, ordinal) =>
      SidecarRow(ordinal, providerNodeId(marker.site), marker.marker.indices)
    }

  private def providerNodeId(site: MarkerSite): String = site match
    case MarkerSite.OnConcept(node) => node.value
    case MarkerSite.OnRole(edge)    => s"role-$edge"
    case MarkerSite.OnTarget(edge)  => s"target-$edge"
