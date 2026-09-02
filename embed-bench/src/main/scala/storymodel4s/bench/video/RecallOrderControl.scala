package storymodel4s.bench.video

import storymodel4s.align.{HsmmConfig, TransitionKind, TransitionModel}

/** The control that makes the sequential model judgeable, and the knob it lets us turn.
  *
  * Kendall tau may not judge a change to the transition model on its own, because tau *is*
  * sequentiality: strengthening a forward prior raises it mechanically, and a model that simply
  * marched through the film ignoring the recall entirely would score well. That is why the study
  * deferred transition work rather than tuning into the metric.
  *
  * Shuffling removes the circularity. Permute the recall's sentences and run the pipeline on the
  * permuted transcript. A mapper driven by content loses its ordering, because the anchors follow
  * text whose order is now scrambled, and its tau collapses toward zero. A mapper driven by its own
  * forward prior keeps emitting monotone anchors whatever it is shown, so its tau survives. The
  * difference `tau(real) - tau(shuffled)` is therefore ordering attributable to the recall rather
  * than to the prior, and a degenerate prior inflates both terms equally and cannot win on it.
  *
  * This also audits the number already claimed: whatever share of the pipeline's tau survives
  * shuffling was never evidence of alignment in the first place.
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
    * earlier version of this control did precisely that and reported a null so clean — tau identical
    * to four decimals in both arms — that the null itself was the evidence it had never run.
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
    * while leaving every other transition, and therefore the state space, untouched.
    */
  def scaledConfig(scale: Double): HsmmConfig =
    val theta = TransitionModel.default.theta.map {
      case (k, v) if orderingKinds.contains(k) => k -> v * scale
      case kv                                  => kv
    }
    HsmmConfig
      .of(transitions = TransitionModel(theta))
      .fold(e => throw new IllegalStateException(e.message), identity)
