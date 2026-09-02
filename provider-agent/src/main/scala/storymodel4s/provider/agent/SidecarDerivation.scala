package storymodel4s.provider.agent

import storymodel4s.amr.graph.{MarkerSite, TokenMarker}

/** One `explicit-index-list/v1` sidecar row mirroring one decoded marker. */
private[agent] final case class SidecarRow(
    ordinal: Int,
    providerNodeId: String,
    tokenIndices: Vector[Int]
)

/** Why a decoded marker set could not become a sidecar: a marker sat somewhere the prompt forbids.
  */
private[agent] enum SidecarRefusal:
  case MarkerNotOnConcept(row: Int, site: MarkerSite)

/** Derive the sidecar the admission court requires from the markers the model wrote inline.
  *
  * Why derivation and not a second model output: two sources of truth for one alignment would let
  * them disagree silently. Rows mirror the markers verbatim in decoder order; indices are neither
  * sorted, deduplicated, nor range-filtered here, so the court's checks still bite. A marker on a
  * role or a literal target is refused outright: the prompt forbids it, and the court would
  * otherwise turn it into relation or attribute evidence the model was never asked for.
  */
object SidecarDerivation:
  private[agent] def derive(
      markers: Vector[TokenMarker]
  ): Either[SidecarRefusal, Vector[SidecarRow]] =
    markers.zipWithIndex
      .foldLeft[Either[SidecarRefusal, Vector[SidecarRow]]](Right(Vector.empty)) {
        case (refused @ Left(_), _)           => refused
        case (Right(rows), (marker, ordinal)) =>
          marker.site match
            case MarkerSite.OnConcept(node) =>
              Right(rows :+ SidecarRow(ordinal, node.value, marker.marker.indices))
            case other => Left(SidecarRefusal.MarkerNotOnConcept(ordinal, other))
      }
