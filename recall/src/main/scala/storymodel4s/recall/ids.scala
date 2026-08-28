package storymodel4s.recall

import storymodel4s.core.OpaqueId

/** Identifier of one recall idea unit. */
object RecallUnitId extends OpaqueId("RecallUnitId")
type RecallUnitId = RecallUnitId.T

/** Identifier of a recall-side entity (a referent as the rememberer names it, before any mapping to
  * a source entity).
  */
object RecallEntityId extends OpaqueId("RecallEntityId")
type RecallEntityId = RecallEntityId.T
