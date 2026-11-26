# GovAccess AI implementation plan

## Objective

Deliver a policy-to-assessment workflow in the existing Java 25 monolith. AI proposes structured policy rules and assists with document extraction. Reviewed policy and verified evidence feed the deterministic Java rules engine. An authorised officer records the official decision.

## 1. Establish a reproducible baseline

- Work in the supplied repository on `codex/ai-implementation`, using the existing `com.hashtech.govaccessai` namespace.
- Run the Maven wrapper with JDK 25. The current POM declares Spring Boot 4.2.0 and Spring AI 2.1.0, while the agreed architecture specifies Spring Boot 3.5.x. Validate the current dependency resolution, then align with the agreed 3.5.x baseline and verify a compatible Spring AI release. Use a direct REST adapter if a compatible starter adds avoidable integration work.
- Confirm the wrapper can test, package and start the executable JAR against local PostgreSQL with Flyway. Record the validated dependency versions and run commands.

## 2. Deliver the first vertical slice

Choose one programme using a synthetic policy, an income threshold and a small set of required evidence. Include an eligible case, an ineligible case and a case requiring review.

1. Extract page text locally. Preserve the document hash, page number and text offsets. Use local OCR for scanned pages and flag extraction failures for review.
2. Send policy excerpts to a provider adapter and request typed draft rules. Each rule includes its field, operator, typed operand and supporting citation. Ambiguous clauses go into a review queue.
3. Validate the schema and semantics in Java. Check allowed fields and operators, value types, units and threshold ranges. Verify each citation against the referenced source text. An authorised reviewer edits, approves and publishes an immutable policy version.
4. Extract candidate facts from supporting documents with local parsers and OCR. Store the raw value, normalised value, source reference and verification status. Treat missing, unreadable or conflicting evidence as requiring review. Use decimal arithmetic for monetary amounts and explicit periods for income comparisons.
5. Evaluate the published policy against verified facts. Persist per-rule outcomes and generate explanations from the stored results via templates. Send the recommendation to the official decision workflow for officer approval and audit.

## 3. Implement processing, provenance and provider boundaries

- Use PostgreSQL and Flyway for ingestion jobs, draft proposals and evidence lineage. Link the assessment to the exact policy version, evidence snapshot and rules engine version so it can be reproduced. Capture provider, model, prompt and schema versions for AI runs.
- Track queued, running, review-required, completed and failed states. Scope idempotency to the record, operation, input hash and processor version. Test repeat submissions and concurrent claims. Retry transient provider errors with a bounded backoff and route invalid output to manual review.
- Feature-flag the live provider and provide a fixture-backed demo mode. Use provider schema enforcement when available and always validate locally. Treat document content as untrusted data and give the model zero tools or write access.
- Keep applicant documents and identifiers in local storage and out of provider payloads and logs. Limit live calls to approved policy excerpts. Store credentials in environment configuration. Enforce record-level authorisation on evidence access, separate policy publication from draft creation, and audit reviews, publications and decisions.
- Track extraction failures, citation mismatches, review rates, processing latency, token use and estimated provider cost. Keep ordinary telemetry free of document contents.

## 4. Verify the workflow with synthetic fixtures

- Maintain reviewed expected outputs for text PDFs, scanned PDFs, ambiguous policy wording, missing pages, duplicate uploads, and conflicting income evidence.
- Check field-level extraction precision and recall, rule and citation accuracy, and case-level recommendation agreement. Publish the fixture counts and per-field results. Run live-provider checks separately from the repeatable Maven suite.
- Require every published rule to have a verified citation and reviewer approval. Require identical results from repeated evaluation of the same stored policy and evidence revisions. Require manual review for failed extraction, invalid model output or unavailable live AI with no validated draft.
- Verify access control, CSRF, protection of document storage, and officer approval using the normal application entry points. Exercise inclusive and exclusive income boundaries and check that unrelated synthetic protected attributes do not affect rule results.

## 5. Extend after the first programme works end to end

Add further programmes, synthetic fairness checks and policy-version simulations using the same deterministic engine. Consider optional AI copy editing for explanations only after the template explanations and their evidence references pass review.

## References

- [Spring Boot 3.5 Java compatibility](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [Spring AI structured output](https://docs.spring.io/spring-ai/reference/api/structured-output.html)
