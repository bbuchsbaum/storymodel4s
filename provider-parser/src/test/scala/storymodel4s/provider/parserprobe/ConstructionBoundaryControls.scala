package storymodel4s.provider.parserprobe

import storymodel4s.core.{Checksum, SurfaceUnitId, TextSpan}
import storymodel4s.provider.parser.*

/** Same-field control showing every generated door on the former cache record shape. */
final case class CacheBoundaryShape(
    key: ParserCacheKey,
    request: Checksum,
    proposal: ParserProposal,
    receipt: Checksum,
    checksum: Checksum
)

/** Same-field control showing every generated door on the former atlas-token shape. */
final case class TokenBoundaryShape(id: SurfaceUnitId, span: TextSpan, text: String)

/** Same-field control showing every generated door on the former report shape. */
final case class ReportBoundaryShape(checks: Vector[ParserDeterminismCheck])

/** JVM bytecode control for the case-class static `fromProduct` forwarder. */
final case class CompanionFromProductBoundaryShape(left: Int, right: Int)
