package storymodel4s.story

import cats.{Hash, Show}

import storymodel4s.proposition

/** Story-level participant roles are exactly the shared `proposition.ParticipantRole` vocabulary.
  *
  * Why an alias rather than a second enum: the local proposition chart and the narrative graph must
  * agree on one closed role algebra so that projection from charts to participant edges is a
  * identity on roles, never a lossy mapping. Imported ontologies (PropBank numbered arguments,
  * VerbNet thematic roles) map into these or into `Custom`, never into free strings.
  */
type ParticipantRole = proposition.ParticipantRole
val ParticipantRole: proposition.ParticipantRole.type = proposition.ParticipantRole

extension (role: ParticipantRole)
  def render: String = role match
    case ParticipantRole.Custom(ns, l) => s"$ns:$l"
    case other                         => other.toString

given participantRoleShow: Show[ParticipantRole] = Show.show(_.render)
given participantRoleHash: Hash[ParticipantRole] = Hash.fromUniversalHashCode
