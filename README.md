# GovAccess AI

GovAccess AI provides a configurable eligibility assessment engine for government programmes. The application uses Java 25, Spring Boot 3.5.16, Thymeleaf, and locally served Bootstrap 5.3.8. Spring AI 1.0.9 supplies the PDF reader. Phase 1 requires no model account or API key.

## Build and test

Install JDK 25 and point `JAVA_HOME` to it. Run the Maven wrapper from the repository root.

```powershell
.\mvnw.cmd clean package
```

On macOS or Linux, use `./mvnw clean package`. The Maven smoke tests use an in-memory database. The running application uses Supabase PostgreSQL.

## Run with Supabase PostgreSQL

The `supabase` profile defaults to the configured project’s IPv4 session pooler on port 5432. Confirm the host and username under **Connect → Session pooler** in the Supabase dashboard. To target a different project, set `SUPABASE_POOLER_HOST` and `SUPABASE_PROJECT_REF` to its values. [Supabase connection modes](https://supabase.com/docs/guides/database/connecting-to-postgres)

In PowerShell, enter the database password when prompted. Use `SESSION_COOKIE_SECURE=false` only for local HTTP.

```powershell
$dbCredential = Get-Credential -UserName 'postgres.vxleshewjuwjcpnrgeev' -Message 'Enter the Supabase database password'
$env:SUPABASE_DB_USERNAME = $dbCredential.UserName
$env:SUPABASE_DB_PASSWORD = $dbCredential.GetNetworkCredential().Password
$env:SESSION_COOKIE_SECURE = 'false'
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=supabase'
```

The profile accepts `SUPABASE_DB_URL`, `SUPABASE_DB_USERNAME`, and `SUPABASE_DB_PASSWORD`. Existing `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` values take precedence. The default URL uses `sslmode=require` to encrypt the connection. For hostname and certificate verification, download the project CA from **Database Settings → SSL Configuration** and set a JDBC URL override such as the following. [Supabase SSL configuration](https://supabase.com/docs/guides/platform/ssl-enforcement)

```powershell
$env:SUPABASE_DB_URL = 'jdbc:postgresql://<session-pooler-host>:5432/postgres?sslmode=verify-full&sslrootcert=C:/certs/supabase-ca.crt'
```

Flyway and Hibernate use the dedicated `govaccess` schema. Keep it outside the Supabase Data API’s exposed schemas. The database role needs to own the schema or have permission to create it on first startup. With automatic baselining enabled, Flyway treats an existing non-empty schema without migration history as already at version 1. Verify that such a schema matches that baseline before starting the app.

Confirm [application health](http://localhost:8080/actuator/health) returns `{"status":"UP"}`. Open [GovAccess AI](http://localhost:8080) and sign in as `user` with the generated application password in the startup log.

After building, the same environment supports running the executable JAR.

```powershell
java -jar target/govaccessai-0.0.1-SNAPSHOT.jar --spring.profiles.active=supabase
```

See the [AI implementation plan](AI_IMPLEMENTATION_PLAN.md) for the policy-drafting workflow.
