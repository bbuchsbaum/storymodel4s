/** Standalone Scala Native runtime witness; contains no StoryModel dependencies. */
object ZeroHashProbe {
  def main(args: Array[String]): Unit = {
    val cases = Array(
      ("empty", "", 0),
      ("dynamic-nul", new String(Array(0.toChar)), 0),
      ("dynamic-zero-ascii", new String(Array('f', '5', 'a', '5', 'a', '6', '0', '8')), 0),
      ("ordinary", "abc", 96354),
      ("negative", "polygenelubricants", Int.MinValue),
      ("literal-nul", "\u0000", 0),
      ("literal-two-nuls", "\u0000\u0000", 0),
      ("literal-zero-ascii", "f5a5a608", 0)
    )
    val selected = if (args.isEmpty) cases else cases.filter(c => args.contains(c._1))
    require(selected.nonEmpty, "select at least one known case")
    selected.foreach { case (name, value, expected) =>
      println("START " + name)
      var iteration = 0
      while (iteration < 10) {
        assert(value.hashCode == expected, name + " hash")
        iteration += 1
      }
      val copy = new String(value.toCharArray)
      assert(copy == value, name + " equal content")
      val map = new java.util.HashMap[String, Int]()
      map.put(value, 7)
      assert(map.get(copy) == 7, name + " hash map lookup")
      val set = new java.util.HashSet[String]()
      assert(set.add(value), name + " hash set insertion")
      assert(!set.add(copy), name + " hash set equality")
      println("PASS " + name)
    }
    println("Passed: " + selected.length + ", Failed: 0, Skipped: 0")
  }
}
