package storymodel4s.acquire

import cats.data.ValidatedNec
import cats.syntax.all.*
import storymodel4s.core.*

/** Reference to a versioned prompt package. The checksum is over the manifest's canonical form so a
  * receipt pins exactly which instructions an agent ran under.
  */
final case class PromptPackageRef(name: String, version: String, checksum: Checksum)

/** The role a prompt package plays in the agent architecture (design record §91). Closed with a
  * `Custom` escape so manifests are typed, not stringly described.
  */
enum PromptRole:
  case LocalSemanticsProposer
  case DocumentResolver
  case HierarchyProposer
  case Critic(family: CriticFamily)
  case Custom(namespace: String, name: String)

  def render: String = this match
    case Critic(f)     => s"critic:$f"
    case Custom(ns, n) => s"custom:$ns:$n"
    case other         => other.toString

/** Operations a prompt package permits its agent to emit (design record §93). Mirrors the patch
  * vocabulary plus abstention.
  */
enum PermittedOperation:
  case AddConcept
  case AddRelation
  case RemoveRelation
  case SetFocus
  case ProposeMerge
  case ProposeSplit
  case ProposeRelation
  case SetContext
  case Annotate
  case Abstain
  case Custom(namespace: String, name: String)

  def render: String = this match
    case Custom(ns, n) => s"custom:$ns:$n"
    case other         => other.toString

/** A prompt package is a versioned, tested program artifact, not prose in application code (design
  * record §93). This is its data contract; the prompt text itself lives with the provider that
  * renders it.
  */
final case class PromptPackageManifest(
    name: String,
    version: String,
    role: PromptRole,
    inputSchemaId: String,
    outputSchemaId: String,
    permittedOperations: Vector[PermittedOperation],
    prohibitedInferences: Vector[String],
    standardsRefs: Vector[StandardsRef],
    exampleIds: Vector[String],
    counterexampleIds: Vector[String],
    abstentionRules: Vector[String],
    selfCheck: Vector[String],
    benchmarkSuiteId: String
):
  /** Deterministic, injective serialization: one `key<US>value` line per field, list items joined
    * by `<RS>`, `StandardsRef` fields joined by `<GS>`. Fields may not contain these separators or
    * newlines (checked by [[PromptPackageManifest.validate]]).
    */
  def canonicalForm: String =
    import PromptPackageManifest.{FS, GS, RS}
    def list(xs: Vector[String]) = xs.mkString(RS)
    val refs = standardsRefs.map(r => s"${r.standard}$GS${r.version}$GS${r.section}")
    Vector(
      "name" -> name,
      "version" -> version,
      "role" -> role.render,
      "inputSchemaId" -> inputSchemaId,
      "outputSchemaId" -> outputSchemaId,
      "permittedOperations" -> list(permittedOperations.map(_.render)),
      "prohibitedInferences" -> list(prohibitedInferences),
      "standardsRefs" -> list(refs),
      "exampleIds" -> list(exampleIds),
      "counterexampleIds" -> list(counterexampleIds),
      "abstentionRules" -> list(abstentionRules),
      "selfCheck" -> list(selfCheck),
      "benchmarkSuiteId" -> benchmarkSuiteId
    ).map((k, v) => s"$k$FS$v").mkString("\n")

  def checksum: Checksum = Checksum.ofText(canonicalForm)
  def ref: PromptPackageRef = PromptPackageRef(name, version, checksum)

object PromptPackageManifest:
  /** Unit separator between key and value. */
  private[acquire] val FS: String = "\u001f"

  /** Record separator between list items. */
  private[acquire] val RS: String = "\u001e"

  /** Group separator inside a `StandardsRef`. */
  private[acquire] val GS: String = "\u001d"
  private val Forbidden: Set[Char] = Set('\u001f', '\u001e', '\u001d', '\n', ':')

  private def nonEmpty(path: String, v: String): ValidatedNec[DomainError, Unit] =
    if v.trim.isEmpty then DomainError.InvariantViolation(path, "must be nonempty").invalidNec
    else ().validNec

  private def nonEmptyList[A](path: String, v: Vector[A]): ValidatedNec[DomainError, Unit] =
    if v.isEmpty then DomainError.InvariantViolation(path, "must list at least one item").invalidNec
    else ().validNec

  private def clean(
      path: String,
      v: String,
      forbidden: Set[Char]
  ): ValidatedNec[DomainError, Unit] =
    if v.exists(forbidden.contains) then
      DomainError.InvalidFormat(path, v, "contains a reserved separator character").invalidNec
    else ().validNec

  private def customParts(role: PromptRole): Vector[String] = role match
    case PromptRole.Custom(ns, n) => Vector(ns, n)
    case _                        => Vector.empty

  private def customParts(op: PermittedOperation): Vector[String] = op match
    case PermittedOperation.Custom(ns, n) => Vector(ns, n)
    case _                                => Vector.empty

  /** Required fields present; nonempty operations, abstention rules, and self-check; no reserved
    * separators anywhere (which is what makes `canonicalForm` injective). `Custom` role/operation
    * parts additionally may not contain `:`, the separator their rendering uses.
    */
  def validate(m: PromptPackageManifest): ValidatedNec[DomainError, PromptPackageManifest] =
    val separators = Forbidden - ':'
    val scalars = Vector(
      "name" -> m.name,
      "version" -> m.version,
      "inputSchemaId" -> m.inputSchemaId,
      "outputSchemaId" -> m.outputSchemaId,
      "benchmarkSuiteId" -> m.benchmarkSuiteId
    )
    val lists = Vector(
      "abstentionRules" -> m.abstentionRules,
      "selfCheck" -> m.selfCheck
    )
    val optionalLists = Vector(
      "prohibitedInferences" -> m.prohibitedInferences,
      "exampleIds" -> m.exampleIds,
      "counterexampleIds" -> m.counterexampleIds
    )
    val refStrings = m.standardsRefs
      .flatMap(r => Vector(r.standard, r.version, r.section))
      .map("standardsRefs" -> _)
    val customs = customParts(m.role).map("role" -> _) ++
      m.permittedOperations.flatMap(customParts).map("permittedOperations" -> _)
    val all = scalars ++ lists.flatMap((k, vs) => vs.map(k -> _)) ++
      optionalLists.flatMap((k, vs) => vs.map(k -> _)) ++ refStrings
    (
      scalars.traverse_((k, v) => nonEmpty(s"prompt/$k", v)),
      lists.traverse_((k, vs) => nonEmptyList(s"prompt/$k", vs)),
      lists.traverse_((k, vs) => vs.traverse_(v => nonEmpty(s"prompt/$k", v))),
      nonEmptyList("prompt/permittedOperations", m.permittedOperations),
      customs.traverse_((k, v) => nonEmpty(s"prompt/$k", v)),
      all.traverse_((k, v) => clean(s"prompt/$k", v, separators)),
      customs.traverse_((k, v) => clean(s"prompt/$k", v, Forbidden))
    ).mapN((_, _, _, _, _, _, _) => m)

  /** True when `ref` was computed from exactly this manifest. */
  def verify(m: PromptPackageManifest, ref: PromptPackageRef): Boolean =
    ref.name == m.name && ref.version == m.version && ref.checksum == m.checksum
