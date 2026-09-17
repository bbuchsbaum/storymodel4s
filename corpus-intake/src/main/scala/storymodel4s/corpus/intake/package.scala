package storymodel4s.corpus

/** JVM-only corpus readers (ADR 0018): xlsx, zip and delimited readers, the byte verifier, and
  * receipt and descriptor emission.
  *
  * It owns I/O and file layout, and no semantics. No portable module depends on it.
  */
package object intake
