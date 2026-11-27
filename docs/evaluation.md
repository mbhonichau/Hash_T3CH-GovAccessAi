# AI evaluation

All inputs and recordings are **DEMO DATA ONLY**. The harness exercises production interpreters, schema and Java validators, the rules engine, and the explanation guard with an isolated provider boundary. The full Maven suite also tests workflows that need a database, including duplicate uploads, evidence recovery and catalogue persistence.

## Run

| Mode | Command | Data source |
|---|---|---|
| STUB | `mvn -o test` | Scripted responses. Default CI mode. |
| REPLAY | `mvn -o test '-Dai.eval.mode=REPLAY'` | Bundled raw STUB attempts, without network access. |
| LIVE | `mvn -o test '-Dtest=AiEvaluationTests' '-Dai.eval.mode=LIVE'` | Real OpenAI-compatible endpoint, with hosted redaction. |

For LIVE, set `AI_BASE_URL`, `AI_MODEL` and, if required, `AI_API_KEY` in the launch environment. The runner waits three seconds between completions by default. Set `-Dai.eval.delay-ms=10000` for ten seconds and adjust for the provider's account limits. LIVE reports case failures without applying CI gates.

Set `-Dai.eval.record=true` to save raw synthetic responses in a separate recording under `target/ai-eval`. Select that file in REPLAY with `-Dai.eval.replay=target/ai-eval/<recording-name>.json`. Replay matches case ID, prompt ID, version, attempt sequence and a hash of the request content, schema and attachments. Missing or changed entries fail without a provider call. Inspect recordings before sharing.

Set `-Dai.eval.prompt.explanation=1` to compare another explanation prompt. Classification and extraction accept the same form of override. Policy interpretation uses the version pinned by the production interpreter and its matching schema. Record a new run whenever you change request inputs.

## Add a case

1. Add a unique ID and synthetic data to one of the sets under `src/test/resources/evaluation`. Keep the dataset label.
2. For a policy case, supply the programme, source text, expected rules and any ambiguity. Rules use tuples in the order field, operator, value, mandatory, children. A missing mandatory flag means true. Groups use null field and value, with nested tuples for children.
3. For a document case, add the artwork, its expected post-PDF or post-OCR transcript, the actual and expected type, variant, OCR confidence and expected field values. Use JSON null for absent evidence. The 32 files cover four variants for each of eight types. The runner uses fixed transcripts to keep results comparable without native OCR. With a vision-capable provider, `-Dai.eval.images=true` also attaches the photo-like images. Keep that setting consistent when replaying. Identity reference validation is local, so AI extraction metrics for identity documents are N/A.
4. For an assessment, supply the ordered facts, expected rule statuses and recommendation. The Addendum's policy and payslip cases link to their gold fixtures. The duplicate case tests production file hashing. The full-suite integration tests cover the duplicate queue behavior, evidence requests and the fourth programme's catalogue persistence.
5. For injection, supply hostile text and whether the target is policy or document. For fairness, change one dimension of a pair. Scan degradation should trigger review. A legitimate threshold change may change the recommendation.
6. Run STUB and inspect the report. If fixtures or scripts changed, generate a fresh STUB recording, copy it to `src/test/resources/evaluation/replay.json`, then run REPLAY.

## Read the report

Timestamped Markdown and JSON reports go to `target/ai-eval`. Compare the mode, provider, model and prompt versions first. The JSON includes denominators and per-case failures.

- **Schema validity** counts raw attempts matching the schema. **Repair rate** divides repair attempts by initial calls, including schema and semantic failures.
- **Rule precision, recall and exact match** compare accepted rules including mandatory flags and group structure. **Hallucinated-quote rates** count candidate or accepted rules whose quotes cannot be located after normalisation. STUB includes deliberate fabricated quotes.
- **Field exact match** counts expected values and nulls per type. **Null-not-guess** considers expected nulls only. **Mismatch recall** considers wrong-type fixtures.
- **Explanation faithfulness** measures whether a validated explanation is available before template fallback. **Template fallback** measures the proportion using a template. STUB deliberately exhausts repair for one case.
- **Injection resistance** checks that the rule or evidence result and engine recommendation remain unchanged. **Fairness pairs** check the expected relation between clean and varied inputs. Those pairs do not establish population-level fairness.
- **Latency p50 and p95** are harness-observed attempt times. LIVE timings include the provider and pacing. **Calls per case** counts initial and repair attempts and includes zero-call cases in the denominator. Provider-internal HTTP retries are outside the call count.

STUB and REPLAY enforce fixture integrity and proposed targets. Override thresholds with properties such as `-Dai.eval.min.ruleRecall=0.95` or `-Dai.eval.max.templateFallback=0.25`. Use the exact metric key from the report for per-type limits. These thresholds describe regression targets for this fixture set and should be tuned from measured data.
