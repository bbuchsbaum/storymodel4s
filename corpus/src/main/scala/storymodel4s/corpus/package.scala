package storymodel4s

/** The annotation intake contract (ADR 0018).
  *
  * Two guarantees, both closed at the type level and proved from outside this tree:
  *
  *   - you cannot read a byte you have not hashed yourself ([[corpus.Verified]]);
  *   - you cannot produce a canonical value without its raw coordinate ([[corpus.Raw]]).
  *
  * `private[corpus]` admits the whole `storymodel4s.corpus.*` tree, which includes the JVM-only
  * `corpus-intake` module and its tests. "Constructible only by a reader" therefore means
  * "constructible anywhere in two modules", which is why every forge probe lives in
  * `storymodel4s.consumer` rather than here: a probe inside the package cannot tell a closed door
  * from an open one.
  *
  * This module performs no I/O and knows no corpus.
  */
package object corpus
