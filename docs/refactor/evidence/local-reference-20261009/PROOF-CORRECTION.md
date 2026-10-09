# Named assertion qualification correction

The first e0d6 campaign's permissive driver treated any named runtime failure as a kill.
The admitted-target-population mutant instead failed via ClassCastException when an unexpected
Computed row reached a test helper. That does not satisfy the project's named-assertion criterion.
The complete original logs/patches/metadata will remain archived without claiming17 qualified kills.

Commit cd740302 adds an explicit expected-outcome-type assertion before that cast and makes the
driver require an assertion failure. All witnesses are rerun on that exact updated source.
The producer algorithm is unchanged. mutation-driver-original-reconstructed.py reverses the
single recorded assertion-classification edit to preserve the initially executed driver shape;
it is identified as a reconstruction, not an independently captured original.

Experiment07 attempted to reach retained empty eligibility but produced excluded targets. It
is not counted as a witness. Independent source review establishes that UnmeasuredTargetCost is
defensive/source-excluded under today's canonical producer; no branch-execution claim is made.
