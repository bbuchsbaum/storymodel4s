package storymodel4s.embed

/** Typed failures of the embedding contract.
  *
  * Why a separate ADT from `core.DomainError`: embedding failures are per-item, provider-shaped
  * facts (dimension, policy, transport) that receipts must record without ever echoing payload
  * text; keeping them typed here lets `align` route them without string matching.
  */
enum EmbedError:
  case DimensionMismatch(expected: Int, actual: Int)
  case NonFiniteValue(index: Int)
  case NotNormalized(norm: Double, expected: Normalization)
  case InvalidDimension(value: Int)
  case InvalidRequestId(raw: String, reason: String)
  case DuplicateRequestId(id: String)
  case UnknownSpace(id: String)
  case IncompatibleSpaces(query: String, document: String, reason: String)
  case InvalidDistance(value: Double)
  case InvalidRecipe(reason: String)
  case InvalidResult(reason: String)
  case InvalidKey(reason: String)

  def message: String = this match
    case DimensionMismatch(e, a)     => s"dimension mismatch: expected $e, got $a"
    case NonFiniteValue(i)           => s"non-finite value at index $i"
    case NotNormalized(n, e)         => s"vector norm $n does not satisfy $e"
    case InvalidDimension(v)         => s"invalid dimension $v"
    case InvalidRequestId(raw, r)    => s"invalid request id '$raw': $r"
    case DuplicateRequestId(id)      => s"duplicate request id '$id'"
    case UnknownSpace(id)            => s"unknown embedding space '$id'"
    case IncompatibleSpaces(q, d, r) => s"incompatible spaces query=$q document=$d: $r"
    case InvalidDistance(v)          => s"invalid distance $v"
    case InvalidRecipe(r)            => s"invalid recipe: $r"
    case InvalidResult(r)            => s"invalid result: $r"
    case InvalidKey(r)               => s"invalid key: $r"
