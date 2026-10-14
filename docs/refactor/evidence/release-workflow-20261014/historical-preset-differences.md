# Ordinary ONNX profile versus the historical Sherlock A preset

The ordinary source-03 run at `0a3a97777c861f41c911ae1232a911590576fd2a` is a new
paired profile, not a numerical reproduction of the September benchmark. Its
[actual readback](source03-onnx.json) accounts for 173 units and 2,495 words in both
members. No gold labels were read and no accuracy estimate was calculated.

The historical authority is the [frozen development protocol](../sherlock-development-20260923/protocol.json),
which identifies preset commit `99344ea01ffd82613b85337d6dd23a49b809f0c7` and its exact
inputs. The current run uses identical model, tokenizer and source-03 recall bytes.
Its ordinary annotation CSV is derived from the same pinned 1,000-row table.
Equal source bytes and unit counts do not establish equal representations or estimands.

| Choice | Historical A | Ordinary ONNX pair |
| --- | --- | --- |
| Population | Eleven development recalls; separately declared gold-available subset | One admitted development recall, source03; no gold scoring |
| Input adapter | Benchmark adapter, frozen environment settings | Ordinary study preparation and checked timed-source rendering |
| Candidate nomination | Eight per level; lexical-overlap augmentation disabled | Twenty at level 0; complete ties up to budget 200; typed overflow refusal |
| Local scoring | Frozen lexical blend 0.8 over lemmas plus embedding channel | Semantic-only half-cosine distance; no function prior, external mismatch or distortion penalty |
| Reference output | Historical report is the structured benchmark condition | Independent local-score mass at temperature 1.0, with all unit outcomes retained |
| Reconstruction | Prior scale 1.5, hard nondecreasing scene decisions, forward penalty 0, monotone fill enabled | Named Sherlock profile: scaled transition families 1.5, nondecreasing group decisions, forward penalty 0, inference temperature 0.15; fill disabled |
| Group ordering | Historical scene decoder and adapter | Explicit root ranking from the checked source's discourse positions |
| Output and authority | Historical benchmark receipts | Shared registered evidence, paired exchange, and independently checked weights 0, 0.5 and 1 |
| Video condition | Historical embedding captions, with the limitations recorded by that protocol | Separate checked caption profile; planned actual-worker acquisition and strict package join require their own receipt |

The current reference and reconstruction intentionally differ in their use of structure.
Weights zero and one preserve those endpoint records exactly. Interior weights run inference
on the same local evidence with the named reconstruction transition coefficients scaled by
the requested weight. The resulting model posterior is not calibrated by the weight sweep.
The actual A/A+V augmentation comparison uses the lexical A and checked caption profiles
so the embedding-versus-lexical scorer change is not mistaken for a video effect. The
ONNX pair remains a separately demonstrated supported route. These differences explain
why the historical benchmark numbers are not carried forward as current performance.
