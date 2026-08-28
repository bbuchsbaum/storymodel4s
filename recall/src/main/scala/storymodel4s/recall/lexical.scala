package storymodel4s.recall

/** Generic English lexical normalization shared by the recall segmenter and the story→align bridge,
  * so lexical overlap compares like with like.
  *
  * Why one implementation: the two sides of an alignment must be normalized by the *same* rules,
  * and those rules must be story-agnostic. Story-specific synonym tables are test resources, never
  * library behaviour (design record fixture policy).
  *
  * Contents: locale-independent lowercasing, a word tokenizer over Unicode letters, the Porter
  * (1980) stemmer, and a table of common irregular verb/noun forms applied before stemming.
  */
object Lexical:

  /** Locale-independent lowercase by code point (no Turkish-I, no length-changing case maps). */
  def lower(s: String): String =
    val sb = new java.lang.StringBuilder(s.length)
    var i = 0
    while i < s.length do
      val cp = s.codePointAt(i)
      sb.appendCodePoint(Character.toLowerCase(cp))
      i += Character.charCount(cp)
    sb.toString

  /** Words are maximal runs of letters, digits, and apostrophes; a trailing possessive is dropped.
    * Returned lower-cased.
    */
  def words(s: String): Vector[String] =
    val out = Vector.newBuilder[String]
    val sb = new java.lang.StringBuilder
    def flush(): Unit =
      if sb.length > 0 then
        val w = sb.toString
        sb.setLength(0)
        val stripped = if w.endsWith("'s") then w.dropRight(2) else w.stripSuffix("'")
        if stripped.nonEmpty then out += stripped
    var i = 0
    while i < s.length do
      val cp = s.codePointAt(i)
      if Character.isLetterOrDigit(cp) || cp == '\'' then
        sb.appendCodePoint(Character.toLowerCase(cp))
      else flush()
      i += Character.charCount(cp)
    flush()
    out.result()

  /** Function words excluded from content-lemma sets. */
  val stopwords: Set[String] = Set(
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
    "am",
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
    "our",
    "your",
    "him",
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
    "which",
    "when",
    "now",
    "one",
    "all",
    "who",
    "if",
    "than"
  )

  /** Irregular forms → dictionary form, applied before stemming. Generic English only. */
  val irregular: Map[String, String] = Map(
    "went" -> "go",
    "gone" -> "go",
    "goes" -> "go",
    "came" -> "come",
    "saw" -> "see",
    "seen" -> "see",
    "heard" -> "hear",
    "said" -> "say",
    "told" -> "tell",
    "thought" -> "think",
    "hid" -> "hide",
    "hidden" -> "hide",
    "shot" -> "shoot",
    "felt" -> "feel",
    "fought" -> "fight",
    "began" -> "begin",
    "begun" -> "begin",
    "made" -> "make",
    "fell" -> "fall",
    "fallen" -> "fall",
    "was" -> "be",
    "were" -> "be",
    "been" -> "be",
    "is" -> "be",
    "are" -> "be",
    "am" -> "be",
    "got" -> "get",
    "gotten" -> "get",
    "spoke" -> "speak",
    "spoken" -> "speak",
    "left" -> "leave",
    "took" -> "take",
    "taken" -> "take",
    "gave" -> "give",
    "given" -> "give",
    "ran" -> "run",
    "struck" -> "strike",
    "found" -> "find",
    "brought" -> "bring",
    "bought" -> "buy",
    "caught" -> "catch",
    "taught" -> "teach",
    "sat" -> "sit",
    "stood" -> "stand",
    "knew" -> "know",
    "known" -> "know",
    "grew" -> "grow",
    "grown" -> "grow",
    "threw" -> "throw",
    "thrown" -> "throw",
    "drew" -> "draw",
    "drawn" -> "draw",
    "flew" -> "fly",
    "flown" -> "fly",
    "ate" -> "eat",
    "eaten" -> "eat",
    "drank" -> "drink",
    "drunk" -> "drink",
    "sang" -> "sing",
    "sung" -> "sing",
    "rang" -> "ring",
    "rung" -> "ring",
    "swam" -> "swim",
    "swum" -> "swim",
    "wrote" -> "write",
    "written" -> "write",
    "rode" -> "ride",
    "ridden" -> "ride",
    "drove" -> "drive",
    "driven" -> "drive",
    "woke" -> "wake",
    "woken" -> "wake",
    "broke" -> "break",
    "broken" -> "break",
    "chose" -> "choose",
    "chosen" -> "choose",
    "froze" -> "freeze",
    "frozen" -> "freeze",
    "forgot" -> "forget",
    "forgotten" -> "forget",
    "held" -> "hold",
    "kept" -> "keep",
    "slept" -> "sleep",
    "met" -> "meet",
    "led" -> "lead",
    "fed" -> "feed",
    "lost" -> "lose",
    "sent" -> "send",
    "spent" -> "spend",
    "built" -> "build",
    "lit" -> "light",
    "bit" -> "bite",
    "bitten" -> "bite",
    "hit" -> "hit",
    "put" -> "put",
    "cut" -> "cut",
    "let" -> "let",
    "set" -> "set",
    "shut" -> "shut",
    "hurt" -> "hurt",
    "read" -> "read",
    "did" -> "do",
    "done" -> "do",
    "had" -> "have",
    "has" -> "have",
    "died" -> "die",
    "dying" -> "die",
    "dies" -> "die",
    "lay" -> "lie",
    "lain" -> "lie",
    "paid" -> "pay",
    "laid" -> "lay",
    "meant" -> "mean",
    "wore" -> "wear",
    "worn" -> "wear",
    "tore" -> "tear",
    "torn" -> "tear",
    "burst" -> "burst",
    "men" -> "man",
    "women" -> "woman",
    "children" -> "child",
    "people" -> "people",
    "feet" -> "foot",
    "teeth" -> "tooth",
    "mice" -> "mouse",
    "geese" -> "goose"
  )

  /** Dictionary form for irregulars, else the Porter stem. Idempotent on its own output for the
    * irregular table (dictionary forms are also stemmed so that `stem("go") == stem("went")`).
    */
  def stem(word: String): String =
    val w = lower(word)
    val stemmed = irregular.get(w) match
      case Some(base) => Porter.stem(base)
      case None       => Porter.stem(w)
    // Porter's step 1c turns y→i only when the stem has a vowel, so "cry" and "cries" diverge
    // ("cry" vs "cri"). Normalizing every final y to i keeps inflected families together.
    if stemmed.length > 1 && stemmed.endsWith("y") then stemmed.dropRight(1) + "i" else stemmed

  /** Content stems of a text: words minus stopwords, stemmed. */
  def stems(text: String): Vector[String] =
    words(text).filterNot(stopwords.contains).map(stem)

  /** Content stems as a set. */
  def stemSet(text: String): Set[String] = stems(text).toSet

  /** The Porter stemming algorithm (Porter 1980), ASCII letters only; other words pass through. */
  object Porter:
    private def isConsonant(w: String, i: Int): Boolean =
      w.charAt(i) match
        case 'a' | 'e' | 'i' | 'o' | 'u' => false
        case 'y'                         => i == 0 || !isConsonant(w, i - 1)
        case _                           => true

    /** Measure m of the stem `w`: number of VC sequences. */
    private def measure(w: String): Int =
      var m = 0
      var i = 0
      val n = w.length
      while i < n && isConsonant(w, i) do i += 1
      var inVowel = i < n
      while i < n do
        if isConsonant(w, i) then
          if inVowel then
            m += 1
            inVowel = false
        else inVowel = true
        i += 1
      m

    private def containsVowel(w: String): Boolean = w.indices.exists(i => !isConsonant(w, i))

    private def endsDoubleConsonant(w: String): Boolean =
      w.length >= 2 && w.charAt(w.length - 1) == w.charAt(w.length - 2) &&
        isConsonant(w, w.length - 1)

    /** *o: stem ends cvc where the final c is not w, x, or y.
      */
    private def endsCvc(w: String): Boolean =
      val n = w.length
      n >= 3 && isConsonant(w, n - 1) && !isConsonant(w, n - 2) && isConsonant(w, n - 3) &&
      !"wxy".contains(w.charAt(n - 1))

    def stem(input: String): String =
      if input.length <= 2 || !input.forall(c => c >= 'a' && c <= 'z') then input
      else
        var w = input
        // Step 1a
        if w.endsWith("sses") then w = w.dropRight(2)
        else if w.endsWith("ies") then w = w.dropRight(2)
        else if w.endsWith("ss") then ()
        else if w.endsWith("s") then w = w.dropRight(1)
        // Step 1b
        var step1bExtra = false
        if w.endsWith("eed") then
          if measure(w.dropRight(3)) > 0 then w = w.dropRight(1)
        else if w.endsWith("ed") && containsVowel(w.dropRight(2)) then
          w = w.dropRight(2)
          step1bExtra = true
        else if w.endsWith("ing") && containsVowel(w.dropRight(3)) then
          w = w.dropRight(3)
          step1bExtra = true
        if step1bExtra then
          if w.endsWith("at") || w.endsWith("bl") || w.endsWith("iz") then w = w + "e"
          else if endsDoubleConsonant(w) && !"lsz".contains(w.last) then w = w.dropRight(1)
          else if measure(w) == 1 && endsCvc(w) then w = w + "e"
        // Step 1c
        if w.endsWith("y") && containsVowel(w.dropRight(1)) then w = w.dropRight(1) + "i"
        // Step 2
        val step2 = Vector(
          "ational" -> "ate",
          "tional" -> "tion",
          "enci" -> "ence",
          "anci" -> "ance",
          "izer" -> "ize",
          "abli" -> "able",
          "alli" -> "al",
          "entli" -> "ent",
          "eli" -> "e",
          "ousli" -> "ous",
          "ization" -> "ize",
          "ation" -> "ate",
          "ator" -> "ate",
          "alism" -> "al",
          "iveness" -> "ive",
          "fulness" -> "ful",
          "ousness" -> "ous",
          "aliti" -> "al",
          "iviti" -> "ive",
          "biliti" -> "ble"
        )
        step2.collectFirst { case (s, r) if w.endsWith(s) => (s, r) }.foreach { (s, r) =>
          val st = w.dropRight(s.length)
          if measure(st) > 0 then w = st + r
        }
        // Step 3
        val step3 = Vector(
          "icate" -> "ic",
          "ative" -> "",
          "alize" -> "al",
          "iciti" -> "ic",
          "ical" -> "ic",
          "ful" -> "",
          "ness" -> ""
        )
        step3.collectFirst { case (s, r) if w.endsWith(s) => (s, r) }.foreach { (s, r) =>
          val st = w.dropRight(s.length)
          if measure(st) > 0 then w = st + r
        }
        // Step 4
        val step4 = Vector(
          "al",
          "ance",
          "ence",
          "er",
          "ic",
          "able",
          "ible",
          "ant",
          "ement",
          "ment",
          "ent",
          "ion",
          "ou",
          "ism",
          "ate",
          "iti",
          "ous",
          "ive",
          "ize"
        )
        step4.find(w.endsWith).foreach { s =>
          val st = w.dropRight(s.length)
          val ok =
            if s == "ion" then measure(st) > 1 && st.nonEmpty && "st".contains(st.last)
            else measure(st) > 1
          if ok then w = st
        }
        // Step 5a
        if w.endsWith("e") then
          val st = w.dropRight(1)
          val m = measure(st)
          if m > 1 || (m == 1 && !endsCvc(st)) then w = st
        // Step 5b
        if measure(w) > 1 && endsDoubleConsonant(w) && w.last == 'l' then w = w.dropRight(1)
        w

/** A nominal mention decomposed into determiner, pre-head modifiers, head, and number, with the
  * token-safe identity keys the aligner compares.
  *
  * Why keys rather than bags of stems: the aligner's name overlap is token-level, so "the young
  * man" and "the five men" would co-refer through the shared head. Keys concatenate sorted modifier
  * stems with the head stem into single tokens (`youngman`, `fiveman`), one per nonempty modifier
  * subset plus the full key, and the bare head only when the mention has no modifiers. Hence "the
  * young man" ⊂ "the two young men" (shared key `youngman`) but "the young man" ≠ "the five men"
  * (no shared key). Everything is generic English; story vocabulary never enters.
  */
final case class NominalMention(
    determiner: Determiner,
    modifiers: Vector[String],
    head: String,
    number: MentionNumber,
    surface: String
):
  /** `young+man`: sorted modifier stems plus the head stem. */
  def distinctiveKey: String = (modifiers.sorted :+ head).mkString("+")

  /** Token-safe identity keys (see the class doc). */
  def keys: Set[String] = NominalMention.keysOf(modifiers, head)

  /** Keys after mapping every stem through `canon` (test resources map synonyms this way). */
  def keysWith(canon: String => String): Set[String] =
    NominalMention.keysOf(modifiers.map(canon), canon(head))

object NominalMention:
  private val Definites = Set("the")
  private val Indefinites = Set("a", "an", "some", "another")
  private val Demonstratives = Set("this", "that", "these", "those")
  private val Possessives = Set("his", "her", "their", "my", "our", "your", "its")
  private val PluralDeterminers = Set("these", "those", "some", "several", "many", "both", "few")

  /** Numerals and quantifiers count as modifiers and fix number. */
  private val Numerals: Map[String, MentionNumber] = Map(
    "one" -> MentionNumber.Singular,
    "two" -> MentionNumber.Plural,
    "three" -> MentionNumber.Plural,
    "four" -> MentionNumber.Plural,
    "five" -> MentionNumber.Plural,
    "six" -> MentionNumber.Plural,
    "seven" -> MentionNumber.Plural,
    "eight" -> MentionNumber.Plural,
    "nine" -> MentionNumber.Plural,
    "ten" -> MentionNumber.Plural,
    "several" -> MentionNumber.Plural,
    "many" -> MentionNumber.Plural,
    "both" -> MentionNumber.Plural,
    "few" -> MentionNumber.Plural,
    "couple" -> MentionNumber.Plural
  )

  /** Words that end a mention when they follow the head ("the man who went"). */
  val relativeMarkers: Set[String] = Set("who", "that", "which", "whom", "whose")

  private val irregularPlurals: Map[String, String] =
    Map(
      "men" -> "man",
      "women" -> "woman",
      "children" -> "child",
      "people" -> "people",
      "feet" -> "foot",
      "teeth" -> "tooth",
      "mice" -> "mouse",
      "geese" -> "goose"
    )

  /** Number of a head noun from its morphology; `None` when the form is not informative. */
  def morphologicalNumber(head: String): Option[MentionNumber] =
    val w = Lexical.lower(head)
    if irregularPlurals.contains(w) then Some(MentionNumber.Plural)
    else if w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") then
      Some(MentionNumber.Plural)
    else Some(MentionNumber.Singular)

  /** Parse a mention phrase already known to end at its head (or to contain a relative marker after
    * it). Words are lower-cased; stopwords other than determiners are dropped from the modifier
    * list; the head is the last word before a relative marker (or the last word).
    */
  def parse(phrase: String): Option[NominalMention] =
    val all = Lexical.words(phrase)
    val cut = all.indexWhere(relativeMarkers.contains)
    val words = if cut >= 0 then all.take(cut) else all
    if words.isEmpty then None
    else
      val det = words.head match
        case w if Definites.contains(w)      => Some(Determiner.Definite)
        case w if Indefinites.contains(w)    => Some(Determiner.Indefinite)
        case w if Demonstratives.contains(w) => Some(Determiner.Demonstrative)
        case w if Possessives.contains(w)    => Some(Determiner.Possessive)
        case _                               => None
      val rest = if det.isDefined then words.tail else words
      if rest.isEmpty then None
      else
        val headWord = rest.last
        val mods = rest.init.filter(w => Numerals.contains(w) || !Lexical.stopwords.contains(w))
        val numeral = mods.collectFirst { case w if Numerals.contains(w) => Numerals(w) }
        val number = numeral
          .orElse(
            if PluralDeterminers.contains(words.head) then Some(MentionNumber.Plural) else None
          )
          .orElse(morphologicalNumber(headWord))
          .getOrElse(MentionNumber.Singular)
        Some(
          NominalMention(
            det.getOrElse(Determiner.Bare),
            mods.map(Lexical.stem),
            Lexical.stem(irregularPlurals.getOrElse(headWord, headWord)),
            number,
            words.mkString(" ")
          )
        )

  /** One token per nonempty sorted modifier subset joined with the head, plus the bare head only
    * when there are no modifiers. Modifier count is capped at 4 (15 keys) to bound growth.
    */
  def keysOf(modifiers: Vector[String], head: String): Set[String] =
    val mods = modifiers.distinct.sorted.take(4)
    if mods.isEmpty then Set(head)
    else (1 to mods.size).flatMap(k => mods.combinations(k).map(c => (c :+ head).mkString)).toSet

  /** Two mentions may co-refer only if their numbers agree and no modifier of one contradicts the
    * other (a modifier present on both sides with a different value counts as a conflict only when
    * both have modifiers and share none).
    */
  def compatible(a: NominalMention, b: NominalMention): Boolean =
    a.head == b.head && a.number == b.number &&
      (a.modifiers.isEmpty || b.modifiers.isEmpty || a.modifiers.exists(b.modifiers.contains))
