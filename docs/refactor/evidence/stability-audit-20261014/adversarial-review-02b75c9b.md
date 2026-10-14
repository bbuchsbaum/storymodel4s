# Adverse independent review: 02b75c9b

The independent compiled probe found three omissions in the previous extractor:

- `Constructed(value: ConstructorParameterOnly)` did not retain its constructor-only parameter.
- `ParentRoot extends ParentOnly(1)` did not retain its term-shaped parent.
- A reached `Returned` did not retain `Returned.create(FactoryParameterOnly)`.

The reproducer source and roots were:

```scala
package storymodel4s.auditprobe
final class ConstructorParameterOnly
final class Constructed(value: ConstructorParameterOnly)
class ParentOnly(value: Int)
final class ParentRoot extends ParentOnly(1)
final class FactoryParameterOnly
final class Returned private ()
object Returned: def create(value: FactoryParameterOnly): Returned = new Returned
object Facade: def returned: Returned = ???
```

Roots: `Constructed`, `ParentRoot`, and `Facade.returned`. The prior inventory omitted all
three dependency types. The repaired fixture mirrors these cases and asserts each dependency,
the constructor-parameter, term-parent, and bounded companion-factory edge kinds.

The follow-up independent review of `64d787c5` found that the original factory-name allowlist
still omitted `Returned.build(BuildParameterOnly): Returned`, and that a private constructor
could promote its hidden parameter. The repair now selects every public companion method whose
result structurally contains the reached class, including wrapper results, and reads constructor
parameters only when the constructor is externally accessible. Fixture assertions cover both
`build` and `wrapped(...): Option[Returned]`, while proving `HiddenParameterOnly` remains absent
behind `PrivateReturned`'s private constructor.
