# GovAccess AI

GovAccess AI provides a configurable eligibility assessment engine for government programmes. The application uses Java 25, Spring Boot 3.5.16, Thymeleaf, and locally served Bootstrap 5.3.8. Spring AI 1.0.9 supplies the PDF reader. Phase 1 requires no model account or API key.

## Build and test

Install JDK 25 and point `JAVA_HOME` to it. Run the offline tests from the repository root before and after a change.

```powershell
mvn -o test
```

On macOS or Linux, use `./mvnw clean package`. The Maven smoke tests use an in-memory database. The running application uses Supabase PostgreSQL.

## Initialise local PostgreSQL

With a local PostgreSQL server running and `psql` on PATH, run this from the repository root. Use an existing PostgreSQL login with permission to create the database and its schema.

```powershell
$dbCredential = Get-Credential -Message 'Enter your local PostgreSQL credentials'
$env:DB_USERNAME = $dbCredential.UserName
$env:DB_PASSWORD = $dbCredential.GetNetworkCredential().Password
.\scripts\Initialize-Database.ps1
```

The script creates the local `govaccess` database if needed, applies every checked-in Flyway migration in the `govaccess` schema, and sets `DB_URL` for the current PowerShell session. You can rerun it to apply later migrations. Use `-Database` or `-Port` to override the defaults. Start the application from the same session with `.\mvnw.cmd spring-boot:run`.

## Run with Supabase PostgreSQL

Flyway creates and upgrades the `govaccess` schema at startup. Hibernate uses `ddl-auto=validate`.

The `supabase` profile defaults to the configured project’s IPv4 session pooler on port 5432. Confirm the host and username under **Connect → Session pooler** in the Supabase dashboard. To target a different project, set `SUPABASE_POOLER_HOST` and `SUPABASE_PROJECT_REF` to its values. [Supabase connection modes](https://supabase.com/docs/guides/database/connecting-to-postgres)

In PowerShell, enter the database password when prompted. Use `SESSION_COOKIE_SECURE=false` only for local HTTP.

```powershell
$dbCredential = Get-Credential -UserName 'postgres.dsexqqtwerhhilfkjkyn' -Message 'Enter the Supabase database password'
$env:SUPABASE_DB_USERNAME = $dbCredential.UserName
$env:DB_PASSWORD = $dbCredential.GetNetworkCredential().Password
$env:SESSION_COOKIE_SECURE = 'false'
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=supabase'
```

The default URL uses `sslmode=require` to encrypt the connection. For hostname and certificate verification, download the project CA from **Database Settings → SSL Configuration** and set a JDBC URL override such as the following. [Supabase SSL configuration](https://supabase.com/docs/guides/platform/ssl-enforcement)

```powershell
$env:SUPABASE_DB_URL = 'jdbc:postgresql://<session-pooler-host>:5432/postgres?sslmode=verify-full&sslrootcert=C:/certs/supabase-ca.crt'
```

Keep the dedicated `govaccess` schema outside the Supabase Data API’s exposed schemas. The migration role must be able to create schemas and manage the tables.

Confirm [application health](http://localhost:8080/actuator/health) returns `{"status":"UP"}`. Open [GovAccess AI](http://localhost:8080) and sign in as `user` with the generated application password in the startup log.

After building, the same environment supports running the executable JAR.

```powershell
java -jar target/govaccessai-0.0.1-SNAPSHOT.jar --spring.profiles.active=supabase
```

See the [AI implementation plan](AI_IMPLEMENTATION_PLAN.md) for the policy-drafting workflow.

### Environment variables

| Variable | Required when | Purpose |
| --- | --- | --- |
| `DB_PASSWORD` | Every Supabase launch | Password for the PostgreSQL role. Startup rejects a missing or blank value. |
| `DB_URL` or `SUPABASE_DB_URL` | Connecting to a database outside the configured Supabase project | PostgreSQL JDBC URL. `DB_URL` takes precedence. You can also supply `SUPABASE_POOLER_HOST` and `SUPABASE_PROJECT_REF` for the configured pooler URL format. |
| `DB_USERNAME` or `SUPABASE_DB_USERNAME` | Connecting as a role other than the configured Supabase role | Database login. `DB_USERNAME` takes precedence and is required for the local database initializer. |
| `AI_BASE_URL` and `AI_MODEL` | Making a live AI call | OpenAI-compatible provider base URL and exact model ID. The app adds `/chat/completions`. |
| `AI_API_KEY` | Calling Groq or another authenticated provider | Provider API key. Keep it in the launch environment. |
| `GOVACCESS_AI_ENABLED` | Optional | Defaults to `true`. Set to `false` for a startup-level kill switch. |
| `SPRING_SECURITY_USER_ROLES` | Using the built-in account for AI administration | Set to `POLICY_EDIT,AI_ADMIN` in a local verification environment. |
| `SPRING_SECURITY_USER_PASSWORD` | Optional for the built-in account | Supply a password through the environment to use a stable login. Otherwise Spring Security generates one at startup. |
| `SESSION_COOKIE_SECURE` | Optional | Set to `false` only for local HTTP. It defaults to `true`. |

The runtime AI toggle applies to the running instance and resets on restart. A startup-level disable remains in force when an administrator tries to enable AI at runtime.

### Secret scanning

Run `.\scripts\Test-RepositorySecrets.ps1 -Staged` before committing, or run it without arguments to check `HEAD`. Use `-Revision <commit>` to check another commit. The script prints matching file paths and exits with an error for known credential formats or embedded password fallbacks. Rotate any exposed credentials separately.

### Verify Flyway on PostgreSQL

1. Prepare two disposable PostgreSQL databases. Start with a fresh database and a copy of a pre-hardening database containing at least one saved policy or audit row. Include a copy of the Hibernate-created policy tables if that is how the existing deployment was provisioned.
2. Back up the existing copy, stop old application instances, and launch this version against each database in turn. Use the schema-owning role. Keep Flyway enabled and Hibernate in validation mode.
3. Check that `govaccess.flyway_schema_history` ends at version `14` with successful entries. For a legacy schema containing the AI tables and no Flyway history, startup records a V2 baseline. If V3 is pending and Hibernate already created the policy tables, Flyway stages them and restores their rows while adopting the versioned schema. V10 converts the saved NSFAS rule JSON into canonical content and preserves the legacy JSON for the existing editor.
4. Compare schema-only dumps of `govaccess` using `pg_dump --schema-only --schema=govaccess --no-owner --no-privileges` or compare the table definitions in the SQL Editor. Check that `ai_call_log.request_id` is non-null and references `ai_request_log.id`. Confirm the existing policy rows and hashes are preserved. Each legacy attempt is linked to its own historical request because the original rows lacked a correlation ID.
5. Restart both databases' application instances and confirm Flyway has no pending migrations and Hibernate validation succeeds. The migration callback for previously Hibernate-created policy tables is part of Spring Boot startup. Use app startup for that upgrade path.

The deployed intake project already used versions V5–V7. Application startup recognises those exact historical entries by version, description, script and checksum, and applies this branch's V8–V14 afterward. Its history rows remain intact. Use Spring Boot startup for that deployment, since the standalone Flyway Maven goal does not run the application-specific validation strategy.

### Policy lifecycle and business audit

- Set `SPRING_SECURITY_USER_NAME`, `SPRING_SECURITY_USER_PASSWORD`, and `SPRING_SECURITY_USER_ROLES=USER_MANAGE` in the launch environment to provision the bootstrap account. Use **Managed users** to create accounts for the five managed roles. Grant officers specific application IDs and policy administrators specific programme IDs for limited audit history.
- Sign in as a Policy Administrator and enable **Policy editing** in Workspace access. Open **Policy lifecycle**, select a draft, submit it for review, and run labelled synthetic cases with expected recommendations. A passed run unlocks publication. Supply an effective date and optionally select the superseded version. With `govaccess.policy.require-independent-publisher=true` (the default), another enabled publisher must publish when one is available.
- Edit a published policy by creating a new draft from its lifecycle page. Policy selection uses the application date. For overlapping effective versions, the latest applicable effective date wins unless an inclusive transitional window selects another eligible version. Higher window priorities take precedence.
- The publication screen records the publisher's username for new publications. Earlier publications retain their publisher IDs.
- An Assessment Officer or Supervisor can send a CSRF-protected `POST /api/assessments` with `programmeCode`, `applicationDate` (ISO date), `verifiedEvidence` (an array of verified evidence objects), and optional `manualReview`. This endpoint trusts the signed-in assessor to supply evidence they verified. A successful run returns HTTP 201 with the recommendation, policy snapshot ID and hash. The same assessor can retrieve that result using the response's `Location`. Publishing another version affects subsequent runs according to their application date. The original run retains its policy reference and recommendation.
- Sign in as an Auditor and open **Audit chain** to verify the complete chain. **Audit history** honours each account’s scope. Sensitive before and after values are stored in protected snapshots. V13 also converts legacy inline business audit values and links historical AI request rows. If any existing published policy lacks authoritative publication metadata, the migration stops so it can be recovered from the original publication record.

For the hosted-provider test, follow the [live Groq verification checklist](docs/live-verification.md).

## NSFAS Policy Studio

Open **Policy Studio** after signing in and load the bundled 2025 draft if the workspace is empty. The financial preview uses synthetic facts and the selected version's stored rule contract.

The built-in user has Policy Edit permission. From **Workspace access**, select **Switch to Policy Edit** and then **Open Policy Studio**. Accounts with that permission can switch back to Review from the same screen. New sessions start in Review.

Configure the AI provider with `AI_BASE_URL`, `AI_API_KEY`, and `AI_MODEL`. The import uses the bundled synthetic financial excerpt. The restricted pathway is copied from the local draft fixture. Imported and edited versions are saved as new drafts.

To start with Groq on Windows, stop the existing app, keep your normal Supabase connection settings, and run `.\scripts\Start-GovAccessWithGroq.ps1` from PowerShell. The launcher prompts for a key, checks model access, and starts Maven with the provider variables in the same session.

## Canonical rules

Policy versions store a versioned rule document in `policy_version.content`. Open **Rule catalogue** to see the field types, units and allowed evidence sources. Switch to **Policy Edit** to add field definitions and document types. `RulePolicyService` creates programmes and drafts from the catalogue. `RuleEvaluator` evaluates verified evidence and `RecommendationService` checks rule coverage before issuing a recommendation. Callers of the former `FinancialScreenEvaluator` can use the existing NSFAS preview service or the canonical evaluator.

Numeric and date ranges use inclusive bounds. `DATE_WITHIN` takes two ISO dates. `govaccess.rules.max-group-depth` defaults to 5. Group results drive recommendations and their child evaluations remain available for review. A missing-evidence rule with `NEEDS_REVIEW` sets the manual-review flag.

The seeded programmes are demo records. The bundled NSFAS draft carries citations and a notice to verify figures against the official policy. The Social Support Grant and Housing Support Subsidy currently have programme records awaiting their policy rules.

## Import policy documents

Switch to **Policy Edit**, then open **Policy imports**. Choose a programme, enter a draft label and document title, and upload a text PDF or paste policy text. The job processes one section at a time. On completion, **Review AI draft** shows citations, low-confidence rules, rejected suggestions, and unmapped requirements. Review edits retain the original value and editor.

Uploaded policy sources use an AI provider on the same machine. For Ollama, configure `AI_BASE_URL=http://127.0.0.1:11434/v1`, `AI_HOSTED=false`, and `AI_MODEL` with the installed tag. Keep hosted providers for the bundled synthetic import. The general import uses prompt v4. Its review includes exemptions, calculation methods and rule-level human-review notes. The bundled sample still uses v2.
