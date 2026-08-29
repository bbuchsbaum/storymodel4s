package storymodel4s.interview

import cats.data.NonEmptyVector

import storymodel4s.core.*
import storymodel4s.features.{Estimate, MissingReason, ScoreEstimate}
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Namespaced reasons why interview specificity was applicable but unavailable. */
object SpecificityMissingReason:
  private val Namespace = "storymodel4s.interview.specificity"

  /** Classification was absent despite the induction graph containing the source unit. */
  val Unclassified: MissingReason = MissingReason.Custom(Namespace, "unclassified")

  /** The source unit was explicitly classified as uninterpretable. */
  val ClassifiedUninterpretable: MissingReason =
    MissingReason.Custom(Namespace, "classified-uninterpretable")

  /** The input detail referred to no unit in the induction graph. */
  val SourceUnitAbsent: MissingReason = MissingReason.Custom(Namespace, "source-unit-absent")

/** Injected semantic distance between two recall units (embeddings); `None` = lexical only. */
trait SemanticDistance:
  def distance(a: RecallUnit, b: RecallUnit): Double

/** Tunable, uncalibrated parameters of v0.1 induction. None of these are probabilities.
  *
  * Constructed only through [[InductionConfig.of]] (or [[InductionConfig.default]]), which checks
  * that every mass lies in `(0, 1]` and every threshold in `[0, 1]`, so induction never has to cope
  * with negative remainders.
  */
final class InductionConfig private (
    val targetMass: Double,
    val otherMass: Double,
    val habitualMass: Double,
    val discourseMass: Double,
    val repetitionThreshold: Double,
    val continuityThreshold: Double,
    val alternativeMargin: Double,
    val softwareVersion: String
):
  override def equals(other: Any): Boolean = other match
    case that: InductionConfig =>
      targetMass == that.targetMass &&
      otherMass == that.otherMass &&
      habitualMass == that.habitualMass &&
      discourseMass == that.discourseMass &&
      repetitionThreshold == that.repetitionThreshold &&
      continuityThreshold == that.continuityThreshold &&
      alternativeMargin == that.alternativeMargin &&
      softwareVersion == that.softwareVersion
    case _ => false

  override def hashCode(): Int =
    (
      targetMass,
      otherMass,
      habitualMass,
      discourseMass,
      repetitionThreshold,
      continuityThreshold,
      alternativeMargin,
      softwareVersion
    ).##

  override def toString: String =
    s"InductionConfig(version=$softwareVersion, targetMass=$targetMass, " +
      s"otherMass=$otherMass, habitualMass=$habitualMass, discourseMass=$discourseMass)"

object InductionConfig:
  val default: InductionConfig =
    new InductionConfig(0.85, 0.8, 0.6, 0.9, 0.6, 0.15, 0.25, "storymodel4s-interview-0.1")

  def of(
      targetMass: Double = default.targetMass,
      otherMass: Double = default.otherMass,
      habitualMass: Double = default.habitualMass,
      discourseMass: Double = default.discourseMass,
      repetitionThreshold: Double = default.repetitionThreshold,
      continuityThreshold: Double = default.continuityThreshold,
      alternativeMargin: Double = default.alternativeMargin,
      softwareVersion: String = default.softwareVersion
  ): Either[DomainError, InductionConfig] =
    def mass(name: String, v: Double): Either[DomainError, Unit] =
      if v > 0.0 && v <= 1.0 && !v.isNaN then Right(())
      else Left(DomainError.InvariantViolation(s"induction/$name", s"mass $v not in (0, 1]"))
    def unit(name: String, v: Double): Either[DomainError, Unit] =
      if v >= 0.0 && v <= 1.0 && !v.isNaN then Right(())
      else Left(DomainError.InvariantViolation(s"induction/$name", s"value $v not in [0, 1]"))
    for
      _ <- mass("targetMass", targetMass)
      _ <- mass("otherMass", otherMass)
      _ <- mass("habitualMass", habitualMass)
      _ <- mass("discourseMass", discourseMass)
      _ <- unit("repetitionThreshold", repetitionThreshold)
      _ <- unit("continuityThreshold", continuityThreshold)
      _ <- unit("alternativeMargin", alternativeMargin)
      _ <-
        if softwareVersion.nonEmpty then Right(())
        else Left(DomainError.InvariantViolation("induction/softwareVersion", "empty"))
    yield new InductionConfig(
      targetMass,
      otherMass,
      habitualMass,
      discourseMass,
      repetitionThreshold,
      continuityThreshold,
      alternativeMargin,
      softwareVersion
    )

/** Result of joint target-episode induction and detail routing (design record §64).
  *
  * `alternatives` are competing target hypotheses scored against the selected target; under the
  * selected hypothesis they are other specific episodes and appear as such in `otherEpisodes`.
  */
final case class InductionResult(
    target: Option[EpisodeModel],
    alternatives: Vector[(EpisodeModel, Double)],
    otherEpisodes: Vector[EpisodeModel],
    addresses: Map[DetailId, Distribution[MemoryAddress]],
    specificity: Map[DetailId, ScoreEstimate],
    repetitions: Map[DetailId, DetailId]
)

/** Joint induction of the latent target episode and routing of every detail.
  *
  * v0.1 is rule-based and documented as such: seed → clusters by continuity and explicit
  * other-episode markers → target selection by cue compatibility, prominence and coherence →
  * routing of the rest. Competing targets are retained as alternatives; every output is a
  * distribution. Embeddings, when injected, only propose cluster continuity — they never decide
  * episode identity.
  */
object TargetInduction:
  private[interview] enum UnitClass:
    case Episodic, Summary, OtherEpisode, Habitual, GeneralFact, Metacognitive, Evaluative,
      Repair, TaskCommentary, Association, Inference, Uninterpretable

  private val Habitual =
    """\b(?:always|usually|every (?:year|time|birthday|summer|week)|used to|we'd|would (?:always|usually|go|have)|typically|normally|tend to|as a rule)\b""".r
  private val OtherEpisodeMarker =
    """\b(?:the year before|the previous year|last year|another (?:time|birthday|year)|a different (?:time|birthday|year)|once when|one time|that other time|two years (?:ago|earlier)|years earlier|on a different)\b""".r

  /** Explicit return to the running target, never a discourse connective (`then`/`later`/`after
    * that` do not change episode membership).
    */
  private val ReturnMarker =
    """\b(?:anyway|back to|but this time|this birthday|that birthday)\b""".r
  private val GeneralFact =
    """\b(?:is (?:a|the) (?:city|capital|kind of|type of)|are (?:usually|generally)|everyone knows|as you know|in general)\b""".r

  /** Total classification: every recall-side discourse function has an explicit class; only
    * `EpisodicAssertion` and `Summary` are further refined by lexical cues, and only those two can
    * ever end up in an episode.
    *
    * `byCue` may refine those two only inside content space (`Habitual`, `GeneralFact`,
    * `OtherEpisode`). Metacognitive, repair, and evaluative language on an episodic unit is
    * recorded on [[ExperientialEvidence]] at assess time and must not erase the episodic address.
    */
  private[interview] def classify(unit: RecallUnit): UnitClass =
    val lower = Text.lower(unit.text)
    def byCue(default: UnitClass): UnitClass =
      if Habitual.findFirstIn(lower).nonEmpty then UnitClass.Habitual
      else if GeneralFact.findFirstIn(lower).nonEmpty then UnitClass.GeneralFact
      else if OtherEpisodeMarker.findFirstIn(lower).nonEmpty then UnitClass.OtherEpisode
      else default
    unit.function match
      case DiscourseFunction.EpisodicAssertion => byCue(UnitClass.Episodic)
      case DiscourseFunction.Summary           => byCue(UnitClass.Summary)
      case DiscourseFunction.Inference         => UnitClass.Inference
      case DiscourseFunction.Association       => UnitClass.Association
      case DiscourseFunction.Evaluation        => UnitClass.Evaluative
      case DiscourseFunction.SourceMonitoring  => UnitClass.Metacognitive
      case DiscourseFunction.TaskCommentary    => UnitClass.TaskCommentary
      case DiscourseFunction.Uninterpretable   => UnitClass.Uninterpretable

  private def isEpisodic(c: UnitClass): Boolean = c match
    case UnitClass.Episodic | UnitClass.Summary | UnitClass.OtherEpisode => true
    case _                                                               => false

  private[interview] def specificityOf(
      classification: Option[UnitClass],
      anchored: Boolean
  ): ScoreEstimate =
    classification match
      case Some(UnitClass.Episodic) | Some(UnitClass.OtherEpisode) =>
        Estimate.observed(if anchored then 0.85 else 0.65)
      // A summary denotes the episode at reduced specificity (design record §61, §65).
      case Some(UnitClass.Summary)         => Estimate.observed(if anchored then 0.45 else 0.3)
      case Some(UnitClass.Habitual)        => Estimate.observed(0.15)
      case Some(UnitClass.GeneralFact)     => Estimate.observed(0.05)
      case Some(UnitClass.Uninterpretable) =>
        Estimate.missing(SpecificityMissingReason.ClassifiedUninterpretable)
      // Eligibility for the remaining discourse classes requires a portable three-way carrier;
      // bd-01M16DBEH9PKER423BZ47ZKBMV owns that migration.
      case Some(_) => Estimate.observed(0.0)
      case None    => Estimate.missing(SpecificityMissingReason.Unclassified)

  private def lexicalOverlap(a: RecallUnit, b: RecallUnit): Double =
    val x = a.proposition.lemmas
    val y = b.proposition.lemmas
    if x.isEmpty || y.isEmpty then 0.0 else x.intersect(y).size.toDouble / x.union(y).size

  /** Why a unit was placed in a cluster. The basis is the examinable record of the interpretation.
    */
  private[interview] enum PlacementBasis:
    case Seed, OtherEpisodeMarker, ExplicitReturn, ContinuityStay, ContinuityReturn, Unattached,
      Ambiguous

  /** Cluster id plus the reason it was chosen, so address mass can stay examinable. */
  private[interview] final case class ClusterAssignment(cluster: Int, basis: PlacementBasis)

  /** One continuity predicate for staying in a digression, returning to the target, and opening an
    * unattached cluster. Opening by [[OtherEpisodeMarker]] is a separate explicit cue.
    */
  private[interview] object ClusterContinuity:
    def score(
        a: RecallUnit,
        b: RecallUnit,
        semantic: Option[SemanticDistance]
    ): Double =
      val shared =
        a.proposition.locations.intersect(b.proposition.locations).nonEmpty ||
          a.proposition.participants
            .flatMap(_.entity)
            .toSet
            .intersect(b.proposition.participants.flatMap(_.entity).toSet)
            .nonEmpty ||
          a.proposition.times.intersect(b.proposition.times).nonEmpty
      val lex = lexicalOverlap(a, b)
      val sem = semantic.map(s => 1.0 - s.distance(a, b)).getOrElse(0.0)
      math.max(if shared then 1.0 else 0.0, math.max(lex, sem))

    def holds(
        a: RecallUnit,
        b: RecallUnit,
        semantic: Option[SemanticDistance],
        threshold: Double
    ): Boolean =
      score(a, b, semantic) >= threshold

    def withAny(
        u: RecallUnit,
        group: Vector[RecallUnit],
        semantic: Option[SemanticDistance],
        threshold: Double
    ): Boolean =
      group.exists(g => holds(u, g, semantic, threshold))

  /** Assign episodic units to clusters.
    *
    * From a digression, return to the target iff (a) an explicit [[ReturnMarker]] or (b) continuity
    * with the digression is lost AND continuity with the target holds. Lost with both opens a new
    * cluster ([[PlacementBasis.Unattached]]). Continuous with both is [[PlacementBasis.Ambiguous]],
    * not iteration order. Discourse connectives are not a return.
    */
  private def clusters(
      units: Vector[RecallUnit],
      classes: Map[RecallUnitId, UnitClass],
      semantic: Option[SemanticDistance],
      config: InductionConfig
  ): Map[RecallUnitId, ClusterAssignment] =
    val threshold = config.continuityThreshold
    var current = 0
    var next = 1
    var digression: Vector[RecallUnit] = Vector.empty
    var targetUnits: Vector[RecallUnit] = Vector.empty
    val out = Map.newBuilder[RecallUnitId, ClusterAssignment]
    units.foreach { u =>
      classes.get(u.id) match
        case Some(UnitClass.OtherEpisode) =>
          current = next
          next += 1
          digression = Vector(u)
          out += u.id -> ClusterAssignment(current, PlacementBasis.OtherEpisodeMarker)
        case Some(UnitClass.Episodic) | Some(UnitClass.Summary) =>
          val lower = Text.lower(u.text)
          val assignment =
            if current == 0 then ClusterAssignment(0, PlacementBasis.Seed)
            else
              val marked = ReturnMarker.findFirstIn(lower).nonEmpty
              val withDig = ClusterContinuity.withAny(u, digression, semantic, threshold)
              val withTarget = ClusterContinuity.withAny(u, targetUnits, semantic, threshold)
              if marked then
                current = 0
                ClusterAssignment(0, PlacementBasis.ExplicitReturn)
              else if withDig && !withTarget then
                digression = digression :+ u
                ClusterAssignment(current, PlacementBasis.ContinuityStay)
              else if !withDig && withTarget then
                current = 0
                ClusterAssignment(0, PlacementBasis.ContinuityReturn)
              else if withDig && withTarget then
                ClusterAssignment(current, PlacementBasis.Ambiguous)
              else
                current = next
                next += 1
                digression = Vector(u)
                ClusterAssignment(current, PlacementBasis.Unattached)
          if assignment.cluster == 0 then targetUnits = targetUnits :+ u
          out += u.id -> assignment
        case _ => ()
    }
    out.result()

  private def episodeOf(
      id: EpisodeId,
      scope: EpisodeScope,
      units: Vector[RecallUnit],
      details: Vector[Detail]
  ): EpisodeModel =
    val unitIds = units.map(_.id).toSet
    val ds = details.filter(d => unitIds.contains(d.sourceUnit))
    val sits = units.map(AtomProjection.situationOf)
    val entities = ds.flatMap { d =>
      d.atom match
        case DetailAtom.ParticipantFact(_, _, e) => Some(e)
        case DetailAtom.MentalStateFact(h, _)    => Some(h)
        case _                                   => None
    }.toSet
    val locations = units.flatMap(_.proposition.locations).map(PlaceName(_)).toSet
    val anchors = ds.collect {
      case Detail(_, DetailAtom.TemporalFact(TemporalClaim.Anchor(_, e)), _, _, _, _) => e
    }
    val relations = ds.collect { case Detail(_, DetailAtom.RelationalFact(r), _, _, _, _) => r }
    val support = SpanSet.of(units.flatMap(_.span.refs.toVector))
    EpisodeModel.hypothesized(id, scope, sits, entities, locations, anchors, relations, support)

  private def cueScore(cue: Cue, units: Vector[RecallUnit]): Double =
    val words = Text
      .words(Text.lower(cue.text + " " + cue.nominatedEvent.getOrElse("")))
      .filter(w => w.length > 3)
      .toSet
    if words.isEmpty then 0.0
    else
      val lemmas = units.flatMap(_.proposition.lemmas).toSet
      words.count(w => lemmas.exists(l => l.startsWith(w.take(5)))).toDouble / words.size

  /** A distribution from a primary mass and the split of the remainder; total by construction. */
  private def dist(
      primary: (MemoryAddress, Double),
      remainder: Vector[(MemoryAddress, Double)]
  ): Distribution[MemoryAddress] =
    val rest = 1.0 - primary._2
    val pairs = primary +: remainder.map { case (a, share) => a -> rest * share }
    Distribution
      .of(pairs.filter(_._2 > 0.0))
      .getOrElse(Distribution.point(MemoryAddress.Unresolved))

  /** Read-only cluster assignments for measurement fixtures. Same function `induce` uses. */
  private[interview] def clusterAssignments(
      graph: RecallGraph[Checked],
      config: InductionConfig = InductionConfig.default,
      semantic: Option[SemanticDistance] = None
  ): Map[RecallUnitId, ClusterAssignment] =
    val units = graph.ordered
    val classes = units.map(u => u.id -> classify(u)).toMap
    clusters(units, classes, semantic, config)

  def induce(
      graph: RecallGraph[Checked],
      details: Vector[Detail],
      cue: Cue,
      config: InductionConfig = InductionConfig.default,
      semantic: Option[SemanticDistance] = None
  ): InductionResult =
    val units = graph.ordered
    val classes = units.map(u => u.id -> classify(u)).toMap
    val cl = clusterAssignments(graph, config, semantic)
    val clusterOf: Map[RecallUnitId, Int] = cl.view.mapValues(_.cluster).toMap
    val byCluster: Map[Int, Vector[RecallUnit]] =
      units
        .filter { u =>
          cl.get(u.id).exists(a => a.basis != PlacementBasis.Ambiguous)
        }
        .groupBy(u => clusterOf(u.id))

    // Target selection: cue compatibility + prominence (unit share) + coherence (relations/locs).
    val scored: Vector[(Int, Double)] = byCluster.toVector
      .map { case (k, us) =>
        val prominence = us.size.toDouble / math.max(1, cl.size)
        val cueFit = cueScore(cue, us)
        val anchored =
          if us.exists(u => u.proposition.locations.nonEmpty || u.proposition.times.nonEmpty) then
            0.2
          else 0.0
        val seedBonus = if k == 0 then 0.15 else 0.0
        (k, prominence + cueFit + anchored + seedBonus)
      }
      .sortBy { case (k, s) => (-s, k) }

    val targetCluster = scored.headOption.map(_._1)
    val targetEpisode = targetCluster.map { k =>
      episodeOf(
        EpisodeId.unsafe("interview:episode:target"),
        EpisodeScope.TargetSpecific,
        byCluster.getOrElse(k, Vector.empty),
        details
      )
    }
    // Every non-target cluster is materialized exactly once, as an other specific episode under
    // the selected hypothesis; the ones close enough in score are additionally reported as
    // competing target hypotheses.
    val others: Map[Int, EpisodeModel] = byCluster.keys.toVector.sorted
      .filter(k => !targetCluster.contains(k))
      .map { k =>
        k -> episodeOf(
          EpisodeId.unsafe(s"interview:episode:other$k"),
          EpisodeScope.OtherSpecific,
          byCluster.getOrElse(k, Vector.empty),
          details
        )
      }
      .toMap
    val best = scored.headOption.map(_._2).getOrElse(0.0)
    val alternatives: Vector[(EpisodeModel, Double)] = scored
      .drop(1)
      .filter { case (_, s) => best - s < config.alternativeMargin }
      .flatMap { case (k, s) => others.get(k).map(_ -> s) }
    val repeatedId = EpisodeId.unsafe("interview:episode:repeated")
    val repeated = {
      val hs = units.filter(u => classes.get(u.id).contains(UnitClass.Habitual))
      if hs.isEmpty then None
      else Some(episodeOf(repeatedId, EpisodeScope.RepeatedOrCategoric, hs, details))
    }

    // Repetition: a later detail whose unit paraphrases an earlier unit of the same class.
    val repetitions: Map[DetailId, DetailId] =
      val byUnit = details.groupBy(_.sourceUnit)
      val pairs = for
        (u, i) <- units.zipWithIndex
        uc <- classes.get(u.id).toVector
        if isEpisodic(uc)
        earlier <- units
          .take(i)
          .reverseIterator
          .find { e =>
            classes.get(e.id).contains(uc) &&
            lexicalOverlap(e, u) >= config.repetitionThreshold &&
            e.proposition.predicate == u.proposition.predicate && e.proposition.predicate.nonEmpty
          }
          .toVector
        d <- byUnit.getOrElse(u.id, Vector.empty)
        first <- byUnit.getOrElse(earlier.id, Vector.empty).headOption.toVector
      yield d.id -> first.id
      pairs.toMap

    val targetAddr: Option[MemoryAddress] =
      targetEpisode.map(t => MemoryAddress.Episode(t.id, EpisodeScope.TargetSpecific))
    val altAddr: Option[MemoryAddress] =
      alternatives.headOption.map(a => MemoryAddress.Episode(a._1.id, EpisodeScope.OtherSpecific))

    def clusterAddress(k: Int): Distribution[MemoryAddress] =
      if targetCluster.contains(k) then
        targetAddr match
          case None    => Distribution.point(MemoryAddress.Unresolved)
          case Some(t) =>
            dist(
              t -> config.targetMass,
              altAddr.map(_ -> 0.3).toVector :+ (MemoryAddress.Unresolved -> 0.7)
            )
      else
        others.get(k) match
          case None    => Distribution.point(MemoryAddress.Unresolved)
          case Some(o) =>
            dist(
              MemoryAddress.Episode(o.id, EpisodeScope.OtherSpecific) -> config.otherMass,
              targetAddr.map(_ -> 0.6).toVector :+ (MemoryAddress.Unresolved -> 0.4)
            )

    def addrOf(k: Int): Option[MemoryAddress] =
      if targetCluster.contains(k) then targetAddr
      else others.get(k).map(o => MemoryAddress.Episode(o.id, EpisodeScope.OtherSpecific))

    def episodicAddress(assignment: ClusterAssignment): Distribution[MemoryAddress] =
      assignment.basis match
        case PlacementBasis.Unattached =>
          dist(
            MemoryAddress.Unresolved -> 0.55,
            addrOf(assignment.cluster).map(_ -> 1.0).toVector
          )
        case PlacementBasis.Ambiguous =>
          dist(
            MemoryAddress.Unresolved -> 0.4,
            addrOf(0).map(_ -> 0.5).toVector ++ addrOf(assignment.cluster).map(_ -> 0.5).toVector
          )
        case _ => clusterAddress(assignment.cluster)

    def discourse(f: InterviewDiscourseFunction): Distribution[MemoryAddress] =
      dist(
        MemoryAddress.Discourse(f) -> config.discourseMass,
        Vector(MemoryAddress.Unresolved -> 1.0)
      )

    val addresses: Map[DetailId, Distribution[MemoryAddress]] = details.flatMap { d =>
      graph.byId.get(d.sourceUnit).map { u =>
        val addr: Distribution[MemoryAddress] = repetitions.get(d.id) match
          case Some(of) => discourse(InterviewDiscourseFunction.Repetition(of))
          case None     =>
            classes.getOrElse(u.id, UnitClass.Uninterpretable) match
              case UnitClass.Episodic | UnitClass.Summary | UnitClass.OtherEpisode =>
                cl.get(u.id)
                  .map(episodicAddress)
                  .getOrElse(Distribution.point(MemoryAddress.Unresolved))
              case UnitClass.Habitual =>
                dist(
                  MemoryAddress.PersonalKnowledge(
                    PersonalKnowledgeKind.HabitOrRoutine
                  ) -> config.habitualMass,
                  Vector(
                    MemoryAddress.Episode(repeatedId, EpisodeScope.RepeatedOrCategoric) -> 0.8,
                    MemoryAddress.Unresolved -> 0.2
                  )
                )
              case UnitClass.GeneralFact =>
                dist(
                  MemoryAddress.GeneralKnowledge -> 0.7,
                  Vector(
                    MemoryAddress.PersonalKnowledge(
                      PersonalKnowledgeKind.AutobiographicalFact
                    ) -> 0.67,
                    MemoryAddress.Unresolved -> 0.33
                  )
                )
              case UnitClass.Metacognitive => discourse(InterviewDiscourseFunction.Metacognitive)
              case UnitClass.Evaluative    => discourse(InterviewDiscourseFunction.Evaluation)
              case UnitClass.Repair => discourse(InterviewDiscourseFunction.ConversationalRepair)
              case UnitClass.TaskCommentary  => discourse(InterviewDiscourseFunction.TaskCommentary)
              case UnitClass.Association     => discourse(InterviewDiscourseFunction.Association)
              case UnitClass.Inference       => discourse(InterviewDiscourseFunction.Inference)
              case UnitClass.Uninterpretable => Distribution.point(MemoryAddress.Unresolved)
        d.id -> addr
      }
    }.toMap

    val specificity: Map[DetailId, ScoreEstimate] = details.map { d =>
      val estimate = graph.byId.get(d.sourceUnit) match
        case Some(u) =>
          val anchored = u.proposition.locations.nonEmpty || u.proposition.times.nonEmpty
          specificityOf(classes.get(u.id), anchored)
        case None => Estimate.missing(SpecificityMissingReason.SourceUnitAbsent)
      d.id -> estimate
    }.toMap

    InductionResult(
      targetEpisode,
      alternatives,
      others.values.toVector.sortBy(_.id.value) ++ repeated.toVector,
      addresses,
      specificity,
      repetitions
    )

  private val FirstPerson =
    """\bi (?:remember|can still|can see|can hear|saw|heard|felt)\b""".r
  private val Hearsay =
    """\b(?:my (?:mother|mom|father|dad|sister|brother) (?:told|says|said)|i was told|they told me)\b""".r
  private val PhotoCue = """\b(?:photo|picture|video)\b""".r
  private val RememberCue = """\bi remember\b""".r

  /** Assemble assessments from an induction result with deterministic claim metadata. Details whose
    * unit or address is unknown are skipped (the model validator reports them as unassessed) rather
    * than invented.
    */
  def assess(
      source: InterviewSource,
      graph: RecallGraph[Checked],
      details: Vector[Detail],
      result: InductionResult,
      config: InductionConfig = InductionConfig.default
  ): Vector[DetailAssessment] =
    val fp = Fingerprint.unsafe(s"interview:target-induction:${config.softwareVersion}")
    val stage = StageId.unsafe("interview-induction")
    val prov = Provenance.deterministic(config.softwareVersion, Checksum.ofText(config.toString))
    details.flatMap { d =>
      for
        u <- graph.byId.get(d.sourceUnit)
        address <- result.addresses.get(d.id)
      yield
        val offset = d.support.minSpan.start
        val phase = source.phaseAt(offset)
        val probe = source.probeBefore(offset).map(_.id)
        val lower = Text.lower(u.text)
        // Unit-level cues, copied onto every atom of this unit. ProfileScoring.phenomenology
        // must collapse by sourceUnit — averaging these flags over details would derive
        // phenomenology from atomization density (contract 7).
        val firstPerson = FirstPerson.findFirstIn(lower).nonEmpty
        val monitoring =
          if Hearsay.findFirstIn(lower).nonEmpty then Some(SourceMonitoring.Hearsay)
          else if PhotoCue.findFirstIn(lower).nonEmpty then Some(SourceMonitoring.Photo)
          else if RememberCue.findFirstIn(lower).nonEmpty then Some(SourceMonitoring.DirectMemory)
          else None
        val topMass = address.toVector.headOption.map(_._2).getOrElse(0.0)
        DetailAssessment(
          d,
          address,
          DetailAssessment.defaultFacets(d.atom),
          result.specificity.getOrElse(d.id, Estimate.missing(MissingReason.Unknown)),
          ExperientialEvidence(
            source.ratings.flatMap(_.reliving),
            firstPerson,
            monitoring.toVector
          ),
          EpistemicStatus.Hypothesized,
          PromptContext(phase, probe),
          monitoring,
          ClaimMeta.unsafe(
            ClaimId.unsafe(s"claim:${d.id.value}"),
            EpistemicStatus.Hypothesized,
            Credence.unsafeRaw(topMass),
            NonEmptyVector.one(
              Evidence(
                EvidenceId.unsafe(s"ev:${d.id.value}"),
                Some(d.support),
                Set.empty,
                fp,
                stage
              )
            ),
            prov
          )
        )
    }
