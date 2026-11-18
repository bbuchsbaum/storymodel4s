# Refining alignment with stated recall relations

A recall can describe two similar events and say that one caused the other. If several
film events fit each description, a causal path in the source can help choose between
them. The Python port applies the graph-HSMM refinement rule to the candidates already
admitted by local evidence. Retrieval, its mode gates, base costs and transition
probabilities stay available in the result.

## Run the example

From the port directory, with the package installed:

```sh
python examples/refined_alignment.py data/refinement-example
cd data/refinement-example
storymodel infer-accounts source.json recall.json spec.json --output cli.json
storymodel verify-account-inference source.json recall.json spec.json cli.json
```

The example supplies three source situations with identical descriptions, linked by
two causal edges. Each of two recall units nominates only the first and last situations.
The recall states one causal relation between its units. All transition coefficients are
zero so the source order contributes no transition preference.

| Passes, weight 2 | Best source situations in recall order | Conditional causal preservation |
|---:|:---:|---:|
| 0 | 3, 3 (tie broken by state key) | 0.2500 |
| 1 | 1, 3 | 0.4235 |
| 2 | 1, 3 | 0.5093 |

Each pass starts with the original emission cost and subtracts the weight times the
posterior mass on compatible partners from the previous pass. Compatibility requires a
positive-length directed path in the corresponding source layer. Thus the unselected
middle situation connects source situations 1 and 3. External states receive no bonus.
The final forward–backward and Viterbi calculations use the same refined costs.

## Inspect the result

Pass a `RefinedInferenceSpec` to `infer_refined_accounts(source, recall, spec)`. Its
`local` field takes the ordinary `LocalEvidenceSpec`, including explicit unit order.
Its `policy` sets the number of passes and the nonnegative finite weight. Zero passes
produces the baseline posterior and still evaluates the stated relations. The command
line detects the specification by its schema version.

- `evidence` contains the original retrieval, local costs, transition policy and base
  sequence request. `refinement.request` and `refinement.inference` contain the final
  costs, posterior, flow and best path.
- `refinement.relations` points back to the admitted recall claim for each unit edge.
  The account workflow uses the evidence spans of each endpoint to identify its selected
  unit. Text-only local scoring still permits an independently evidenced relation. Each
  endpoint must resolve to a unique unit in the same recall document.
- `excluded_claims` gives reasons for unused temporal and causal claims, including
  ambiguous membership, both endpoints in one unit, attributed context and inferred
  chronology. Multiple claims for the same directed unit edge contribute one bonus.
  The source projection supplies the world-time and causal paths.
- `refinement.preservation` reports the admitted unit edges stated and evaluated per layer.
  Each edge score uses the product of its two endpoint marginals, conditioning each on
  its source mass. The layer mean averages the scores of evaluable edges.
  Complete external assignment makes the edge unevaluable. A stated simultaneous relation
  counts as stated and has no directed preservation score. The score measures agreement between the two graphs under the model posterior.

The account adapter admits narrated causal and before relations and surface-explicit temporal
before/equal claims. Inverse before/after declarations follow the chronology model's canonical
orientation. Other interval relations retain an exclusion reason. The kernel also accepts
declared unit edges directly for numerical comparisons. Use the account workflow for
claim admission and source binding.

## Read the refined alignment

The same saved inference feeds both account readouts. Reconstruction selects a source
anchor for each recall unit from its marginal probabilities. Organization assigns the
joint probability of each adjacent pair to categories such as forward, backward,
same target and external, using the supplied source order.

From the example output directory:

```sh
storymodel reconstruct-accounts source.json recall.json spec.json reconstruction-spec.json --inference result.json --output reconstructed.json
storymodel verify-account-reconstruction source.json recall.json spec.json reconstruction-spec.json reconstructed.json
storymodel organize-accounts source.json recall.json spec.json order.json --inference result.json --output organized.json
storymodel verify-account-organization source.json recall.json spec.json order.json organized.json
```

| Readout on the example | Baseline | Two refinement passes |
|---|---:|---:|
| Unconstrained reconstruction, source anchors in recall order | 1, 1 | 1, 3 |
| Joint probability of a forward move | 0.0952 | 0.2780 |
| Joint probability of a backward move | 0.0952 | 0.0446 |
| Probability that either endpoint is external | 0.6192 | 0.4541 |

The baseline has tied source anchors. The reconstruction and sequence decoders have
their own tie rules, so the baseline reconstruction can differ from the best sequence
path above. Each refined readout embeds its full inference result, including the
relation claims and base evidence. A supplied inference is checked against the two
accounts and the inference specification. Omitting it computes the same result.
