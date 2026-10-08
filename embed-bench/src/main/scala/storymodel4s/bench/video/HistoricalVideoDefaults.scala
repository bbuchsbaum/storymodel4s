package storymodel4s.bench.video

/** Shared runner defaults keep historical compatibility tests tied to the recipe users run. */
private[video] object HistoricalVideoDefaults:
  val blendAlpha: Double = 0.8
  val lexicalFields: LexicalBlend.LexicalFields = LexicalBlend.LexicalFields.WithLemmas
  val candidatesPerLevel: Int = 8
  val lexicalOverlap: Boolean = false
  val orderingScale: Double = 1.5
