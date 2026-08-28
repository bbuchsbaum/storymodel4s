package storymodel4s.interview

import cats.data.NonEmptyVector

import storymodel4s.core.*
import storymodel4s.features.{Estimate, ScoreEstimate}
import storymodel4s.recall.*

/** Injected semantic distance between two recall units (embeddings); `None` = lexical only. */
trait SemanticDistance:
  def distance(a: RecallUnit, b: RecallUnit): Double

/** Tunable, uncalibrated parameters of v0.1 induction. None of these are probabilities. */
final case class InductionConfig(
    targetMass: Double = 0.85,
    otherMass: Double = 0.8,
    habitualMass: Double = 0.6,
    discourseMass: Double = 0.9,
    repetitionThreshold: Double = 0.6,
    continuityThreshold: Double = 0.15,
    alternativeMargin: Double = 0.25,
    softwareVersion: String = "storymodel4s-interview-0.1"
)

/** Result of joint target-episode induction and detail routing (design record §64). */
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
    case Episodic, OtherEpisode, Habitual, GeneralFact, Metacognitive, Evaluative, Repair,
      TaskCommentary

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

  private[interview] def classify(unit: RecallUnit): UnitClass =
    val lower = unit.text.toLowerCase
    unit.function match
      case DiscourseFunction.TaskCommentary   => UnitClass.TaskCommentary
      case DiscourseFunction.SourceMonitoring => UnitClass.Metacognitive
      case DiscourseFunction.Evaluation       => UnitClass.Evaluative
      case _                                  =>
        if Metacognitive.findFirstIn(lower).nonEmpty then UnitClass.Metacognitive
        else if Repair.findFirstIn(lower).nonEmpty then UnitClass.Repair
        else if Habitual.findFirstIn(lower).nonEmpty then UnitClass.Habitual
        else if GeneralFact.findFirstIn(lower).nonEmpty then UnitClass.GeneralFact
        else if Evaluative.findFirstIn(lower).nonEmpty then UnitClass.Evaluative
        else if OtherEpisodeMarker.findFirstIn(lower).nonEmpty then UnitClass.OtherEpisode
        else UnitClass.Episodic

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
      classes(u.id) match
        case UnitClass.OtherEpisode =>
          current = next
          next += 1
          digression = Vector(u)
          out += u.id -> current
        case UnitClass.Episodic =>
          val lower = u.text.toLowerCase
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
      details: Vector[Detail],
      status: EpistemicStatus
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
    val locations = units.flatMap(_.proposition.locations).toSet
    val anchors = ds.collect {
      case Detail(_, DetailAtom.TemporalFact(TemporalClaim.Anchor(_, e)), _, _, _, _) => e
    }
    val relations = ds.collect { case Detail(_, DetailAtom.RelationalFact(r), _, _, _, _) => r }
    val support = SpanSet.of(units.flatMap(_.span.refs.toVector))
    EpisodeModel
      .of(id, scope, sits, entities, locations, anchors, relations, status, support)
      .fold(e => throw new IllegalStateException(e.message), identity)

  private def cueScore(cue: Cue, units: Vector[RecallUnit]): Double =
    val words = (cue.text + " " + cue.nominatedEvent.getOrElse("")).toLowerCase
      .split("[^a-z]+")
      .filter(w => w.length > 3)
      .toSet
    if words.isEmpty then 0.0
    else
      val lemmas = units.flatMap(_.proposition.lemmas).toSet
      words.count(w => lemmas.exists(l => l.startsWith(w.take(5)))).toDouble / words.size

  def induce(
      graph: RecallGraph,
      details: Vector[Detail],
      cue: Cue,
      config: InductionConfig = InductionConfig(),
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
        byCluster(k),
        details,
        EpistemicStatus.Hypothesized
      )
    }
    val best = scored.headOption.map(_._2).getOrElse(0.0)
    val alternatives = scored
      .drop(1)
      .filter { case (_, s) => best - s < config.alternativeMargin }
      .map { case (k, s) =>
        (
          episodeOf(
            EpisodeId.unsafe(s"interview:episode:alt$k"),
            EpisodeScope.TargetSpecific,
            byCluster(k),
            details,
            EpistemicStatus.Hypothesized
          ),
          s
        )
      }
    val altClusters = alternatives.map(_._1.id).zip(scored.drop(1).map(_._1)).map(_.swap).toMap
    val others = byCluster.keys.toVector.sorted
      .filter(k => !targetCluster.contains(k))
      .map { k =>
        k -> episodeOf(
          EpisodeId.unsafe(s"interview:episode:other$k"),
          EpisodeScope.OtherSpecific,
          byCluster(k),
          details,
          EpistemicStatus.Hypothesized
        )
      }
      .toMap
    val repeatedId = EpisodeId.unsafe("interview:episode:repeated")
    val repeated = {
      val hs = units.filter(u => classes(u.id) == UnitClass.Habitual)
      if hs.isEmpty then None
      else
        Some(
          episodeOf(
            repeatedId,
            EpisodeScope.RepeatedOrCategoric,
            hs,
            details,
            EpistemicStatus.Hypothesized
          )
        )
    }

    // Repetition: a later detail whose unit paraphrases an earlier unit of the same class.
    val repetitions: Map[DetailId, DetailId] =
      val byUnit = details.groupBy(_.sourceUnit)
      val pairs = for
        (u, i) <- units.zipWithIndex
        if classes(u.id) == UnitClass.Episodic || classes(u.id) == UnitClass.OtherEpisode
        earlier <- units
          .take(i)
          .reverseIterator
          .find { e =>
            classes(e.id) == classes(u.id) &&
            lexicalOverlap(e, u) >= config.repetitionThreshold &&
            e.proposition.predicate == u.proposition.predicate && e.proposition.predicate.nonEmpty
          }
          .toVector
        d <- byUnit.getOrElse(u.id, Vector.empty)
        first <- byUnit.getOrElse(earlier.id, Vector.empty).headOption.toVector
      yield d.id -> first.id
      pairs.toMap

    val unitOf = details.map(d => d.id -> graph.byId(d.sourceUnit)).toMap
    val addresses: Map[DetailId, Distribution[MemoryAddress]] = details.map { d =>
      val u = unitOf(d.id)
      val dist: Distribution[MemoryAddress] = repetitions.get(d.id) match
        case Some(of) =>
          Distribution.unsafe(
            MemoryAddress.Discourse(
              InterviewDiscourseFunction.Repetition(of)
            ) -> config.discourseMass,
            MemoryAddress.Unresolved -> (1.0 - config.discourseMass)
          )
        case None =>
          classes(u.id) match
            case UnitClass.Episodic | UnitClass.OtherEpisode =>
              val k = cl(u.id)
              if targetCluster.contains(k) then
                val tid = targetEpisode.get.id
                val others2 = alternatives.headOption
                  .map(a => MemoryAddress.Episode(a._1.id, EpisodeScope.TargetSpecific) -> 0.05)
                Distribution.unsafe(
                  (Vector(
                    MemoryAddress.Episode(tid, EpisodeScope.TargetSpecific) -> config.targetMass,
                    MemoryAddress.Unresolved -> (1.0 - config.targetMass - 0.05),
                    MemoryAddress.Episode(tid, EpisodeScope.Extended) -> 0.05
                  ) ++ others2.toVector)*
                )
              else
                val oid = altClusters
                  .get(k)
                  .orElse(others.get(k).map(_.id))
                  .getOrElse(
                    others.values.head.id
                  )
                val tid = targetEpisode.map(_.id).getOrElse(oid)
                Distribution.unsafe(
                  MemoryAddress.Episode(oid, EpisodeScope.OtherSpecific) -> config.otherMass,
                  MemoryAddress.Episode(tid, EpisodeScope.TargetSpecific) -> 0.1,
                  MemoryAddress.Unresolved -> (1.0 - config.otherMass - 0.1)
                )
            case UnitClass.Habitual =>
              Distribution.unsafe(
                MemoryAddress.PersonalKnowledge(
                  PersonalKnowledgeKind.HabitOrRoutine
                ) -> config.habitualMass,
                MemoryAddress.Episode(
                  repeatedId,
                  EpisodeScope.RepeatedOrCategoric
                ) -> (1.0 - config.habitualMass - 0.05),
                MemoryAddress.Unresolved -> 0.05
              )
            case UnitClass.GeneralFact =>
              Distribution.unsafe(
                MemoryAddress.GeneralKnowledge -> 0.7,
                MemoryAddress.PersonalKnowledge(PersonalKnowledgeKind.AutobiographicalFact) -> 0.2,
                MemoryAddress.Unresolved -> 0.1
              )
            case UnitClass.Metacognitive =>
              Distribution.unsafe(
                MemoryAddress.Discourse(
                  InterviewDiscourseFunction.Metacognitive
                ) -> config.discourseMass,
                MemoryAddress.Unresolved -> (1.0 - config.discourseMass)
              )
            case UnitClass.Evaluative =>
              Distribution.unsafe(
                MemoryAddress.Discourse(
                  InterviewDiscourseFunction.Evaluation
                ) -> config.discourseMass,
                MemoryAddress.Unresolved -> (1.0 - config.discourseMass)
              )
            case UnitClass.Repair =>
              Distribution.unsafe(
                MemoryAddress.Discourse(
                  InterviewDiscourseFunction.ConversationalRepair
                ) -> config.discourseMass,
                MemoryAddress.Unresolved -> (1.0 - config.discourseMass)
              )
            case UnitClass.TaskCommentary =>
              Distribution.unsafe(
                MemoryAddress.Discourse(
                  InterviewDiscourseFunction.TaskCommentary
                ) -> config.discourseMass,
                MemoryAddress.Unresolved -> (1.0 - config.discourseMass)
              )
      d.id -> dist
    }.toMap

    val specificity: Map[DetailId, ScoreEstimate] = details.map { d =>
      val u = unitOf(d.id)
      val s: ScoreEstimate = classes(u.id) match
        case UnitClass.Episodic | UnitClass.OtherEpisode =>
          val anchored = u.proposition.locations.nonEmpty || u.proposition.times.nonEmpty
          Estimate.observed(if anchored then 0.85 else 0.65)
        case UnitClass.Habitual    => Estimate.observed(0.15)
        case UnitClass.GeneralFact => Estimate.observed(0.05)
        case _                     => Estimate.observed(0.0)
      d.id -> s
    }.toMap

    InductionResult(
      targetEpisode,
      alternatives,
      others.values.toVector.sortBy(_.id.value) ++ repeated.toVector,
      addresses,
      specificity,
      repetitions
    )

  /** Assemble assessments from an induction result with deterministic claim metadata. */
  def assess(
      source: InterviewSource,
      graph: RecallGraph,
      details: Vector[Detail],
      result: InductionResult,
      config: InductionConfig = InductionConfig()
  ): Vector[DetailAssessment] =
    val fp = Fingerprint.unsafe(s"interview:target-induction:${config.softwareVersion}")
    val stage = StageId.unsafe("interview-induction")
    val prov = Provenance.deterministic(config.softwareVersion, Checksum.ofText(config.toString))
    details.map { d =>
      val u = graph.byId(d.sourceUnit)
      val offset = d.support.minSpan.start
      val phase = source.phaseAt(offset)
      val probe = source.probeBefore(offset).map(_.id)
      val lower = u.text.toLowerCase
      val firstPerson = """\bi (?:remember|can still|can see|can hear|saw|heard|felt)\b""".r
        .findFirstIn(lower)
        .nonEmpty
      val monitoring =
        if """\b(?:my (?:mother|mom|father|dad|sister|brother) (?:told|says|said)|i was told|they told me)\b""".r
            .findFirstIn(lower)
            .nonEmpty
        then Some(SourceMonitoring.Hearsay)
        else if """\b(?:photo|picture|video)\b""".r.findFirstIn(lower).nonEmpty then
          Some(SourceMonitoring.Photo)
        else if """\bi remember\b""".r.findFirstIn(lower).nonEmpty then
          Some(SourceMonitoring.DirectMemory)
        else None
      DetailAssessment(
        d,
        result.addresses(d.id),
        DetailAssessment.defaultFacets(d.atom),
        result.specificity(d.id),
        ExperientialEvidence(
          source.ratings.flatMap(_.reliving),
          firstPerson,
          monitoring.toVector
        ),
        EpistemicStatus.Hypothesized,
        PromptContext(phase, probe),
        monitoring,
        ClaimMeta(
          ClaimId.unsafe(s"claim:${d.id.value}"),
          EpistemicStatus.Hypothesized,
          Credence.unsafeRaw(result.addresses(d.id).toVector.head._2),
          NonEmptyVector.one(
            Evidence(EvidenceId.unsafe(s"ev:${d.id.value}"), Some(d.support), Set.empty, fp, stage)
          ),
          prov
        )
      )
    }
