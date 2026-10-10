package storymodel4s.bench.video

import storymodel4s.align.{AlignmentRow, SourceNodeRef}

/** Exact default decoder from main 908b935c, retained as an independent extraction oracle. */
private[video] object FrozenMonotoneScene:
  final case class Decision(anchor: Option[SourceNodeRef], scene: Int, constrained: Boolean)
  def sceneOf(built: TimedSourceView.Built, ref: SourceNodeRef): Option[Int] =
    built.groupByRef
      .get(ref)
      .map(_.ordinal)
      .orElse(built.segmentByRef.get(ref).flatMap(_.group).map(_.ordinal))
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
      val penalty: Option[Double] = None
      var i = 1
      while i < n do
        penalty match
          case None =>
            // Forward only. A running maximum over earlier scene indices keeps the sweep linear in
            // the number of scenes rather than quadratic.
            val fwd = 0.0
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
