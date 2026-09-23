# Task 1 report — reproducible Maven build

Date: 2026-09-23

## Outcome

Task 1 is implemented and verified. The repository now has a Java 25 Maven
reactor with five child modules, two executable Spring Boot service jars, an
official checksum-pinned Maven Wrapper, and a Java 25 GitHub Actions workflow.

## Version choices and official verification

- Spring Boot `4.1.1`: latest stable Spring Boot 4 release in Maven Central at
  implementation time. The official Spring Boot system requirements state that
  4.1.1 supports Java 17 through Java 26 and Maven 3.6.3 or later.
  - https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/maven-metadata.xml
  - https://docs.spring.io/spring-boot/system-requirements.html
- Apache Maven `3.9.16`: latest stable Maven 3 release in Maven Central; the
  newer entries were a 3.10 release candidate and Maven 4 release candidates.
  - https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/maven-metadata.xml
- Maven Wrapper Plugin `3.3.4`: current stable wrapper release according to the
  Apache Maven Wrapper documentation and Maven Central metadata.
  - https://maven.apache.org/tools/wrapper/download.cgi
  - https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-wrapper-plugin/maven-metadata.xml
- Maven Compiler Plugin `3.16.0`, Maven Surefire Plugin `3.6.0`, and Maven
  Failsafe Plugin `3.6.0`: latest stable releases in their Maven Central metadata
  at implementation time; prerelease entries were excluded.
- The downloaded Maven 3.9.16 binary ZIP matched Maven Central's published
  SHA-512:
  `ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3`.
  Its SHA-256,
  `5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce`,
  is pinned in `maven-wrapper.properties`.

## Files added or changed

- `pom.xml`: parent reactor, central version/dependency management, Java 25,
  Surefire `*Test`, and Failsafe `*IT` lifecycle configuration.
- `api-contracts/pom.xml`
- `api-clients/pom.xml`
- `event-contracts/pom.xml`
- `order-service/pom.xml`: Boot starter and service-only repackage execution.
- `inventory-service/pom.xml`: Boot starter and service-only repackage execution.
- `order-service/src/main/java/io/github/tmejs/reservation/orders/OrderApplication.java`
- `inventory-service/src/main/java/io/github/tmejs/reservation/inventory/InventoryApplication.java`
- `mvnw`
- `mvnw.cmd`
- `.mvn/wrapper/maven-wrapper.properties`
- `.gitignore`
- `.gitattributes`: preserves the official Windows wrapper's CRLF endings and
  treats its carriage returns correctly during Git whitespace checks.
- `.github/workflows/verify.yml`
- `codex/progress.md`
- `codex/execution/task-1-report.md`

No API contracts, event records, domain behavior, persistence, security,
observability, or runtime infrastructure were implemented in this checkpoint.

## Commands and outcomes

1. Host tool inspection:

   ```text
   java -version
   mvn -version
   docker version --format 'client={{.Client.Version}} server={{.Server.Version}}'
   docker compose version
   ```

   The host default was Temurin 21.0.8 with Maven 3.9.6. Docker Desktop was
   available with client/server 28.3.3 and Compose 2.39.2. Accessing the daemon
   required the permitted host-socket execution because the sandbox cannot open
   the user's Docker socket directly.

2. Official metadata and checksum inspection:

   ```text
   curl -fsSL https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/maven-metadata.xml
   curl -fsSL https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-wrapper-plugin/maven-metadata.xml
   curl -fsSL https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/maven-metadata.xml
   curl -fsSL https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip -o /private/tmp/apache-maven-3.9.16-bin.zip
   shasum -a 512 /private/tmp/apache-maven-3.9.16-bin.zip
   shasum -a 256 /private/tmp/apache-maven-3.9.16-bin.zip
   ```

   The stable versions above were present. The downloaded ZIP's SHA-512 matched
   Maven Central's published checksum, and the computed SHA-256 was pinned.

3. Official wrapper generation, using the permitted existing Maven 3.9.6/Java 21
   only to run the wrapper generator:

   ```text
   mvn org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper \
     -Dmaven=3.9.16 \
     -Dtype=only-script \
     -DdistributionSha256Sum=5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce
   ```

   Result: `BUILD SUCCESS`; the plugin generated `mvnw`, `mvnw.cmd`, and the
   wrapper properties file targeting Maven 3.9.16.

4. Wrapper and Java identity:

   ```text
   env JAVA_HOME=/private/tmp/reservation-jdk25/jdk-25.0.4.1+1/Contents/Home \
     PATH=/private/tmp/reservation-jdk25/jdk-25.0.4.1+1/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin \
     ./mvnw -version
   ```

   Result: Apache Maven 3.9.16 running on Eclipse Adoptium Java 25.0.4.1.

5. Full reactor verification:

   ```text
   env JAVA_HOME=/private/tmp/reservation-jdk25/jdk-25.0.4.1+1/Contents/Home \
     PATH=/private/tmp/reservation-jdk25/jdk-25.0.4.1+1/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin \
     ./mvnw -B verify
   ```

   Result: `BUILD SUCCESS`. The parent and all five child modules were
   `SUCCESS`. Maven compiled each application with `javac [debug release 25]`.
   No tests ran because the checkpoint contains no behavior and the plan forbids
   artificial scaffolding tests.

6. Executable-jar smoke checks, run once for each service:

   ```text
   env JAVA_HOME=/private/tmp/reservation-jdk25/jdk-25.0.4.1+1/Contents/Home \
     PATH=/private/tmp/reservation-jdk25/jdk-25.0.4.1+1/Contents/Home/bin:/usr/local/bin:/usr/bin:/bin \
     java -jar <service>/target/<service>-0.0.1-SNAPSHOT.jar \
       --spring.main.web-application-type=none --spring.main.banner-mode=off
   ```

   Result: both commands exited 0. `OrderApplication` and
   `InventoryApplication` each reported a successful start using Java 25.0.4.1.

7. Artifact inspection:

   ```text
   unzip -p <service-jar> META-INF/MANIFEST.MF
   javap -verbose <application-class> | rg 'major version'
   ```

   Result: service manifests use the Spring Boot `JarLauncher`, identify the
   correct `Start-Class`, and report Java 25 and Spring Boot 4.1.1. Both classes
   have class-file major version 69. The three contract/module jars have no Boot
   `Main-Class`, `Start-Class`, or `Spring-Boot-Version` manifest entries.

## Self-review

- The root has exactly the five requested child modules.
- Common versions are centralized; dependencies receive exact transitive versions
  from the pinned Spring Boot BOM.
- `maven.compiler.release` is exactly 25.
- Surefire includes only `*Test`; Failsafe includes only `*IT` and is bound to
  `integration-test` and `verify` without a skip switch.
- Boot repackaging appears only in the service POMs.
- Generated build output is under ignored `target/` directories.
- The generated Windows wrapper retains CRLF line endings without producing
  false-positive trailing-whitespace errors in Git checks.
- The workflow runs `./mvnw -B verify` with Temurin Java 25 for pushes and pull
  requests and grants only read access to repository contents.
- Local environment and Docker prerequisites are recorded honestly; the host
  Java default was not changed.
- No execution ledger or task brief was modified.
- No out-of-scope implementation was added.
