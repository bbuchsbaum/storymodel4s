# Exact caption correspondence

Scala and Python read the nine cases in `cases.json`. Each part names a distinct
physical source with its own tick extent. With the caption blend set to one, the
Scala probe queries the bound session with AA and BB to observe which caption
actually scored each target. The fixture records the matches or the reason for
refusal. Both implementations normalize touching intervals into one extent.
A gap or an explicit point remains observable.

Run the JVM witness from a standalone clone with the port evidence available.

```sh
mkdir -p codec/src/test/scala/storymodel4s/align
cp port/evidence/caption-parity/CaptionExactParityProbe.scala codec/src/test/scala/storymodel4s/align/
sbt "codecJVM/Test/runMain storymodel4s.align.CaptionExactParityProbe $PWD/port/evidence/caption-parity/cases.json"
```

The reference run used Scala source `3171f5b07`. It produced all nine expected cases.
The Python regression `test_native_caption_parity_cases` reads the same inputs and expected
outputs. The saved Sherlock example also exercises the intake-to-account row join and
scores the resulting candidates.
