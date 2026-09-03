package storymodel4s.bench.video

import storymodel4s.align.{AlignState, AlignmentRow, ExternalState, SourceNodeRef}

/** The per-unit posterior written beside the report, so a viewer can draw the mapping's uncertainty
  * from the aligner's own numbers rather than from two columns of a table.
  *
  * Why a sidecar and not more columns: the report's `mapAnchorMass`, `runnerUpAnchor` and
  * `localizability` describe the emission argmax, because the scene-monotone decode never touches
  * the posterior; when the decode overrides the argmax the report's anchor and its mass belong to
  * different nodes. Fixing that inside the TSV would change the identity of every landed run. The
  * sidecar leaves the report byte-identical and carries, per unit, every admitted state with its
  * mass, the argmax, the decoded anchor with *its own* mass, and what the decode concluded. It
  * holds no recall text, so it is content-free.
  */
object PosteriorSidecar:

  /** JSON for the whole recall. Units in recall order; anchors by descending mass then key. */
  def render(
      built: TimedSourceView.Built,
      ordinals: Vector[Int],
      rows: Vector[AlignmentRow],
      decisions: Vector[MonotoneScene.Decision],
      chosen: Vector[Option[SourceNodeRef]]
  ): String =
    require(ordinals.size == rows.size && chosen.size == rows.size, "one row per unit")
    val sb = new StringBuilder
    sb.append("{\"schema\":\"storymodel4s.bench.recall-to-video.posterior\",\"schemaVersion\":1,")
    sb.append("\"decode\":")
    sb.append(if decisions.isEmpty then "\"argmax\"" else "\"scene-monotone\"")
    sb.append(",\"units\":[")
    var first = true
    ordinals.indices.foreach { i =>
      if !first then sb.append(',')
      first = false
      unit(sb, built, ordinals(i), rows(i), decisions.lift(i), chosen(i))
    }
    sb.append("]}")
    sb.toString

  private def unit(
      sb: StringBuilder,
      built: TimedSourceView.Built,
      ordinal: Int,
      row: AlignmentRow,
      decision: Option[MonotoneScene.Decision],
      chosen: Option[SourceNodeRef]
  ): Unit =
    val anchors = row.anchorMass.toVector.sortBy { case (ref, m) => (-m, ref.key) }
    sb.append("{\"unit\":").append(ordinal)
    sb.append(",\"sourceMass\":").append(num(row.sourceMass))
    sb.append(",\"externalMass\":").append(num(row.externalMass))
    sb.append(",\"localizability\":")
    sb.append(row.localizability(built.view.nodes.size).fold("null")(num))
    sb.append(",\"argmax\":")
    anchors.headOption match
      case Some((ref, m)) =>
        sb.append("{\"ref\":").append(str(ref.key)).append(",\"mass\":").append(num(m)).append('}')
      case None => sb.append("null")
    sb.append(",\"decoded\":")
    chosen match
      case Some(ref) =>
        val scene = decision.map(_.scene).orElse(MonotoneScene.sceneOf(built, ref))
        sb.append("{\"ref\":").append(str(ref.key))
        sb.append(",\"mass\":").append(num(row.anchorMass.getOrElse(ref, 0.0)))
        sb.append(",\"scene\":").append(scene.fold("null")(_.toString))
        sb.append(",\"constrained\":").append(decision.exists(_.constrained))
        sb.append('}')
      case None => sb.append("null")
    sb.append(",\"anchors\":[")
    anchors.zipWithIndex.foreach { case ((ref, m), j) =>
      if j > 0 then sb.append(',')
      val faithful = row(AlignState.Source(ref))
      sb.append("{\"ref\":").append(str(ref.key))
      sb.append(",\"level\":").append(built.view.node(ref).fold("null")(_.level.toString))
      sb.append(",\"scene\":").append(MonotoneScene.sceneOf(built, ref).fold("null")(_.toString))
      sb.append(",\"mass\":").append(num(m))
      sb.append(",\"faithful\":").append(num(faithful))
      sb.append(",\"distorted\":").append(num(m - faithful))
      sb.append('}')
    }
    sb.append("],\"external\":[")
    val externals = ExternalState.values.toVector
      .map(s => s -> row.externalMass(s))
      .filter(_._2 > 0.0)
    externals.zipWithIndex.foreach { case ((s, m), j) =>
      if j > 0 then sb.append(',')
      sb.append("{\"state\":")
        .append(str(s.toString))
        .append(",\"mass\":")
        .append(num(m))
        .append('}')
    }
    sb.append("]}")

  private def num(d: Double): String =
    if d.isNaN || d.isInfinite then "null" else f"$d%.6f"

  private def str(s: String): String =
    val out = new StringBuilder("\"")
    s.foreach {
      case '"'          => out.append("\\\"")
      case '\\'         => out.append("\\\\")
      case c if c < ' ' => out.append(f"\\u${c.toInt}%04x")
      case c            => out.append(c)
    }
    out.append('"').toString
