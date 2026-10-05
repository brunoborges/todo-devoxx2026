# Repository instructions

## Build and test

- Use JDK 25 (`java.version` in `pom.xml`).
- Use the checked-in Maven wrapper: `./mvnw` on macOS/Linux or `mvnw.cmd` on Windows. It pins Maven 3.9.16.
- Build and run all tests: `./mvnw clean verify`.
- Run all tests: `./mvnw test`.
- Run one test class: `./mvnw -Dtest=TodoappApplicationTests test`.
- Run one test method: `./mvnw '-Dtest=TodoappApplicationTests#contextLoads' test`.
- Run the application locally: `./mvnw spring-boot:run`.
- Package the executable Spring Boot JAR: `./mvnw package`; run it with `java -jar target/todoapp-0.0.1-SNAPSHOT.jar`.

## Architecture

This is a single-module Maven application using Spring Boot 4.1.1, Spring MVC, Thymeleaf, and Spring JDBC. It provides a shared todo list and category management with file-backed H2 persistence and Flyway migrations.

`TodoappApplication` in `com.example.todoapp` bootstraps Spring Boot and establishes the default component-scan root. Keep application components in this package or its subpackages so they are discovered automatically.

The web stack is provided by `spring-boot-starter-webmvc`, with server-rendered view support from `spring-boot-starter-thymeleaf`. When adding MVC views, use Thymeleaf templates in `src/main/resources/templates` and static assets in `src/main/resources/static`, following the selected starters' default resource conventions.

Runtime configuration lives in `src/main/resources/application.properties`, which currently sets `spring.application.name=todoapp`.

`persistence` owns records, repository operations, validation, filtering, and transactional category deletion. Schema changes belong in versioned migrations under `src/main/resources/db/migration`. The default database is stored in the ignored `data/` directory relative to the working directory; preserve it across restarts. Tests use isolated databases, not local application data.

`time` owns strict due-date/time conversion and overdue evaluation using the explicitly configured `app.time-zone` and an injectable `Clock`. `TODO_TIME_ZONE` selects the zone, defaulting to UTC. Reject ambiguous or nonexistent daylight-saving times rather than silently adjusting them.

`todo` and `category` own their MVC controllers and form models. Use repository and time helpers rather than duplicating domain rules. Keep form binding restricted to editable fields, render user text safely, and retain submitted values when reporting validation or save failures.

## Repository conventions

- Dependency and plugin versions are managed by the Spring Boot parent in `pom.xml`. Preserve the Boot 4 starter naming used here, including `spring-boot-starter-webmvc`, `spring-boot-starter-webmvc-test`, and `spring-boot-starter-thymeleaf-test`.
- Tests mirror the application's package under `src/test/java`. The existing `TodoappApplicationTests` uses JUnit Jupiter, a package-private test class and method, and `@SpringBootTest` to load the complete application context.
- Existing Java and Maven XML files use tab indentation.
- Maven build output belongs in the ignored `target/` directory.
