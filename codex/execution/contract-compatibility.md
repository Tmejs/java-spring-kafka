# Contract-generation compatibility recommendation

Research date: 2026-09-23. Target runtime: Spring Boot 4.1.1, Java 25,
Jackson 3.

## Decision

Pin `org.openapitools:openapi-generator-maven-plugin:7.25.0`. It is the
current stable release and its Java `spring` and `java` generators both have
explicit Spring Boot 4/Jackson 3 switches. Version 7.21.0 is the oldest sensible
fallback: that release introduced Jackson 3 support for the Spring generator and
the Java native, RestClient, WebClient, and RestTemplate clients. Do not use
7.20.0; its initial Spring option processing had a reported `useJackson3` /
`useSpringBoot4` ordering failure. Releases 7.21.0 through 7.25.0 are viable for
this shape, but 7.25.0 is preferred for its later fixes and is the released tag.

Generate the clients with the JDK `HttpClient` (`library=native`) and Jackson 3.
This keeps the client module independent of Spring while avoiding a second,
Jackson-2 databind stack inside Boot 4.1.1 applications. Seeing
`com.fasterxml.jackson.core:jackson-annotations` is expected: Jackson 3 retained
the 2.x annotations artifact and package. The classpath must not contain
`com.fasterxml.jackson.core:jackson-databind`; generated databind imports should
be `tools.jackson.databind.*`.

## Exact generator configuration

Use one execution per specification and a distinct `output` directory per
execution. Leave generated material under `target/`; the plugin adds its generated
source directory as a compile root by default.

Spring server interface executions (`generatorName=spring`,
`library=spring-boot`):

```xml
<apiPackage>io.github.tmejs.reservation.api.orders</apiPackage>
<modelPackage>io.github.tmejs.reservation.api.orders.model</modelPackage>
<generateSupportingFiles>false</generateSupportingFiles>
<generateApiTests>false</generateApiTests>
<generateModelTests>false</generateModelTests>
<generateApiDocumentation>false</generateApiDocumentation>
<generateModelDocumentation>false</generateModelDocumentation>
<configOptions>
  <interfaceOnly>true</interfaceOnly>
  <skipDefaultInterface>true</skipDefaultInterface>
  <requestMappingMode>api_interface</requestMappingMode>
  <useSpringBoot4>true</useSpringBoot4>
  <useJackson3>true</useJackson3>
  <useJakartaEe>true</useJakartaEe>
  <useBeanValidation>true</useBeanValidation>
  <performBeanValidation>false</performBeanValidation>
  <useResponseEntity>true</useResponseEntity>
  <useTags>true</useTags>
  <documentationProvider>none</documentationProvider>
  <annotationLibrary>none</annotationLibrary>
  <openApiNullable>false</openApiNullable>
  <useJspecify>true</useJspecify>
  <dateLibrary>java8</dateLibrary>
  <hideGenerationTimestamp>true</hideGenerationTimestamp>
  <disallowAdditionalPropertiesIfNotPresent>false</disallowAdditionalPropertiesIfNotPresent>
  <generateJsonIncludeAnnotations>false</generateJsonIncludeAnnotations>
  <generateJsonSetterNullsAnnotations>false</generateJsonSetterNullsAnnotations>
</configOptions>
```

Use the corresponding inventory packages for its execution. `useSpringBoot4`
automatically disables the generator's Boot 3 default and selects Jakarta imports.
`requestMappingMode=api_interface` matters with `interfaceOnly=true`: the path
mapping belongs on the interface implemented by the hand-written controller.
`skipDefaultInterface=true` removes placeholder response bodies and their
`ApiUtil` helper. `documentationProvider=none` and `annotationLibrary=none` keep
the checked-in YAML authoritative rather than reconstructing a contract from
annotations. `openApiNullable=false` is sufficient because these APIs do not need
PATCH-style absent-versus-explicit-null state.

Java client executions (`generatorName=java`, `library=native`):

```xml
<apiPackage>io.github.tmejs.reservation.client.orders.api</apiPackage>
<modelPackage>io.github.tmejs.reservation.client.orders.model</modelPackage>
<invokerPackage>io.github.tmejs.reservation.client.orders</invokerPackage>
<generateApiTests>false</generateApiTests>
<generateModelTests>false</generateModelTests>
<generateApiDocumentation>false</generateApiDocumentation>
<generateModelDocumentation>false</generateModelDocumentation>
<configOptions>
  <useJackson3>true</useJackson3>
  <useJakartaEe>true</useJakartaEe>
  <useJspecify>true</useJspecify>
  <useBeanValidation>false</useBeanValidation>
  <openApiNullable>false</openApiNullable>
  <annotationLibrary>none</annotationLibrary>
  <dateLibrary>java8</dateLibrary>
  <hideGenerationTimestamp>true</hideGenerationTimestamp>
  <disallowAdditionalPropertiesIfNotPresent>false</disallowAdditionalPropertiesIfNotPresent>
</configOptions>
```

Do not disable client supporting files: the native client needs generated
`ApiClient`, JSON, auth, configuration, and exception helpers. OAuth2 bearer
security in the specification produces a bearer auth helper; callers can inject
their access token through the generated client instead of storing credentials.

## Minimal compile/runtime dependencies

For each service, generated interfaces/models are covered by these normal Boot
dependencies:

- `org.springframework.boot:spring-boot-starter-webmvc`
- `org.springframework.boot:spring-boot-starter-validation`
- `org.jspecify:jspecify` (explicit because generated source imports it)
- `jakarta.annotation:jakarta.annotation-api` (explicit/provided is acceptable for
  generated `@Generated`)

The Boot 4.1.1 BOM supplies Jackson 3 through the web/JSON starter. Do not add
Jackson 2 databind. For Swagger UI that is pointed at the original resource, use
`org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1`; springdoc documents v3
as the Boot 4 line. Disable its generated API-doc endpoint and set
`springdoc.swagger-ui.url` to the served contract resource.

For `api-clients`, declare the dependencies used by the native Jackson 3 template:

- `tools.jackson.core:jackson-core`
- `tools.jackson.core:jackson-databind`
- `com.fasterxml.jackson.core:jackson-annotations`
- `org.openapitools:jackson-databind-nullable:0.2.11`
- `org.jspecify:jspecify`
- `jakarta.annotation:jakarta.annotation-api` with `provided` scope

The nullable module is still used by the generated native client's JSON support
even with `openApiNullable=false`; 0.2.11 supports both Jackson 2 and Jackson 3 and
declares both databind implementations as `provided`, so it does not pull Jackson
2 databind transitively. Let the Boot parent BOM manage Jackson/Jakarta versions;
pin 0.2.11 and JSpecify 1.0.0 if they are not managed.

## Contract JAR and clean-reactor resolution

Keep `api-contracts` as a normal `jar` module with the YAML under
`src/main/resources/openapi/`. In every generator consumer, declare a real project
dependency on `api-contracts`; a coordinate that appears only inside plugin
configuration does **not** establish Maven reactor ordering. Bind
`maven-dependency-plugin:3.11.0:unpack` to `initialize`, before OpenAPI generation
at `generate-sources`:

```xml
<dependency>
  <groupId>${project.groupId}</groupId>
  <artifactId>api-contracts</artifactId>
  <version>${project.version}</version>
</dependency>
...
<execution>
  <id>unpack-api-contracts</id>
  <phase>initialize</phase>
  <goals><goal>unpack</goal></goals>
  <configuration>
    <artifactItems>
      <artifactItem>
        <groupId>${project.groupId}</groupId>
        <artifactId>api-contracts</artifactId>
        <version>${project.version}</version>
        <type>jar</type>
        <outputDirectory>${project.build.directory}/contracts</outputDirectory>
        <includes>openapi/*.yaml</includes>
      </artifactItem>
    </artifactItems>
    <markersDirectory>${project.build.directory}/dependency-maven-plugin-markers</markersDirectory>
  </configuration>
</execution>
```

Point generation at
`${project.build.directory}/contracts/openapi/orders.yaml` or `inventory.yaml`.
In `./mvnw clean verify`, Maven sorts `api-contracts` first because of the concrete
project dependency and completes that module through `package` before entering the
consumer's `initialize` phase, so its JAR is available from the reactor without a
prior `install`. The same is true for `-pl api-clients -am verify`; invoking a child
POM alone without `-am` legitimately requires an installed contract artifact.

Keep the `api-contracts` dependency in each service at runtime as well, then map
`/openapi/**` to `classpath:/openapi/` in `OpenApiConfiguration`. This serves the
exact YAML from the dependency JAR while Swagger UI is configured only as a viewer.

## Primary sources

- [OpenAPI Generator 7.25.0 stable release](https://github.com/OpenAPITools/openapi-generator/releases/tag/v7.25.0)
- [7.21.0 release notes: Spring Boot 4/Jackson 3 server and Java clients](https://github.com/OpenAPITools/openapi-generator/releases/tag/v7.21.0)
- [Spring generator options](https://openapi-generator.tech/docs/generators/spring/)
- [Java client generator options](https://openapi-generator.tech/docs/generators/java/)
- [7.25.0 Spring generator option processing](https://github.com/OpenAPITools/openapi-generator/blob/v7.25.0/modules/openapi-generator/src/main/java/org/openapitools/codegen/languages/SpringCodegen.java#L602-L650)
- [7.25.0 native-client dependency template](https://github.com/OpenAPITools/openapi-generator/blob/v7.25.0/modules/openapi-generator/src/main/resources/Java/libraries/native/pom.mustache#L196-L305)
- [Maven plugin configuration reference](https://github.com/OpenAPITools/openapi-generator/blob/v7.25.0/modules/openapi-generator-maven-plugin/README.md)
- [jackson-databind-nullable 0.2.11 POM](https://github.com/OpenAPITools/jackson-databind-nullable/blob/v0.2.11/pom.xml#L78-L100)
- [Maven reactor sorting rules](https://maven.apache.org/guides/mini/guide-multiple-modules.html#reactor-sorting)
- [Maven dependency plugin unpack example](https://maven.apache.org/plugins/maven-dependency-plugin/examples/unpacking-artifacts.html)
- [springdoc Boot 4 support and starter](https://github.com/springdoc/springdoc-openapi#readme)
- [springdoc 3.1.1 release](https://github.com/springdoc/springdoc-openapi/releases/tag/v3.1.1)
