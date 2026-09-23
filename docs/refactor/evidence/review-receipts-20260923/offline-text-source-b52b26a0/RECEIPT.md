# Independent review receipt: solo/offline-text-source@b52b26a0 (claude-sm-0923, 2026-09-23)

Export: `git -C .worktrees/recall-timing-portable archive b52b26a0 | tar -x -C b52b-export`, which had 39 top-level entries and build.sbt present.
Build: `sbt -batch -Dstorymodel4s.grakern.build=~/code/scala/grakern "coreJVM/compile" "export coreJVM/Runtime/fullClasspath"` exited 0.

1. `javac -cp "$CP" Diff.java && java -cp "$CP:." Diff 300000 wide.tsv`
   - Diff.oldCanon is the pre-b52b26a0 regex chain, run by the JVM regex engine. It is compared with the compiled storymodel4s.core.StorySource.canonicalize.
   - Output: `cases 300000 new-vs-old-JVM mismatches 0`
2. The Python mirror was imported from b52b-export/tools/check_text_source_exchange.py; canonical_text was compared with the new Scala output recorded in wide.tsv.
   - Output: `cases 300000 python-vs-new-scala mismatches 0`
3. `sbt -batch ... "coreJVM/testOnly storymodel4s.core.TextSourceSuite"` exited 0 with `Passed: Total 16, Failed 0`, including the frozen-reference digest test.
4. Earlier finding at ee076a47: Canon2.java produced 20000 cases (seed 20260923, 8-char alphabet, length 0-8). The Python mirror at ee076a47 had 363 mismatches; with \Z anchors it had 0.

Space covered by step 1: seed 7, lengths 0-20, alphabet {space, tab, \n, \r, U+0085, U+2028, U+2029, a, b, VT, FF, NBSP, ZWSP, BOM, U+3000}.
Not covered: JS and Native (your cross-platform gate).
