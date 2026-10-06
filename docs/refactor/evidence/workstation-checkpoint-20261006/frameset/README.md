# FrameSet recovery qualification

Mote `bd-01M37ESFCE1SZFTV7NHJEC7SNC`; exact source candidate
`49e61bca197238d3462789ac2565faeed72cdd33` recovers `69e48e9a` without its old ancestry.

Clean standalone clone, declared dependency pins, no local dependency override. Actual sbt
runtime was Homebrew Java 25.0.1 (the shell default Java command was a different installation).
Reference scope selects JVM media. Media: **61 passed, 0 failed, 3 live-tool skips**.
Additional pipeline: **120 passed, 0 failed, 0 skipped**. Strict fatal warnings and formatting
last passed. The source-pinned dependency checkouts were at their declared SHAs and clean.

The named pre-fix control swaps only frames.scala to the 12147bcc base, runs
`media/testOnly *SampledCaptionConsumerSuite`, then restores the tested source. The new
outside-module two-stream witness fails while its eleven siblings pass. Restored source recompiled and all twelve named-suite tests passed. This is a compiled,
named baseline control for stream-bound receipt/identity, not a live decoding qualification.

[Receipt](receipt.json) binds commands, actual exits, totals and raw-log hashes. Gzip logs retain
all output. The three media skips leave fresh external-tool decoding unqualified. Full baseline,
consumer pair, GitHub and workstation checks follow separately. Exact-SHA cold review follows.
