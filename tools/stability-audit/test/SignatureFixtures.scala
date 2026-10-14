package storymodel4s.stabilityfixture

import storymodel4s.core.OpaqueId

final class ParameterOnly
sealed trait Lower
sealed trait Upper
final class BoundOnly extends Upper
opaque type OpaqueOnly = String
object OpaqueOnly:
  def checked(value: String): OpaqueOnly = value

object FixtureId extends OpaqueId("FixtureId")
type FixtureId = FixtureId.T

/** Fixtures deliberately put dependencies only in signature positions. */
object FixtureFacade:
  def overload(value: ParameterOnly): Unit = ()
  def overload(value: String): BoundOnly = new BoundOnly
  def bounded[A >: Lower <: Upper](value: A): A = value
  def opaque(value: OpaqueOnly): OpaqueOnly = value
  def inheritedOpaque(value: FixtureId): FixtureId = value
  extension (value: ParameterOnly) def extensionResult: BoundOnly = new BoundOnly
  given Conversion[ParameterOnly, BoundOnly] with
    def apply(value: ParameterOnly): BoundOnly = new BoundOnly

final case class Construction(value: ParameterOnly = new ParameterOnly)
