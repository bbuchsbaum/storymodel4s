package storymodel4s.bench.video

import storymodel4s.align.{AlignmentRow, SourceNodeRef}

/** Decodes the whole recall at once under the constraint that its scenes never go backwards.
  *
  * Why this is allowed to exist, when the study spent a long time refusing to tune sequential
  * behaviour: the constraint is not assumed, it is measured against human labels. In the released
  * scene coding, consecutive recall units are non-decreasing in scene **97.9%** of the time on the
  * development participants. The pipeline's own output was non-decreasing only 69.5% of the time.
  * Free recall of a narrative walks forwards through it; this aligner did not, and the gap was pure
  * loss.
  *
  * What it does. The per-unit posterior already carries mass over many candidate nodes, so each
  * unit is scored for every scene by summing the mass of its candidates that live in that scene. A
  * single dynamic program then picks the non-decreasing sequence of scenes maximising total mass,
  * and each unit's anchor becomes the heaviest candidate inside its assigned scene. Nothing is
  * re-inferred and no evidence is invented: this only changes which of the model's own candidates
  * is believed, using the ordering evidence the per-unit argmax throws away.
  *
  * Cost of the hard constraint: it forbids the 2.1% of genuine backward transitions. That is paid
  * knowingly, and it is why the rule is a constraint on scenes rather than on segments — within a
  * scene the order is free, so only the coarse narrative direction is fixed.
  *
  * **The escape hatch, and why it is not abstention.** The assigned scene sequence is
  * non-decreasing by construction. A unit may still have no candidate inside its assigned scene,
  * which happens when monotonicity forbids the only place that unit put mass. Such a unit keeps the
  * model's own unconstrained anchor, so its emitted scene can step backwards. The alternative —
  * emitting no anchor — would be worse than it looks: an unanchored unit drops out of scoring
  * entirely, so the arm would raise its own accuracy by discarding exactly the units it finds
  * hardest. Keeping a possibly-wrong anchor is honest; abstaining on the hard ones is not.
  * [[Decision.constrained]] reports which units the constraint actually bound.
  */
object MonotoneScene:

  /** On by default. Designed and validated on the development participants, then confirmed once on
    * the untouched five: scene accuracy 37.9% to 57.9% on development and 31.7% to 50.7% on the
    * untouched, +17.67 points pooled with 14 of 15 participants improving. Set the variable to
    * `off` to recover the per-unit argmax.
    */
  def enabled: Boolean =
    sys.env.get("STORYMODEL4S_MONOTONE_SCENE").map(_.trim.toLowerCase) match
      case Some("off") | Some("false") => false
      case _                           => true

  /** The scene a node belongs to: a scene node is its own scene, a segment its parent's. */
  def sceneOf(built: TimedSourceView.Built, ref: SourceNodeRef): Option[Int] =
    built.groupByRef
      .get(ref)
      .map(_.ordinal)
      .orElse(built.segmentByRef.get(ref).flatMap(_.group).map(_.ordinal))

  /** What the decode concluded for one unit: the scene the dynamic program assigned, the anchor
    * emitted, and whether that anchor actually came from the assigned scene.
    */
  final case class Decision(anchor: Option[SourceNodeRef], scene: Int, constrained: Boolean)

  /** One anchor per unit, in the recall's own order. */
  def anchors(
      built: TimedSourceView.Built,
      rows: Vector[AlignmentRow]
  ): Vector[Option[SourceNodeRef]] = decide(built, rows).map(_.anchor)

  /** Cost of stepping backwards a scene, in units of posterior mass. `None` forbids it outright.
    *
    * **This default is fitted to this corpus and should not travel unexamined.** Recall of a single
    * linear television episode runs forwards: the human coding is 97.9% non-decreasing, and on
    * development a permissive penalty of 0.05 cost 8.91 points of scene accuracy and helped none of
    * the ten participants, while 0.15 and 0.40 were indistinguishable from forbidding backward
    * steps outright. Forbidding them is therefore best-or-tied *here*, and knowingly wrong about
    * the remaining 2.1%.
    *
    * A corpus with genuine reminiscence, a non-linear narrative, an interviewer prompting revisits,
    * or recall of several stories at once would not look like this, and the constraint would then
    * be doing real damage rather than 2.1% of it. That is why this stays a parameter with a sweep
    * behind it rather than becoming a hard-coded property of recall: refit it per corpus, on
    * development participants, before trusting it.
    */
  def backwardPenalty: Option[Double] =
    sys.env.get("STORYMODEL4S_BACKWARD_PENALTY").map(_.trim.toLowerCase) match
      case Some("hard") | None => None
      case Some(raw)           => raw.toDoubleOption.filter(_ >= 0.0)

  /** Cost of skipping forward, per scene skipped beyond the first.
    *
    * The decode's prior is currently asymmetric in a way nothing justified: a backward step is
    * forbidden outright while a leap twenty scenes forward is free. With gross displacement now
    * solved the remaining errors are boundary errors, median distance one scene, which is what an
    * unpenalised forward jump produces when it advances early.
    *
    * It must stay gentle. Skipping is legitimate here: participants recall between 24 and 55 of the
    * 50 scenes, so most of them genuinely pass over scenes they do not remember, and a heavy cost
    * would force the path to crawl through material the recall never mentions.
    */
  def forwardPenalty: Double =
    sys.env
      .get("STORYMODEL4S_FORWARD_PENALTY")
      .flatMap(_.trim.toDoubleOption)
      .filter(_ >= 0.0)
      .getOrElse(0.0)

  /** Whether an unbound unit may be filled from its assigned scene. On by default.
    *
    * Validated on development: scene accuracy 57.9% to 65.2%, +5.77 points with 8 of 10
    * participants improving, and emitted scenes go from 84.9% non-decreasing to 100%, since filling
    * is exactly what closes the escape hatch.
    */
  def fillEnabled: Boolean =
    sys.env.get("STORYMODEL4S_MONOTONE_FILL").map(_.trim.toLowerCase) match
      case Some("off") | Some("false") => false
      case _                           => true

  /** The full decision per unit. `scene` is non-decreasing across the returned vector.
    *
    * `fill` is consulted only for a unit the constraint would otherwise leave unbound — one whose
    * candidates carry no mass in its assigned scene, which is the escape hatch and the whole of the
    * remaining gap to the 97.9% the human coding shows. Given the unit's index and its assigned
    * scene it may name a node inside that scene. This applies the emission channel the model
    * already uses to a node the shortlist happened to omit; it is a wider search, not new evidence,
    * and a filler returning `None` restores the previous behaviour exactly.
    */
  def decide(
      built: TimedSourceView.Built,
      rows: Vector[AlignmentRow],
      fill: (Int, Int) => Option[SourceNodeRef] = (_, _) => None
  ): Vector[Decision] =
    val massByScene: Vector[Map[Int, Double]] = rows.map { r =>
      r.anchorMass.toVector
        .flatMap { case (ref, m) => sceneOf(built, ref).filter(_ => m > 0.0).map(_ -> m) }
        .groupMapReduce(_._1)(_._2)(_ + _)
    }
    val scenes = massByScene.flatMap(_.keys).distinct.sorted
    if rows.isEmpty || scenes.isEmpty then
      rows.map(r => Decision(r.mapSource, Int.MinValue, constrained = false))
    else
      val n = rows.size
      val s = scenes.size
      val best = Array.ofDim[Double](n, s)
      val back = Array.ofDim[Int](n, s)
      var j = 0
      while j < s do
        best(0)(j) = massByScene(0).getOrElse(scenes(j), 0.0)
        j += 1
      val penalty = backwardPenalty
      var i = 1
      while i < n do
        penalty match
          case None =>
            // Forward only. A running maximum over earlier scene indices keeps the sweep linear in
            // the number of scenes rather than quadratic.
            val fwd = forwardPenalty
            if fwd <= 0.0 then
              var runBest = Double.NegativeInfinity
              var runArg = 0
              j = 0
              while j < s do
                if best(i - 1)(j) > runBest then
                  runBest = best(i - 1)(j)
                  runArg = j
                best(i)(j) = runBest + massByScene(i).getOrElse(scenes(j), 0.0)
                back(i)(j) = runArg
                j += 1
            else
              j = 0
              while j < s do
                var bestVal = Double.NegativeInfinity
                var bestArg = 0
                var k = 0
                while k <= j do
                  // Skipping is free for the first scene advanced and priced after that, so an
                  // ordinary step forward costs nothing and only leaps are discouraged.
                  val skipped = math.max(0, scenes(j) - scenes(k) - 1)
                  val step = best(i - 1)(k) - fwd * skipped
                  if step > bestVal then
                    bestVal = step
                    bestArg = k
                  k += 1
                best(i)(j) = bestVal + massByScene(i).getOrElse(scenes(j), 0.0)
                back(i)(j) = bestArg
                j += 1
          case Some(lambda) =>
            // Backward steps allowed at a price, so strong evidence can buy one. Quadratic in the
            // number of scenes, which is fifty here.
            j = 0
            while j < s do
              var bestVal = Double.NegativeInfinity
              var bestArg = 0
              var k = 0
              while k < s do
                val step = best(i - 1)(k) - (if k > j then lambda * (k - j) else 0.0)
                if step > bestVal then
                  bestVal = step
                  bestArg = k
                k += 1
              best(i)(j) = bestVal + massByScene(i).getOrElse(scenes(j), 0.0)
              back(i)(j) = bestArg
              j += 1
        i += 1
      var cur = 0
      j = 1
      while j < s do
        if best(n - 1)(j) > best(n - 1)(cur) then cur = j
        j += 1
      val chosen = Array.ofDim[Int](n)
      i = n - 1
      while i >= 0 do
        chosen(i) = cur
        if i > 0 then cur = back(i)(cur)
        i -= 1
      rows.zipWithIndex.map { case (row, idx) =>
        val scene = scenes(chosen(idx))
        val inScene = row.anchorMass.toVector.filter { case (ref, m) =>
          m > 0.0 && sceneOf(built, ref).contains(scene)
        }
        if inScene.isEmpty then
          fill(idx, scene) match
            case Some(ref) => Decision(Some(ref), scene, constrained = true)
            case None      => Decision(row.mapSource, scene, constrained = false)
        else
          Decision(
            Some(inScene.sortBy { case (ref, m) => (-m, ref.key) }.head._1),
            scene,
            constrained = true
          )
      }
