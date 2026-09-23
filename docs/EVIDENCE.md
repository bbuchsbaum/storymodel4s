# Evidence discipline (AGENTS.md rule 4)

Moved verbatim from `AGENTS.md` at `50a07f69` on 2026-09-23 (owner directive: refactor
AGENTS.md). Nothing here was reworded; the short current form of these rules lives in
`AGENTS.md`, which links here. Rule 4 applies in every operating mode.

4. **Evidence discipline.** *Thirty-five sub-rules; find yours here.* **What
counts as proof of a fix (12):** a control must fail for the property under
test · mutation proof · capacity to fail ·
distinguishability · a negative compile-time assertion needs a positive control ·
or kill it with a mutation, which is stronger · a fixture must tell the
hypotheses apart · prefer a fixture derived from the pipeline over one
synthesised at the consumer · check a fixture's inputs are in the form the
pipeline produces · an inequality is not a discriminating assertion · a pinned
literal can be updated but a behavioural assertion has to be argued with · a
positive control requires the old code to be capable of the thing tested. **How
to run the gate (16):** never pipe a gate · the gate is not the last step, re-read
the board before merging · an author's own gate is LocallyObserved, not
verified · never put a message body in an inline double-quoted shell argument · a check and the action it gates must
not share a batch · a trailing success line is not an exit
status · a compile error anywhere makes the gate inconclusive · run the format
check last · verify the export before you gate it · a compile-time probe needs a
clean recompile · a gate with no test totals did not run · a shell equality test
over two failed command substitutions passes · a backgrounded gate's exit status
describes the fork not the run · a shared-tree gate compiles other agents'
untracked files · never head a list whose length is the finding · `>` inside `[ ]` is a redirect.
**How to scope a finding (4):**
sweep the shape not the spelling · sweep your own work first · reading a branch establishes permission
not occurrence · trace the consequence. **What a number may claim (3):** the
estimand check · no value and low support are different failures · dropping a
term from a normalized aggregate rescales the rest. *(Twelve of the thirty-three are
about whether a test can actually fail, and FOURTEEN are about whether the gate
measured anything at all — that second group went from three to fourteen in a
single day, every entry added after a run reported a status with nothing behind
it, and the last of them after a run reported SUCCESS with nothing behind it.
On 2026-08-29 alone, THREE separate red gates measured infrastructure rather
than a candidate: a truncated archive, a linked worktree, and a directory name
used as an sbt project id. This index is
load-bearing and it ROTS: it was stale by five before 2026-08-29, was corrected
that day, and was stale by four again within two hours because the same person
who wrote "if you add one, re-count" added four without re-counting. Re-count
mechanically; do not trust the number above without checking it.)*

**A finding has a CHAIN, and reporting one link as the whole chain is the most
common way work here has been wrong.** Four questions, each answered by a
different method, each capable of overturning the last:

| link | question | method |
|---|---|---|
| **permits** | can the code produce this? | read it |
| **occurs** | does it actually happen? | measure the corpus |
| **produces** | what comes out when it does? | **run it** |
| **consumes** | who depends on that output? | trace forward |

Measured against the chief's own rulings on 2026-08-29, all four caught by other
agents: `trajectory.scala` was ranked worst-placed on a consumer that does not
exist (*permits*, never traced *consumes*); the WOG segmenter override was sized
as systemic when it fires for one paraphrase of ten (*permits*, never measured
*occurs*); three NaN guards were called live when one is reachable (*permits*,
never traced *occurs*); and that one reachable site publishes a degenerate
all-zero plan rather than the `NaN` predicted (*occurs*, never ran *produces*).
**Each correction moved one link further along a chain the chief had not
walked.** Severity is a property of the whole chain: a defect that is permitted,
occurs, produces garbage, and is consumed by a published figure is a different
thing from one that stops at any earlier link. Say which link you reached.

**The one principle under most of what follows: READ THE ARTIFACT, NOT THE
STATUS.** A step that reports success while having done nothing — or part of a
thing — is the single most common way work here has gone wrong, and it has
appeared in four different tools in one day: `mote discuss post --body -` printed
*"posted"* and stored a hyphen; `sbt console` with redirected input exited 0 and
executed nothing; a `scalafmtCheckAll; compileAll; testJVM` chain returned an exit
status with **zero tests** behind it; `git archive | tar` truncated by a full disk
exited clean and left a directory that still looked like a project. In every case
the status was green or explicable, and the artifact — the body length, the test
totals, the log contents, the directory size — was obviously wrong the moment
anyone looked. Several sub-rules below are this principle wearing different
clothes. If you remember one thing from rule 4, remember to look at the thing that
was produced rather than the report that it was produced.

A passing test suite is not evidence; it is the
absence of one kind of counter-evidence. Three checks, each earned by a
defect that a green suite did not catch:

*Mutation proof.* If a candidate adds a guard, delete the guard, show a test
goes red, restore it. "The guard exists" and "the guard is load-bearing" are
different claims.

*Capacity to fail.* Assert the preconditions of a test's own meaningfulness
before asserting content — that the probe set is large enough, the corpus
non-empty, the rendering non-trivial. A leak canary that probed whole
sentences missed a six-word slice straddling two of them; a metric that is
always `Missing` passes a no-failures check forever.

*Estimand check.* For every number the code publishes: name what it claims to
measure about the subject; say where **our own uncertainty** goes — numerator,
denominator, abstention, or silently nowhere; and ask whether that number
moves in a consistent direction when our uncertainty correlates with a
property of the subject (vagueness, disorganisation, length). A yes to the
last is **P1 by default**: it can manufacture a group difference that is not
there. Unresolved placement scored as an external detail, unranked mass
summed into `externalMass`, and absent admissibility scored as gate
compliance were all found this way, and none failed a test. Note also that
the naive repair — dropping the uncertain observations — replaces one bias
with its mirror image: carry the uncertainty through the arithmetic instead.

*Sweep the shape, not the spelling.* When you find one instance of a defect
class, sweep for its **shape** across the codebase, not its literal text. A
fix for a chronology default missed an identical defect nine lines away
because the search was for `.getOrElse`; sweeping four shapes instead — `if
X.isEmpty then <literal>`, `.getOrElse(<numeric>)`, `math.max(1, n)` as a
denominator, vacuous `forall`/`exists` in a scoring position — surfaced
several more instances that a string search had missed. (The sweep's most
dramatic hit, a relation-preservation prior said to bias the aligner, was
**retracted**: the defaulting function has one caller and it is a test. See
*Trace the consequence* below — that retraction is why this paragraph no
longer cites it.)

*Sweep your own work first.* When a review catches a shape in **your** code,
sweep your own work for that shape **before** fixing the instance: the
reviewer found one, you own the rest. The sweep instinct fires readily on
other people's code, where a defect is a discovery, and poorly on your own,
where it is a correction. Observed three times in five hours on one shape —
case-class machinery defeating a private constructor — met once as a
hand-written reflection filter, once as a reviewer's block, and only then
generalized, with two more instances sitting two files away in the same
commit. **This binds reviewers hardest.** A defect you ratify a one-site fix
for is a defect you have declined to sweep; the chief did exactly that here,
and 45 forgeable types stayed open until someone walked into one by hand.

*No value and low support are different failures.* **No value** means the
term cannot be computed and any substitute is invention — **refuse**. **Low
support** means it *was* computed, from little evidence — **publish it with
the evidence**, because refusing discards a real measurement. Most defects
found here have been the first mistake, which primes everyone to see
invention everywhere; the over-correction is a library that abstains whenever
support is thin, and that is not more honest, it is differently dishonest —
it withholds real evidence and leaves the researcher with nothing. Where an
aggregate's support can only be computed over *some* of its terms, report the
minimum over the terms that carry support **and** the count of contributing
terms that carry none: a support figure covering four of eleven terms is a
different claim from one covering eleven.

*Dropping a term from a normalized aggregate silently rescales the rest.*
Excluding unplaceable mass from a distribution does not remove its influence
— normalization redistributes it over the survivors, so a detail half of
whose mass could not be placed starts contributing a **whole** detail's worth
of counts. Scale the item's weight by the fraction actually retained, so
counts track evidence rather than proportion. On the Birthday fixture the
difference is 49.0 expected detail-counts renormalized against 33.7 honest: a
third of the account's counted detail manufactured from mass nobody placed.
**The dangerous part is that it looks like a fix** — it is the natural repair
for over-counting, and it replaces one bias with its mirror image while
appearing to remove it. Encountered twice: once in a chief-prescribed fix
caught in review before it was written, once inside the very candidate
correcting the bias it mirrors.

*An inequality is not a discriminating assertion.* `!=`, `<=`, and bounds
generally can be satisfied by **both** the correct implementation and the
mutant, so a suite built on them records kills it never made. The fix is to
**recompute the expected value independently** — a different expression over
the same inputs — and assert equality against it. Observed on mass-weighted
fidelity: `conditioningMass != unitCount` and `conditioningMass <= sourceMass`
both passed under the mutation, because the surviving-unit count was 2.0 and
the source mass 2.63, an inequality that happens to hold. The assertion that
kills it recomputes the denominator as the mass of every anchored state whose
assessment specified at least one facet. Its author reports this as the sixth
such survival in one session.

*A fixture must be able to tell the hypotheses apart.* When you replace one
formula with another, the fixture has to be one on which the two **disagree**
— otherwise the mutation survives, the suite is green, and the evidence post
records a killed mutant that was never killed. Observed on the compression
estimand: reverting ratio-of-sums to mean-of-ratios survived, because the
assertions checked only that the value abstains without conditioning mass,
which *both* formulas satisfy; the two diverge only when units carry
**unequal** source mass. The killing assertion is that the conditioning mass
equals the summed source mass and not the row count (2.629, not 4.0). This is
protocol law **I6** — a set must be able to falsify the model — applied to
unit-test fixtures rather than benchmark corpora.

*THE GATE IS NOT THE LAST STEP — RE-READ THE BOARD IMMEDIATELY BEFORE THE
MERGE COMMIT.* A gate takes minutes and reviewers work in parallel, so a hold
can arrive INSIDE your gate window. Measured 2026-08-29: a reviewer posted an
exact-SHA HOLD at 20:40:24 and the chief merged that candidate ninety seconds
later, having checked the board BEFORE gating and not again after. The hold was
correct and the defect went to main — `GraphHsmm.infer` returning `Left` on a
lawful weighting, found by a runnable probe no test in the suite constructs.
**A 950-test green gate is evidence about the code, not about the board.**
Checking before you start tells you what was known then; the merge needs what is
known now.

*And re-read for what changes the CLAIM, not only for what withdraws
PERMISSION.* The failure above is a hold arriving inside the gate window. On
2026-08-30 the chief hit the same window with nothing forbidden: m1 posted at
13:52:44 — addressed to the chief by name — that two ancestor rows had just
been refreshed clean and "it changes what your merge commit should say"; collab
posted the same correction independently at 13:54:13; the chief committed at
13:54:51, having read the board before gating and not after. Nothing was
blocked, the gate was green, every substantive claim in the message was true,
and the merge was still WRONGLY DESCRIBED: it announced three overridden
ancestor reasons when those reasons had already dissolved and the rows only
needed attesting — which the chief then did anyway, forty minutes later. A
scan for holds would have passed. **A merge commit makes claims, and the board
can falsify a claim without revoking a permission**, so the question on the
re-read is not only "may I still merge" but "is what I am about to write still
true". Correct a shared commit's message with `git notes` rather than a rewrite;
ad3f8c2 carries one.

*A GATE RUN BY THE AUTHOR IS `LocallyObserved`, NOT VERIFIED.* Until 2026-09-17
there was no CI in this repository — no `.github/workflows` — so every gate result
was self-reported by the actor who wanted the merge, and "the tests passed" and
"an agent reported that the tests passed" are currently the same artifact.
Found 2026-08-30, by accident, in a documentation candidate's build inventory;
nobody was looking for it. **The fix is not to wait for CI.** An independent
actor's clean reproduction, bound to the exact SHA, command, platform,
configuration, exit status and output digest, is a Confirmed-class receipt and
needs no workflow file — `codex-storymodel-collab` had already been producing
them ("I independently exported the immutable commit and ran
`scalafmtCheckAll`: exit 0"). So: an author's own gate is REQUIRED and is
EVIDENCE, but it is `LocallyObserved`, and a merge commit must not word it as
though someone else had checked. **A candidate whose evidence will be cited
publicly** — in the docs site, a published figure, an ADR claim — **needs an
independent reproduction before that claim leaves the repository**, though not
before it merges. Routine merges keep the self-reported gate and SAY SO: what
changes is the label, not the workload, and the label is the point. When CI
arrives it is not magical authority either — name its workflow revision,
dependencies, platforms, logs and a failure-sensitive test, because a green
badge detached from the claimed SHA is no better than board prose. CI arrived
2026-09-17: `.github/workflows/ci.yml` (generated by sbt-typelevel; regenerate with
`sbt githubWorkflowGenerate` after touching the build, `githubWorkflowCheck` fails
the run otherwise) runs the JVM/JS/Native test matrix on Temurin 17 and 21 with
fatal warnings on, and `docs.yml` runs the docs-site gate. A CI run is evidence only
when cited by run URL, matrix cell and the SHA it built.

Corollary, measured 2026-08-30 within hours of the rule: **A BOTCHED
REPRODUCTION LIBELS THE AUTHOR.** `claude-storymodel4s-m1`'s first attempt to
reproduce `a0cf33d` checked the SHA out as a DETACHED HEAD inside a linked
worktree and produced no `Passed: Total` line at all plus two scalafmt errors,
under `jgit MissingObjectException: Missing unknown a0cf33d` and an
interactive `Project loading failed: (r)etry, (q)uit`. Read carelessly that is
"the chief's gate does not reproduce and the tree has format errors" — a
verdict about another actor's work, published under the authority of an
independent check. The second attempt, in a clean clone, matched 228 exactly.
A reproduction failure is the ONE result that must never be reported before
the reproducer has proved their own setup: apply the zero-totals test to
yourself FIRST, and when a reproduction disagrees with the author, say what
you ran and where before you say what it means.

*NEVER PUT A MESSAGE BODY IN AN INLINE DOUBLE-QUOTED SHELL ARGUMENT.* Backticks
inside double quotes are COMMAND SUBSTITUTION, and every board post we write is
full of `identifiers` in backticks. Measured 2026-08-30, by the chief, on a
post four actors were notified about: `mote ready` EXECUTED and its output — a
list of ready beads — was spliced into the middle of a sentence, while
`doing` failed with "command not found" and left THE EMPTY STRING, deleting the
word twice. The result still parsed and read as sloppy writing rather than as a
machine eating two words. Two hazards, and the second is worse: it ran a
command that was never intended to run (harmless here; the mechanism does not
care), and its failure mode is DELETION rather than an error, so a corrupted
message looks like a clean one. Write the body to a file and pass `--body
"$(cat file)"`, the tool's stdin form, or `git commit -F` — the same call whose
post was corrupted had a perfectly intact commit message, because that one came
from a file.

*A check and the action it gates must not run in the SAME BATCH.* Piping
destroys the exit status; batching destroys the DECISION. If the format check,
the commit and the push are one command sequence, there is no point at which the
check's answer can change what happens next — you read the failure after the
push has already landed. Measured 2026-08-29, by the chief, on a commit whose
subject was "NormTolerance states the precision it defends": `scalafmtCheckAll`
returned 1 and main took the unformatted file anyway, four minutes before the
repair. The same batch also released the reservation, so repairing an unverified
push required re-reserving a file already given up. **Run the gate, READ IT, then
act** — and never release a hold in the same breath as the work it protects.

*Never pipe a gate.* In a shell pipeline the exit status is the **last**
command's, so `sbt -batch "..." | tail` reports `tail`'s status — which
succeeds even when the build fails — and a line-limited pipe silently
discards most of the evidence. Redirect the whole run to a file, report the
unpiped command's own exit status, and `grep` the file for the summary. An
`exit 0` in an evidence post means nothing unless the command producing it
was unpiped. (Caught here by comparing against a previous run: three
`Passed:` lines became one, for candidates differing by a trimmed test file.
Keep the prior gate's numbers visible for exactly this reason.)

*Verify the export before you gate it.* An export is an INPUT to the gate, not
infrastructure that either works or crashes — it does neither. `git archive | tar`
truncated by a full disk exits clean and leaves a directory that still looks like
a project; sbt loads the partial tree happily, and every failure after that is
attributed to the code under test. Measured 2026-08-29: a merged-tree gate
returned `GATE_EXIT=1` with **311 error lines** on a sound candidate, because the
export held 3 of 27 top-level directories. The tell was the FIRST error —
`FileNotFoundException` writing a JUnit report XML, a shape no Scala defect
produces. After extracting, assert the entry count and that `build.sbt` exists,
before starting sbt. Two seconds, and it separates "the candidate is broken" from
"I never gave the compiler the candidate."

*Run the format check LAST.* sbt's `;` aborts the chain on first failure, so
`scalafmtCheckAll; compileAll; testJVM` lets a whitespace nit destroy the
correctness signal for everyone. Formatting stays IN the gate — nothing lands
unformatted, and the captured exit status still covers the whole chain — but it
belongs at the end. Measured on one candidate, same tree, order the only
variable: format-first gave `GATE_EXIT=1`, 7 errors, **zero** test totals and no
information; format-last gave the same exit status and the same 7 errors *plus*
17 suite totals and 1355 passing tests, which said the candidate's logic was
sound and only its whitespace was not. Same verdict, one of them actionable.
Note this is the *inconclusive* rule above arriving through chain ORDER rather
than through anyone's broken code — a rule you have just written is at its least
useful when you believe you already comply with it.

*A compile error anywhere makes the gate INCONCLUSIVE, not partially clean.*
`compileAll` aborts before any test runs, so a green-looking partial log means
nothing was measured — a runtime break in an unrelated module stays hidden
behind it. Never report "module X passed" from a run whose compile failed
elsewhere; the honest word is *inconclusive*, and the remedy is to fix the
compile and re-run, not to read around the error.

*And a trailing success line is not an exit status.* Redirecting to a file is
only half of it; if you do not actually record the status, the log's last line
is what you are left with, and that line lies in a specific way. sbt prints
`[success] Total time: Ns` for **each command in the chain**, so a run killed
after its last test suite ends in exactly the text a completed run ends in.
Zero `[error]` lines do not close the gap either — a run that never reached a
failing suite has none. Append the status to the log itself
(`... > log 2>&1; echo "GATE_EXIT=$?" >> log`) and certify on that marker, not
on the shape of the tail. Caught on slice 7: the first run showed 17 suite
totals, zero errors and a trailing `[success]`, and was **unverifiable** —
re-running it in the same export with the status captured gave `GATE_EXIT=0`
and 1295 tests. Same result, but only the second one was evidence.

*A negative compile-time assertion needs a positive control.* `typeChecks`
returns `false` if the snippet produces **any** error and does not say which,
so `assert(!typeChecks(...))` can pass for a reason unrelated to the property
under test — and keep passing after a mutation that should break it. Put a
positive control in the same file and scope: assert `typeChecks` is **true**
for a type that certainly has the property. If the control fails, every
negative assertion beside it is meaningless. **The control must match the
shape under test**, not merely be some type with the property — a control
that differs structurally can fail for an unrelated compiler-shape reason and
tell you nothing. Observed on slice 5: only a control matching a *live
private-constructor case class* showed the probe could see the actual
`fromProduct` door. Use `typeCheckErrors` to read
the actual message when diagnosing. (Same shape as the `copy` control:
"`PopulationAggregate` has zero `copy` methods" proved nothing until
`SubjectAlignment` in the same compile showed five. An absence proves nothing
without a present case beside it.)

*A control must fail when THE PROPERTY UNDER TEST is broken — not merely when
something is broken.* "Able to fail" is necessary and not sufficient. A control
that dies when you break something ADJACENT looks exactly like a control that
works, and keeps looking that way until someone mutates the specific property
by name. Measured 2026-08-29: a chart-eligibility candidate shipped a positive
control that a reviewer killed with `val structuralEligible = false` — and THE
TEST STAYED GREEN, because term production happens BEFORE eligibility, so the
term was still present and the totals still differed. The test proved provider
EXECUTION, not ELIGIBILITY. It was not vacuous; it discriminated something real,
just not the thing it was named for, which is far harder to see than a test that
always passes. **Reading the test cannot find this** — the assertions look
correct and they are, about something else. Mutate the property BY NAME, and if
the control survives, it is a control for a different property.

*A positive control requires the OLD CODE TO BE CAPABLE of the thing being
tested.* Where the fix INTRODUCES the capability, no positive control exists —
only a regression guard, and the two must not share a name. Measured
2026-08-29: the chief required a law stating that dropping an unmeasurable term
must not change which state wins, and justified it as "today's code fails this,
so it is a positive control rather than a hope." Today's code has NO ELIGIBILITY
CONCEPT, so "drop a term that could not be measured" is not an operation the
unfixed model can perform and no arm of it can fail. The engineer wrote the law
twice — once VACUOUS (it passed with the scaling disabled, found only by
mutating) and once TOO STRONG (it failed on the correct implementation, and
should have: a unit that supplies a sensory term and gets it wrong ought to cost
more than one that never claimed sensory content, and the law as specified would
have made the term useless) — then reported that the specification could not be
met instead of adjusting it until it passed. **Ship the regression guard and say
in the test that it is one.** A regression guard wearing a positive control's
name is how a suite acquires authority it has not earned.

*Or kill it with a mutation, which is stronger.* The control is a PROXY: it
asks "would this snippet compile if the door were open?" by compiling an
equivalent snippet against a forgeable stand-in. A mutation asks the same
question **directly, of the real type** — open the door and watch the probe go
red. So a demonstrated mutation kill SATISFIES this rule and the control is
the substitute for when you cannot mutate. **But a mutation kill counts only
if the mutant COMPILES and the failure is the NAMED ASSERTION rather than a
blanket red** — a mutation that breaks the module turns every test in it red,
and "the probe failed" then carries no information whatever. The signature to
report is the discriminating one: the mutant compiled, the sibling test in the
same suite still PASSED, and the failure cites a line. One failed and one
passed is evidence; all red is not. Measured on the three slice 7 phantom
witnesses, whose only structural control was
`summon[Mirror.ProductOf[(Int, String)]]` — a tuple, which always has a Mirror
and so cannot fail for any reason connected to the type under test. Minimal
mutation `final class` → `final case class`, clean recompile per module:
AmrGraph → suite:27, StoryModel → suite:23, PropositionChart → suite:28, each
1 failed / 1 passed / 2 total. Weak control, live probes — which is exactly
why the mutation is worth running rather than reasoning about.

*Check a fixture's inputs are in the form the pipeline produces before pinning
a literal from it.* A pinned expectation is only as good as the shape it was
computed from, and a fixture that feeds the code a shape production never
emits produces real numbers that measure nothing. Three separate defects came
from this one thing: unstemmed lemmas where production stems, text without the
punctuation its span includes, and point masses in a module whose subject is
distributed mass.

*Prefer a fixture DERIVED from the pipeline over one SYNTHESISED at the
consumer.* A synthesised fixture defaults to the easy shape, because the easy
shape is what you reach for when the fixture is not the thing you are testing.
A derived one inherits the real distribution whether you were thinking about it
or not. Measured across two modules: `interview` tests call
`Distribution.point` 20 times and `Distribution.of` 4 times — and three of
those four are in `InterviewSuite` testing `Distribution.of`'s OWN validation
(that it rejects zero and negative mass), leaving ONE that builds a real
spread. `InterviewProfileSuite`, which scores every profile metric, had zero
spread-mass cases. `align`, whose fixtures come from 8 `GraphHsmm.infer` calls,
carries spread rows for free. So an entire module whose subject is DISTRIBUTED
PLACEMENT was scored almost exclusively on point masses, and every defect that
appears only when mass is spread was structurally invisible — not missed, but
unseeable, and green the whole time. **When you must synthesise — and sometimes
you must, to isolate — synthesise the AWKWARD shape**, because the easy one is
what you will write by accident.

*A gate that produced no test totals did not fail — it did not RUN.* A
non-zero exit is not evidence about the code until you have found a test count
behind it. Measured twice on 2026-08-29, both on sound candidates: a truncated
`git archive | tar` under a full disk reported `GATE_EXIT=1` with 311 errors
from 3 of 27 directories, and a gate run inside a LINKED GIT WORKTREE reported
`GATE_EXIT=1` with zero totals because sbt-git's jgit call sees a worktree's
`.git` FILE as a bare repository and project loading fails before compilation
starts. Neither result said anything about the candidate; both looked exactly
like a failing test suite. **Gate in a clone, never in a linked worktree**, and
before you report a red gate, grep the log for `Passed: Total` — if there is
none, you have measured your own infrastructure.

*A gate in the SHARED TREE compiles other agents' untracked files, so its exit
status is not about your commit.* `compileAll`/`testAll` build the working tree,
not the index and not the commit — every untracked `.scala` another session has
left in a source directory enters the run. A green result therefore certifies
"the commit plus whatever else was lying there", and a red one may be somebody
else's half-written file. Reported 2026-08-29 by an actor who ran JVM/JS/Native
to exit 0 and **disclosed the run as non-exact** because untracked NFRD sources
had entered it, rather than reporting a clean green — that is the standard. Check
with `git status --short` for untracked sources under any module you are gating;
if there are any, either gate in a clone or state the contamination in the
evidence post. Exact-SHA evidence REQUIRES a clean tree, and this is a second
reason the clone rule above is not merely about worktrees.

*Never `head` a list whose LENGTH or COMPLETENESS is the finding.* If the question
is "how many" or "is X absent", truncation MANUFACTURES the answer — and it
manufactures the reassuring one, because what falls off the bottom is by definition
what you did not see. Count first (`grep -c`), then display. Measured twice on
2026-08-29 by the same person in one session: a contamination incident reported as
**9** files under `git show --stat | head -12` when it was **13**, and eight hours
later a candidate declared review-satisfied under
`mote candidate show … | head -6` when `review_missing` was line **7** — which
nearly merged two candidates on a review status never actually read. The second one
happened AFTER the first had been written up as a rule, which is the argument for
putting the mechanism here rather than trusting the memory of it.

*The exit status of a BACKGROUNDED gate is the exit of the backgrounding, not of
the work.* `nohup sbt ... &` returns 0 the instant the fork succeeds, so "the gate
passed" and "the gate was successfully LAUNCHED" render as the identical value.
Measured on 2026-08-29: a Native gate exceeded a foreground timeout, was relaunched
in the background, and was reported COMPLETED, EXIT CODE 0 while sbt was still
mid-optimisation — no totals, no `[success]`, zero errors, exit 0. The only evidence
is a `Passed: Total` line PER MODULE plus a terminal `[success]`/`[error]`, and you
must confirm the process has actually ended before reading either; poll the log for a
terminal line rather than trusting the launcher's status. This is the sub-rule above
in its most dangerous form: there, a bad exit code looked like a failing suite; here,
a good one looks like a passing gate, which is the direction you are hoping for and
therefore the one you will not question. Native is the platform slow enough to make
backgrounding tempting, so this will be met there first.

*A shell equality test over two command substitutions PASSES when both of them
fail.* `[ "$(cmd_a)" = "$(cmd_b)" ]` compares two empty strings and succeeds, so
a verification step reports agreement precisely when it learned nothing. Measured
on 2026-08-29 in this repo's own gate scaffolding: a check that the merged tree
equalled the candidate tree printed a confident `YES` while every `rev-parse` in
it had failed with `cannot change to ...: No such file or directory`. This is the
fail-open guard of rule 7 wearing shell syntax, and it is worse than the ones we
hunt in Scala because nothing type-checks it. **Assert non-emptiness before
comparing** — `[ -n "$a" ] && [ -n "$b" ] && [ "$a" = "$b" ]` — and prefer `set
-o pipefail` plus explicit exit checks in any script whose OUTPUT IS A VERDICT.

*`>` and `<` inside `[ ... ]` are REDIRECTS, not comparisons.* `[ "$x" > 0.0 ]`
reduces to `[ "$x" ]` — true for ANY non-empty left operand — and silently creates
a file named `0.0` in the working directory. So a numeric threshold written this way
is a guard that CANNOT FAIL, and its only symptom is an unexplained file named after
its own right-hand side. Verified 2026-08-30: `[ "5" > 0.0 ]` and `[ "0" > 0.0 ]`
both return true and both create `./0.0`, while `awk 'BEGIN{...}'` correctly reports
`0 > 0.0` as false. Diagnosed by claude-storymodel4s-m1 after a zero-byte `0.0` sat
unexplained at the repo root for six hours, visible in every `git status` any of us
ran. Use `awk 'BEGIN{exit !(a>b)}'`, `bc -l`, or `[ "$x" -gt "$y" ]` for integers.
Third member of this family, with the two above: a shell construct that looks like a
test, is not one, and fails toward "pass".

*A pinned literal can be updated; a behavioural assertion has to be argued
with.* When a model change moves a number, a test pinned to that number tells
you only that it moved — and re-pinning is the normal, correct response, which
is exactly the problem: re-pinning an improvement and re-pinning a regression
are the same edit. A behavioural assertion states the CLAIM rather than the
value (`"'Stephen King' goes to the Association external state, not to a source
node"`), so it cannot be re-pinned — making it pass again means writing down
something false, which is a decision instead of an edit. Measured on the `d_ent`
candidate: four failures, TWO pinned literals (chronology `0.039516 → 0.104070`,
specificity `0.678220 → 0.659371`) and TWO behavioural. The implementing
engineer states plainly that with only the two literals it would have updated
them and shipped — and would have shipped a cost model in which an unmeasurable
term acts as a DISCOUNT, making participant-less units cheaper to anchor
everywhere. The two behavioural assertions are the whole reason it did not land.
**Credit the fixture, not the engineer**: the stop was caused by a test written
in the right shape long before, by someone who was not there. So when you pin a
number, ask what claim the number stands in for, and assert THAT beside it.

*Reading a branch establishes permission, not occurrence.* That the code
CAN produce a bad value is a different claim from that it DOES, and the
second one needs a measurement. A finding derived by reading a branch must
state whether the branch FIRES — and if that is unknown, say so in the
finding itself, not in a footnote. Otherwise it is a mutation surviving
because the corpus cannot reach it, wearing the costume of a finding.
Measured: a P1 escalation derived the exact masses a `PlacementBasis.Ambiguous`
branch would emit (0.4 / 0.3 / 0.3) and called them incoherent; across four
induction fixtures and 33 details the incoherent set was **zero** and the
branch never fired at all, real distributions being concentrated
([0.08 0.12 0.80], [0.11 0.89]). What survived was narrower and true: the two
thresholds do measure different quantities and the code permits them to
disagree. What could not survive was that it happens. Note the better finding
hiding inside the retraction — **no fixture reaches that branch**, which is
worth more than the claim it replaced, because an untested branch is a real
gap where a mis-read one is only a wasted hour.

*A compile-time probe needs a clean recompile.* A mutation that changes a
**type's shape** — `case` to non-`case`, a constructor's visibility, or
anything a compile-time assertion inspects — must be proved after a **clean**
recompilation of the module. `typeCheckErrors` and friends are compile-time
macros, and Zinc may reuse stale test bytecode across such a mutation: the
probe never re-runs, the suite goes green, and the author records a killed
mutation that was never tested. A false green in the exact test whose job is
to prove the boundary holds. State in the evidence post that the recompile
was clean; "mutation killed" without it is not accepted for a compile-time
probe. (Chief gates satisfy this by construction — they run from a fresh
`git archive` export with no target directories — so the hazard belongs to
authors gating in a warm worktree.)

*Trace the consequence.* A claim about what a defect **feeds** — what depends
on it, what it corrupts downstream — is a claim about the call graph, and the
call graph is cheap to check. Do not escalate a consequence you have not
traced. A finding that matches an already-confirmed pattern needs **more**
verification than one that does not, not less: it arrives feeling
pre-validated, which is exactly when the check gets skipped. This rule exists
because the chief escalated a pattern-matching finding to P1 above all other
work without running one `grep` for its callers.

*Distinguishability.* Where a test separates a correct value from a specific
wrong one, assert that the two differ by more than the comparison tolerance.
Capacity to fail is not enough: inputs whose right and wrong answers fall
within `eps` produce a test that passes under both the fix and the defect
while looking rigorous.
