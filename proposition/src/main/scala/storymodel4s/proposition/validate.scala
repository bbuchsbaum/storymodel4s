package storymodel4s.proposition

import cats.data.{NonEmptyChain, Validated, ValidatedNec}
import cats.syntax.all.*
import storymodel4s.core.*

/** A structural violation found while checking a chart. `path` locates the offending component. */
enum ChartViolation:
  case MissingFocus(id: ConceptId)
  case DanglingRelationSource(index: Int, id: ConceptId)
  case DanglingRelationTarget(index: Int, id: ConceptId)
  case NumberedRoleOutOfRange(index: Int, argIndex: Int)
  case InvalidRoleName(index: Int, name: String, why: String)
  case NumberedRoleAsNamed(index: Int, name: String)
  case UnlicensedNormalization(index: Int, from: ConceptId)
  case DuplicateRelation(index: Int)
  case DanglingPolarity(id: ConceptId)
  case DanglingEmbeddingContainer(index: Int, id: ConceptId)
  case DanglingEmbeddingContent(index: Int, id: ConceptId)
  case SelfEmbedding(index: Int, id: ConceptId)
  case DuplicateEmbedding(index: Int)
  case DanglingAlignmentConcept(index: Int, id: ConceptId)
  case UnknownAlignmentRelation(index: Int)
  case InvalidClaim(index: Int, error: DomainError)

  def path: String = this match
    case MissingFocus(_)                  => "chart/focus"
    case DanglingRelationSource(i, _)     => s"chart/relations/$i/from"
    case DanglingRelationTarget(i, _)     => s"chart/relations/$i/to"
    case NumberedRoleOutOfRange(i, _)     => s"chart/relations/$i/role"
    case InvalidRoleName(i, _, _)         => s"chart/relations/$i/role"
    case NumberedRoleAsNamed(i, _)        => s"chart/relations/$i/role"
    case UnlicensedNormalization(i, _)    => s"chart/relations/$i/role/normalized"
    case DuplicateRelation(i)             => s"chart/relations/$i"
    case DanglingPolarity(id)             => s"chart/polarity/${id.value}"
    case DanglingEmbeddingContainer(i, _) => s"chart/embedded/$i/container"
    case DanglingEmbeddingContent(i, _)   => s"chart/embedded/$i/content"
    case SelfEmbedding(i, _)              => s"chart/embedded/$i"
    case DuplicateEmbedding(i)            => s"chart/embedded/$i"
    case DanglingAlignmentConcept(i, _)   => s"chart/alignments/$i/target"
    case UnknownAlignmentRelation(i)      => s"chart/alignments/$i/target"
    case InvalidClaim(i, _)               => s"chart/alignments/$i/meta"

  def message: String = this match
    case MissingFocus(id)                 => s"focus ${id.value} is not a concept"
    case DanglingRelationSource(_, id)    => s"relation source ${id.value} is not a concept"
    case DanglingRelationTarget(_, id)    => s"relation target ${id.value} is not a concept"
    case NumberedRoleOutOfRange(_, a)     => s"ARG$a outside 0..${SourceRole.MaxNumbered}"
    case InvalidRoleName(_, n, why)       => s"role name '$n': $why"
    case NumberedRoleAsNamed(_, n)        => s"role '$n' is a numbered argument; use Numbered"
    case UnlicensedNormalization(_, from) =>
      s"numbered role on frameless concept ${from.value} carries a normalized role"
    case DuplicateRelation(_)              => "identical relation appears more than once"
    case DanglingPolarity(id)              => s"polarity on non-concept ${id.value}"
    case DanglingEmbeddingContainer(_, id) => s"embedding container ${id.value} is not a concept"
    case DanglingEmbeddingContent(_, id)   => s"embedding content ${id.value} is not a concept"
    case SelfEmbedding(_, id)              => s"concept ${id.value} embeds itself"
    case DuplicateEmbedding(_)             => "identical embedding appears more than once"
    case DanglingAlignmentConcept(_, id)   => s"alignment references non-concept ${id.value}"
    case UnknownAlignmentRelation(_)       => "alignment references a relation not in the chart"
    case InvalidClaim(_, e)                => e.message

/** Promotes an unchecked chart to a checked one, accumulating every violation.
  *
  * Checking is structural only: it never consults a frame lexicon, so a chart with unknown frames,
  * bare lemmas, or `Unknown` fillers is valid by design. What it does enforce about roles: numbered
  * indices are in range, named roles are bare and well-formed, numbered arguments are never
  * smuggled in as names (`Named("ARG0")`, `Extension(_, "arg1")`), and no numbered argument on a
  * frameless concept carries a normalized role (design record §102). Embeddings must be unique;
  * embedding *cycles* are legitimate (AMR §41.3: "the boy wants the girl to believe that he wants
  * it" holds `want` under `believe` under `want`) and are not rejected.
  */
object ChartValidator:
  type Result = ValidatedNec[ChartViolation, PropositionChart[Checked]]

  def validate[C <: CheckState](chart: PropositionChart[C]): Result =
    val violations = Vector.newBuilder[ChartViolation]
    val ids = chart.concepts.keySet

    chart.focus.filterNot(ids.contains).foreach(id => violations += ChartViolation.MissingFocus(id))

    val seen = scala.collection.mutable.HashSet.empty[PropositionRelation]
    chart.relations.zipWithIndex.foreach { (r, i) =>
      if !ids.contains(r.from) then violations += ChartViolation.DanglingRelationSource(i, r.from)
      r.to.nodeId.filterNot(ids.contains).foreach { id =>
        violations += ChartViolation.DanglingRelationTarget(i, id)
      }
      val frameless = chart.concepts.get(r.from).forall(_.frame.isEmpty)
      r.role.source match
        case SourceRole.Numbered(a) =>
          if a < 0 || a > SourceRole.MaxNumbered then
            violations += ChartViolation.NumberedRoleOutOfRange(i, a)
          if frameless && r.role.normalized.nonEmpty then
            violations += ChartViolation.UnlicensedNormalization(i, r.from)
        case SourceRole.Named(n) =>
          SourceRole
            .nameProblem(n)
            .foreach(why => violations += ChartViolation.InvalidRoleName(i, n, why))
          if SourceRole.numberedShape(n).nonEmpty then
            violations += ChartViolation.NumberedRoleAsNamed(i, n)
        case SourceRole.Extension(ns, n) =>
          SourceRole
            .nameProblem(n)
            .foreach(why => violations += ChartViolation.InvalidRoleName(i, n, why))
          if ns.isEmpty || ns.exists(_.isWhitespace) then
            violations += ChartViolation.InvalidRoleName(i, s"$ns:$n", "invalid namespace")
          if SourceRole.numberedShape(n).nonEmpty then
            violations += ChartViolation.NumberedRoleAsNamed(i, r.role.source.render)
        case SourceRole.Operand(k) =>
          if k < 1 then violations += ChartViolation.InvalidRoleName(i, s"op$k", "operand < 1")
      if !seen.add(r) then violations += ChartViolation.DuplicateRelation(i)
    }

    chart.polarity.keys.toVector.sorted.filterNot(ids.contains).foreach { id =>
      violations += ChartViolation.DanglingPolarity(id)
    }

    val seenEmb = scala.collection.mutable.HashSet.empty[EmbeddedProposition]
    chart.embedded.zipWithIndex.foreach { (e, i) =>
      if !ids.contains(e.container) then
        violations += ChartViolation.DanglingEmbeddingContainer(i, e.container)
      if !ids.contains(e.content) then
        violations += ChartViolation.DanglingEmbeddingContent(i, e.content)
      if e.container == e.content then violations += ChartViolation.SelfEmbedding(i, e.content)
      if !seenEmb.add(e) then violations += ChartViolation.DuplicateEmbedding(i)
    }

    val relationSet = chart.relations.toSet
    chart.alignments.zipWithIndex.foreach { (a, i) =>
      a.target match
        case AlignmentTarget.Concepts(cs) =>
          cs.toSortedSet.filterNot(ids.contains).foreach { id =>
            violations += ChartViolation.DanglingAlignmentConcept(i, id)
          }
        case AlignmentTarget.Relation(r) =>
          if !relationSet.contains(r) then violations += ChartViolation.UnknownAlignmentRelation(i)
      ClaimMeta.validated(a.meta).left.foreach(e => violations += ChartViolation.InvalidClaim(i, e))
    }

    NonEmptyChain.fromSeq(violations.result()) match
      case Some(nec) => Validated.invalid(nec)
      case None      =>
        Validated.valid(
          new PropositionChart[Checked](
            chart.focus,
            chart.concepts,
            chart.relations,
            chart.polarity,
            chart.embedded,
            chart.alignments,
            chart.provenance,
            chart.sentence
          )
        )

  /** Convenience: violations as a `Left` list. */
  def check[C <: CheckState](
      chart: PropositionChart[C]
  ): Either[Vector[ChartViolation], PropositionChart[Checked]] =
    validate(chart).toEither.leftMap(_.toChain.toVector)
