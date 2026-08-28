package storymodel4s.core

/** Structured failures for smart constructors and validators.
  *
  * Why: the core never throws for domain conditions; callers pattern-match on the failure kind and
  * the path that identifies the offending component.
  */
enum DomainError:
  /** A span with negative or inverted bounds, or one that escapes its text. */
  case InvalidSpan(start: Int, endExclusive: Int, reason: String)

  /** An identifier that violates the lexical rules for its kind. */
  case InvalidId(kind: String, raw: String, reason: String)

  /** A probability outside `[0, 1]` or non-finite. */
  case InvalidProbability(value: Double)

  /** A structural invariant failed at `path` (e.g. `"atlas/units/s3"`). */
  case InvariantViolation(path: String, reason: String)

  /** An identifier was added twice to a collection that requires uniqueness. */
  case DuplicateId(kind: String, id: String)

  /** A value did not satisfy a lexical or format rule. */
  case InvalidFormat(kind: String, raw: String, reason: String)

  def message: String = this match
    case InvalidSpan(s, e, r)          => s"invalid span [$s, $e): $r"
    case InvalidId(k, raw, r)          => s"invalid $k '$raw': $r"
    case InvalidProbability(v)         => s"invalid probability $v"
    case InvariantViolation(p, r)      => s"invariant violated at $p: $r"
    case DuplicateId(k, id)            => s"duplicate $k '$id'"
    case InvalidFormat(k, raw, reason) => s"invalid $k '$raw': $reason"
