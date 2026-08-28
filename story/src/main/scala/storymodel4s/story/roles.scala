package storymodel4s.story

// INTEGRATION: replaced by storymodel4s.proposition.ParticipantRole

import cats.{Hash, Show}

/** Compact, closed set of normalized participant roles.
  *
  * Why closed: story-level relations must remain a small stable algebra. Imported ontologies
  * (PropBank numbered arguments, VerbNet thematic roles) map into these or into `Custom`, never
  * into free strings.
  */
enum ParticipantRole:
  case Agent, Patient, Theme, Experiencer, Stimulus, Instrument, Beneficiary, Source, Destination,
    Location, Time, Manner, Cause, Result
  case Custom(namespace: String, label: String)

  def render: String = this match
    case Custom(ns, l) => s"$ns:$l"
    case other         => other.toString

object ParticipantRole:
  given Show[ParticipantRole] = Show.show(_.render)
  given Hash[ParticipantRole] = Hash.fromUniversalHashCode
