package storymodel4s.interview

import cats.data.NonEmptyVector

import storymodel4s.core.*
import storymodel4s.features.{Estimate, ScoreEstimate}
import storymodel4s.recall.*

/** Injected semantic distance between two recall units (embeddings); `None` = lexical only. */
trait SemanticDistance:
  def distance(a: RecallUnit, b: RecallUnit): Double

/** Tunable, uncalibrated parameters of v0.1 induction. None of these are probabilities.
  *
  * Constructed only through [[InductionConfig.of]] (or [[InductionConfig.default]]), which checks
  * that every mass lies in `(0, 1]` and every threshold in `[0, 1]`, so induction never has to cope
  * with negative remainders.
  */
final case class InductionConfig private (
    targetMass: Double,
    otherMass: Double,
    habitualMass: Double,
    discourseMass: Double,
    repetitionThreshold: Double,
    continuityThreshold: Double,
    alternativeMargin: Double,
    softwareVersion: String
)

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
  private val ReturnMarker =
    """\b(?:anyway|back to|but this time|this birthday|that birthday|then|after that|later|eventually|afterwards)\b""".r
  private val Metacognitive =
    """\b(?:i (?:can'?t|cannot|don'?t|do not) (?:really )?(?:remember|recall)|i forget|i'?m not sure|i have no memory|it'?s a blur|i'?m blanking)\b""".r
  private val Evaluative =
    """\b(?:it was (?:great|wonderful|lovely|awful|terrible|nice|fun|perfect|amazing)|i (?:loved|hated|enjoyed)|the best|the worst|so nice|really nice)\b""".r
  private val Repair =
    """\b(?:i mean|sorry|no wait|actually,? no|let me (?:think|start over))\b""".r
  private val GeneralFact =
    """\b(?:is (?:a|the) (?:city|capital|kind of|type of)|are (?:usually|generally)|everyone knows|as you know|in general)\b""".r

  /** Total classification: every recall-side discourse function has an explicit class; only
    * `EpisodicAssertion` and `Summary` are further refined by lexical cues, and only those two can
    * ever end up in an episode.
    */
  private[interview] def classify(unit: RecallUnit): UnitClass =
    val lower = Text.lower(unit.text)
    def byCue(default: UnitClass): UnitClass =
      if Metacognitive.findFirstIn(lower).nonEmpty then UnitClass.Metacognitive
      else if Repair.findFirstIn(lower).nonEmpty then UnitClass.Repair
      else if Habitual.findFirstIn(lower).nonEmpty then UnitClass.Habitual
      else if GeneralFact.findFirstIn(lower).nonEmpty then UnitClass.GeneralFact
      else if Evaluative.findFirstIn(lower).nonEmpty then UnitClass.Evaluative
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

  private def lexicalOverlap(a: RecallUnit, b: RecallUnit): Double =
    val x = a.proposition.lemmas
    val y = b.proposition.lemmas
    if x.isEmpty || y.isEmpty then 0.0 else x.intersect(y).size.toDouble / x.union(y).size

  private def continuity(
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

  /** Assign episodic units to clusters: the running cluster continues unless an explicit
    * other-episode marker opens a new one, and a return marker (or continuity with the seed
    * cluster) returns to it.
    */
  private def clusters(
      units: Vector[RecallUnit],
      classes: Map[RecallUnitId, UnitClass],
      semantic: Option[SemanticDistance],
      config: InductionConfig
  ): Map[RecallUnitId, Int] =
    // The target cluster (0) is the default; an explicit other-episode marker opens a digression
    // that persists only while successive units stay continuous with it and carry no return cue.
    var current = 0
    var next = 1
    var digression: Vector[RecallUnit] = Vector.empty
    val out = Map.newBuilder[RecallUnitId, Int]
    units.foreach { u =>
      classes.get(u.id) match
        case Some(UnitClass.OtherEpisode) =>
          current = next
          next += 1
          digression = Vector(u)
          out += u.id -> current
        case Some(UnitClass.Episodic) | Some(UnitClass.Summary) =>
          val lower = Text.lower(u.text)
          if current != 0 then
            val stays = ReturnMarker.findFirstIn(lower).isEmpty &&
              digression.exists(d => continuity(d, u, semantic) >= config.continuityThreshold)
            if stays then digression = digression :+ u else current = 0
          out += u.id -> current
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

  def induce(
      graph: RecallGraph,
      details: Vector[Detail],
      cue: Cue,
      config: InductionConfig = InductionConfig.default,
      semantic: Option[SemanticDistance] = None
  ): InductionResult =
    val units = graph.ordered
    val classes = units.map(u => u.id -> classify(u)).toMap
    val cl = clusters(units, classes, semantic, config)
    val byCluster: Map[Int, Vector[RecallUnit]] =
      units.filter(u => cl.contains(u.id)).groupBy(u => cl(u.id))

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

    def episodicAddress(k: Int): Distribution[MemoryAddress] =
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

    val specificity: Map[DetailId, ScoreEstimate] = details.flatMap { d =>
      graph.byId.get(d.sourceUnit).map { u =>
        val anchored = u.proposition.locations.nonEmpty || u.proposition.times.nonEmpty
        val s: ScoreEstimate = classes.getOrElse(u.id, UnitClass.Uninterpretable) match
          case UnitClass.Episodic | UnitClass.OtherEpisode =>
            Estimate.observed(if anchored then 0.85 else 0.65)
          // A summary denotes the episode at reduced specificity (design record §61, §65).
          case UnitClass.Summary     => Estimate.observed(if anchored then 0.45 else 0.3)
          case UnitClass.Habitual    => Estimate.observed(0.15)
          case UnitClass.GeneralFact => Estimate.observed(0.05)
          case _                     => Estimate.observed(0.0)
        d.id -> s
      }
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
      graph: RecallGraph,
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
          result.specificity.getOrElse(d.id, Estimate.observed(0.0)),
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
