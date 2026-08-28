package storymodel4s.interview

import storymodel4s.core.OpaqueId

/** Identifier of one countable detail atom. */
object DetailId extends OpaqueId("DetailId")
type DetailId = DetailId.T

/** Identifier of an episode (target, other specific, extended, or repeated) as induced from the
  * transcript. Never an assertion that the episode occurred: see [[EpisodeModel]].
  */
object EpisodeId extends OpaqueId("EpisodeId")
type EpisodeId = EpisodeId.T
