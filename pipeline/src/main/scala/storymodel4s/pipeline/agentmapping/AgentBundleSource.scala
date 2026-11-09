package storymodel4s.pipeline.agentmapping

import storymodel4s.corpus.intake.SherlockAnnotations

/** The source dictionary a bundle carries, so a cited row or a chosen scene can be resolved to its
  * place in the film without the annotation table or a Scala process.
  */
object AgentBundleSource:
  def dictionary(
      atlas: SherlockAnnotations.Atlas,
      film: AgentMappingRecord.Film
  ): (Vector[AgentBundle.SourceScene], Vector[AgentBundle.SourceRow]) =
    val parts = Vector(atlas.manifest.partA, atlas.manifest.partB)
    val leaves = film.view.nodes.filter(_.level == 0).sortBy(_.discoursePosition)
    val targetByRow = atlas.rows.map(_.row).sorted.zip(leaves.map(_.ref.key)).toMap
    val scenes = atlas.scenes.flatMap { s =>
      film.scenes
        .get(s.ordinal)
        .map(ref => AgentBundle.SourceScene(s.ordinal, ref.key, s.label, s.firstRow, s.lastRow))
    }
    val rows = atlas.rows.flatMap { r =>
      for
        locus <- atlas.mediaByRow.get(r.row)
        part <- parts.find(_.partId == locus.part)
        target <- targetByRow.get(r.row)
      yield AgentBundle.SourceRow(
        r.row,
        target,
        atlas.sceneOf(r.row).map(_.ordinal),
        part.partId,
        FilmOutline.clock(locus.startTick, part.ticksPerSecond),
        FilmOutline.clock(locus.endTick, part.ticksPerSecond),
        r.description
      )
    }
    scenes -> rows
