# GovAccess AI

GovAccess AI provides a configurable eligibility assessment engine for government programmes. The application uses Java 25, Spring Boot 3.5.16, Thymeleaf, and locally served Bootstrap 5.3.8. Spring AI 1.0.9 supplies the PDF reader. Phase 1 requires no model account or API key.

## Build and test

Install JDK 25 and point `JAVA_HOME` to it. Run the Maven wrapper from the repository root.

```powershell
.\mvnw.cmd clean package
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

Flyway is enabled on application startup and owns the schema; Hibernate uses `ddl-auto=validate`. A fresh database applies the checked-in migrations, including the NSFAS policy draft and synthetic intake cases. Existing installs with Flyway history upgrade normally. If version 5 is the only validation issue and its account tables still match the expected schema, startup repairs the stored checksum and continues without replaying that migration; unrelated validation errors still stop startup. Existing non-empty schemas without Flyway history are baselined at their latest complete legacy migration, then receive only the remaining migrations. This avoids replaying the original NSFAS draft insert over existing policy data.

Staff with `APPLICATION_READ` can open `/applications` and reopen cases by selecting their reference. Staff with `APPLICATION_CREATE` can open `/applications/new` and submit a case; case writes and their audit events are committed together. The intake migrations seed three synthetic NSFAS examples: likely eligible, missing evidence/manual review, and not eligible. The detail screen shows evidence, assessment, decision, and audit history. Synthetic recommendations are not official decisions; every case still requires an authorised human decision workflow before a result becomes final.

The `supabase` profile defaults to the configured project’s IPv4 session pooler on port 5432. Confirm the host and username under **Connect → Session pooler** in the Supabase dashboard. To target a different project, set `SUPABASE_POOLER_HOST` and `SUPABASE_PROJECT_REF` to its values. [Supabase connection modes](https://supabase.com/docs/guides/database/connecting-to-postgres)

In PowerShell, enter the database password when prompted. Use `SESSION_COOKIE_SECURE=false` only for local HTTP.

```powershell
$dbCredential = Get-Credential -UserName 'postgres.dsexqqtwerhhilfkjkyn' -Message 'Enter the Supabase database password'
$env:SUPABASE_DB_USERNAME = $dbCredential.UserName
$env:SUPABASE_DB_PASSWORD = $dbCredential.GetNetworkCredential().Password
$env:SESSION_COOKIE_SECURE = 'false'
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=supabase'
```

The profile accepts `SUPABASE_DB_URL`, `SUPABASE_DB_USERNAME`, and `SUPABASE_DB_PASSWORD`. Existing `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` values take precedence. The default URL uses `sslmode=require` to encrypt the connection. For hostname and certificate verification, download the project CA from **Database Settings → SSL Configuration** and set a JDBC URL override such as the following. [Supabase SSL configuration](https://supabase.com/docs/guides/platform/ssl-enforcement)

```powershell
$env:SUPABASE_DB_URL = 'jdbc:postgresql://<session-pooler-host>:5432/postgres?sslmode=verify-full&sslrootcert=C:/certs/supabase-ca.crt'
```

Hibernate uses the dedicated `govaccess` schema. Keep it outside the Supabase Data API’s exposed schemas. The application’s PostgreSQL role needs permission to create the schema if it is missing and to manage the tables inside it.

When the user table is empty, the first startup creates an `admin` account automatically. It generates a strong one-time password and prints the username and password in a clearly marked startup log block. Copy the password immediately; it is not stored in plaintext and cannot be displayed again after startup. Set `GOVACCESS_BOOTSTRAP_USERNAME` to choose a different initial username, or set both `GOVACCESS_BOOTSTRAP_USERNAME` and `GOVACCESS_BOOTSTRAP_PASSWORD` to supply your own credentials (passwords must contain at least 12 characters). The initial account receives all permissions. Later runs keep the same account and do not rotate its password; use the protected Staff access page to create other accounts and assign permissions. Passwords are stored as BCrypt hashes. Accounts and permissions persist in the `user_account` tables.

For a demo database, activate the `demo` profile together with the database profile. The demo seeder creates `demo-admin`, `policy-officer`, `case-reviewer`, and `read-only` accounts with different permission sets, and prints each newly generated password once. It never resets credentials for accounts that already exist. Use this only with a demo database; the seeder writes these accounts to the configured database.

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=supabase,demo'
```

Confirm [application health](http://localhost:8080/actuator/health) returns `{"status":"UP"}`. Open [GovAccess AI](http://localhost:8080) and sign in with the bootstrap credentials. The session expires after 30 minutes of inactivity, uses an HTTP-only SameSite=Lax cookie, and enables Secure by default. Set `SESSION_COOKIE_SECURE=false` only for local HTTP. Spring Security rotates the session ID at sign in. HTML forms receive CSRF tokens automatically; Fetch clients can read the `_csrf` and `_csrf_header` meta tags from the shared layout and send that header with state-changing requests. Sign out uses POST with CSRF protection.

The demo machine must have JDK 25 and Tesseract OCR installed and available on `PATH` (`tesseract --version`). The current checked environment has no Tesseract executable on `PATH`; install it before demonstrating OCR-backed document intake. Verify `java -version` reports Java 25 before packaging or running the executable JAR.

After building, the same environment supports running the executable JAR.

```powershell
java -jar target/govaccessai-0.0.1-SNAPSHOT.jar --spring.profiles.active=supabase
```

See the [AI implementation plan](AI_IMPLEMENTATION_PLAN.md) for the policy-drafting workflow.
