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
  */
object RecallSegmenter:

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
    SplitRule("""(?i),?\s+(before\s+that)\b""".r, Some(1)),
    SplitRule("""(?i),?\s+(after\s+that)\b""".r, Some(1)),
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

  private val AssociationCue = """\bkind of like\b|\blike an?\b|\breminded me\b|\bsimilar to\b""".r
  private val SourceMonitoringCue =
    """\bi (?:don'?t|can'?t|do not|cannot) remember\b|\bi forget\b|\bi'?m not sure (?:if|whether)\b""".r
  private val TaskCommentaryCue =
    """^(?:that'?s (?:all|it)|i'?m done|okay|ok|um+|uh+)\b|\bthat'?s (?:all|it) i (?:remember|got)\b""".r
  private val EvaluationCue =
    """\bi (?:liked|loved|hated|enjoyed)\b|\bwas (?:good|bad|boring|interesting|funny|scary|weird|great|sad)\b|\bgood story\b""".r
  private val SummaryCue =
    """\bbasically\b|\boverall\b|\bthe (?:whole|first|second) (?:half|part|story)\b|\bit was about\b|\bthe story was about\b""".r
  private val InferenceCue = """\bmust have\b|\bprobably because\b|\bi assume\b|\bpresumably\b""".r

  private val Negation = """\b(?:not|never|no|nobody|nothing)\b|n't\b""".r
  private val Reported = """\b(?:said|told|claimed|announced)\b""".r
  private val Intended = """\b(?:going to|wanted to|planned to|were to|was to)\b""".r

  private val StopWords: Set[String] = Set(
    "the",
    "a",
    "an",
    "and",
    "or",
    "but",
    "so",
    "then",
    "of",
    "to",
    "in",
    "on",
    "at",
    "into",
    "this",
    "that",
    "these",
    "those",
    "it",
    "its",
    "is",
    "was",
    "were",
    "be",
    "been",
    "are",
    "he",
    "she",
    "they",
    "them",
    "his",
    "her",
    "their",
    "i",
    "we",
    "you",
    "me",
    "us",
    "my",
    "there",
    "here",
    "kind",
    "sort",
    "some",
    "like",
    "with",
    "from",
    "for",
    "as",
    "by",
    "up",
    "down",
    "out",
    "about",
    "before",
    "after",
    "while",
    "because",
    "had",
    "has",
    "have",
    "did",
    "do",
    "does",
    "think",
    "guess",
    "maybe",
    "very",
    "really",
    "just",
    "not",
    "no",
    "somebody",
    "someone",
    "something",
    "anyone",
    "anything",
    "who",
    "what",
    "which"
  )

  private val VerbLemmas: Map[String, String] = Map(
    "went" -> "go",
    "goes" -> "go",
    "going" -> "go",
    "gone" -> "go",
    "found" -> "find",
    "finds" -> "find",
    "finding" -> "find",
    "heard" -> "hear",
    "hears" -> "hear",
    "hearing" -> "hear",
    "saw" -> "see",
    "sees" -> "see",
    "seen" -> "see",
    "seeing" -> "see",
    "searched" -> "search",
    "searches" -> "search",
    "searching" -> "search",
    "entered" -> "enter",
    "enters" -> "enter",
    "entering" -> "enter",
    "arrived" -> "arrive",
    "arrives" -> "arrive",
    "arriving" -> "arrive",
    "came" -> "come",
    "comes" -> "come",
    "coming" -> "come",
    "said" -> "say",
    "says" -> "say",
    "saying" -> "say",
    "told" -> "tell",
    "tells" -> "tell",
    "telling" -> "tell",
    "took" -> "take",
    "takes" -> "take",
    "taking" -> "take",
    "taken" -> "take",
    "got" -> "get",
    "gets" -> "get",
    "getting" -> "get",
    "made" -> "make",
    "makes" -> "make",
    "making" -> "make",
    "ran" -> "run",
    "runs" -> "run",
    "running" -> "run",
    "felt" -> "feel",
    "feels" -> "feel",
    "feeling" -> "feel",
    "thought" -> "think",
    "thinks" -> "think",
    "killed" -> "kill",
    "kills" -> "kill",
    "killing" -> "kill",
    "died" -> "die",
    "dies" -> "die",
    "dying" -> "die",
    "left" -> "leave",
    "leaves" -> "leave",
    "leaving" -> "leave",
    "gave" -> "give",
    "gives" -> "give",
    "giving" -> "give",
    "hit" -> "hit",
    "hits" -> "hit",
    "struck" -> "strike",
    "strikes" -> "strike",
    "shot" -> "shoot",
    "shoots" -> "shoot",
    "fought" -> "fight",
    "fights" -> "fight",
    "fighting" -> "fight",
    "hid" -> "hide",
    "hides" -> "hide",
    "hiding" -> "hide",
    "returned" -> "return",
    "returns" -> "return",
    "returning" -> "return",
    "fell" -> "fall",
    "falls" -> "fall",
    "falling" -> "fall",
    "cried" -> "cry",
    "cries" -> "cry",
    "crying" -> "cry",
    "wanted" -> "want",
    "wants" -> "want",
    "wanting" -> "want",
    "asked" -> "ask",
    "asks" -> "ask",
    "asking" -> "ask",
    "walked" -> "walk",
    "walks" -> "walk",
    "walking" -> "walk",
    "opened" -> "open",
    "opens" -> "open",
    "opening" -> "open",
    "looked" -> "look",
    "looks" -> "look",
    "looking" -> "look",
    "screamed" -> "scream",
    "screams" -> "scream",
    "screaming" -> "scream",
    "picked" -> "pick",
    "picks" -> "pick",
    "picking" -> "pick",
    "escaped" -> "escape",
    "escapes" -> "escape",
    "escaping" -> "escape",
    "go" -> "go",
    "find" -> "find",
    "hear" -> "hear",
    "see" -> "see",
    "search" -> "search",
    "enter" -> "enter",
    "arrive" -> "arrive",
    "come" -> "come",
    "say" -> "say",
    "tell" -> "tell",
    "take" -> "take",
    "get" -> "get",
    "make" -> "make",
    "run" -> "run",
    "feel" -> "feel",
    "kill" -> "kill",
    "die" -> "die",
    "leave" -> "leave",
    "give" -> "give",
    "strike" -> "strike",
    "shoot" -> "shoot",
    "fight" -> "fight",
    "hide" -> "hide",
    "return" -> "return",
    "fall" -> "fall",
    "cry" -> "cry",
    "want" -> "want",
    "ask" -> "ask",
    "walk" -> "walk",
    "open" -> "open",
    "look" -> "look",
    "scream" -> "scream",
    "pick" -> "pick",
    "escape" -> "escape"
  )

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
    "restaurant"
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
    "wet",
    "rain",
    "voice",
    "shout",
    "cry",
    "smoke",
    "fire",
    "bang"
  )

  private val AgentPronouns: Set[String] = Set("he", "she", "they", "i", "we", "it")
  private val RoleNouns: Set[String] =
    Set("woman", "man", "girl", "boy", "brother", "sister", "mother", "father", "guy", "lady")
  private val Indefinites: Set[String] =
    Set("somebody", "someone", "something", "anyone", "anything", "nobody", "nothing", "people")

  // ---- public entry point -----------------------------------------------------------------

  def segment(transcript: StorySource): RecallGraph =
    val atlas = SurfaceAnalyzer.analyze(transcript)
    val text = transcript.canonicalText
    val sid = transcript.id.value

    val clauseSpans: Vector[(TextSpan, SurfaceUnitId)] =
      atlas.sentences.flatMap(s => splitClauses(text, s.span).map(c => (c, s.id)))

    val entities = collectEntities(text, clauseSpans.map(_._1))
    val entityByName: Map[String, RecallEntityId] =
      entities.iterator.map(e => e.label.toLowerCase -> e.id).toMap

    val units = clauseSpans.zipWithIndex.map { case ((span, sentence), i) =>
      val raw = span.slice(text).getOrElse("")
      val lower = raw.toLowerCase
      val hedges = hedgeCues(lower, span)
      val uncertainty = hedges match
        case Vector() => ExpressedUncertainty.Unmarked
        case cues     =>
          val spanSet = SpanSet.unsafe(cues.map { case (s, _) => SpanRef(Some(sentence), s) }*)
          if cues.exists(_._2) then ExpressedUncertainty.Explicit(spanSet)
          else ExpressedUncertainty.Hedged(spanSet)
      val function = classify(lower)
      val sketch = sketchOf(lower, hedges.nonEmpty, function, entityByName)
      RecallUnit(
        RecallUnitId.unsafe(s"$sid:u$i"),
        i,
        SpanSet.one(SpanRef(Some(sentence), span)),
        raw,
        function,
        uncertainty,
        sketch,
        None
      )
    }

    val (temporal, causal) = connectiveEdges(units)
    RecallGraph(transcript, atlas, units, RecallRelations(temporal, causal, entities, Vector.empty))

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

  private def hedgeCues(lower: String, span: TextSpan): Vector[(TextSpan, Boolean)] =
    HedgeCues
      .flatMap { case (re, explicit) =>
        re.findAllMatchIn(lower)
          .map(m => (TextSpan.unsafe(span.start + m.start, span.start + m.end), explicit))
          .toVector
      }
      .sortBy(_._1.start)

  private[recall] def classify(lower: String): DiscourseFunction =
    if lower.trim.isEmpty then DiscourseFunction.Uninterpretable
    else if TaskCommentaryCue.findFirstIn(lower).nonEmpty then DiscourseFunction.TaskCommentary
    else if SourceMonitoringCue.findFirstIn(lower).nonEmpty then DiscourseFunction.SourceMonitoring
    else if AssociationCue.findFirstIn(lower).nonEmpty then DiscourseFunction.Association
    else if EvaluationCue.findFirstIn(lower).nonEmpty then DiscourseFunction.Evaluation
    else if SummaryCue.findFirstIn(lower).nonEmpty then DiscourseFunction.Summary
    else if InferenceCue.findFirstIn(lower).nonEmpty then DiscourseFunction.Inference
    else DiscourseFunction.EpisodicAssertion

  private def wordsOf(lower: String): Vector[String] =
    // Explicit ranges rather than \p{L}: Scala.js regexes lack Unicode property classes.
    """[A-Za-z0-9À-ɏ']+""".r.findAllIn(lower).toVector.map(_.stripSuffix("'s"))

  private[recall] def lemma(word: String): String =
    VerbLemmas.get(word) match
      case Some(l) => l
      case None    =>
        if word.endsWith("ies") && word.length > 4 then word.dropRight(3) + "y"
        else if word.length > 4 && Vector("sses", "shes", "ches", "xes", "zes").exists(
            word.endsWith
          )
        then word.dropRight(2)
        else if word.endsWith("s") && !word.endsWith("ss") && word.length > 3 then word.dropRight(1)
        else word

  private def sketchOf(
      lower: String,
      hedged: Boolean,
      function: DiscourseFunction,
      entityByName: Map[String, RecallEntityId]
  ): PropositionSketch =
    val words = wordsOf(lower)
    val lemmas = words.filterNot(StopWords.contains).map(lemma).toSet
    val predIdx = words.indexWhere(w => VerbLemmas.contains(w))
    val predicate = if predIdx >= 0 then Some(VerbLemmas(words(predIdx))) else None

    val agent: Option[SketchParticipant] =
      if predIdx < 0 then None
      else
        words.take(predIdx).reverse.collectFirst {
          case w
              if AgentPronouns.contains(w) || RoleNouns.contains(w) || entityByName.contains(w) =>
            SketchParticipant(
              SketchRole.Agent,
              entityByName.get(w),
              w,
              specified = !Indefinites.contains(w)
            )
          case w if Indefinites.contains(w) =>
            SketchParticipant(SketchRole.Agent, None, w, specified = false)
        }
    val patient: Option[SketchParticipant] =
      if predIdx < 0 then None
      else
        words.drop(predIdx + 1).collectFirst {
          case w if Indefinites.contains(w) =>
            SketchParticipant(SketchRole.Patient, None, w, specified = false)
          case w if RoleNouns.contains(w) || entityByName.contains(w) =>
            SketchParticipant(SketchRole.Patient, entityByName.get(w), w)
          case w if Set("him", "her", "them", "me", "us").contains(w) =>
            SketchParticipant(SketchRole.Patient, None, w)
        }
    val hedgeMask = HedgeCues.map(_._1).foldLeft(lower)((acc, re) => re.replaceAllIn(acc, " "))
    val polarity =
      if Negation.findFirstIn(hedgeMask).nonEmpty then PolarityTag.Negative
      else if predicate.nonEmpty then PolarityTag.Positive
      else PolarityTag.Unknown
    val modality =
      if Reported.findFirstIn(lower).nonEmpty then ModalityTag.Reported
      else if Intended.findFirstIn(lower).nonEmpty then ModalityTag.Intended
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

  private def collectEntities(text: String, clauses: Vector[TextSpan]): Vector[RecallEntity] =
    val mentions = scala.collection.mutable.LinkedHashMap.empty[String, Vector[TextSpan]]
    clauses.foreach { c =>
      val s = c.slice(text).getOrElse("")
      """[A-Za-zÀ-ɏ]+""".r.findAllMatchIn(s).foreach { m =>
        val w = m.matched
        val lower = w.toLowerCase
        val isName = w.head.isUpper && m.start > 0 && !AgentPronouns.contains(lower) &&
          !StopWords.contains(lower)
        if isName || RoleNouns.contains(lower) then
          val span = TextSpan.unsafe(c.start + m.start, c.start + m.end)
          mentions.update(lower, mentions.getOrElse(lower, Vector.empty) :+ span)
      }
    }
    mentions.toVector.zipWithIndex.map { case ((label, spans), i) =>
      RecallEntity(RecallEntityId.unsafe(s"re$i:$label"), label, spans)
    }

  private def connectiveEdges(
      units: Vector[RecallUnit]
  ): (Vector[RecallTemporalEdge], Vector[RecallCausalEdge]) =
    val ordered = units.sortBy(_.ordinal)
    val pairs = ordered.sliding(2).collect { case Vector(p, u) => (p, u) }.toVector
    val temporal = Vector.newBuilder[RecallTemporalEdge]
    val causal = Vector.newBuilder[RecallCausalEdge]
    pairs.foreach { case (prev, u) =>
      val lower = u.text.toLowerCase.trim
      val cueSpan = Some(
        TextSpan.unsafe(u.minSpan.start, math.min(u.minSpan.endExclusive, u.minSpan.start + 12))
      )
      if lower.startsWith("because") then causal += RecallCausalEdge(u.id, prev.id, cueSpan)
      else if lower.startsWith("so ") then causal += RecallCausalEdge(prev.id, u.id, cueSpan)
      else if lower.startsWith("before that") || lower.startsWith("before this") then
        temporal += RecallTemporalEdge(u.id, RecallTemporalRelation.Before, prev.id, cueSpan)
      else if lower.startsWith("after that") || lower.startsWith("then") ||
        lower.startsWith("and then") || lower.startsWith("afterwards") ||
        lower.startsWith("later") || lower.startsWith("next") || lower.startsWith("eventually")
      then temporal += RecallTemporalEdge(prev.id, RecallTemporalRelation.Before, u.id, cueSpan)
      else if lower.startsWith("while") || lower.startsWith("meanwhile") then
        temporal += RecallTemporalEdge(prev.id, RecallTemporalRelation.Simultaneous, u.id, cueSpan)
    }
    (temporal.result(), causal.result())
