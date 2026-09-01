package storymodel4s.provider.parser

import storymodel4s.core.Checksum

/** Injective framing for provider-parser identities that may contain arbitrary supplied text. */
private[parser] object ParserIdentity:
  private val FramingVersion = "storymodel4s.parser.identity/length-prefixed-v1"

  def digest(domain: String, parts: Iterable[String]): Checksum =
    Checksum.ofText(frame(FramingVersion +: domain +: parts.toVector))

  def orderKey(parts: Iterable[String]): String = frame(parts)

  private def frame(parts: Iterable[String]): String =
    parts.iterator.map(part => s"${part.length}:$part").mkString
