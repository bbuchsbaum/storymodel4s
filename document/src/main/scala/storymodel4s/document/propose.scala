package storymodel4s.document

import cats.data.NonEmptyVector
import cats.syntax.all.*
import storymodel4s.acquire.*
import storymodel4s.core.*
import storymodel4s.core.TextNorm.lower as foldCase
import storymodel4s.features.CanonicalDouble
import storymodel4s.proposition.{
  Canonical,
  ChartOrigin,
  Checked,
  Concept,
  ConceptId,
  ConceptKind,
  Gloss,
  Polarity as ChartPolarity,
  PropositionAlignment,
  PropositionChart,
  PropositionEvidence,
  RoleAssignment,
  SourceRole
}
import storymodel4s.story.{
  CircumstanceKind,
  EntityType,
  Modality,
  ParticipantRole,
  Polarity as StoryPolarity,
  Predicate,
  StoryModel,
  TemporalRelation
}

/** Why a chart with concepts yielded no situation attempt at its anchor.
  *
  * Why typed: the compiler refuses every root that is not the chart focus (or a branch of a
  * coordinating focus), not a situation shape, or embedded; recording which of those held keeps "no
  * proposal" distinguishable from "not run".
  */
enum AbstentionReason:
  case NoFocus
  case FocusNotPredicate(kind: ConceptKind)
  case FocusEmbedded

  /** A coordinating focus with no `:opN` or `:sntN` branch to descend into. */
  case NoCoordinationBranch

  /** A branch of a coordinating focus that is itself a coordinator. The provider does not descend:
    * see [[ChartProposalProvider.RulesText]].
    */
  case NestedCoordination

  /** A branch of a coordinating focus that is not an admissible situation root. */
  case BranchNotAdmissible(kind: ConceptKind)

  /** A branch of a coordinating focus that some embedding of the chart holds. */
  case BranchEmbedded

  def render: String = this match
    case NoFocus                   => "no-focus"
    case FocusNotPredicate(kind)   => s"focus-not-predicate:$kind"
    case FocusEmbedded             => "focus-embedded"
    case NoCoordinationBranch      => "no-coordination-branch"
    case NestedCoordination        => "coordination-branch-nested"
    case BranchNotAdmissible(kind) => s"coordination-branch-not-admissible:$kind"
    case BranchEmbedded            => "coordination-branch-embedded"

/** One branch of a coordinating focus, and what the provider did with it.
  *
  * Why the abstained branches are carried and not dropped: a sentence whose focus coordinates three
  * clauses of which two are predicates has one silent clause, and a ledger that listed only the two
  * admitted roots would read as complete coverage of the sentence.
  */
enum CoordinatedBranch:
  /** `counts` divides the branch's fillers exactly as it does on [[SentenceCoverage.Proposed]]. */
  case Admitted(root: ChartNodeRef, role: SourceRole, counts: FillerCounts)
  case Abstained(root: ChartNodeRef, role: SourceRole, reason: AbstentionReason)

  /** The branch root, whichever way the branch went. */
  def branchRoot: ChartNodeRef = this match
    case Admitted(root, _, _)  => root
    case Abstained(root, _, _) => root

  /** The role that attached the branch to its coordinator. */
  def branchRole: SourceRole = this match
    case Admitted(_, role, _)  => role
    case Abstained(_, role, _) => role

/** How the entity-kind fillers the chart reaches from one root divided under the referentiality
  * rule. Honest product data: every combination of counts is a lawful state of some chart.
  *
  * Why four counters and not one: "the chart reached no participant here" is a different fact from
  * "it reached a time", from "it reached a role nothing licenses as referential", and from "no
  * single normalized role reached the filler at all". A ledger that summed them would let the
  * entity layer shrink with nothing saying where the fillers went, which is the shape of every
  * silent-drop defect this project has had.
  *
  *   - `referents` became participant edges and entity mentions;
  *   - `circumstances` became time or manner values on the situation;
  *   - `nonReferential` were refused by role or by concept kind, each with a receipt naming which;
  *   - `unlicensed` were reached by no single normalized role, and were never evaluated further.
  */
final case class FillerCounts(
    referents: Int,
    circumstances: Int,
    nonReferential: Int,
    unlicensed: Int
):
  /** Every filler the scan saw. Equal to the size of the scanned population, by construction. */
  def seen: Int = referents + circumstances + nonReferential + unlicensed

object FillerCounts:
  val empty: FillerCounts = FillerCounts(0, 0, 0, 0)

/** One ledger row per atlas sentence: what the provider did with it.
  *
  * Why a per-sentence ledger: a story with fifty sentences and three proposals must say, for the
  * other forty-seven, whether a chart was missing, empty, or inadmissible; silence would let a
  * partial run read as a complete one.
  */
enum SentenceCoverage:
  /** `counts` divides every entity-kind filler the chart reaches from the root; the sentence is the
    * root's own.
    */
  case Proposed(root: ChartNodeRef, counts: FillerCounts)

  /** A sentence whose focus coordinates several roots, with one entry per branch in discourse
    * order. Why one row and not several `Proposed` rows: the branches are siblings inside one
    * sentence, and emitting a row apiece would make the ledger claim more sentences than the atlas
    * has. A row with no admitted branch is a lawful value: it says the focus was a coordinator and
    * names why each branch abstained.
    */
  case Coordinated(coordinator: ChartNodeRef, branches: Vector[CoordinatedBranch])
  case Abstained(anchor: ChartNodeRef, reason: AbstentionReason)
  case EmptyChart(unit: SurfaceUnitId)
  case NoChart(unit: SurfaceUnitId)

  /** The sentence the row is about: derived from the chart node for rows that carry one. */
  def sentence: SurfaceUnitId = this match
    case Proposed(root, _)      => root.sentence
    case Coordinated(anchor, _) => anchor.sentence
    case Abstained(anchor, _)   => anchor.sentence
    case EmptyChart(unit)       => unit
    case NoChart(unit)          => unit

  /** The roots this sentence actually produced, in discourse order; empty when it produced none. */
  def admittedRoots: Vector[ChartNodeRef] = this match
    case Proposed(root, _)  => Vector(root)
    case Coordinated(_, bs) =>
      bs.collect { case CoordinatedBranch.Admitted(root, _, _) =>
        root
      }
    case Abstained(_, _) => Vector.empty
    case EmptyChart(_)   => Vector.empty
    case NoChart(_)      => Vector.empty

/** Whether a story-summary proposal was emitted; the title is the only summary source here.
  *
  * Why three cases and not two: a source with no title and a source carrying a title nobody
  * established are different states of the world, and the provider abstains in both. Folding them
  * together would hide the one that needs a caller's attention — a title is sitting right there and
  * the model refuses to publish it — behind the one that needs a new rule.
  */
enum SummaryCoverage:
  case Proposed(title: String, provenance: TitleProvenance)

  /** The source carries no title at all. */
  case NoTitle

  /** The source carries a title whose provenance it does not record, so nothing entitles the model
    * to publish it. A filename put there by a tool is the case this exists for.
    */
  case TitleUnestablished

/** Coverage counts over the sentence ledger. Honest product data: every combination is lawful.
  *
  * `coordinated` counts sentences, not roots: one coordinating focus is one sentence however many
  * branches it admitted, so [[sentences]] still equals the number of atlas sentences.
  */
final case class CoverageCounts(
    proposed: Int,
    coordinated: Int,
    abstained: Int,
    emptyCharts: Int,
    noCharts: Int
):
  def sentences: Int = proposed + coordinated + abstained + emptyCharts + noCharts

/** Everything [[ChartProposalProvider.propose]] emitted for one story, in canonical order.
  *
  * Why a non-case class with a package-private factory: the attempt vectors stand in a relation
  * (one context, one membership, and one participant-coverage attempt per situation attempt, one
  * entity-mention attempt per participant filler, one evidence record per proposal, one coverage
  * row per sentence) that only the provider establishes. There is no `copy` or `fromProduct` door.
  */
final class ChartProposals private (
    val evidence: Vector[Evidence],
    val situations: Vector[SituationAttempt],
    val contexts: Vector[ContextAssignmentAttempt],
    val summary: StorySummaryAttempt,
    val memberships: Vector[SegmentMembershipAttempt],
    val causal: Vector[CausalAttempt],
    val entityMentions: Vector[EntityMentionAttempt],
    val participants: Vector[ParticipantAttempt],
    val participantCoverage: Vector[ParticipantCoverageAttempt],
    val circumstances: Vector[SituationCircumstanceAttempt],
    val temporal: Vector[TemporalAttempt],
    val calls: Vector[ProviderCall],
    val coverage: Vector[SentenceCoverage],
    val summaryCoverage: SummaryCoverage
):
  def counts: CoverageCounts =
    coverage.foldLeft(CoverageCounts(0, 0, 0, 0, 0)) { (acc, row) =>
      row match
        case SentenceCoverage.Proposed(_, _)    => acc.copy(proposed = acc.proposed + 1)
        case SentenceCoverage.Coordinated(_, _) => acc.copy(coordinated = acc.coordinated + 1)
        case SentenceCoverage.Abstained(_, _)   => acc.copy(abstained = acc.abstained + 1)
        case SentenceCoverage.EmptyChart(_)     => acc.copy(emptyCharts = acc.emptyCharts + 1)
        case SentenceCoverage.NoChart(_)        => acc.copy(noCharts = acc.noCharts + 1)
    }

  override def equals(other: Any): Boolean = other match
    case that: ChartProposals =>
      evidence == that.evidence &&
      situations == that.situations &&
      contexts == that.contexts &&
      summary == that.summary &&
      memberships == that.memberships &&
      causal == that.causal &&
      entityMentions == that.entityMentions &&
      participants == that.participants &&
      participantCoverage == that.participantCoverage &&
      circumstances == that.circumstances &&
      temporal == that.temporal &&
      calls == that.calls &&
      coverage == that.coverage &&
      summaryCoverage == that.summaryCoverage
    case _ => false

  override def hashCode(): Int =
    (
      evidence,
      situations,
      contexts,
      summary,
      memberships,
      causal,
      entityMentions,
      participants,
      participantCoverage,
      circumstances,
      temporal,
      calls,
      coverage,
      summaryCoverage
    ).##

  override def toString: String =
    val c = counts
    s"ChartProposals(proposed=${c.proposed}, coordinated=${c.coordinated}, " +
      s"abstained=${c.abstained}, empty=${c.emptyCharts}, noChart=${c.noCharts}, " +
      s"participants=${participants.size}, circumstances=${circumstances.size}, " +
      s"temporal=${temporal.size}, calls=${calls.size})"

object ChartProposals:
  private[document] def derived(
      evidence: Vector[Evidence],
      situations: Vector[SituationAttempt],
      contexts: Vector[ContextAssignmentAttempt],
      summary: StorySummaryAttempt,
      memberships: Vector[SegmentMembershipAttempt],
      entityMentions: Vector[EntityMentionAttempt],
      participants: Vector[ParticipantAttempt],
      participantCoverage: Vector[ParticipantCoverageAttempt],
      circumstances: Vector[SituationCircumstanceAttempt],
      temporal: Vector[TemporalAttempt],
      calls: Vector[ProviderCall],
      coverage: Vector[SentenceCoverage],
      summaryCoverage: SummaryCoverage
  ): ChartProposals =
    new ChartProposals(
      evidence,
      situations,
      contexts,
      summary,
      memberships,
      Vector.empty,
      entityMentions,
      participants,
      participantCoverage,
      circumstances,
      temporal,
      calls,
      coverage,
      summaryCoverage
    )

/** Deterministic, receipted proposal provider from sentence charts to compiler input.
  *
  * Why it exists: ADR 0005's compiler consumes typed proposals, and until now the only provider was
  * a one-sentence lexical fixture. This provider reads every chart of a story and emits the
  * situation, context-assignment, segment-membership, participant, entity-mention,
  * participant-coverage, temporal, and summary attempts the compiler needs, with typed abstention
  * wherever a chart has no admissible root. It never reads the sentence text for content: every
  * content word comes from the chart. Its rules are [[RulesText]], whose checksum is the
  * prompt-package checksum and the provenance config hash, so a rule change changes every receipt.
  */
object ChartProposalProvider:
  val Stage: StageId = StageId.unsafe("chart-proposal-provider")

  /** Extractor identity on every evidence record this provider writes. */
  val ProviderFingerprint: Fingerprint =
    Fingerprint.unsafe("storymodel4s:chart-proposal-provider:0.1")

  /** Provider identity on every receipt; the resolver counts agreement by this triple. */
  val ProviderName: String = "chart-proposal-provider"
  val ModelName: String = "chart-rules"
  val Version: String = "1"

  /** Calibration model of every rule that is a total function of the chart: the value cannot
    * disagree with what the chart says, so probability 1.0 is honest.
    */
  val CalibrationModel: String = "chart-rule-v1"

  /** Calibration model of the context rule. `NarratedWorld` is the absence-of-embedding default at
    * sentence grain (the focus is held by no embedding, so the sentence asserts it at root), not a
    * context the chart licenses positively; the name keeps that visible on every receipt.
    */
  val ContextCalibrationModel: String = "narrated-world-default-v1"

  /** Calibration model of the title-summary rule, which reads no chart. */
  val SummaryCalibrationModel: String = "title-rule-v1"

  /** Frame namespace of the `-91` reification set: the AMR adapter records every frame under its
    * `InteropTables.FrameNamespace`, which `ChartProposalCourtSuite` pins equal to this value
    * (`document` cannot depend on `amr-interop`). An id in another namespace is not a state.
    */
  val StateFrameNamespace: String = "propbank"

  /** The closed set of AMR `-91` reification frames whose focus denotes a state, not an event. */
  val StateFrames: Set[String] = Set(
    "be-destined-for-91",
    "be-from-91",
    "be-located-at-91",
    "be-temporally-at-91",
    "have-concession-91",
    "have-condition-91",
    "have-degree-91",
    "have-extent-91",
    "have-frequency-91",
    "have-instrument-91",
    "have-li-91",
    "have-manner-91",
    "have-mod-91",
    "have-name-91",
    "have-ord-91",
    "have-org-role-91",
    "have-part-91",
    "have-polarity-91",
    "have-purpose-91",
    "have-quant-91",
    "have-rel-role-91",
    "have-subevent-91",
    "have-value-91",
    "include-91"
  )

  /** Named (non-core) chart roles whose participant reading is stable across frames. Every entry
    * but `domain` is the AMR adapter's standard-role table verbatim; `domain` is this provider's
    * own, because the adapter's table does not normalize it and the predicative root rule needs the
    * concept a property is predicated of to reach the participant layer. It is `Custom("amr",
    * "domain")` and not `Theme` or `Patient`: `:domain` says which concept the head is predicated
    * of and nothing about how that concept participates, so naming a thematic role would assert
    * what the chart did not. Numbered arguments never appear here: their meaning is frame-specific
    * and only a lexicon-licensed normalization on the chart itself counts.
    */
  val NamedRoles: Map[String, ParticipantRole] = Map(
    "domain" -> ParticipantRole.Custom("amr", "domain"),
    "location" -> ParticipantRole.Location,
    "time" -> ParticipantRole.Time,
    "manner" -> ParticipantRole.Manner,
    "cause" -> ParticipantRole.Cause,
    "purpose" -> ParticipantRole.Custom("amr", "purpose"),
    "instrument" -> ParticipantRole.Instrument,
    "beneficiary" -> ParticipantRole.Beneficiary,
    "source" -> ParticipantRole.Source,
    "destination" -> ParticipantRole.Destination
  )

  /** Rule names recorded on receipts. */
  val SituationRule: String = "focus-situation-rule"
  val ContextRule: String = "narrated-world-context-rule"
  val MembershipRule: String = "primary-story-membership-rule"
  val ParticipantRule: String = "licensed-role-participant-rule"
  val MentionRule: String = "entity-filler-mention-rule"
  val CoverageRule: String = "participant-coverage-rule"
  val CircumstanceRule: String = "situation-circumstance-rule"
  val TemporalRule: String = "adjacent-root-unclear-rule"
  val SummaryRule: String = "title-summary-rule"
  val AbstainSituationRule: String = "abstain-situation-rule"
  val AbstainContextRule: String = "abstain-context-rule"
  val AbstainMembershipRule: String = "abstain-membership-rule"
  val AbstainCoverageRule: String = "abstain-coverage-rule"
  val AbstainSummaryRule: String = "abstain-summary-rule"

  /** Abstention reason when the source carries no title. */
  val NoTitleReason: String = "no-title"

  /** Abstention reason when the source carries a title but records no provenance for it. Named
    * separately from [[NoTitleReason]] so a receipt says which of the two happened.
    */
  val UnestablishedTitleReason: String = "title-provenance-unrecorded"

  /** The mapping rules, verbatim. Its checksum is the prompt-package checksum and the provenance
    * config hash.
    */
  val RulesText: String =
    s"""chart-proposal-provider rules, version 1
       |
       |root: the situation roots of a sentence are the chart focus when it is admissible, and
       |  otherwise the direct branches of the focus when the focus is a coordinator (below).
       |  There is no fallback to any other predicate; a chart whose focus is neither admissible
       |  nor a coordinator is abstained.
       |admissible: the concept is not held by any embedding and is one of four closed shapes.
       |  (1) predicate: kind Predicate. (2) state roleset: kind Special with a state frame
       |  (below); the AMR adapter classifies every -91 roleset as Special, so the state rule is
       |  reachable only through that clause, and any other Special focus (a frameless AMR special
       |  such as date-entity, or a -91 frame outside the closed set) abstains with
       |  focus-not-predicate:Special. (3) predicative: a frameless concept of kind Property or
       |  Entity whose ${ChartRoots.PredicationRole} role reaches a concept of the chart, as in
       |  (d / dead :${ChartRoots.PredicationRole} (h / he)); the property is the predicate and the
       |  ${ChartRoots.PredicationRole} filler is what it is predicated of. (4) existential: a
       |  frameless concept of kind Entity whose ${ChartRoots.ExistenceRole} role reaches a concept
       |  of the chart, as in (p / person :${ChartRoots.ExistenceRole} (e / egulac)); the sentence
       |  asserts that the entity is at that place. Shapes 3 and 4 require the role to reach a
       |  concept: a role reaching a literal, an unknown, or nothing is not the shape, because a
       |  predication with no subject and a location with no place are not what the chart said. No
       |  frame is invented for either: the predicate frame is the concept's own, which for a
       |  frameless root is none.
       |kind: State when the focus concept carries a frame in namespace $StateFrameNamespace whose
       |  id is in the closed set {${StateFrames.toVector.sorted.mkString(", ")}}, and State for
       |  the predicative and existential shapes, which assert how something is and not that
       |  something happened; otherwise Event. Lexical statives without such a frame are Event in
       |  this version.
       |coordination: a focus concept is a coordinator when it carries no frame and its lemma is in
       |  the closed set {${ChartRoots.CoordinationLemmas.toVector.sorted.mkString(", ")}}. No
       |  concept kind separates a coordinator from an ordinary entity, so the lemma names the set.
       |  Each direct branch of a coordinating focus — a relation under opN (and, or) or sntN
       |  (multi-sentence) whose target is a concept of this chart — is evaluated as a root in its
       |  own right, in branch order: operands before sentences, then by index. An admitted branch
       |  yields its own situation, context, membership and participant-coverage attempts, with
       |  support drawn from its own subtree, its description from its own gloss, and its polarity
       |  from its own chart polarity. A branch that is itself a coordinator is not descended into
       |  and abstains with coordination-branch-nested: one level is what the branch order licenses,
       |  and a deeper order is not something a single opN index states. A branch that is embedded
       |  abstains with coordination-branch-embedded, and any other inadmissible branch abstains
       |  with coordination-branch-not-admissible:kind. A coordinator with no branch at all
       |  abstains at the coordinator with no-coordination-branch. Coordinated branches are
       |  siblings of one sentence, never separate sentences: the coverage ledger records them in
       |  one row, and their story-world relation stays Unclear, because and asserts conjunction
       |  and not sequence.
       |predicate: lemma = the concept lemma; frame = "namespace:id" of the concept frame when
       |  present; gloss = the concept gloss when present, else the lemma.
       |description: Gloss.predicate over the chart at the root, so every word is a chart lemma
       |  or literal; the sentence text is never copied.
       |polarity: the chart polarity of the root, mapped one-to-one (Positive, Negative, Unknown).
       |modality: Asserted. aspect: none.
       |context: NarratedWorld, one attempt per admissible root, anchored at the root.
       |membership: PrimaryStoryMember, one attempt per admissible root, anchored at the root.
       |support: for a focus root, the union of the alignment spans of every alignment naming at
       |  least one non-embedded concept, recorded as span-source=chart-alignments. For a
       |  coordinated branch, the same union restricted to the concepts the branch reaches (the
       |  branch itself and everything under it, never through the coordinator), recorded as
       |  span-source=branch-alignments, so two branches of one sentence are supported by different
       |  words. When either union is empty the sentence span is used, recorded as
       |  span-source=sentence. Every alignment span of a chart must lie inside the chart's own
       |  sentence unit, whatever surface unit the span names; a chart violating this is refused,
       |  not repaired.
       |raw score: the minimum alignment credence among the alignments that supply a proposal's
       |  support; chart credence propagates only as this uncalibrated raw score and never as a
       |  probability. A sentence-fallback support has no alignment credence and carries 1.0,
       |  which span-source=sentence distinguishes from a measured 1.0.
       |calibration: probability 1.0 under $CalibrationModel for every rule that is a total
       |  function of the chart (situation, membership, coverage, participant, mention, temporal);
       |  the context rule under $ContextCalibrationModel, because NarratedWorld is the
       |  absence-of-embedding default at sentence grain; the summary rule under
       |  $SummaryCalibrationModel.
       |receipts: every evidence id is the content address of its scope, chart checksum, and
       |  rendered span set; every call render names the evidence id it cites; the chart receipts
       |  bind the source by their input checksum (the canonical text or the sentence text); the
       |  chart origin is a receipt parameter, and root-rule names which of the four admissible
       |  shapes admitted the root. The scope of a focus root's situation, context, membership and
       |  coverage task is its sentence, which identifies it; the scope of a coordinated branch's
       |  is the branch root key, because its sentence does not.
       |summary: the source title with evidence spanning the whole canonical text, and only when
       |  the source records how that title was established. A title is a claim about the work, so
       |  this rule publishes one only on a basis the source names; the sole basis this version
       |  knows is that a caller stated it, recorded as title-provenance=caller-supplied on the
       |  receipt. A source with no title, or a blank one, abstains with $NoTitleReason; a source
       |  carrying a title whose provenance it does not record abstains with
       |  $UnestablishedTitleReason. Nothing derives a title from anything that is not the work: a
       |  path, a filename, or a request identifier is not a title, and a model built from a bare
       |  text file carries a summary gap instead, which is true.
       |referentiality: reaching exactly one normalized role does not make a filler a participant.
       |  A filler is a referent of the root only when the ROLE takes a referent and the CONCEPT
       |  can denote one, decided by ${Referentiality.RuleName}. The roles that take a referent are
       |  the closed set {${referentialRolesText}}; Location is among them because a place is a
       |  referent and no chart signal separates "a place" from "an entity standing in for one",
       |  and asserting that separation from a word list would be world knowledge this layer does
       |  not have. Time and Manner name circumstances (below). Cause and Result relate
       |  eventualities. Every Custom role is refused, this provider's own amr:domain included,
       |  because :domain says which concept a property is predicated of and therefore describes
       |  rather than participates, and because an extension role's referentiality is not something
       |  anyone established. The concept kinds that denote a referent are Entity and Name; a
       |  Quantity measures a referent without being one, and Property, Predicate, Special and
       |  Unknown are not referents either. A filler the rule turns away is counted nonReferential
       |  on the coverage row and carries a receipt naming whether the role or the concept kind
       |  refused it. It never becomes an entity: a time or a property admitted as an entity is a
       |  cast member the model never observed, and every measurement over the entity layer counts
       |  it.
       |circumstances: a filler under Time or Manner yields one circumstance attempt (situation =
       |  root, filler = the concept) valued at that kind and the concept lemma, with evidence the
       |  filler's own alignment spans (span-source=filler-alignments), else the root support
       |  (span-source=root-support). The lemma is the source's own word and is never normalized to
       |  a date, a duration, or an interval: placing a circumstance on a timeline is a different
       |  claim under a different licence. Circumstances are counted on the coverage row and are
       |  never participants and never entities.
       |participants: for each admissible root, every relation from the root whose filler is a
       |  chart concept of kind Entity, Name, or Quantity, whose role carries exactly one
       |  normalized participant role, and which the referentiality rule admits as a referent
       |  yields one participant attempt (situation = root, filler = the concept) valued at that
       |  role. A numbered argument carries a normalized role only when
       |  the chart's frame lexicon licensed one; a named role carries the chart's own normalized
       |  role, else the standard table {$namedRolesText}. A filler reached by no licensed role, or
       |  by two different licensed roles, is counted unlicensed on the coverage row and never
       |  proposed; literal and unknown fillers are not counted. Participant evidence is the union
       |  of the filler's alignment spans and the root support.
       |mentions: one entity-mention attempt per proposed filler with label = the concept lemma
       |  and type = Custom("chart", concept kind lowercased); evidence = the filler's alignment
       |  spans (span-source=filler-alignments), else the root support (span-source=root-support).
       |  A filler that several coordination branches license is mentioned once, by the first
       |  branch in branch order that licenses it, and is a participant of every branch that
       |  licenses it. Reentrancy is one occurrence of a word: mentioning it once per branch would
       |  make several claims that it occurs out of one occurrence.
       |coverage: one participant-coverage attempt per admissible root listing exactly the
       |  proposed fillers, possibly none, with the root's evidence. An empty coverage is a value:
       |  it says the chart reaches no licensed participant from the root, never that participants
       |  were not evaluated. The ledger row divides every entity-kind filler the scan saw into
       |  referents, circumstances, nonReferential, and unlicensed, and those four sum to the
       |  fillers seen, so no filler leaves the provider unaccounted for.
       |temporal: one attempt per consecutive pair of admissible roots in sentence order and, within
       |  a sentence, in branch order, valued Unclear, with evidence spanning both roots' support.
       |  Never Before or Meets: a time filler in a sentence chart is a concept of that chart, not a
       |  preceding root, so nothing in a chart licenses strict precedence between roots; and
       |  StrictPrecedence is a high-impact family whose conservative policy this single provider
       |  could not satisfy alone. Coordinated siblings are no exception: conjunction is not
       |  sequence.
       |abstention: an inadmissible root yields one abstained attempt in each of the situation,
       |  context, membership, and participant-coverage families at the anchor (the focus when
       |  present, else the lowest concept id), and an inadmissible coordination branch yields the
       |  same four at the branch root. An empty chart or a sentence without a chart yields no
       |  attempt and a coverage row only.
       |causal: no causal attempt is emitted; absent pairs are not evaluated.
       |bundles: one proposed value per attempt with the raw score above, source support 1.0
       |  over the evidence spans, agreement 1.0, and one calibration at probability 1.0 under the
       |  rule's model; abstained attempts carry no value, support 0.0, no spans, agreement 0.0,
       |  no calibration.
       |policy: AcceptancePolicy.Conservative, except ContextAssignment and SegmentMembership at
       |  requireAgreement = 1, because one deterministic program is one provider; EntityMention,
       |  ParticipantRole, ParticipantCoverage, SituationCircumstance, and TemporalRelation are
       |  FamilyPolicy.Ordinary.
       |""".stripMargin

  /** The closed referential-role set as printed into [[RulesText]], derived from
    * [[Referentiality.licence]] over every non-`Custom` case of `ParticipantRole` rather than typed
    * out beside it: a list a reader could disagree with the code about is not a rule.
    */
  private def referentialRolesText: String =
    Referentiality.referentialRoles.map(_.toString).sorted.mkString(", ")

  /** The named-role table as printed into [[RulesText]]. */
  private def namedRolesText: String =
    NamedRoles.toVector
      .sortBy(_._1)
      .map((name, role) => s"$name=${renderRole(role)}")
      .mkString(", ")

  val Prompt: PromptPackageRef =
    PromptPackageRef("chart-rules", Version, Checksum.ofText(RulesText))

  /** Conservative everywhere, with the two high-impact families this single provider can satisfy
    * alone lowered to one agreeing provider. Recorded in [[RulesText]], so in every config hash.
    */
  val Policy: AcceptancePolicy =
    // The literal arguments satisfy FamilyPolicy.of; if a future edit broke that, the family
    // would stay at Conservative (two providers, never satisfiable here) and the policy court
    // pins requireAgreement = 1, so the failure is closed and visible rather than a thrown
    // initializer.
    val single = FamilyPolicy
      .of(Probability.unsafe(0.9), Probability.unsafe(0.5), 1, true, true, true)
      .getOrElse(FamilyPolicy.Conservative)
    val base = AcceptancePolicy.Conservative
    base.copy(perFamily =
      base.perFamily ++ Map(
        ClaimFamily.ContextAssignment -> single,
        ClaimFamily.SegmentMembership -> single,
        ClaimFamily.EntityMention -> FamilyPolicy.Ordinary,
        ClaimFamily.ParticipantRole -> FamilyPolicy.Ordinary,
        ClaimFamily.ParticipantCoverage -> FamilyPolicy.Ordinary,
        ClaimFamily.SituationCircumstance -> FamilyPolicy.Ordinary,
        ClaimFamily.TemporalRelation -> FamilyPolicy.Ordinary
      )
    )

  private val ChartPath = "chart-proposal-provider/charts"
  private val AlignmentPath = "chart-proposal-provider/alignments"

  /** A proposed root together with what the temporal rule needs from it. */
  private final case class ProposedRoot(
      unit: SurfaceUnit,
      root: ChartNodeRef,
      checksum: Checksum,
      spans: SpanSet,
      raw: Double
  )

  /** Support spans, where they came from, and the minimum alignment credence behind them. */
  private final case class Support(spans: SpanSet, source: String, raw: Double)

  /** An entity-kind filler of the root reached by exactly one licensed participant role, whose role
    * takes a referent and whose concept can denote one.
    */
  private final case class LicensedFiller(
      concept: ConceptId,
      kind: ConceptKind,
      lemma: String,
      role: ParticipantRole
  )

  /** A filler whose role names a circumstance of the situation rather than a participant in it. */
  private final case class CircumstanceFiller(
      concept: ConceptId,
      kind: CircumstanceKind,
      lemma: String,
      role: ParticipantRole
  )

  /** A filler the referentiality rule turned away, with the reason it goes on the receipt. Kept
    * rather than counted anonymously: `then` refused for its role and `5` refused for its concept
    * kind are different findings, and a bare count cannot tell a reader which happened.
    */
  private final case class RefusedFiller(
      concept: ConceptId,
      lemma: String,
      role: ParticipantRole,
      reason: String
  )

  /** Which closed admissibility shape admitted a root, and what kind of situation it makes.
    *
    * Why the shape is carried rather than recomputed: the situation kind and the receipt's
    * `root-rule` parameter both come from it, and deriving them twice from the chart is two places
    * to disagree.
    */
  private enum RootRule(val kind: SituationKind, val label: String):
    case Predicate extends RootRule(SituationKind.Event, "predicate")
    case StateRoleset extends RootRule(SituationKind.State, "state-roleset")
    case Predicative extends RootRule(SituationKind.State, "predicative")
    case Existential extends RootRule(SituationKind.State, "existential")

  /** Everything one admitted root contributed. */
  private final case class RootOutcome(
      proposedRoot: ProposedRoot,
      counts: FillerCounts,
      evidence: Vector[Evidence],
      situation: SituationAttempt,
      context: ContextAssignmentAttempt,
      membership: SegmentMembershipAttempt,
      participantCoverage: ParticipantCoverageAttempt,
      entityMentions: Vector[EntityMentionAttempt],
      participants: Vector[ParticipantAttempt],
      circumstances: Vector[SituationCircumstanceAttempt],
      calls: Vector[ProviderCall]
  )

  /** What one circumstance filler contributed: its span evidence, its attempt, and its receipt. */
  private final case class CircumstanceOutcome(
      evidence: Evidence,
      attempt: SituationCircumstanceAttempt,
      call: ProviderCall
  )

  /** A circumstance attempt carrying the filler's own alignment spans, so the words that state the
    * time or the manner stay attached to the claim rather than to the situation as a whole.
    */
  private def circumstanceOutcome(
      source: StorySource,
      unit: SurfaceUnit,
      chart: PropositionChart[Checked],
      checksum: Checksum,
      root: ChartNodeRef,
      rootSupport: Support,
      filler: CircumstanceFiller,
      params: Map[String, String]
  ): CircumstanceOutcome =
    val fillerRef = ChartNodeRef(unit.id, filler.concept)
    val fillerAlignments = chart.alignments.filter(_.target.conceptIds.contains(filler.concept))
    val fillerSpans = SpanSet.of(fillerAlignments.flatMap(_.spans.refs.toVector))
    val (spans, spanSource, raw) = fillerSpans match
      case Some(own) => (own, "filler-alignments", minCredence(fillerAlignments))
      case None      => (rootSupport.spans, "root-support", rootSupport.raw)
    val evidence = evidenceRecord(s"${root.key}~${fillerRef.key}", checksum, spans)
    val value = CircumstanceProposal(filler.kind, filler.lemma)
    val (bundle, call) = proposed(
      source,
      CircumstanceRule,
      s"${root.key}~${fillerRef.key}",
      checksum,
      value,
      evidence,
      math.min(raw, rootSupport.raw),
      CalibrationModel,
      Vector("circumstance", root.key, fillerRef.key, filler.kind.render, filler.lemma),
      params + ("filler" -> filler.concept.value) + ("role" -> renderRole(filler.role)) +
        ("circumstance" -> filler.kind.render) + ("span-source" -> spanSource)
    )
    CircumstanceOutcome(evidence, SituationCircumstanceAttempt(root, fillerRef, bundle), call)

  /** The four families every root — admitted or not — is accounted for in. */
  private final case class RootAttempts(
      situation: SituationAttempt,
      context: ContextAssignmentAttempt,
      membership: SegmentMembershipAttempt,
      participantCoverage: ParticipantCoverageAttempt,
      calls: Vector[ProviderCall]
  )

  private final case class SentenceOutcome(
      coverage: SentenceCoverage,
      proposedRoots: Vector[ProposedRoot],
      evidence: Vector[Evidence],
      situations: Vector[SituationAttempt],
      contexts: Vector[ContextAssignmentAttempt],
      memberships: Vector[SegmentMembershipAttempt],
      participantCoverage: Vector[ParticipantCoverageAttempt],
      entityMentions: Vector[EntityMentionAttempt],
      participants: Vector[ParticipantAttempt],
      circumstances: Vector[SituationCircumstanceAttempt],
      calls: Vector[ProviderCall]
  )

  private object SentenceOutcome:
    /** A sentence that produced no attempt at all: an empty chart or one the atlas has no chart
      * for.
      */
    def bare(coverage: SentenceCoverage): SentenceOutcome =
      SentenceOutcome(
        coverage,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty,
        Vector.empty
      )

  private final case class SummaryOutcome(
      attempt: StorySummaryAttempt,
      coverage: SummaryCoverage,
      evidence: Option[Evidence],
      call: ProviderCall
  )

  /** Emit every attempt and the coverage ledger for `charts`, one chart per sentence at most. */
  def propose(
      source: StorySource,
      atlas: SurfaceAtlas,
      charts: Vector[(SurfaceUnitId, PropositionEvidence)]
  ): Either[DomainError, ChartProposals] =
    for
      _ <- checkAtlas(source, atlas)
      _ <- checkSourceIsContentAddressed(source)
      ordered <- checkCharts(source, atlas, charts)
      _ <- ordered.traverse_((unit, ev) => checkAlignments(source, atlas, unit, ev.chart))
      outcomes <- ordered.traverse((unit, ev) => sentenceOutcome(source, unit, ev))
      summary <- summaryOutcome(source)
    yield
      val byUnit = outcomes.map(o => o.coverage.sentence -> o).toMap
      val inOrder = atlas.sentences.sortBy(_.ordinal)
      val coverage = inOrder.map(unit =>
        byUnit.get(unit.id).map(_.coverage).getOrElse(SentenceCoverage.NoChart(unit.id))
      )
      val proposedRoots =
        inOrder.flatMap(unit => byUnit.get(unit.id).toVector.flatMap(_.proposedRoots))
      val temporal = proposedRoots
        .zip(proposedRoots.drop(1))
        .map((prev, next) => temporalOutcome(source, prev, next))
      ChartProposals.derived(
        (outcomes.flatMap(_.evidence) ++ temporal.map(_._2) ++ summary.evidence.toVector)
          .sortBy(_.id),
        outcomes.flatMap(_.situations).sortBy(_.source.key),
        outcomes.flatMap(_.contexts).sortBy(_.source.key),
        summary.attempt,
        outcomes.flatMap(_.memberships).sortBy(_.member.key),
        outcomes.flatMap(_.entityMentions).sortBy(_.mention.key),
        outcomes.flatMap(_.participants).sortBy(a => (a.situation.key, a.filler.key)),
        outcomes.flatMap(_.participantCoverage).sortBy(_.situation.key),
        outcomes.flatMap(_.circumstances).sortBy(a => (a.situation.key, a.filler.key)),
        temporal.map(_._1).sortBy(a => (a.from.key, a.to.key)),
        (outcomes.flatMap(_.calls) ++ temporal.map(_._3) :+ summary.call).sortBy(renderCall),
        coverage,
        summary.coverage
      )

  /** [[propose]] and bind the result into checked compiler input with this provider's receipt.
    *
    * `parserStage` names the stage that produced the charts; its digest is derived here from the
    * charts themselves ([[chartsDigest]]), never typed by the caller.
    */
  def input(
      source: StorySource,
      atlas: SurfaceAtlas,
      charts: Vector[(SurfaceUnitId, PropositionEvidence)],
      parserStage: Option[StageId],
      createdAtEpochMillis: Long
  ): Either[NarrativeCompilerError, NarrativeCompilerInput] =
    propose(source, atlas, charts).left
      .map(e => NarrativeCompilerError.InvalidInput(NonEmptyVector.one(e)))
      .flatMap { proposals =>
        val stageDigest = ContentAddress.digest(proposals.calls.map(_.outputChecksum.hex))
        val receipt = BuildReceipt(
          source.id,
          source.canonicalChecksum,
          StoryModel.SchemaVersion,
          parserStage.map(_ -> chartsDigest(charts)).toVector :+ (Stage -> stageDigest),
          createdAtEpochMillis
        )
        val chartReceipts = charts.flatMap(_._2.provenance.receipts)
        val provenance = Provenance(
          (proposals.calls ++ chartReceipts).distinct.sortBy(renderCall),
          StoryModel.SchemaVersion,
          Checksum.ofText(RulesText)
        )
        NarrativeCompilerInput.of(
          source,
          atlas,
          charts,
          proposals.evidence,
          proposals.situations,
          proposals.contexts,
          proposals.summary,
          proposals.memberships,
          proposals.causal,
          proposals.entityMentions,
          proposals.participants,
          proposals.participantCoverage,
          proposals.circumstances,
          proposals.temporal,
          Policy,
          receipt,
          provenance
        )
      }

  private def checkAtlas(source: StorySource, atlas: SurfaceAtlas): Either[DomainError, Unit] =
    if atlas.source.id != source.id || atlas.source.canonicalChecksum != source.canonicalChecksum
    then
      Left(
        DomainError.InvariantViolation(
          "chart-proposal-provider/atlas",
          "atlas belongs to a different source identity or checksum"
        )
      )
    else Right(())

  /** Digest of the charts a build consumed: each sentence, its canonical chart checksum, its
    * rendered alignment spans, and its receipts' output checksums. This is the parser stage's
    * digest on the build receipt.
    */
  def chartsDigest(charts: Vector[(SurfaceUnitId, PropositionEvidence)]): Checksum =
    ContentAddress.digest(
      charts
        .sortBy(_._1)
        .flatMap((unit, ev) =>
          Vector(
            "chart/v1",
            unit.value,
            Canonical.checksum(ev.chart).hex,
            renderAlignments(ev.chart)
          ) ++ ev.provenance.receipts.map(_.outputChecksum.hex)
        )
    )

  private def checkCharts(
      source: StorySource,
      atlas: SurfaceAtlas,
      charts: Vector[(SurfaceUnitId, PropositionEvidence)]
  ): Either[DomainError, Vector[(SurfaceUnit, PropositionEvidence)]] =
    val sorted = charts.sortBy(_._1)
    val duplicate = sorted.map(_._1).zip(sorted.map(_._1).drop(1)).collectFirst {
      case (a, b) if a == b => a
    }
    duplicate match
      case Some(id) =>
        Left(DomainError.InvariantViolation(ChartPath, s"duplicate chart ${id.value}"))
      case None =>
        sorted.traverse { (id, ev) =>
          atlas.byId.get(id).filter(_.kind == SurfaceUnitKind.Sentence) match
            case None =>
              Left(
                DomainError.InvariantViolation(
                  ChartPath,
                  s"${id.value} is not a sentence in the atlas"
                )
              )
            case Some(_) if !ev.chart.sentence.contains(id) =>
              Left(
                DomainError.InvariantViolation(
                  ChartPath,
                  s"chart sentence does not equal ${id.value}"
                )
              )
            case Some(unit) => Right(unit -> ev)
        }

  /** A chart names the sentence it came from, and a sentence id is minted as
    * `<story id>:s<ordinal>`, so a chart can only belong to this text if the story id is itself
    * derived from that text. `StorySource.fromText` admits a caller-supplied `explicitId`, which
    * would break that chain, so a source whose id is not its own content address is refused here:
    * with an asserted id, no id-based binding proves anything.
    *
    * Why the chart receipts are not inspected: a receipt's `inputChecksum` names the provider's own
    * input, which for a remote parser is the request envelope, not the story text. Requiring it to
    * equal the source or the sentence refused every honestly receipted machine chart.
    */
  private def checkSourceIsContentAddressed(source: StorySource): Either[DomainError, Unit] =
    val derived = StoryId.unsafe(ContentAddress.of("story", source.canonicalChecksum.hex))
    if source.id == derived then Right(())
    else
      Left(
        DomainError.InvariantViolation(
          ChartPath,
          s"story ${source.id.value} is not the content address of its text " +
            s"(${derived.value}); chart-to-sentence binding cannot be trusted"
        )
      )

  private def checkAlignments(
      source: StorySource,
      atlas: SurfaceAtlas,
      unit: SurfaceUnit,
      chart: PropositionChart[Checked]
  ): Either[DomainError, Unit] =
    val text = source.canonicalText
    chart.alignments.flatMap(_.spans.refs.toVector).traverse_ { ref =>
      val span = ref.span
      if span.endExclusive > text.length then
        Left(
          DomainError.InvariantViolation(
            AlignmentPath,
            s"${unit.id.value}: span $span exceeds source length ${text.length}"
          )
        )
      else if !isCodePointBoundary(text, span.start) ||
        !isCodePointBoundary(text, span.endExclusive)
      then
        Left(
          DomainError.InvariantViolation(
            AlignmentPath,
            s"${unit.id.value}: span $span cuts a UTF-16 surrogate pair"
          )
        )
      else if !unit.span.contains(span) then
        // Whatever unit the ref names, a chart's alignment is evidence about its own sentence.
        Left(
          DomainError.InvariantViolation(
            AlignmentPath,
            s"${unit.id.value}: span $span lies outside the chart's sentence ${unit.span}"
          )
        )
      else
        ref.unit match
          case None        => Right(())
          case Some(named) =>
            atlas.byId.get(named) match
              case None =>
                Left(
                  DomainError.InvariantViolation(
                    AlignmentPath,
                    s"${unit.id.value}: span names unknown surface unit ${named.value}"
                  )
                )
              case Some(holder) if !holder.span.contains(span) =>
                Left(
                  DomainError.InvariantViolation(
                    AlignmentPath,
                    s"${unit.id.value}: span $span escapes surface unit ${named.value}"
                  )
                )
              case Some(holder) if !unit.span.contains(holder.span) =>
                Left(
                  DomainError.InvariantViolation(
                    AlignmentPath,
                    s"${unit.id.value}: span names surface unit ${named.value}, which is not " +
                      "the chart's sentence or a unit inside it"
                  )
                )
              case Some(_) => Right(())
    }

  private def isCodePointBoundary(text: String, index: Int): Boolean =
    def highSurrogate(value: Char): Boolean = value >= '\uD800' && value <= '\uDBFF'
    def lowSurrogate(value: Char): Boolean = value >= '\uDC00' && value <= '\uDFFF'
    index <= 0 || index >= text.length ||
    !(highSurrogate(text.charAt(index - 1)) && lowSurrogate(text.charAt(index)))

  private def sentenceOutcome(
      source: StorySource,
      unit: SurfaceUnit,
      ev: PropositionEvidence
  ): Either[DomainError, SentenceOutcome] =
    val chart = ev.chart
    val origin = ev.provenance.origin
    val checksum = Canonical.checksum(chart)
    if chart.isEmpty then Right(SentenceOutcome.bare(SentenceCoverage.EmptyChart(unit.id)))
    else
      chart.focus match
        case None =>
          val anchor = ChartNodeRef(unit.id, chart.conceptIds.head)
          Right(abstainSentence(source, unit, origin, checksum, anchor, AbstentionReason.NoFocus))
        case Some(focus) =>
          val root = ChartNodeRef(unit.id, focus)
          chart.concept(focus) match
            case None =>
              Left(
                DomainError.InvariantViolation(
                  ChartPath,
                  s"${unit.id.value}: focus ${focus.value} is not a concept of the chart"
                )
              )
            case Some(concept) =>
              val rule = admissibleRoot(chart, focus, concept)
              val coordinator = ChartRoots.isCoordinator(concept)
              if rule.isEmpty && !coordinator then
                Right(
                  abstainSentence(
                    source,
                    unit,
                    origin,
                    checksum,
                    root,
                    AbstentionReason.FocusNotPredicate(concept.kind)
                  )
                )
              else if chart.isEmbedded(focus) then
                Right(
                  abstainSentence(
                    source,
                    unit,
                    origin,
                    checksum,
                    root,
                    AbstentionReason.FocusEmbedded
                  )
                )
              else
                rule match
                  case Some(admitted) =>
                    proposeRoot(
                      source,
                      unit,
                      chart,
                      origin,
                      checksum,
                      root,
                      concept,
                      admitted,
                      supportSpans(unit, chart),
                      unit.id.value
                    ).map(outcome =>
                      SentenceOutcome(
                        SentenceCoverage.Proposed(root, outcome.counts),
                        Vector(outcome.proposedRoot),
                        outcome.evidence,
                        Vector(outcome.situation),
                        Vector(outcome.context),
                        Vector(outcome.membership),
                        Vector(outcome.participantCoverage),
                        outcome.entityMentions,
                        outcome.participants,
                        outcome.circumstances,
                        outcome.calls
                      )
                    )
                  case None =>
                    coordinatedOutcome(source, unit, chart, origin, checksum, root)

  /** One situation per admitted branch of a coordinating focus, in branch order.
    *
    * Every branch is accounted for: an admitted one contributes exactly what a focus root would,
    * and an inadmissible one contributes the same four abstained attempts a focus root would, at
    * the branch. A coordinator with no branch at all abstains at the coordinator itself, because
    * there is nothing under it to be about.
    *
    * Reentrancy makes one filler the participant of several branches (`:op1 (c / carry :ARG0 (t /
    * they)) :op2 (p / put :ARG0 t)`). That is two participant edges and one entity: the first
    * branch in branch order that licenses the filler mentions it, and the later branches take it as
    * a participant without mentioning it again. Mentioning it twice would be two claims that the
    * word occurs, from one occurrence.
    */
  private def coordinatedOutcome(
      source: StorySource,
      unit: SurfaceUnit,
      chart: PropositionChart[Checked],
      origin: ChartOrigin,
      checksum: Checksum,
      coordinator: ChartNodeRef
  ): Either[DomainError, SentenceOutcome] =
    val branches = ChartRoots.branches(chart, coordinator.concept)
    if branches.isEmpty then
      Right(
        abstainSentence(
          source,
          unit,
          origin,
          checksum,
          coordinator,
          AbstentionReason.NoCoordinationBranch
        )
      )
    else
      val admitted = branches.flatMap(branch =>
        chart
          .concept(branch.concept)
          .filterNot(ChartRoots.isCoordinator)
          .filterNot(_ => chart.isEmbedded(branch.concept))
          .flatMap(concept => admissibleRoot(chart, branch.concept, concept))
          .map(_ => branch.concept)
      )
      val mentionOwner: Map[ConceptId, ConceptId] = admitted
        .flatMap(root => scanFillers(chart, root).referents.map(filler => filler.concept -> root))
        .foldLeft(Map.empty[ConceptId, ConceptId]) { (owners, entry) =>
          if owners.contains(entry._1) then owners else owners + entry
        }
      branches
        .traverse(branch =>
          branchOutcome(source, unit, chart, origin, checksum, coordinator, branch, mentionOwner)
        )
        .map(outcomes =>
          SentenceOutcome(
            SentenceCoverage.Coordinated(coordinator, outcomes.map(_._1)),
            outcomes.flatMap(_._2.toVector.map(_.proposedRoot)),
            outcomes.flatMap(_._2.toVector.flatMap(_.evidence)),
            outcomes.map(_._3.situation),
            outcomes.map(_._3.context),
            outcomes.map(_._3.membership),
            outcomes.map(_._3.participantCoverage),
            outcomes.flatMap(_._2.toVector.flatMap(_.entityMentions)),
            outcomes.flatMap(_._2.toVector.flatMap(_.participants)),
            outcomes.flatMap(_._2.toVector.flatMap(_.circumstances)),
            outcomes.flatMap(_._3.calls)
          )
        )

  /** One branch: its ledger entry, what it produced if admitted, and its four attempts. */
  private def branchOutcome(
      source: StorySource,
      unit: SurfaceUnit,
      chart: PropositionChart[Checked],
      origin: ChartOrigin,
      checksum: Checksum,
      coordinator: ChartNodeRef,
      branch: ChartRoots.Branch,
      mentionOwner: Map[ConceptId, ConceptId]
  ): Either[DomainError, (CoordinatedBranch, Option[RootOutcome], RootAttempts)] =
    val root = ChartNodeRef(unit.id, branch.concept)
    def refuse(reason: AbstentionReason) =
      Right(
        (
          CoordinatedBranch.Abstained(root, branch.role, reason),
          None,
          abstainAttempts(source, unit, origin, checksum, root, reason, root.key)
        )
      )
    chart.concept(branch.concept) match
      case None =>
        Left(
          DomainError.InvariantViolation(
            ChartPath,
            s"${unit.id.value}: coordination branch ${branch.concept.value} of " +
              s"${coordinator.concept.value} is not a concept of the chart"
          )
        )
      case Some(concept) if ChartRoots.isCoordinator(concept) =>
        refuse(AbstentionReason.NestedCoordination)
      case Some(_) if chart.isEmbedded(branch.concept) =>
        refuse(AbstentionReason.BranchEmbedded)
      case Some(concept) =>
        admissibleRoot(chart, branch.concept, concept) match
          case None       => refuse(AbstentionReason.BranchNotAdmissible(concept.kind))
          case Some(rule) =>
            proposeRoot(
              source,
              unit,
              chart,
              origin,
              checksum,
              root,
              concept,
              rule,
              branchSupport(unit, chart, coordinator.concept, branch.concept),
              root.key,
              filler => mentionOwner.get(filler).contains(branch.concept)
            ).map(outcome =>
              (
                CoordinatedBranch.Admitted(root, branch.role, outcome.counts),
                Some(outcome),
                RootAttempts(
                  outcome.situation,
                  outcome.context,
                  outcome.membership,
                  outcome.participantCoverage,
                  outcome.calls
                )
              )
            )

  /** Every attempt one admitted root contributes, whether it is a focus or a coordinated branch.
    *
    * Three things differ between the two: a focus root is supported by the whole chart, scoped by
    * its sentence, and mentions every filler it licenses, while a branch is supported by its own
    * subtree, scoped by its own root key, and mentions only the fillers `mentions` gives it (see
    * [[coordinatedOutcome]]). A filler it does not mention is still its participant.
    */
  private def proposeRoot(
      source: StorySource,
      unit: SurfaceUnit,
      chart: PropositionChart[Checked],
      origin: ChartOrigin,
      checksum: Checksum,
      root: ChartNodeRef,
      concept: Concept,
      rule: RootRule,
      support: Support,
      scope: String,
      mentions: ConceptId => Boolean = _ => true
  ): Either[DomainError, RootOutcome] =
    Gloss.predicate(chart, root.concept) match
      case None =>
        Left(
          DomainError.InvariantViolation(
            ChartPath,
            s"${unit.id.value}: root ${root.concept.value} has no gloss"
          )
        )
      case Some(description) =>
        val spans = support.spans
        val evidence = evidenceRecord(scope, checksum, spans)
        val kind = rule.kind
        val lemma = concept.lemma.value
        val value = SituationProposal(
          kind,
          Predicate(
            lemma,
            concept.frame.map(f => s"${f.namespace}:${f.id}"),
            concept.gloss.getOrElse(lemma)
          ),
          description,
          polarity(chart.polarityOf(root.concept)),
          Modality.Asserted,
          None
        )
        val params = chartParams(unit, checksum, origin) +
          ("span-source" -> support.source) + ("root-rule" -> rule.label)
        val (situation, situationCall) = proposed(
          source,
          SituationRule,
          scope,
          checksum,
          value,
          evidence,
          support.raw,
          CalibrationModel,
          Vector(
            "situation",
            kind.toString,
            value.predicate.lemma,
            value.predicate.frame.getOrElse(""),
            value.predicate.gloss,
            value.description,
            value.polarity.toString
          ),
          params
        )
        val (context, contextCall) = proposed(
          source,
          ContextRule,
          scope,
          checksum,
          ContextAssignmentProposal.NarratedWorld,
          evidence,
          support.raw,
          ContextCalibrationModel,
          Vector("narrated-world", root.key),
          params
        )
        val (membership, membershipCall) = proposed(
          source,
          MembershipRule,
          scope,
          checksum,
          SegmentMembershipProposal.PrimaryStoryMember,
          evidence,
          support.raw,
          CalibrationModel,
          Vector("primary-story-member", root.key),
          params
        )
        val scanned = scanFillers(chart, root.concept)
        val fillerOutcomes = scanned.referents.map(filler =>
          fillerOutcome(
            source,
            unit,
            chart,
            checksum,
            root,
            support,
            filler,
            params,
            mentions(filler.concept)
          )
        )
        val circumstanceOutcomes = scanned.circumstances.map(filler =>
          circumstanceOutcome(source, unit, chart, checksum, root, support, filler, params)
        )
        val refusalCalls = scanned.nonReferential.map(filler =>
          providerCall(
            source,
            Referentiality.RuleName,
            Vector("non-referential", root.key, filler.concept.value, filler.reason),
            params + ("filler" -> filler.concept.value) + ("lemma" -> filler.lemma) +
              ("role" -> renderRole(filler.role)) + ("reason" -> filler.reason)
          )
        )
        val coverageValue =
          ParticipantCoverage.of(scanned.referents.map(f => ChartNodeRef(unit.id, f.concept)))
        val (coverage, coverageCall) = proposed(
          source,
          CoverageRule,
          scope,
          checksum,
          coverageValue,
          evidence,
          support.raw,
          CalibrationModel,
          "participant-coverage" +: root.key +: coverageValue.fillers.map(_.key),
          params + ("fillers" -> scanned.referents.size.toString) +
            ("circumstances" -> scanned.circumstances.size.toString) +
            ("nonReferential" -> scanned.nonReferential.size.toString) +
            ("unlicensed" -> scanned.unlicensed.toString)
        )
        Right(
          RootOutcome(
            ProposedRoot(unit, root, checksum, spans, support.raw),
            scanned.counts,
            evidence +: (fillerOutcomes.flatMap(_.evidence) ++
              circumstanceOutcomes.map(_.evidence)),
            SituationAttempt(root, situation),
            ContextAssignmentAttempt(root, context),
            SegmentMembershipAttempt(root, membership),
            ParticipantCoverageAttempt(root, coverage),
            fillerOutcomes.flatMap(_.mention),
            fillerOutcomes.map(_.participant),
            circumstanceOutcomes.map(_.attempt),
            Vector(situationCall, contextCall, membershipCall, coverageCall) ++
              fillerOutcomes.flatMap(_.calls) ++ circumstanceOutcomes.map(_.call) ++ refusalCalls
          )
        )

  /** `mention` is absent when an earlier coordination branch already mentioned this filler; the
    * participant edge is emitted either way.
    */
  private final case class FillerOutcome(
      evidence: Vector[Evidence],
      mention: Option[EntityMentionAttempt],
      participant: ParticipantAttempt,
      calls: Vector[ProviderCall]
  )

  /** How `root`'s entity-kind fillers divide under the referentiality rule, in concept order.
    *
    * Every filler the scan saw lands in exactly one of the four: a referent, a circumstance, a
    * non-referent that named its reason, or one no single role reached. The counts are what the
    * coverage row publishes, so a filler cannot leave the provider unaccounted for.
    */
  private final case class ScannedFillers(
      referents: Vector[LicensedFiller],
      circumstances: Vector[CircumstanceFiller],
      nonReferential: Vector[RefusedFiller],
      unlicensed: Int
  ):
    def counts: FillerCounts =
      FillerCounts(referents.size, circumstances.size, nonReferential.size, unlicensed)

  /** Entity-kind fillers of `root` sorted by the referentiality rule.
    *
    * `unlicensed` counts, as before, the fillers no single normalized role reached. What changed is
    * that reaching a role is no longer enough: the role must take a referent and the concept must
    * be able to denote one. A `:time` or `:manner` filler becomes a circumstance instead of a
    * participant, and everything else the rule turns away is carried with its reason rather than
    * dropped.
    */
  private def scanFillers(
      chart: PropositionChart[Checked],
      root: ConceptId
  ): ScannedFillers =
    val byFiller = chart
      .relationsFrom(root)
      .flatMap(r => r.to.nodeId.map(id => id -> r.role))
      .flatMap((id, role) =>
        chart
          .concept(id)
          .filter(c => KindWitness.entity.accepts(c.kind))
          .map(concept => (id, concept, role))
      )
      .groupBy(_._1)
      .toVector
      .sortBy(_._1)
    byFiller.foldLeft(ScannedFillers(Vector.empty, Vector.empty, Vector.empty, 0)) {
      case (acc, (id, rows)) =>
        val concept = rows.head._2
        val lemma = concept.lemma.value
        rows.map(_._3).flatMap(licensedRole).distinct match
          case Vector(role) =>
            Referentiality.licence(role) match
              case RoleLicence.Referent if Referentiality.denotesReferent(concept.kind) =>
                acc.copy(referents = acc.referents :+ LicensedFiller(id, concept.kind, lemma, role))
              case RoleLicence.Referent =>
                acc.copy(nonReferential =
                  acc.nonReferential :+
                    RefusedFiller(id, lemma, role, Referentiality.kindReason(concept.kind))
                )
              case RoleLicence.Circumstance(kind) =>
                acc.copy(circumstances =
                  acc.circumstances :+ CircumstanceFiller(id, kind, lemma, role)
                )
              case refused =>
                acc.copy(nonReferential =
                  acc.nonReferential :+
                    RefusedFiller(id, lemma, role, s"role-not-referential:${refused.render}")
                )
          case _ => acc.copy(unlicensed = acc.unlicensed + 1)
    }

  /** The chart's own normalized role when present; a standard named role otherwise; nothing for a
    * numbered argument without a lexicon licence, an operand, or an extension role.
    */
  private def licensedRole(role: RoleAssignment): Option[ParticipantRole] =
    role.normalizedRole.orElse(role.source match
      case SourceRole.Named(name) => NamedRoles.get(name)
      case _                      => None)

  private def fillerOutcome(
      source: StorySource,
      unit: SurfaceUnit,
      chart: PropositionChart[Checked],
      checksum: Checksum,
      root: ChartNodeRef,
      rootSupport: Support,
      filler: LicensedFiller,
      params: Map[String, String],
      mentioned: Boolean
  ): FillerOutcome =
    val fillerRef = ChartNodeRef(unit.id, filler.concept)
    val fillerAlignments = chart.alignments.filter(_.target.conceptIds.contains(filler.concept))
    val fillerSpans = SpanSet.of(fillerAlignments.flatMap(_.spans.refs.toVector))
    val (mentionSpans, mentionSource, mentionRaw) = fillerSpans match
      case Some(spans) => (spans, "filler-alignments", minCredence(fillerAlignments))
      case None        => (rootSupport.spans, "root-support", rootSupport.raw)
    val mentionEvidence = evidenceRecord(fillerRef.key, checksum, mentionSpans)
    val participantEvidence = evidenceRecord(
      s"${root.key}->${fillerRef.key}",
      checksum,
      fillerSpans.fold(rootSupport.spans)(_ ++ rootSupport.spans)
    )
    val participantRaw = math.min(mentionRaw, rootSupport.raw)
    val mentionValue = EntityMentionProposal(
      filler.lemma,
      EntityType.Custom("chart", foldCase(filler.kind.toString))
    )
    val fillerParams = params + ("filler" -> filler.concept.value)
    val mentionOutcome = Option.when(mentioned)(
      proposed(
        source,
        MentionRule,
        fillerRef.key,
        checksum,
        mentionValue,
        mentionEvidence,
        mentionRaw,
        CalibrationModel,
        Vector(
          "entity-mention",
          fillerRef.key,
          mentionValue.label,
          mentionValue.entityType.toString
        ),
        fillerParams + ("span-source" -> mentionSource)
      )
    )
    val (participant, participantCall) = proposed(
      source,
      ParticipantRule,
      s"${root.key}->${fillerRef.key}",
      checksum,
      filler.role,
      participantEvidence,
      participantRaw,
      CalibrationModel,
      Vector("participant", root.key, fillerRef.key, renderRole(filler.role)),
      fillerParams + ("role" -> renderRole(filler.role))
    )
    FillerOutcome(
      mentionOutcome.map(_ => mentionEvidence).toVector :+ participantEvidence,
      mentionOutcome.map((bundle, _) => EntityMentionAttempt(fillerRef, bundle)),
      ParticipantAttempt(root, fillerRef, participant),
      mentionOutcome.map(_._2).toVector :+ participantCall
    )

  /** One `Unclear` temporal attempt between two consecutive proposed roots. */
  private def temporalOutcome(
      source: StorySource,
      prev: ProposedRoot,
      next: ProposedRoot
  ): (TemporalAttempt, Evidence, ProviderCall) =
    val scope = s"${prev.unit.id.value}->${next.unit.id.value}"
    val checksum = ContentAddress.digest(Vector(prev.checksum.hex, next.checksum.hex))
    val evidence = evidenceRecord(scope, checksum, prev.spans ++ next.spans)
    val params = Map(
      "from" -> prev.unit.id.value,
      "to" -> next.unit.id.value,
      "chart" -> checksum.hex,
      "span-source" -> "root-supports"
    )
    val (bundle, call) = proposed(
      source,
      TemporalRule,
      scope,
      checksum,
      TemporalRelation.Unclear,
      evidence,
      math.min(prev.raw, next.raw),
      CalibrationModel,
      Vector("temporal", prev.root.key, next.root.key, TemporalRelation.Unclear.toString),
      params
    )
    (TemporalAttempt(prev.root, next.root, bundle), evidence, call)

  private def renderRole(role: ParticipantRole): String = role match
    case ParticipantRole.Custom(namespace, label) => s"Custom($namespace,$label)"
    case other                                    => other.toString

  /** The four abstained attempts an inadmissible root leaves behind, at `anchor` under `scope`. */
  private def abstainAttempts(
      source: StorySource,
      unit: SurfaceUnit,
      origin: ChartOrigin,
      checksum: Checksum,
      anchor: ChartNodeRef,
      reason: AbstentionReason,
      scope: String
  ): RootAttempts =
    val params = chartParams(unit, checksum, origin) + ("reason" -> reason.render)
    val render = Vector("abstain", anchor.key, reason.render)
    val (situation, situationCall) =
      abstained[SituationProposal](source, AbstainSituationRule, scope, checksum, render, params)
    val (context, contextCall) =
      abstained[ContextAssignmentProposal](
        source,
        AbstainContextRule,
        scope,
        checksum,
        render,
        params
      )
    val (membership, membershipCall) =
      abstained[SegmentMembershipProposal](
        source,
        AbstainMembershipRule,
        scope,
        checksum,
        render,
        params
      )
    val (coverage, coverageCall) =
      abstained[ParticipantCoverage](source, AbstainCoverageRule, scope, checksum, render, params)
    RootAttempts(
      SituationAttempt(anchor, situation),
      ContextAssignmentAttempt(anchor, context),
      SegmentMembershipAttempt(anchor, membership),
      ParticipantCoverageAttempt(anchor, coverage),
      Vector(situationCall, contextCall, membershipCall, coverageCall)
    )

  /** A whole sentence abstaining at one anchor: no root, one ledger row, four attempts. */
  private def abstainSentence(
      source: StorySource,
      unit: SurfaceUnit,
      origin: ChartOrigin,
      checksum: Checksum,
      anchor: ChartNodeRef,
      reason: AbstentionReason
  ): SentenceOutcome =
    val attempts = abstainAttempts(source, unit, origin, checksum, anchor, reason, unit.id.value)
    SentenceOutcome(
      SentenceCoverage.Abstained(anchor, reason),
      Vector.empty,
      Vector.empty,
      Vector(attempts.situation),
      Vector(attempts.context),
      Vector(attempts.membership),
      Vector(attempts.participantCoverage),
      Vector.empty,
      Vector.empty,
      Vector.empty,
      attempts.calls
    )

  /** Why `establishedTitle` and not `title`: the title alone says a caller put a string there, and
    * the string that used to be there was the input file's name. The provenance is what entitles
    * this rule to publish it as the story's summary, so a title with none abstains under its own
    * reason rather than sharing `no-title` with a source that has no title at all — two different
    * facts about the same field, and a reader must be able to tell them apart.
    */
  private def summaryOutcome(source: StorySource): Either[DomainError, SummaryOutcome] =
    val scope = "story"
    val scopeChecksum = source.canonicalChecksum
    def abstain(reason: String, coverage: SummaryCoverage): SummaryOutcome =
      val params = Map("scope" -> scope, "rule" -> AbstainSummaryRule, "reason" -> reason)
      val (bundle, call) = abstained[StorySummaryProposal](
        source,
        AbstainSummaryRule,
        scope,
        scopeChecksum,
        Vector("abstain", scope, reason),
        params
      )
      SummaryOutcome(StorySummaryAttempt(bundle), coverage, None, call)

    source.establishedTitle match
      case None if source.title.exists(_.trim.nonEmpty) =>
        Right(abstain(UnestablishedTitleReason, SummaryCoverage.TitleUnestablished))
      case None =>
        Right(abstain(NoTitleReason, SummaryCoverage.NoTitle))
      case Some(title) =>
        TextSpan.of(0, source.canonicalText.length).map { whole =>
          val evidence = evidenceRecord(scope, scopeChecksum, SpanSet.one(SpanRef(None, whole)))
          val params = Map(
            "scope" -> scope,
            "rule" -> SummaryRule,
            "span-source" -> "canonical-text",
            "title-provenance" -> title.provenance.render
          )
          val (bundle, call) = proposed(
            source,
            SummaryRule,
            scope,
            scopeChecksum,
            StorySummaryProposal(title.value),
            evidence,
            1.0,
            SummaryCalibrationModel,
            Vector("summary", title.value, title.provenance.render),
            params
          )
          SummaryOutcome(
            StorySummaryAttempt(bundle),
            SummaryCoverage.Proposed(title.value, title.provenance),
            Some(evidence),
            call
          )
        }

  /** A `-91` reification frame in the adapter's namespace: the only frames that make a State. */
  private def stateFrame(concept: Concept): Boolean =
    concept.frame.exists(f => f.namespace == StateFrameNamespace && StateFrames(f.id))

  /** The closed shape that admits `concept` as a situation root, or nothing.
    *
    * The four shapes are stated in [[RulesText]]: a predicate, a Special concept carrying a state
    * frame, a frameless predicative concept with a `:domain` filler, and a frameless entity with a
    * `:location` filler. Any other concept — a frameless AMR special, a `-91` frame outside the
    * closed set, a bare entity — abstains. Nothing here reads the sentence text or invents a frame.
    */
  private def admissibleRoot(
      chart: PropositionChart[Checked],
      id: ConceptId,
      concept: Concept
  ): Option[RootRule] =
    if concept.isPredicate || (concept.kind == ConceptKind.Special && stateFrame(concept)) then
      Some(if stateFrame(concept) then RootRule.StateRoleset else RootRule.Predicate)
    else if ChartRoots.isPredicative(chart, id, concept) then Some(RootRule.Predicative)
    else if ChartRoots.isExistential(chart, id, concept) then Some(RootRule.Existential)
    else None

  /** Alignment spans of every alignment naming a non-embedded concept, with their minimum credence;
    * the sentence with raw score 1.0 otherwise (recorded as span-source=sentence).
    */
  private def supportSpans(unit: SurfaceUnit, chart: PropositionChart[Checked]): Support =
    val supporting =
      chart.alignments.filter(_.target.conceptIds.exists(id => !chart.isEmbedded(id)))
    SpanSet.of(supporting.flatMap(_.spans.refs.toVector)) match
      case Some(set) => Support(set, "chart-alignments", minCredence(supporting))
      case None      => Support(SpanSet.one(SpanRef(Some(unit.id), unit.span)), "sentence", 1.0)

  /** [[supportSpans]] restricted to one coordination branch: the alignments naming a non-embedded
    * concept the branch reaches. Two branches of one sentence are then evidenced by different
    * words, which is what makes them two situations and not one asserted twice.
    */
  private def branchSupport(
      unit: SurfaceUnit,
      chart: PropositionChart[Checked],
      coordinator: ConceptId,
      branch: ConceptId
  ): Support =
    val within = reachable(chart, coordinator, branch)
    val supporting = chart.alignments.filter(
      _.target.conceptIds.exists(id => within(id) && !chart.isEmbedded(id))
    )
    SpanSet.of(supporting.flatMap(_.spans.refs.toVector)) match
      case Some(set) => Support(set, "branch-alignments", minCredence(supporting))
      case None      => Support(SpanSet.one(SpanRef(Some(unit.id), unit.span)), "sentence", 1.0)

  /** Every concept `from` reaches, never through `stop`. Reentrancy is a shared node, so a concept
    * two branches both reach is in both; the traversal keeps a visited set, so a cycle terminates.
    */
  private def reachable(
      chart: PropositionChart[Checked],
      stop: ConceptId,
      from: ConceptId
  ): Set[ConceptId] =
    @annotation.tailrec
    def walk(pending: List[ConceptId], seen: Set[ConceptId]): Set[ConceptId] = pending match
      case Nil        => seen
      case id :: rest =>
        val next = chart
          .relationsFrom(id)
          .flatMap(_.to.nodeId)
          .filter(chart.concepts.contains)
          .filterNot(seen)
          .filter(_ != stop)
        walk(next.toList ++ rest, seen ++ next)
    if from == stop then Set.empty else walk(List(from), Set(from))

  /** Minimum raw credence of the given alignments; 1.0 for none, which callers only reach with a
    * fallback support whose span-source says so.
    */
  private def minCredence(alignments: Vector[PropositionAlignment]): Double =
    alignments.map(_.credence.rawScore).minOption.getOrElse(1.0)

  /** Content-addressed evidence over its scope, chart checksum, and rendered span set. */
  private def evidenceRecord(scope: String, checksum: Checksum, spans: SpanSet): Evidence =
    Evidence(
      EvidenceId.unsafe(
        ContentAddress.of("chart-proposal-evidence/v2", scope, checksum.hex, renderSpans(spans))
      ),
      Some(spans),
      Set.empty,
      ProviderFingerprint,
      Stage
    )

  /** A score as it enters an identity preimage: IEEE-754 bits, with negative zero folded onto zero.
    * `Credence` already refuses non-finite scores, so the only way two scores could compare equal
    * yet digest differently is `-0.0`; equality must imply one identity.
    */
  private def canonicalScore(score: Double): String =
    CanonicalDouble.render(if score == 0.0 then 0.0 else score)

  private def renderSpans(spans: SpanSet): String =
    spans.refs.toVector
      .map(r => s"${r.unit.fold("-")(_.value)}:${r.span.start}:${r.span.endExclusive}")
      .mkString(",")

  private def renderAlignments(chart: PropositionChart[Checked]): String =
    chart.alignments
      .map(a =>
        s"${a.target.conceptIds.toVector.sorted.map(_.value).mkString("+")}=" +
          s"${renderSpans(a.spans)}@${canonicalScore(a.credence.rawScore)}"
      )
      .sorted
      .mkString(";")

  private def renderOrigin(origin: ChartOrigin): String = origin match
    case ChartOrigin.Hand               => "hand"
    case ChartOrigin.Parser(f)          => s"parser:${f.value}"
    case ChartOrigin.Agent(f)           => s"agent:${f.value}"
    case ChartOrigin.Converted(from, f) => s"converted:$from:${f.value}"
    case ChartOrigin.Resolved           => "resolved"

  private def polarity(value: ChartPolarity): StoryPolarity = value match
    case ChartPolarity.Positive => StoryPolarity.Positive
    case ChartPolarity.Negative => StoryPolarity.Negative
    case ChartPolarity.Unknown  => StoryPolarity.Unknown

  /** The origin comes from the evidence's provenance, the same record the binding rule reads. */
  private def chartParams(
      unit: SurfaceUnit,
      checksum: Checksum,
      origin: ChartOrigin
  ): Map[String, String] =
    Map(
      "sentence" -> unit.id.value,
      "chart" -> checksum.hex,
      "chart-origin" -> renderOrigin(origin)
    )

  private def taskId(rule: String, scope: String, checksum: Checksum): TaskId =
    TaskId.unsafe(ContentAddress.of("chart-proposal-task", rule, scope, checksum.hex))

  private def providerCall(
      source: StorySource,
      rule: String,
      render: Vector[String],
      params: Map[String, String]
  ): ProviderCall =
    ProviderCall(
      ProviderName,
      ModelName,
      Version,
      None,
      source.canonicalChecksum,
      ContentAddress.digest(rule +: render),
      params + ("rule" -> rule),
      None,
      cached = false
    )

  /** One proposed value with its evidence; the call render always ends with the evidence id, so a
    * receipt identifies the exact spans it was made over.
    */
  private def proposed[A](
      source: StorySource,
      rule: String,
      scope: String,
      checksum: Checksum,
      value: A,
      evidence: Evidence,
      rawScore: Double,
      calibrationModel: String,
      render: Vector[String],
      params: Map[String, String]
  ): (EvidenceBundle[A], ProviderCall) =
    val task = taskId(rule, scope, checksum)
    val call = providerCall(source, rule, render :+ evidence.id.value, params)
    val proposal = AgentProposal.proposed(
      task,
      value,
      NonEmptyVector.one(EvidenceRef.Inline(evidence)),
      Some(RawScore.unsafe(rawScore)),
      Vector.empty,
      AgentCallReceipt(call, Prompt, task)
    )
    (
      EvidenceBundle(
        Vector(proposal),
        Vector.empty,
        StructuralValidity.Valid,
        SourceSupport(1.0, evidence.spans),
        agreementScore = 1.0,
        Vector(CandidateCalibration(value, Probability.One, calibrationModel))
      ),
      call
    )

  private def abstained[A](
      source: StorySource,
      rule: String,
      scope: String,
      checksum: Checksum,
      render: Vector[String],
      params: Map[String, String]
  ): (EvidenceBundle[A], ProviderCall) =
    val task = taskId(rule, scope, checksum)
    val call = providerCall(source, rule, render, params)
    val proposal = AgentProposal.abstained[A](task, AgentCallReceipt(call, Prompt, task))
    (
      EvidenceBundle(
        Vector(proposal),
        Vector.empty,
        StructuralValidity.Valid,
        SourceSupport(0.0, None),
        agreementScore = 0.0,
        Vector.empty
      ),
      call
    )

  /** Total, collision-free ordering key over a call; params are sorted so map order is irrelevant.
    */
  private def renderCall(call: ProviderCall): String =
    (Vector(
      call.provider,
      call.model,
      call.version,
      call.promptTemplateVersion.fold("")(_.value),
      call.inputChecksum.hex,
      call.outputChecksum.hex,
      call.seed.fold("")(_.toString),
      call.cached.toString
    ) ++ call.params.toVector.sorted.map((k, v) => s"$k=$v")).mkString("\u0000")
