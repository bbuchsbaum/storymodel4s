package storymodel4s.recall

import scala.util.matching.Regex

import storymodel4s.core.*

/** Deterministic, rule-based baseline that turns a recall transcript into idea units with
  * connective-derived relations, hedging, discourse function, and a shallow proposition sketch.
  *
  * Why a baseline: the recall-side protocol must exist before any provider (parser, LLM) is plugged
  * in, and the aligner's tests need units whose structure is reproducible. Every heuristic here is
  * meant to be replaced by a provider behind the same `RecallGraph` contract; none of them is a
  * scientific claim about recall language.
  *
  * Cue discipline (review findings #23/#24): task-commentary cues fire only when the whole unit is
  * commentary; "so" is causal only mid-sentence; "like a" is an association only when it is not the
  * complement of a perception verb; evaluation cues need a speaker-evaluative subject; hedge cues
  * inside quoted speech are ignored; a discourse "No," is not a negation.
  */
object RecallSegmenter:
  import RecallGraphStatus.Checked

  // ---- clause splitting -------------------------------------------------------------------

  private val Pronouns = "(?:he|she|they|it|i|we|there|the|a|an|his|her|their|my|our|you)"

  /** A portable split rule. `clauseStartGroup` keeps a matched connective with the next clause;
    * `None` consumes the whole match as a separator. Regex lookaround is deliberately prohibited:
    * Scala Native's regex engine does not implement it.
    */
  private final case class SplitRule(
      pattern: Regex,
      clauseStartGroup: Option[Int],
      rejectedFollowingWords: Set[String] = Set.empty
  )

  private final case class SplitCandidate(
      separatorStart: Int,
      clauseStart: Int,
      occupiedEnd: Int,
      priority: Int
  )

  private val Splitters: Vector[SplitRule] = Vector(
    SplitRule(""";\s+""".r, None),
    SplitRule("""(?i),?\s+(and\s+then)\b""".r, Some(1)),
    SplitRule("""(?i),?\s+(then)\b""".r, Some(1)),
    SplitRule("""(?i),?\s+(but)\b""".r, Some(1)),
    SplitRule(
      """(?i),?\s+(so)\b""".r,
      Some(1),
      Set("much", "many", "far", "long")
    ),
    SplitRule("""(?i),?\s+(because)\b""".r, Some(1)),
    SplitRule("""(?i),?\s+(while)\b""".r, Some(1)),
    SplitRule("""(?i),?\s+(before\s+(?:that|this))\b""".r, Some(1)),
    SplitRule("""(?i),?\s+(after\s+(?:that|this))\b""".r, Some(1)),
    SplitRule("""(?i),?\s+(prior\s+to\s+that)\b""".r, Some(1)),
    SplitRule(("""(?i),?\s+and\s+(""" + Pronouns + """)\b""").r, Some(1))
  )

  private val MinClauseTokens = 3

  // ---- cue lexicons -----------------------------------------------------------------------

  private val HedgeCues: Vector[(Regex, Boolean)] = Vector(
    ("""\bi'?m not sure\b""".r, true),
    ("""\bi don'?t know (?:if|whether)\b""".r, true),
    ("""\bi can'?t remember exactly\b""".r, true),
    ("""\bi think\b""".r, false),
    ("""\bi believe\b""".r, false),
    ("""\bi guess\b""".r, false),
    ("""\bmaybe\b""".r, false),
    ("""\bperhaps\b""".r, false),
    ("""\bprobably\b""".r, false),
    ("""\bsome kind of\b""".r, false),
    ("""\bkind of\b""".r, false),
    ("""\bsort of\b""".r, false),
    ("""\bor something\b""".r, false)
  )

  /** Strong association cues: always an association. */
  private val AssociationCue =
    """\bkind of like\b|\breminded me\b|\bsimilar to\b|\bsort of like\b""".r

  /** Bare "like a/an" is an association unless it completes a perception verb ("sounded like a war
    * party" is content, not an association).
    */
  private val BareLike = """\blike an?\b""".r
  private val PerceptionBeforeLike =
    """\b(?:sound(?:ed|s)?|look(?:ed|s)?|seem(?:ed|s)?|smell(?:ed|s)?|taste[ds]?|felt|feels?|appear(?:ed|s)?)\s+like an?\b""".r

  private val SourceMonitoringCue =
    """\bi (?:don'?t|can'?t|do not|cannot) remember\b|\bi forget\b|\bi'?m not sure (?:if|whether)\b""".r

  /** Whole-unit task commentary: the unit is nothing but the cue (plus punctuation). */
  private val WholeTaskCommentary =
    """^(?:okay|ok|um+|uh+|hmm+|that'?s (?:all|it)(?: i (?:remember|got|can recall))?|i'?m done|i think that'?s (?:all|it)|that'?s about it)[\s.,!?]*$""".r
  private val EmbeddedTaskCommentary = """\bthat'?s (?:all|it) i (?:remember|got|can recall)\b""".r

  /** Leading fillers stripped before classification and sketching ("Um, they went…"). */
  private val LeadingFiller = """^(?:(?:okay|ok|um+|uh+|hmm+|well|so|yeah|yes|no)[,.\s]+)+""".r

  /** Evaluation needs a speaker-evaluative frame: first person, or an impersonal subject. */
  private val EvaluationCue =
    """\bi (?:liked|loved|hated|enjoyed)\b|\b(?:it|that|this|the (?:story|whole thing|movie|film|ending)) was (?:good|bad|boring|interesting|funny|scary|weird|great|sad|creepy|confusing)\b|\bgood story\b""".r
  private val SummaryCue =
    """\bbasically\b|\boverall\b|\bthe (?:whole|first|second) (?:half|part|story)\b|\bit was about\b|\bthe story was about\b""".r
  private val InferenceCue =
    """\bmust (?:have|be)\b|\bprobably because\b|\bi assume\b|\bpresumably\b|\bmust'?ve\b""".r

  private val Negation = """\b(?:not|never|no|nobody|nothing|none)\b|n't\b""".r
  private val Reported = """\b(?:said|told|claimed|announced|says|tells)\b""".r
  private val Intended = """\b(?:going to|wanted to|planned to|were to|was to)\b""".r

  /** Regions of quoted speech: hedges and cues inside them belong to a quoted speaker. */
  private val Quoted = """"[^"]*"|“[^”]*”|'[^']{3,}'""".r

  private val StopWords: Set[String] = Lexical.stopwords ++ Set("here", "somebody", "someone")

  private val VerbLemmas: Map[String, String] = {
    val bases = Vector(
      "go",
      "find",
      "hear",
      "see",
      "search",
      "enter",
      "arrive",
      "come",
      "say",
      "tell",
      "take",
      "get",
      "make",
      "run",
      "feel",
      "kill",
      "die",
      "leave",
      "give",
      "strike",
      "shoot",
      "fight",
      "hide",
      "return",
      "fall",
      "cry",
      "want",
      "ask",
      "walk",
      "open",
      "look",
      "scream",
      "pick",
      "escape",
      "lift",
      "carry",
      "burst",
      "paddle",
      "land",
      "light",
      "sit",
      "stand",
      "speak",
      "bring",
      "know",
      "meet",
      "put",
      "let",
      "keep",
      "hold",
      "hurt",
      "start",
      "stop",
      "help",
      "call",
      "try",
      "turn",
      "move",
      "live",
      "reach",
      "wait",
      "follow",
      "throw",
      "catch",
      "eat",
      "drink",
      "sing",
      "dance",
      "drive",
      "ride",
      "fly",
      "swim",
      "write",
      "read",
      "break",
      "cut",
      "hit",
      "push",
      "pull",
      "send",
      "buy",
      "sell",
      "pay",
      "win",
      "lose",
      "wake",
      "sleep",
      "grab",
      "climb",
      "jump",
      "shout",
      "laugh",
      "smile",
      "notice",
      "remember",
      "forget",
      "decide",
      "refuse",
      "agree",
      "join",
      "attack",
      "recruit",
      "invite",
      "tell",
      "show",
      "watch",
      "listen",
      "hunt",
      "travel",
      "sail",
      "row",
      "become",
      "happen",
      "begin",
      "continue",
      "end",
      "finish"
    )
    val out = scala.collection.mutable.Map.empty[String, String]
    bases.foreach { b =>
      out(b) = b
      out(b + "s") = b
      out(b + "es") = b
      out(b + "ed") = b
      out(b + "d") = b
      out(b + "ing") = b
      if b.endsWith("e") then out(b.dropRight(1) + "ing") = b
      if b.endsWith("y") then
        out(b.dropRight(1) + "ies") = b
        out(b.dropRight(1) + "ied") = b
      if b.length >= 3 && !"aeiouy".contains(b.last) && "aeiou".contains(b(b.length - 2)) then
        out(b + b.last + "ed") = b
        out(b + b.last + "ing") = b
    }
    Lexical.irregular.foreach { case (form, base) => if bases.contains(base) then out(form) = base }
    out.toMap
  }

  private val LocationWords: Set[String] = Set(
    "house",
    "cellar",
    "basement",
    "downstairs",
    "upstairs",
    "room",
    "river",
    "home",
    "village",
    "forest",
    "town",
    "city",
    "apartment",
    "kitchen",
    "beach",
    "shore",
    "canoe",
    "boat",
    "camp",
    "attic",
    "garden",
    "street",
    "school",
    "restaurant",
    "water",
    "bank",
    "hill",
    "mountain",
    "road",
    "church",
    "hospital",
    "office",
    "station"
  )

  private val SensoryWords: Set[String] = Set(
    "noise",
    "sound",
    "scream",
    "loud",
    "quiet",
    "dark",
    "darkness",
    "light",
    "bright",
    "red",
    "black",
    "white",
    "cold",
    "hot",
    "warm",
    "smell",
    "creepy",
    "foggy",
    "fog",
    "misty",
    "mist",
    "wet",
    "rain",
    "voice",
    "shout",
    "cry",
    "smoke",
    "fire",
    "bang",
    "still",
    "calm",
    "silent"
  )

  private val AgentPronouns: Set[String] = Set("he", "she", "they", "i", "we", "it")

  /** Generic person nouns usable as participants (no story-specific vocabulary). */
  private val RoleNouns: Set[String] = Set(
    "woman",
    "man",
    "girl",
    "boy",
    "brother",
    "sister",
    "mother",
    "father",
    "guy",
    "lady",
    "friend",
    "friends",
    "stranger",
    "strangers",
    "warrior",
    "warriors",
    "soldier",
    "soldiers",
    "family",
    "relative",
    "relatives",
    "child",
    "children",
    "kid",
    "kids",
    "men",
    "women",
    "guys",
    "person",
    "villager",
    "villagers",
    "hunter",
    "hunters",
    "companion",
    "companions",
    "fellow",
    "fellows",
    "ghost",
    "ghosts",
    "enemy",
    "enemies",
    "husband",
    "wife",
    "son",
    "daughter",
    "uncle",
    "aunt",
    "grandmother",
    "grandfather",
    "neighbour",
    "neighbor",
    "doctor",
    "king",
    "queen",
    "captain",
    "chief",
    "everyone",
    "everybody"
  )
  private val Indefinites: Set[String] =
    Set("somebody", "someone", "something", "anyone", "anything", "nobody", "nothing", "people")

  // ---- public entry point -----------------------------------------------------------------

  def segment(transcript: StorySource): RecallGraph[Checked] =
    val atlas = SurfaceAnalyzer.analyze(transcript)
    val text = transcript.canonicalText
    val sid = transcript.id.value

    val clauseSpans: Vector[(TextSpan, SurfaceUnit)] =
      atlas.sentences.flatMap(s => splitClauses(text, s.span).map(c => (c, s)))

    val found = collectEntities(text, clauseSpans.map(_._1))
    val entities = found.entities
    val coreference = found.coreference
    // nominal entities are addressed by their distinctive key only; name entities also by their
    // label and its words (so "Anna" and "anna" resolve, but "five" never looks like a head)
    val entityByName: Map[String, RecallEntityId] =
      found.byKey ++ entities.iterator
        .filter(e => found.nameLabels.contains(e.label))
        .flatMap { e =>
          (e.label +: e.label.split(' ').toVector.filter(_.length > 2)).map(_ -> e.id)
        }
        .toMap

    val units = clauseSpans.zipWithIndex.map { case ((span, sentence), i) =>
      val raw = span.slice(text).getOrElse("")
      val lower = Lexical.lower(raw)
      val quoteMask = maskQuoted(lower)
      val hedges = hedgeCues(quoteMask, span)
      val uncertainty = hedges match
        case Vector() => ExpressedUncertainty.Unmarked
        case cues     =>
          val spanSet = SpanSet.unsafe(cues.map { case (s, _) => SpanRef(Some(sentence.id), s) }*)
          if cues.exists(_._2) then ExpressedUncertainty.Explicit(spanSet)
          else ExpressedUncertainty.Hedged(spanSet)
      val function = classify(lower)
      val sketch = sketchOf(quoteMask, hedges.nonEmpty, function, entityByName)
      RecallUnit(
        RecallUnitId.unsafe(s"$sid:u$i"),
        i,
        SpanSet.one(SpanRef(Some(sentence.id), span)),
        raw,
        function,
        uncertainty,
        sketch,
        None
      )
    }

    val sentenceStart: Map[RecallUnitId, Int] =
      units.zip(clauseSpans).map { case (u, (_, s)) => u.id -> s.span.start }.toMap
    val (temporal, causal) = connectiveEdges(units, sentenceStart)
    RecallGraph
      .validated(
        transcript,
        atlas,
        units,
        RecallRelations(temporal, causal, entities, Vector.empty, coreference)
      )
      .fold(
        errors =>
          throw new IllegalStateException(s"RecallSegmenter produced an invalid graph: $errors"),
        identity
      )

  // ---- pieces ----------------------------------------------------------------------------

  private[recall] def splitClauses(text: String, sentence: TextSpan): Vector[TextSpan] =
    val s = sentence.slice(text).getOrElse("")
    val candidates = Splitters.zipWithIndex
      .flatMap { case (rule, priority) =>
        rule.pattern.findAllMatchIn(s).flatMap { m =>
          val rejected = rule.rejectedFollowingWords.contains(wordFollowing(s, m.end))
          if rejected then None
          else
            Some(
              SplitCandidate(
                m.start,
                rule.clauseStartGroup.fold(m.end)(m.start),
                m.end,
                priority
              )
            )
        }
      }
      .sortBy(c => (c.separatorStart, c.priority))
    val boundaries = candidates.foldLeft(Vector.empty[SplitCandidate]) { case (acc, c) =>
      if acc.exists(b => c.separatorStart < b.occupiedEnd) then acc else acc :+ c
    }
    val pieces = boundaries.foldLeft((Vector.empty[TextSpan], 0)) { case ((acc, from), cut) =>
      (
        acc :+ TextSpan.unsafe(sentence.start + from, sentence.start + cut.separatorStart),
        cut.clauseStart
      )
    }
    val all = pieces._1 :+ TextSpan.unsafe(sentence.start + pieces._2, sentence.endExclusive)
    // Merge fragments that are too short into their predecessor (or successor for the first).
    val merged = all.foldLeft(Vector.empty[TextSpan]) { (acc, span) =>
      val ntok = SurfaceAnalyzer.tokenSpans(text, span).count(t => t.length > 1)
      acc.lastOption match
        case Some(prev) if ntok < MinClauseTokens || prevTooShort(text, prev) =>
          acc.init :+ prev.hull(span)
        case _ => acc :+ span
    }
    merged.map(trimSpan(text, _)).filter(_.length > 0)

  private def wordFollowing(text: String, offset: Int): String =
    var start = offset
    while start < text.length && text.charAt(start).isWhitespace do start += 1
    var end = start
    while end < text.length && text.charAt(end).isLetter do end += 1
    text.substring(start, end).toLowerCase

  private def prevTooShort(text: String, span: TextSpan): Boolean =
    SurfaceAnalyzer.tokenSpans(text, span).count(t => t.length > 1) < MinClauseTokens

  private def trimSpan(text: String, span: TextSpan): TextSpan =
    var a = span.start
    var b = span.endExclusive
    while a < b && (text.charAt(a).isWhitespace || ",;".contains(text.charAt(a))) do a += 1
    while b > a && (text.charAt(b - 1).isWhitespace || ",;".contains(text.charAt(b - 1))) do b -= 1
    TextSpan.unsafe(a, b)

  /** Replace quoted regions with spaces of equal length so offsets are preserved. */
  private[recall] def maskQuoted(lower: String): String =
    Quoted.replaceAllIn(lower, m => " " * (m.end - m.start))

  private def hedgeCues(masked: String, span: TextSpan): Vector[(TextSpan, Boolean)] =
    HedgeCues
      .flatMap { case (re, explicit) =>
        re.findAllMatchIn(masked)
          .map(m => (TextSpan.unsafe(span.start + m.start, span.start + m.end), explicit))
          .toVector
      }
      .sortBy(_._1.start)

  private[recall] def stripFiller(lower: String): String =
    LeadingFiller.replaceFirstIn(lower.trim, "")

  private[recall] def classify(lower: String): DiscourseFunction =
    val trimmed = lower.trim
    val content = stripFiller(trimmed)
    if trimmed.isEmpty then DiscourseFunction.Uninterpretable
    else if WholeTaskCommentary.findFirstIn(trimmed).nonEmpty ||
      EmbeddedTaskCommentary.findFirstIn(trimmed).nonEmpty || content.isEmpty
    then DiscourseFunction.TaskCommentary
    else if SourceMonitoringCue.findFirstIn(content).nonEmpty then
      DiscourseFunction.SourceMonitoring
    else if isAssociation(content) then DiscourseFunction.Association
    else if EvaluationCue.findFirstIn(content).nonEmpty then DiscourseFunction.Evaluation
    else if SummaryCue.findFirstIn(content).nonEmpty then DiscourseFunction.Summary
    else if InferenceCue.findFirstIn(content).nonEmpty then DiscourseFunction.Inference
    else DiscourseFunction.EpisodicAssertion

  private def isAssociation(content: String): Boolean =
    AssociationCue.findFirstIn(content).nonEmpty || {
      val bare = BareLike.findAllMatchIn(content).map(_.start).toVector
      val perceptual = PerceptionBeforeLike.findAllMatchIn(content).map(_.end).toVector
      // a bare "like a" counts unless every occurrence completes a perception verb
      bare.exists(b => !perceptual.exists(e => e >= b && e <= b + 8))
    }

  private def wordsOf(lower: String): Vector[String] = Lexical.words(lower)

  private[recall] def lemma(word: String): String = Lexical.stem(word)

  private def sketchOf(
      masked: String,
      hedged: Boolean,
      function: DiscourseFunction,
      entityByName: Map[String, RecallEntityId]
  ): PropositionSketch =
    val content = stripFiller(masked)
    val words = wordsOf(content)
    val lemmas = words.filterNot(StopWords.contains).map(lemma).toSet
    val predIdx = words.indexWhere(w => VerbLemmas.contains(w))
    val predicate = if predIdx >= 0 then Some(VerbLemmas(words(predIdx))) else None

    val agent: Option[SketchParticipant] =
      if predIdx < 0 then None
      else
        // the nearest head-like word before the predicate, expanded leftwards to its noun phrase
        (predIdx - 1 to 0 by -1).iterator
          .map { i =>
            val w = words(i)
            if AgentPronouns.contains(w) then
              Some(SketchParticipant(SketchRole.Agent, None, w, specified = true))
            else if Indefinites.contains(w) then
              Some(SketchParticipant(SketchRole.Agent, None, w, specified = false))
            else if isHead(w, entityByName) then
              Some(nominalParticipant(SketchRole.Agent, words, i, entityByName))
            else None
          }
          .collectFirst { case Some(p) => p }
    val patient: Option[SketchParticipant] =
      if predIdx < 0 then None
      else
        (predIdx + 1 until words.size).iterator
          .map { i =>
            val w = words(i)
            if Indefinites.contains(w) then
              Some(SketchParticipant(SketchRole.Patient, None, w, specified = false))
            else if isHead(w, entityByName) then
              Some(nominalParticipant(SketchRole.Patient, words, i, entityByName))
            else if ObjectPronouns.contains(w) then
              Some(SketchParticipant(SketchRole.Patient, None, w))
            else None
          }
          .collectFirst { case Some(p) => p }
    // Negation is judged after masking hedges and source-monitoring phrases ("I don't remember"
    // is not a negated proposition) and after dropping a discourse "No," interjection.
    val negationMask =
      (HedgeCues.map(_._1) :+ SourceMonitoringCue)
        .foldLeft(content)((acc, re) => re.replaceAllIn(acc, " "))
    val polarity =
      if Negation.findFirstIn(negationMask).nonEmpty then PolarityTag.Negative
      else if predicate.nonEmpty then PolarityTag.Positive
      else PolarityTag.Unknown
    val modality =
      if Reported.findFirstIn(content).nonEmpty then ModalityTag.Reported
      else if Intended.findFirstIn(content).nonEmpty then ModalityTag.Intended
      else if hedged then ModalityTag.Possible
      else if function == DiscourseFunction.EpisodicAssertion || function == DiscourseFunction.Summary
      then ModalityTag.Asserted
      else ModalityTag.Unknown
    PropositionSketch(
      predicate,
      agent.toVector ++ patient.toVector,
      polarity,
      modality,
      words.filter(LocationWords.contains).distinct,
      Vector.empty,
      words.filter(SensoryWords.contains).distinct,
      lemmas
    )

  // ---- nominal mentions -----------------------------------------------------------------

  private val ObjectPronouns: Set[String] = Set("him", "her", "them", "me", "us", "it")
  private val Determiners: Set[String] = Set(
    "the",
    "a",
    "an",
    "this",
    "that",
    "these",
    "those",
    "his",
    "her",
    "their",
    "my",
    "our",
    "your",
    "its",
    "some",
    "another",
    "several",
    "many",
    "both",
    "few"
  )
  private val NumeralWords: Set[String] = Set(
    "one",
    "two",
    "three",
    "four",
    "five",
    "six",
    "seven",
    "eight",
    "nine",
    "ten",
    "several",
    "many",
    "both",
    "few",
    "couple"
  )

  /** Pronouns that can be resolved to a preceding nominal mention, with the number they require. */
  private val ResolvablePronouns: Map[String, MentionNumber] = Map(
    "he" -> MentionNumber.Singular,
    "she" -> MentionNumber.Singular,
    "it" -> MentionNumber.Singular,
    "him" -> MentionNumber.Singular,
    "her" -> MentionNumber.Singular,
    "they" -> MentionNumber.Plural,
    "them" -> MentionNumber.Plural
  )

  private def isHead(w: String, entityByName: Map[String, RecallEntityId]): Boolean =
    RoleNouns.contains(w) || entityByName.contains(w)

  /** Leftmost index of the noun phrase whose head is `words(headIdx)`: at most three pre-head
    * modifiers (numerals, or content words that are not verbs, pronouns, or stopwords), then an
    * optional determiner. Multiword names extend leftwards over further name words.
    */
  private[recall] def phraseStart(words: Vector[String], headIdx: Int): Int =
    var i = headIdx
    var mods = 0
    def modifierLike(w: String): Boolean =
      NumeralWords.contains(w) ||
        (!StopWords.contains(w) && !Determiners.contains(w) && !VerbLemmas.contains(w) &&
          !AgentPronouns.contains(w) && !ObjectPronouns.contains(w) && !RoleNouns.contains(w) &&
          !Indefinites.contains(w))
    while i - 1 >= 0 && mods < 3 && modifierLike(words(i - 1)) do
      i -= 1
      mods += 1
    if i - 1 >= 0 && Determiners.contains(words(i - 1)) then i -= 1
    i

  private def nominalParticipant(
      role: SketchRole,
      words: Vector[String],
      headIdx: Int,
      entityByName: Map[String, RecallEntityId]
  ): SketchParticipant =
    val start = phraseStart(words, headIdx)
    val phrase = words.slice(start, headIdx + 1).mkString(" ")
    NominalMention.parse(phrase) match
      case Some(m) =>
        val label = (m.modifiers.isEmpty, phrase) match
          case (true, _) => words(headIdx)
          case _ => words.slice(start, headIdx + 1).filterNot(Determiners.contains).mkString(" ")
        SketchParticipant(
          role,
          entityByName.get(m.distinctiveKey).orElse(entityByName.get(words(headIdx))),
          label,
          specified = true,
          aliases = m.keys,
          head = m.head,
          modifiers = m.modifiers,
          determiner = Some(m.determiner),
          number = Some(m.number)
        )
      case None =>
        SketchParticipant(role, entityByName.get(words(headIdx)), words(headIdx))

  /** One nominal or name mention occurrence in the transcript. */
  private final case class MentionOccurrence(
      key: String,
      label: String,
      span: TextSpan,
      mention: Option[NominalMention]
  )

  /** Entities are (a) capitalized names — clause-initial capitals count only when the same word is
    * capitalized mid-clause elsewhere or is followed by another capitalized word; consecutive name
    * words merge — and (b) nominal mentions headed by a generic person noun, expanded to their noun
    * phrase and keyed by [[NominalMention.distinctiveKey]] so "the young man" and "the five men"
    * are distinct entities. Coreference links are made backwards only: same-key nominals link to
    * the entity's first mention; pronouns link to the nearest preceding nominal of compatible
    * number.
    */
  private final case class FoundEntities(
      entities: Vector[RecallEntity],
      coreference: Vector[RecallCorefLink],
      byKey: Map[String, RecallEntityId],
      nameLabels: Set[String]
  )

  private def collectEntities(text: String, clauses: Vector[TextSpan]): FoundEntities =
    val Token = """[A-Za-zÀ-ɏ]+""".r
    val perClause: Vector[Vector[(String, Int, Int)]] = clauses.map { c =>
      val s = c.slice(text).getOrElse("")
      Token.findAllMatchIn(s).map(m => (m.matched, c.start + m.start, c.start + m.end)).toVector
    }
    val midClauseCapitals: Set[String] = perClause.flatMap { toks =>
      toks.drop(1).collect {
        case (w, _, _)
            if w.head.isUpper && !AgentPronouns.contains(Lexical.lower(w)) &&
              !StopWords.contains(Lexical.lower(w)) =>
          Lexical.lower(w)
      }
    }.toSet
    def nameLike(w: String, first: Boolean, nextCap: Boolean): Boolean =
      val lw = Lexical.lower(w)
      w.head.isUpper && !AgentPronouns.contains(lw) && !StopWords.contains(lw) &&
      (!first || midClauseCapitals.contains(lw) || nextCap)

    val occurrences = Vector.newBuilder[MentionOccurrence]
    val pronouns = Vector.newBuilder[(String, TextSpan)]
    perClause.foreach { toks =>
      val lowerWords = toks.map(t => Lexical.lower(t._1))
      var i = 0
      while i < toks.size do
        val (w, start, _) = toks(i)
        val nextCap = i + 1 < toks.size && toks(i + 1)._1.head.isUpper &&
          !StopWords.contains(Lexical.lower(toks(i + 1)._1))
        if nameLike(w, i == 0, nextCap) then
          var j = i
          while j + 1 < toks.size && nameLike(toks(j + 1)._1, first = false, nextCap = false) do
            j += 1
          val label = lowerWords.slice(i, j + 1).mkString(" ")
          occurrences += MentionOccurrence(label, label, TextSpan.unsafe(start, toks(j)._3), None)
          i = j + 1
        else
          val lw = lowerWords(i)
          if RoleNouns.contains(lw) then
            val from = phraseStart(lowerWords, i)
            val phrase = lowerWords.slice(from, i + 1).mkString(" ")
            NominalMention.parse(phrase) match
              case Some(m) =>
                val label =
                  if m.modifiers.isEmpty then lw
                  else lowerWords.slice(from, i + 1).filterNot(Determiners.contains).mkString(" ")
                occurrences += MentionOccurrence(
                  m.distinctiveKey,
                  label,
                  TextSpan.unsafe(toks(from)._2, toks(i)._3),
                  Some(m)
                )
              case None =>
                occurrences += MentionOccurrence(lw, lw, TextSpan.unsafe(start, toks(i)._3), None)
          else if ResolvablePronouns.contains(lw) then
            pronouns += ((lw, TextSpan.unsafe(start, toks(i)._3)))
          i += 1
    }

    val occs = occurrences.result()
    val order = occs.map(_.key).distinct
    val ids: Map[String, RecallEntityId] = order.zipWithIndex.map { case (key, i) =>
      key -> RecallEntityId.unsafe(s"re$i:${key.replace(' ', '_').replace('+', '_')}")
    }.toMap
    val entities = order.map { key =>
      val mine = occs.filter(_.key == key)
      RecallEntity(ids(key), mine.head.label, mine.map(_.span))
    }
    val sameKey = occs
      .groupBy(_.key)
      .toVector
      .flatMap { case (key, mine) =>
        mine
          .sortBy(_.span.start)
          .drop(1)
          .map(o => RecallCorefLink(o.span, ids(key), RecallCorefKind.SameKey))
      }
    val nominalOccs = occs.filter(_.mention.isDefined).sortBy(_.span.start)
    val pronounLinks = pronouns.result().flatMap { case (p, span) =>
      val need = ResolvablePronouns(p)
      nominalOccs
        .filter(o => o.span.endExclusive <= span.start && o.mention.exists(_.number == need))
        .lastOption
        .map(o => RecallCorefLink(span, ids(o.key), RecallCorefKind.Pronoun))
    }
    val links = (sameKey ++ pronounLinks).sortBy(l => (l.anaphor.start, l.anaphor.endExclusive))
    val nameLabels = occs.filter(_.mention.isEmpty).map(_.label).toSet
    FoundEntities(entities, links, ids, nameLabels)

  private def connectiveEdges(
      units: Vector[RecallUnit],
      sentenceStart: Map[RecallUnitId, Int]
  ): (Vector[RecallTemporalEdge], Vector[RecallCausalEdge]) =
    val ordered = units.sortBy(_.ordinal)
    val pairs = ordered.sliding(2).collect { case Vector(p, u) => (p, u) }.toVector
    val temporal = Vector.newBuilder[RecallTemporalEdge]
    val causal = Vector.newBuilder[RecallCausalEdge]
    pairs.foreach { case (prev, u) =>
      val lower = Lexical.lower(u.text).trim
      val midSentence = sentenceStart.get(u.id).exists(_ < u.minSpan.start)
      val cueSpan = Some(
        TextSpan.unsafe(u.minSpan.start, math.min(u.minSpan.endExclusive, u.minSpan.start + 12))
      )
      if lower.startsWith("because") then causal += RecallCausalEdge(u.id, prev.id, cueSpan)
      else if (lower.startsWith("so ") || lower.startsWith("so that")) && midSentence then
        causal += RecallCausalEdge(prev.id, u.id, cueSpan)
      else if lower.startsWith("before that") || lower.startsWith("before this") ||
        lower.startsWith("earlier") || lower.startsWith("prior to that")
      then temporal += RecallTemporalEdge(u.id, RecallTemporalRelation.Before, prev.id, cueSpan)
      else if lower.startsWith("after that") || lower.startsWith("after this") ||
        lower.startsWith("then") || lower.startsWith("and then") ||
        lower.startsWith("afterwards") || lower.startsWith("later") ||
        lower.startsWith("next") || lower.startsWith("eventually")
      then temporal += RecallTemporalEdge(prev.id, RecallTemporalRelation.Before, u.id, cueSpan)
      else if lower.startsWith("while") || lower.startsWith("meanwhile") then
        temporal += RecallTemporalEdge(prev.id, RecallTemporalRelation.Simultaneous, u.id, cueSpan)
    }
    (temporal.result(), causal.result())
