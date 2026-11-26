# GovAccess AI

GovAccess AI provides a configurable eligibility assessment engine for government programmes. The application uses Java 25, Spring Boot 3.5.16, Thymeleaf, and locally served Bootstrap 5.3.8. Spring AI 1.0.9 supplies the PDF reader. Phase 1 requires no model account or API key.

## Build and test

Install JDK 25 and point `JAVA_HOME` to it. Run the Maven wrapper from the repository root.

```powershell
.\mvnw.cmd clean package
```

On macOS or Linux, use `./mvnw clean package`. The Maven smoke tests use an in-memory database. The running application uses PostgreSQL.

## Run locally with PostgreSQL

Create a local `govaccess` database owned by a dedicated application login. The login needs permission to create objects in the default schema for Flyway. Set the following values for the current PowerShell session, substituting your local credentials.

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/govaccess'
$env:DB_USERNAME = 'govaccess'
$env:DB_PASSWORD = '<local database password>'
$env:SESSION_COOKIE_SECURE = 'false'
.\mvnw.cmd spring-boot:run
```

Open [localhost:8080](http://localhost:8080). This phase uses Spring Security's development login. Sign in as `user` with the generated password printed in the startup log. Set `SESSION_COOKIE_SECURE=false` only for local HTTP. Cookie security defaults to enabled for HTTPS.

After building, the same environment supports running the executable JAR.

```powershell
java -jar target/govaccessai-0.0.1-SNAPSHOT.jar
```

See the [AI implementation plan](AI_IMPLEMENTATION_PLAN.md) for the policy-drafting workflow.
