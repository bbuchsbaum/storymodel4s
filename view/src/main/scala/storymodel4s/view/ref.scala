package storymodel4s.view

import storymodel4s.align.AlignRef
import storymodel4s.core.{Address, Addressable, CoreRef}
import storymodel4s.document.DocRef
import storymodel4s.features.FeatureAddress
import storymodel4s.recall.RecallRef
import storymodel4s.story.StoryRef

/** Closed visualization reference space assembled above every addressable domain module.
  *
  * Why: serialized artifacts use universal [[Address]] values, while compilation needs proof that
  * each target belongs to a supported typed reference family rather than an arbitrary tag and key.
  */
enum ViewRef:
  case Core(value: CoreRef)
  case Feature(value: FeatureAddress)
  case Story(value: StoryRef)
  case Doc(value: DocRef)
  case Recall(value: RecallRef)
  case Align(value: AlignRef)

  /** Return the canonical universal address without replacing the module's typed identity. */
  def address: Address = this match
    case ViewRef.Core(value)    => Addressable[CoreRef].address(value)
    case ViewRef.Feature(value) => Addressable[FeatureAddress].address(value)
    case ViewRef.Story(value)   => Addressable[StoryRef].address(value)
    case ViewRef.Doc(value)     => Addressable[DocRef].address(value)
    case ViewRef.Recall(value)  => Addressable[RecallRef].address(value)
    case ViewRef.Align(value)   => Addressable[AlignRef].address(value)

object ViewRef:
  /** Re-type an address by dispatching only to the module family declared by its tag. */
  def parse(address: Address): Option[ViewRef] =
    if address.tag == CoreRef.Tag then Addressable[CoreRef].parse(address).map(ViewRef.Core.apply)
    else if address.tag == FeatureAddress.Tag then
      Addressable[FeatureAddress].parse(address).map(ViewRef.Feature.apply)
    else if address.tag == StoryRef.Tag then
      Addressable[StoryRef].parse(address).map(ViewRef.Story.apply)
    else if address.tag == DocRef.Tag then Addressable[DocRef].parse(address).map(ViewRef.Doc.apply)
    else if address.tag == RecallRef.Tag then
      Addressable[RecallRef].parse(address).map(ViewRef.Recall.apply)
    else if address.tag == AlignRef.Tag then
      Addressable[AlignRef].parse(address).map(ViewRef.Align.apply)
    else None
