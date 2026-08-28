package storymodel4s.amr.schema

/** Reification (`:location` ⇄ `be-located-at-91`, …) is deliberately not implemented in this
  * milestone: AMR is an interop adapter and no consumer needs reified forms yet. When one does, add
  * a versioned `ReificationTable` with reify/dereify and the partial round-trip law here.
  */
object ReificationStatus:
  val implemented: Boolean = false
