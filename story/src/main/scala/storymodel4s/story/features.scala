package storymodel4s.story

import storymodel4s.features

/** Feature-track types the story model uses are exactly the `storymodel4s.features` types.
  *
  * Why aliases rather than a wildcard import: fixtures and downstream modules refer to these names
  * through `storymodel4s.story.*`; keeping one binding per name (the alias) avoids ambiguity with
  * the originating module while guaranteeing there is a single definition of each type.
  */
type WorldTimeTransition = features.WorldTimeTransition
val WorldTimeTransition: features.WorldTimeTransition.type = features.WorldTimeTransition

type DurationEstimate = features.DurationEstimate
val DurationEstimate: features.DurationEstimate.type = features.DurationEstimate

type TemporalHypothesis = features.TemporalHypothesis
val TemporalHypothesis: features.TemporalHypothesis.type = features.TemporalHypothesis

/** A scalar estimate with missingness; `Missing` is never zero. */
type ScoreEstimate = features.ScoreEstimate
type Estimate[+V] = features.Estimate[V]
val Estimate: features.Estimate.type = features.Estimate

type FeatureSpace[V] = features.FeatureSpace[V]
val FeatureSpace: features.FeatureSpace.type = features.FeatureSpace

type FeatureValueSchema = features.FeatureValueSchema
val FeatureValueSchema: features.FeatureValueSchema.type = features.FeatureValueSchema

type FeatureTarget = features.FeatureTarget
val FeatureTarget: features.FeatureTarget.type = features.FeatureTarget

type FeatureRef = features.FeatureRef
val FeatureRef: features.FeatureRef.type = features.FeatureRef

type SidecarManifest = features.SidecarManifest
val SidecarManifest: features.SidecarManifest.type = features.SidecarManifest

type BoundaryEvidence = features.BoundaryEvidence
val BoundaryEvidence: features.BoundaryEvidence.type = features.BoundaryEvidence
