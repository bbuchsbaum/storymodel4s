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

final class ConstructorParameterOnly
final class Constructed(value: ConstructorParameterOnly)
class ParentOnly(value: Int)
final class ParentRoot extends ParentOnly(1)
final class FactoryParameterOnly
final class BuildParameterOnly
final class WrappedFactoryParameterOnly
final class Returned private ()
type ReturnedAlias = Returned
final class AliasParameterOnly
object Returned:
  def create(value: FactoryParameterOnly): Returned = new Returned
  def build(value: BuildParameterOnly): Returned = new Returned
  def wrapped(value: WrappedFactoryParameterOnly): Option[Returned] = Some(new Returned)
  def aliased(value: AliasParameterOnly): ReturnedAlias = new Returned
object ConstructorFacade:
  def returned: Returned = ???

final class HiddenParameterOnly
final class PrivateReturned private (value: HiddenParameterOnly)
object PrivateConstructorFacade:
  def returned: PrivateReturned = ???
