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

  private def sceneOf(built: TimedSourceView.Built, ref: SourceNodeRef): Option[Int] =
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

  /** The full decision per unit. `scene` is non-decreasing across the returned vector. */
  def decide(
      built: TimedSourceView.Built,
      rows: Vector[AlignmentRow]
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
      var i = 1
      while i < n do
        // Running maximum over every earlier scene index, which is what makes the sweep linear in
        // the number of scenes rather than quadratic.
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
        if inScene.isEmpty then Decision(row.mapSource, scene, constrained = false)
        else
          Decision(
            Some(inScene.sortBy { case (ref, m) => (-m, ref.key) }.head._1),
            scene,
            constrained = true
          )
      }
