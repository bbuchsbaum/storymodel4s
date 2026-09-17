package storymodel4s.corpus

/** Which side of the recall/stimulus pair a capability describes. */
enum Side:
  case Stimulus, Recall
  def render: String = productPrefix.toLowerCase

/** What a corpus can actually support, so that "can this corpus run this arm" is a computed answer
  * rather than an archaeology project.
  *
  * Only capabilities that can be DERIVED from an opened corpus appear here. An earlier design let a
  * manifest assert its own capability set and admitted on that assertion, which is identity
  * asserted by the caller; and a later one derived them but had nothing consume them, which
  * re-creates the defect this whole contract is about -- a declared record wired to nothing.
  *
  * Semantic capabilities (is this column a thread label? is this gold?) cannot be derived from a
  * reading alone; they need a declaration this layer does not yet have, and are deliberately absent
  * rather than guessed.
  */
enum Capability:
  /** A time column exists on the stimulus side, read under this encoding. */
  case StimulusClock(encoding: CellEncoding)

  /** The stimulus is partitioned into this many rows on a named sheet. */
  case StimulusRows(sheet: String, rows: Int)

  /** A column whose declared encoding makes it a candidate ordinal identity. */
  case OrdinalColumn(sheet: String, column: String, distinctValues: Int)
  case Custom(namespace: String, label: String)

  def render: String = this match
    case StimulusClock(e)       => s"stimulus-clock:${e.render}"
    case StimulusRows(s, n)     => s"stimulus-rows:$s=$n"
    case OrdinalColumn(s, c, n) => s"ordinal:$s.$c=$n"
    case Custom(ns, label)      => s"$ns:$label"

object Capability:
  /** Whether an encoding makes a column a clock. A predicate rather than a set, because
    * `ExcelSerialDays` now carries a day origin and so is a family, not a value.
    */
  def isClock(encoding: CellEncoding): Boolean = encoding match
    case CellEncoding.ExcelSerialDays(_) | CellEncoding.MinuteDotSecond => true
    case _                                                              => false
