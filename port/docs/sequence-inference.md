# Sparse sequence inference

`infer_sequence` computes posterior state probabilities, adjacent transition flow, a best
path and a sequence log normalizer from declared numeric inputs. This is the first numerical
part of the Scala Graph-HSMM port. It accepts supplied transitions; it does not yet construct
them from account graphs or run the evidence mode gate, refinement or reconstruction.

## Run the connected example

From `port/`, choose a new output directory:

```sh
uv run python examples/sequence_workflow.py data/sequence-example
uv run storymodel infer-sequence data/sequence-example/request.json \
  --output data/sequence-example/cli-result.json
uv run storymodel verify-sequence data/sequence-example/request.json \
  data/sequence-example/cli-result.json
```

The example has two possible paths with equal total cost `1e20`, but prior probabilities
`0.75` and `0.25`. Both later units must retain those probabilities. It demonstrates numerical
behavior with abstract labels, not story evidence or mapping accuracy. The saved request
declares the complete state/cost/transition population; output creation refuses overwrites.

Python uses the same operations:

```python
from pathlib import Path
from storymodel import infer_sequence, verify_sequence

request = Path("data/sequence-example/request.json").read_bytes()
result = infer_sequence(request)
assert verify_sequence(request, result) == result
```

Construct typed inputs with `SequenceState`, `StateCost`, `SequenceRow`, `TransitionEdge`,
`SequenceTransition` and `SequenceRequest` from `storymodel.sequence`. The emitted
[schema](../schemas/sequence.json) describes both exchanged artifacts.

## Numerical contract

For a path through `n` ordered units, the unnormalized weight is
`(1 / number_of_initial_states) * exp(-sum(cost) / temperature) * product(transition_probability)`.
The normalizer sums these weights over all possible paths. Costs are finite signed energies;
temperature is finite and positive. They are not probabilities or calibrated evidence scores.

Each unit declares a nonempty set of distinct source, distorted or external states. Distorted
states retain their anchor and explicit facets. Unranked must be the sole state of its unit;
it is a declaration about available ranking evidence, not intrusion or a failed calculation.
The kernel checks state shape and topology, not anchor membership in a scientific account.
An account-specific producer with that responsibility remains subsequent work.

Every origin has an outgoing stochastic distribution over the next unit's declared states.
Sums within absolute `1e-12` of one are accepted and normalized by their actual sum. Duplicate
edges, foreign endpoints and missing distributions refuse. Absent and explicit zero edges
are impossible; neither appears in the sparse flow output. Possible edges with zero posterior
mass remain visible. All declared states remain in posterior rows, including unreachable ones.

The implementation uses standard-library Decimal arithmetic for log messages and cancellation,
with precision derived from input magnitude and path length. Log-sum-exp uses centered binary64
exponentials, `fsum` and `log1p`; the output is approximately binary64 precision, not arbitrary
precision. No state is discarded because its filtered mass would underflow: later observations
can make an expensive prefix competitive. Extremely small final probabilities may underflow
to zero. Absolute log weights outside binary64 range refuse explicitly rather than returning
infinity or claiming no path exists. Even within range, a huge absolute log normalizer may
round away a small correction; posterior probabilities are computed before that conversion.

State and edge enumeration order cannot change the numeric output. The request digest still
binds the exact supplied ordering. Viterbi uses the greatest Scala-style rendered predecessor
key, and then final-state key, when scores differ by at most `1e-28 * n` in absolute log weight.
Content identity breaks collisions in rendered keys. This is an explicit numerical tie rule,
not a guarantee of exact real-arithmetic comparison or globally lexicographic path ordering.

Inputs and copied objects are revalidated at public consumption. `verify_sequence` checks the
entire result by recomputing against a separately supplied request. A digest or internally
consistent result alone does not authenticate its source. CLI result export also checks the
request's producer grant. No output becomes a reference binding, fidelity grade or calibrated
confidence automatically.

## Evidence and remaining work

The independent tests enumerate all 343 paths of the three-unit, seven-state Scala
`StructuralWeightSuite` diagnostic, checking every marginal, adjacent flow, normalizer and
best path. Additional sparse and high-precision direct-enumeration cases distinguish impossible
edges, changing state sets, mathematical ties, huge shared/traded costs and malformed inputs.
They do not invoke the production dynamic-programming recurrence as their expected-value path.

The source references are `align/hsmm.scala` (`GraphHsmm.run`, `transitionRow`, `viterbi`),
`align/matrix.scala` (anchored/external modes), and `align/StructuralWeightSuite.scala` under
the main/test Scala trees. The supplied-transition kernel deliberately leaves graph features,
mode admission, relation refinement and scientific readouts to their own connected slices.
No Scala execution, empirical quality or performance comparison is implied by these tests.
