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

Hibernate creates missing tables from the JPA entities on startup using `ddl-auto=update`. Flyway is disabled for normal startup. Seed rows such as the NSFAS draft require a separate data-loading step when they are absent.

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

Confirm [application health](http://localhost:8080/actuator/health) returns `{"status":"UP"}`. Open [GovAccess AI](http://localhost:8080) and sign in as `user` with the generated application password in the startup log.

After building, the same environment supports running the executable JAR.

```powershell
java -jar target/govaccessai-0.0.1-SNAPSHOT.jar --spring.profiles.active=supabase
```

See the [AI implementation plan](AI_IMPLEMENTATION_PLAN.md) for the policy-drafting workflow.
