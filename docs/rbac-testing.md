## Full manual walkthrough

This sequence uses Thandi Maseko's bursary case. It covers intake, document handling, deterministic assessment, official decision, applicant notice, and audit. The sample PDFs contain synthetic values only and are labelled as test documents.

### A. Start and sign in

1. Confirm that the Supabase test project is selected through your normal database environment variables. Do not run this seed against a live service database. Confirm that the port is free with `Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue`.
2. Start with the `supabase` and `demo` profiles. The Java startup runner creates the named accounts and cases. New accounts use `SPRING_SECURITY_USER_PASSWORD` when set. All nine RBAC accounts use the shared demo password `Gov!access12345`. The startup runner resets these test accounts to this password on every demo startup.

```powershell
.mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=supabase,demo'
```

3. Visit `http://localhost:8080/login`. Sign in as `admin` with your configured bootstrap password. Open **Managed users** and find the nine `rbac-...` test accounts. Record the seeded case IDs from each reference. If the application started successfully but no accounts exist, check the startup log for an RBAC seeder error.
4. Sign in as `rbac-thandi.maseko` with the shared RBAC test password. Open **Applications**. Verify that the table includes Thandi's THANDI-BURSARY and THANDI-UNASSIGNED cases and shows applicant name **Thandi Maseko**.
5. Open the THANDI-BURSARY case. Copy its URL. Check that it has a published policy pinned. When the case was seeded before a policy was published, it has no policy pin and cannot continue into upload and assessment. Use the policy-publishing section below, then create a new application from **New application** to get a pinned case.

### B. Check each role's access before processing a case

Use a separate browser profile for each role, or sign out between accounts. Use the same seeded password.

| Account | Confirm these cases appear in Applications | Confirm these cases are hidden |
|---|---|---|
| `rbac-thandi.maseko` | THANDI-BURSARY, THANDI-UNASSIGNED | KABELO-GRANT, KABELO-ESCALATED |
| `rbac-kabelo.dlamini` | KABELO-GRANT, KABELO-ESCALATED | THANDI-BURSARY, THANDI-UNASSIGNED |
| `rbac-lerato.nkosi` | THANDI-BURSARY | KABELO-GRANT, KABELO-ESCALATED, THANDI-UNASSIGNED |
| `rbac-sibusiso.khumalo` | KABELO-GRANT, KABELO-ESCALATED | THANDI-BURSARY, THANDI-UNASSIGNED |
| `rbac-nomsa.mthembu` | THANDI-BURSARY, KABELO-ESCALATED | KABELO-GRANT, THANDI-UNASSIGNED |
| `rbac-pieter.jacobs` | KABELO-GRANT, KABELO-ESCALATED | THANDI-BURSARY, THANDI-UNASSIGNED |
| `rbac-amina.patel` | All four cases, read-only | No case restrictions |
| `rbac-naledi.molefe`, `rbac-ayanda.zulu` | Case queue is denied | Applications |

Open a copied case URL directly as an unauthorised role. The application should return not found for a case outside that role's scope. The Auditor can read case history but should receive forbidden responses for assessment, correction, and decision actions. Policy Administrators should receive forbidden on the application queue.

### C. Prepare and publish a test policy

The seed creates two drafts labelled **DEMO DATA ONLY - Named RBAC publishing exercise**, one for NSFAS Bursary authored by Naledi Molefe and one for Social Support Grant authored by Ayanda Zulu.

1. Sign in as `rbac-naledi.molefe`. Open **Policy lifecycle**. Select the NSFAS Bursary draft and inspect its single synthetic evidence rule.
2. Submit the draft for review.
3. Open the test form. Use the following JSON. The present case should return LIKELY_ELIGIBLE and the missing document case should return NEEDS_REVIEW.

```json
[
  {"label":"DEMO DATA ONLY - declaration present","synthetic":true,"expected":"LIKELY_ELIGIBLE","manualReview":false,"evidence":[{"reference":"DEMO-DOCUMENT-1","documentType":"INCOME_DECLARATION","fieldKey":null,"value":null}]},
  {"label":"DEMO DATA ONLY - declaration missing","synthetic":true,"expected":"NEEDS_REVIEW","manualReview":false,"evidence":[]}
]
```

4. After the tests pass, try to publish while signed in as Naledi. With independent publishing enabled, the system should require another policy reviewer.
5. Sign in as `rbac-ayanda.zulu` and publish the tested version with an effective date of today. Record the publication confirmation and publisher name shown in the lifecycle page. The published version should be immutable.
6. This seed does not automatically pin the draft to a seeded application. As `rbac-thandi.maseko`, choose **New application**, select NSFAS Bursary, enter **Thandi Maseko**, and create a fresh case after publication. The application pins the policy active on its submission date.

### D. Upload and verify documents

1. As Thandi, open the new case, then upload `docs/demo/synthetic-income-180000.pdf`. It is a one-page synthetic income declaration.
2. Open the document row and wait for processing to reach **Succeeded** or **Processed**. Check the type and extracted income. The PDF text is available to the configured extractor only when the AI provider is configured.
3. An identical re-upload should be marked **Duplicate document** and should not start another AI extraction.
4. Sign in as Lerato Nkosi. Confirm that the assigned case and document are visible. Correct or verify an extracted field on the document screen. Enter a reason if requested. Verify that the corrected value remains after another extraction run.
5. Sign in as Amina Patel. Confirm that she can inspect the case and document but cannot correct the evidence or rerun extraction.
6. For a mismatch path, create another Thandi case and upload `docs/demo/synthetic-income-360000.pdf`. It remains synthetic. Verify what extraction and reconciliation show against the current policy.

### E. Assess and decide

1. As Lerato, open Thandi's assigned case and choose **Assess available evidence**. Check the policy version and content hash, evidence snapshot, rule results, three confidence measures, and the recommendation. A recommendation is a review aid. It does not record an official decision.
2. If evidence is missing or conflicting, use **Request additional evidence**. Save a draft and inspect its message. Supply the required reason when sending through communications.
3. Open **Decision workspace**. Generate the explanation and check that each rule cites its evidence. For an amend test, correct a verified field with a reason, rerun assessment, then confirm the new assessment is appended and previous assessment history remains.
4. Record an official action with a reason using **Confirm recommendation** or **Override recommendation**. To test a role denial, use an officer without delegated override authority on an outcome reserved for the supervisor. Then sign in as Nomsa and check the escalated case. Use an action available to her, supply a reason, and verify the recorded official outcome.
5. An Auditor can review the final record but cannot perform the decision action.

### F. Draft and send the notice

1. On the case, open **Case messages and notices**. Draft a decision notice after an official decision has been recorded.
2. If notice drafting reports that approved appeal or review wording is missing, sign in as a policy administrator with publishing permission. Open **Notice configuration**, select the programme, and enter the programme's approved procedure wording. Save it, then retry the notice. The wording must come from your team’s approved programme configuration.
3. Review the accessible HTML notice and reading-level result. Approve it as an authorised officer, then send it with a reason. Check that its states move through Draft, Approved, and Sent.
4. If translation is configured, generate the configured language version from the approved English notice. Review it and approve the translation before using it.
5. Sign in as the applicant and open the applicant workspace. Confirm the sent notice appears there.

### G. Inspect audit and finish

1. As Lerato, open **Audit history**. Her limited scope should show only cases assigned to her.
2. As Naledi, check that limited policy audit scope shows the NSFAS programme events. As Ayanda, check the Social Support Grant events.
3. As Amina Patel, open **AI governance** and **Audit verification**. Review the request metadata and confirm the business audit chain is intact.
4. Confirm that Auditor, Applicant, and Policy Administrator cannot make actions outside their permissions. Confirm every assessment and official action has its own recorded history.
5. The seeder is idempotent for cases and policy drafts. It resets the nine RBAC demo account passwords on every demo startup. Disable the test accounts from **Managed users** when the walkthrough is complete.

### What each screen is for

- **Applications** shows records the current role may access.
- **New application** opens an applicant case and pins the published policy active on its submission date.
- **Case details** brings intake values, files, evidence, assessments, decisions and case history together.
- **Document detail** shows the PDF, extracted fields, source snippets and staff verification controls.
- **Decision workspace** explains the deterministic assessment and offers the officer's allowed actions.
- **Communications** stores additional-evidence requests and notices through draft, approval and send.
- **Policy lifecycle** supports draft editing, review, synthetic tests and human publication.
- **Notice configuration** stores approved programme wording for notices.
- **Audit history**, **Audit verification**, and **AI governance** let permitted staff inspect recorded actions, chain integrity, and AI request metadata.

All people, applications, and example values in this guide are fictional test data. Keep the walkthrough on a test database.
