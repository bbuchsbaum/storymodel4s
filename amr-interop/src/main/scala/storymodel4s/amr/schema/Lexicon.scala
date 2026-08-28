package storymodel4s.amr.schema

import storymodel4s.amr.graph.*
import storymodel4s.amr.graph.CheckState.Checked

/** Whether a frame argument is expected. AMR routinely omits arguments, so `Required` produces a
  * warning, never an error.
  */
enum Cardinality:
  case Optional
  case Required

/** PropBank functional tags on numbered arguments. Closed where the inventory is stable; `Custom`
  * keeps unknown or newer tags representable without ever being mistaken for a known one.
  */
enum FunctionalTag:
  case PAG, PPT, GOL, LOC, MNR, TMP, CAU, PRD, DIR, EXT, ADV, COM, PRP, REC, VSP, ADJ, DSP
  case Custom(raw: String)

  def render: String = this match
    case Custom(raw) => raw
    case other       => other.toString

object FunctionalTag:
  /** The closed inventory (no `values` on an enum with a parameterized case). */
  val standard: Vector[FunctionalTag] =
    Vector(PAG, PPT, GOL, LOC, MNR, TMP, CAU, PRD, DIR, EXT, ADV, COM, PRP, REC, VSP, ADJ, DSP)

  private val known: Map[String, FunctionalTag] = standard.map(t => t.render -> t).toMap

  /** Parse a tag as written in a frame file; whitespace is trimmed and ASCII case is normalized
    * (locale-independently) so `"pag "` still resolves to `PAG`.
    */
  def parse(raw: String): FunctionalTag =
    val norm = raw.trim.map(c => if c >= 'a' && c <= 'z' then (c - 32).toChar else c)
    known.getOrElse(norm, Custom(norm))

final case class ArgumentSpec(
    index: ArgIndex,
    description: String,
    functionalTag: Option[FunctionalTag],
    cardinality: Cardinality
)

/** A frame's argument structure. Argument meaning is frame-specific: `ARG0` is not "Agent". */
final case class FrameSpec(
    id: FrameId,
    gloss: String,
    arguments: Map[ArgIndex, ArgumentSpec],
    aliases: Set[Lemma]
):
  def licenses(i: ArgIndex): Boolean = arguments.contains(i)

/** Pure, versioned frame lookup. Implementations may be in-memory, generated, or remote-backed. */
trait FrameLexicon:
  def version: String
  def lookup(id: FrameId): Option[FrameSpec]
  def frames: Iterable[FrameSpec]
  def byLemma(lemma: String): Vector[FrameSpec] =
    frames.filter(f => f.id.lemma == lemma || f.aliases.exists(_.value == lemma)).toVector

final case class InMemoryLexicon(version: String, specs: Map[FrameId, FrameSpec])
    extends FrameLexicon:
  def lookup(id: FrameId): Option[FrameSpec] = specs.get(id)
  def frames: Iterable[FrameSpec] = specs.values
  def add(spec: FrameSpec): InMemoryLexicon = copy(specs = specs.updated(spec.id, spec))

object InMemoryLexicon:
  def of(version: String, specs: FrameSpec*): InMemoryLexicon =
    InMemoryLexicon(version, specs.map(s => s.id -> s).toMap)

/** Helper DSL for authoring specs. */
object Frames:
  def arg(i: Int, description: String, tag: String = "", required: Boolean = false): ArgumentSpec =
    ArgumentSpec(
      ArgIndex.unsafe(i),
      description,
      Option(tag).map(_.trim).filter(_.nonEmpty).map(FunctionalTag.parse),
      if required then Cardinality.Required else Cardinality.Optional
    )

  def frame(id: String, gloss: String, args: ArgumentSpec*): FrameSpec =
    FrameSpec(FrameId.unsafe(id), gloss, args.map(a => a.index -> a).toMap, Set.empty)

  def frameWithAliases(
      id: String,
      gloss: String,
      aliases: Set[String],
      args: ArgumentSpec*
  ): FrameSpec =
    frame(id, gloss, args*).copy(aliases = aliases.map(Lemma.unsafe))

/** Small hand-curated starter lexicon of common PropBank frames.
  *
  * Argument descriptions follow PropBank conventions from memory and are illustrative; the pinned,
  * generated PropBank lexicon replaces this in a later milestone. Nothing in the narrative layer
  * may depend on a lookup here succeeding.
  */
object StarterLexicon:
  import Frames.*

  val version: String = "starter-0.1"

  val lexicon: InMemoryLexicon = InMemoryLexicon.of(
    version,
    frame(
      "go-02",
      "motion",
      arg(0, "goer", "PAG"),
      arg(1, "extent"),
      arg(3, "start point"),
      arg(4, "end point")
    ),
    frame(
      "hear-01",
      "perceive sound",
      arg(0, "hearer", "PAG"),
      arg(1, "utterance or sound", "PPT"),
      arg(2, "speaker")
    ),
    frame(
      "hide-01",
      "conceal",
      arg(0, "hider", "PAG"),
      arg(1, "thing hidden", "PPT"),
      arg(2, "hidden from"),
      arg(3, "hiding place")
    ),
    frame(
      "come-01",
      "motion toward",
      arg(1, "entity in motion", "PPT"),
      arg(3, "start point"),
      arg(4, "end point")
    ),
    frame(
      "say-01",
      "utter",
      arg(0, "sayer", "PAG"),
      arg(1, "utterance", "PPT"),
      arg(2, "hearer", "GOL")
    ),
    frame(
      "think-01",
      "consider, believe",
      arg(0, "thinker", "PAG"),
      arg(1, "thought", "PPT"),
      arg(2, "about")
    ),
    frame(
      "want-01",
      "desire",
      arg(0, "wanter", "PAG"),
      arg(1, "wanted", "PPT"),
      arg(2, "beneficiary")
    ),
    frame(
      "fight-01",
      "combat",
      arg(0, "fighter", "PAG"),
      arg(1, "opponent or cause", "PPT"),
      arg(2, "co-fighter")
    ),
    frame(
      "hit-01",
      "strike",
      arg(0, "hitter", "PAG"),
      arg(1, "thing hit", "PPT"),
      arg(2, "instrument")
    ),
    frame(
      "strike-01",
      "hit with force",
      arg(0, "striker", "PAG"),
      arg(1, "thing struck", "PPT"),
      arg(2, "instrument")
    ),
    frame(
      "shoot-02",
      "fire a projectile at",
      arg(0, "shooter", "PAG"),
      arg(1, "target", "PPT"),
      arg(2, "projectile or weapon")
    ),
    frame(
      "feel-01",
      "experience a sensation",
      arg(0, "experiencer", "PAG"),
      arg(1, "feeling or stimulus", "PPT")
    ),
    frame("sick-05", "be ill", arg(1, "sick entity", "PPT"), arg(2, "illness")),
    frame("die-01", "cease living", arg(1, "the one dying", "PPT"), arg(2, "cause")),
    frame(
      "tell-01",
      "communicate",
      arg(0, "teller", "PAG"),
      arg(1, "utterance", "PPT"),
      arg(2, "hearer", "GOL")
    ),
    frame(
      "return-01",
      "go back",
      arg(1, "entity returning", "PPT"),
      arg(3, "source"),
      arg(4, "destination")
    ),
    frame("make-02", "create", arg(0, "creator", "PAG"), arg(1, "creation", "PRD")),
    frame("war-01", "wage war", arg(0, "party", "PAG"), arg(1, "opponent", "PPT")),
    frame("see-01", "view", arg(0, "viewer", "PAG"), arg(1, "thing viewed", "PPT")),
    frame("come-out-06", "emerge", arg(1, "thing emerging", "PPT"), arg(2, "source")),
    frame("fall-01", "move downward", arg(1, "faller", "PPT"), arg(3, "start"), arg(4, "end")),
    frame("cry-02", "shed tears", arg(0, "crier", "PAG"), arg(1, "cause")),
    frame("hunt-01", "pursue prey", arg(0, "hunter", "PAG"), arg(1, "prey", "PPT")),
    frame(
      "call-01",
      "call out, summon",
      arg(0, "caller", "PAG"),
      arg(1, "called", "PPT"),
      arg(2, "name")
    ),
    frame("accompany-01", "go with", arg(0, "accompanier", "PAG"), arg(1, "accompanied", "PPT")),
    frame("paddle-01", "propel a boat", arg(0, "paddler", "PAG"), arg(1, "vessel", "PPT")),
    frame(
      "kill-01",
      "cause to die",
      arg(0, "killer", "PAG"),
      arg(1, "killed", "PPT"),
      arg(2, "instrument")
    ),
    frame("know-01", "be aware", arg(0, "knower", "PAG"), arg(1, "known", "PPT")),
    frame("live-01", "reside", arg(0, "resident", "PAG"), arg(1, "residence")),
    frame(
      "give-01",
      "transfer",
      arg(0, "giver", "PAG"),
      arg(1, "thing given", "PPT"),
      arg(2, "recipient", "GOL")
    ),
    frame("find-01", "discover", arg(0, "finder", "PAG"), arg(1, "found", "PPT")),
    frame("enter-01", "go into", arg(0, "enterer", "PAG"), arg(1, "place entered", "PPT")),
    frame(
      "search-01",
      "look for",
      arg(0, "searcher", "PAG"),
      arg(1, "place searched", "LOC"),
      arg(2, "sought")
    ),
    frame("arrive-01", "reach", arg(1, "arriver", "PPT"), arg(4, "destination")),
    frame("scream-01", "cry out", arg(0, "screamer", "PAG"), arg(1, "content")),
    frame("injure-01", "harm", arg(0, "injurer", "PAG"), arg(1, "injured", "PPT")),
    frame("sing-01", "produce song", arg(0, "singer", "PAG"), arg(1, "song", "PPT")),
    frame("cause-01", "bring about", arg(0, "cause", "PAG"), arg(1, "effect", "PPT")),
    frame("be-located-at-91", "location reification", arg(1, "located entity"), arg(2, "location")),
    frame("be-temporally-at-91", "time reification", arg(1, "entity"), arg(2, "time")),
    frame("have-purpose-91", "purpose reification", arg(1, "entity"), arg(2, "purpose")),
    frame("have-mod-91", "modifier reification", arg(1, "modified"), arg(2, "modifier")),
    frame("have-manner-91", "manner reification", arg(1, "event"), arg(2, "manner")),
    frame("have-instrument-91", "instrument reification", arg(1, "event"), arg(2, "instrument")),
    frame("have-part-91", "part reification", arg(1, "whole"), arg(2, "part")),
    frame("have-quant-91", "quantity reification", arg(1, "entity"), arg(2, "quantity")),
    frame(
      "have-org-role-91",
      "organisational role",
      arg(0, "member"),
      arg(1, "organisation"),
      arg(2, "title")
    ),
    frame(
      "have-rel-role-91",
      "relational role",
      arg(0, "first"),
      arg(1, "second"),
      arg(2, "role of first"),
      arg(3, "role of second")
    ),
    frame("own-01", "possess", arg(0, "owner", "PAG"), arg(1, "possession", "PPT")),
    frame("benefit-01", "benefit", arg(0, "benefactor"), arg(1, "beneficiary")),
    frame("last-01", "endure", arg(1, "event"), arg(2, "duration")),
    frame("concern-02", "be about", arg(0, "topic"), arg(1, "entity concerned")),
    frame("be-from-91", "source reification", arg(1, "entity"), arg(2, "source")),
    frame("be-destined-for-91", "destination reification", arg(1, "entity"), arg(2, "destination")),
    frame("have-subevent-91", "subevent reification", arg(1, "event"), arg(2, "subevent")),
    frame("have-condition-91", "condition reification", arg(1, "consequence"), arg(2, "condition")),
    frame("have-concession-91", "concession reification", arg(1, "event"), arg(2, "concession")),
    frame("have-extent-91", "extent reification", arg(1, "entity"), arg(2, "extent")),
    frame("have-frequency-91", "frequency reification", arg(1, "event"), arg(2, "frequency")),
    frame("have-polarity-91", "polarity reification", arg(1, "proposition"), arg(2, "polarity")),
    frame("have-value-91", "value reification", arg(1, "entity"), arg(2, "value")),
    frame("age-01", "have age", arg(1, "entity"), arg(2, "age")),
    frame("exemplify-01", "be an example", arg(0, "example"), arg(1, "class"))
  )

/** Schema findings are advisory by default (§40.3): the ontology is open and evolving. */
enum Severity:
  case Warning
  case Error

enum SchemaFinding:
  case UnknownFrame(node: NodeId, frame: FrameId)
  case UnlicensedArgument(node: NodeId, frame: FrameId, index: ArgIndex)
  case MissingRequiredArgument(node: NodeId, frame: FrameId, index: ArgIndex)
  case DuplicateArgument(node: NodeId, frame: FrameId, index: ArgIndex)

  def severity: Severity = this match
    case UnknownFrame(_, _)               => Severity.Warning
    case MissingRequiredArgument(_, _, _) => Severity.Warning
    case UnlicensedArgument(_, _, _)      => Severity.Error
    case DuplicateArgument(_, _, _)       => Severity.Error

/** Checks frame usage against a lexicon without ever equating `ARG0` with an agent. */
object SchemaChecker:
  def check[R <: RoleForm](g: AmrGraph[Checked, R], lexicon: FrameLexicon): Vector[SchemaFinding] =
    g.frameNodes.flatMap { (n, f) =>
      val args = g.canonicalTriples.collect { case (s, Role.Arg(i), _) if s == n => i }
      lexicon.lookup(f) match
        case None       => Vector(SchemaFinding.UnknownFrame(n, f))
        case Some(spec) =>
          val dup = args.groupBy(identity).collect { case (i, occ) if occ.size > 1 => i }.toVector
          val unlicensed = args.distinct.filterNot(spec.licenses)
          val missing = spec.arguments.values
            .filter(a => a.cardinality == Cardinality.Required && !args.contains(a.index))
            .map(_.index)
            .toVector
          dup.sorted.map(SchemaFinding.DuplicateArgument(n, f, _)) ++
            unlicensed.sorted.map(SchemaFinding.UnlicensedArgument(n, f, _)) ++
            missing.sorted.map(SchemaFinding.MissingRequiredArgument(n, f, _))
    }

  def errors[R <: RoleForm](g: AmrGraph[Checked, R], lexicon: FrameLexicon): Vector[SchemaFinding] =
    check(g, lexicon).filter(_.severity == Severity.Error)
