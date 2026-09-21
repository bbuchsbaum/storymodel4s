package storymodel4s.bench.video

import storymodel4s.align.{HsmmConfig, TransitionKind, TransitionModel}

/** A sensitivity control for the sequential model, and the knob it lets us turn.
  *
  * Kendall tau may not judge a change to the transition model on its own, because tau *is*
  * sequentiality: strengthening a forward prior raises it mechanically, and a model that simply
  * marched through the film ignoring the recall entirely would score well. That is why the study
  * deferred transition work rather than tuning into the metric.
  *
  * Shuffling is a sensitivity diagnostic, not a bias correction. The prior can interact differently
  * with real and shuffled content; shuffled language can be less interpretable, and resegmentation
  * can change the units being compared. Therefore `tau(real) - tau(shuffled)` does not isolate
  * ordering attributable to the participant. A reference measurement requires fixed packets and an
  * inference policy without behavioral sequence preferences; see docs/refactor/PLAN.md. This
  * control remains useful for inspecting reconstruction sensitivity.
  */
object RecallOrderControl:

  /** Transitions that encode temporal order, and so constitute the sequential prior.
    *
    * Hierarchy, entity-thread, semantic-neighbour and external moves are left alone: they express
    * what a move costs, not which direction time runs, and scaling them would confound a test of
    * the ordering prior with a change to the state space.
    */
  private val orderingKinds: Set[TransitionKind] = Set(
    TransitionKind.DiscourseSuccessor,
    TransitionKind.WorldTimeSuccessor,
    TransitionKind.Backward,
    TransitionKind.LongJump
  )

  /** Permutation of already-segmented recall units, returned as a transcript to re-segment.
    *
    * Units rather than sentences: these transcripts are produced from word-level CSVs and carry no
    * punctuation at all, so a sentence splitter finds exactly one sentence and permutes nothing. An
    * earlier version of this control did precisely that and reported a null so clean — tau
    * identical to four decimals in both arms — that the null itself was the evidence it had never
    * run.
    *
    * Units are the right granularity regardless: they are what the aligner anchors and what the
    * report's row order is, so permuting them is exactly the manipulation the sequential prior
    * claims to exploit. The text of each unit is preserved verbatim and only their order changes.
    */
  def shuffleUnits(unitTexts: Vector[String], seed: Long): String =
    if unitTexts.size < 2 then unitTexts.mkString(" ")
    else
      val rng = new java.util.Random(seed)
      val buf = scala.collection.mutable.ArrayBuffer.from(unitTexts)
      var i = buf.size - 1
      while i > 0 do
        val j = rng.nextInt(i + 1)
        val t = buf(i)
        buf(i) = buf(j)
        buf(j) = t
        i -= 1
      // Joined as sentences, not with spaces. The segmenter finds sentences first and splits
      // clauses inside them, so joining the permuted units with a space lets neighbouring units
      // merge: a first attempt did that and collapsed 172 units into 91, which would have
      // compared arms with different unit counts and different amounts of text per unit.
      buf.map(_.trim.stripSuffix(".")).filter(_.nonEmpty).mkString(". ") + "."

  /** Scale the ordering prior. `1.0` is the shipped model; `0.0` removes the direction of time
    * while leaving every other transition, and therefore the state space, untouched. Zero is still
    * a reconstruction ablation, not an order-free reference measurement: `Stay`, the external
    * logits and any post-inference scene decode remain (docs/refactor/PLAN.md §1, ADR 0019).
    */
  def scaledConfig(scale: Double): HsmmConfig =
    val theta = TransitionModel.default.theta.map {
      case (k, v) if orderingKinds.contains(k) => k -> v * scale
      case kv                                  => kv
    }
    HsmmConfig
      .of(transitions = TransitionModel(theta))
      .fold(e => throw new IllegalStateException(e.message), identity)

  private val hierarchyKinds: Set[TransitionKind] =
    Set(TransitionKind.HierarchyUp, TransitionKind.HierarchyDown)
  // `Stay` joins the ladder's order rung and not the scale control's `orderingKinds`: dwelling on
  // a node is a sequential-persistence prior, so a rung without it has no notion of sequence at
  // all, whereas the scale control scales only the direction of time and leaves occupancy alone.
  private val orderKinds: Set[TransitionKind] = orderingKinds + TransitionKind.Stay
  private val causalKinds: Set[TransitionKind] =
    Set(TransitionKind.CausalNeighbor, TransitionKind.CauseToEffect, TransitionKind.EffectToCause)
  private val similarityKinds: Set[TransitionKind] =
    Set(TransitionKind.SemanticNeighbor, TransitionKind.SameEntityThread)
  private val externalKinds: Set[TransitionKind] =
    Set(TransitionKind.ExternalIn, TransitionKind.ExternalStay)

  /** The ablation ladder `docs/design/gates.md` requires — `content → +hierarchy → +order →
    * +causality → +external → +sensory` — as cumulative sets of *admitted* feature kinds; every
    * other feature kind is withheld, meaning its weight is zero in the model the rung runs under,
    * which for a feature is inert.
    *
    * Two of the gate's rungs are not on this ladder, and are named gaps rather than approximated.
    * The external logits are not features: a zero logit is the prior p = 0.5, stronger than the
    * shipped σ(−1.5) ≈ 0.18, so "withholding" them would give the lower rungs *more* external mass
    * than the shipped model. Every rung therefore holds the external prior at its shipped value,
    * and `+external` (a change to the state space) is not a rung here. The aligner has no sensory
    * feature, so `+sensory` is not a rung either; the last rung admits the source-similarity kinds.
    * `Content` is the local cost model plus the shipped external prior: no feature kind carries
    * weight.
    */
  enum Rung(val label: String, val admitted: Set[TransitionKind]):
    case Content extends Rung("content", Set.empty)
    case Hierarchy extends Rung("+hierarchy", hierarchyKinds)
    case Order extends Rung("+order", hierarchyKinds ++ orderKinds)
    case Causality extends Rung("+causality", hierarchyKinds ++ orderKinds ++ causalKinds)
    case Similarity
        extends Rung("+similarity", hierarchyKinds ++ orderKinds ++ causalKinds ++ similarityKinds)

    def withheld: Set[TransitionKind] = TransitionKind.features.toSet -- admitted

  /** What a run withholds: a rung of the ladder, extra feature kinds named by hand, or neither
    * (`full`, the shipped model). A declaration for provenance, parsed from the environment by
    * [[Ladder.fromEnv]] and refused, not defaulted, when a name is unknown or names an external
    * logit.
    */
  final case class Ladder(rung: Option[Rung], extraWithheld: Set[TransitionKind]):
    def withheld: Set[TransitionKind] = rung.fold(Set.empty[TransitionKind])(_.withheld) ++
      extraWithheld

    def label: String =
      val base = rung.fold("full")(_.label)
      if extraWithheld.isEmpty then base
      else base + "-" + extraWithheld.toVector.map(_.toString).sorted.mkString(",")

  object Ladder:
    val full: Ladder = Ladder(None, Set.empty)

    private val rungNames: String = Rung.values.map(_.label.stripPrefix("+")).mkString(",")

    /** `rungRaw` names a rung without its `+` (`content`, `hierarchy`, …); `withheldRaw` is a
      * comma-separated list of feature `TransitionKind` names.
      */
    def parse(rungRaw: Option[String], withheldRaw: Option[String]): Either[String, Ladder] =
      val rung = rungRaw.map(_.trim).filter(_.nonEmpty) match
        case None      => Right(None)
        case Some(raw) =>
          Rung.values.find(_.label.stripPrefix("+") == raw.stripPrefix("+")) match
            case Some(r) => Right(Some(r))
            case None    => Left(s"unknown ladder rung '$raw'; one of $rungNames")
      val extra = withheldRaw.map(_.trim).filter(_.nonEmpty) match
        case None      => Right(Set.empty[TransitionKind])
        case Some(raw) =>
          val names = raw.split(",").map(_.trim).filter(_.nonEmpty).toVector
          val parsed = names.map(n => n -> TransitionKind.values.find(_.toString == n))
          parsed.collect { case (n, None) => n } match
            case Vector() =>
              val kinds = parsed.flatMap(_._2).toSet
              val external = kinds.intersect(externalKinds)
              if external.isEmpty then Right(kinds)
              else
                Left(
                  s"${external.toVector.map(_.toString).sorted.mkString(",")} are external " +
                    "logits, not features: a zero logit is the prior p = 0.5, so they cannot be withheld"
                )
            case unknown => Left(s"unknown transition kinds withheld: ${unknown.mkString(",")}")
      for r <- rung; e <- extra yield Ladder(r, e)

    def fromEnv: Either[String, Ladder] =
      parse(sys.env.get("STORYMODEL4S_RUNG"), sys.env.get("STORYMODEL4S_WITHHOLD_KINDS"))

  /** The model a rung runs under: the scaled shipped model with every withheld feature kind at
    * zero; the external logits are never touched. The configuration's own
    * [[storymodel4s.align.LayerUse]] then states what was read.
    */
  def ladderConfig(scale: Double, withheld: Set[TransitionKind]): HsmmConfig =
    val theta = scaledConfig(scale).transitions.theta ++
      withheld.diff(externalKinds).map(_ -> 0.0)
    HsmmConfig
      .of(transitions = TransitionModel(theta))
      .fold(e => throw new IllegalStateException(e.message), identity)

  /** One run's ladder position, prior scale and the configuration they yield, bound together so the
    * label rendered into provenance cannot disagree with the model that produced the numbers. Not a
    * case class: the configuration is derived from the other two and never supplied.
    */
  final class LadderRun private (val ladder: Ladder, val scale: Double, val config: HsmmConfig)

  object LadderRun:
    def of(ladder: Ladder, scale: Double): Either[String, LadderRun] =
      val external = ladder.withheld.intersect(externalKinds)
      if external.nonEmpty then
        Left(
          s"${external.toVector.map(_.toString).sorted.mkString(",")} are external logits, " +
            "not features, and cannot be withheld"
        )
      else if scale < 0.0 || scale.isNaN || scale.isInfinite then
        Left(s"prior scale must be finite and nonnegative, got $scale")
      else Right(new LadderRun(ladder, scale, ladderConfig(scale, ladder.withheld)))
