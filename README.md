# Todo app

A small, server-rendered todo application built with Java 25, Spring Boot,
Spring MVC, and Thymeleaf. Track names, multiline descriptions, due dates and
times, completion, and categories. See [SPEC.md](SPEC.md) for the requirements.

## Run locally

Install JDK 25, then use the checked-in Maven wrapper:

```sh
./mvnw spring-boot:run
```

Open <http://localhost:8080/> for todos and
<http://localhost:8080/categories> to manage categories.

The app has one shared list and no authentication. It is intended for local,
trusted use, not exposure as a public multi-user service.

## Configuration and storage

Due dates use the explicitly configured application time zone, UTC by default.
Set `TODO_TIME_ZONE` to an IANA zone identifier before starting the application:

```sh
TODO_TIME_ZONE=Europe/Paris ./mvnw spring-boot:run
```

The application displays the zone with due times. Both a date and time are
required when setting a deadline; leave both empty to remove it. Past deadlines
are allowed. Local times that are nonexistent or ambiguous during a daylight
saving transition are rejected, rather than silently adjusted. Invalid time-zone
configuration prevents startup.

No external database server is required. H2 stores data in `./data/todoapp`,
relative to the process working directory. Keep this ignored directory across
restarts to retain todos and categories. Start from the same working directory
each time, and do not run multiple application instances against the same file.
To use another location, set Spring Boot's `SPRING_DATASOURCE_URL`, for example:

```sh
SPRING_DATASOURCE_URL='jdbc:h2:file:/absolute/path/todoapp;DB_CLOSE_ON_EXIT=FALSE' \
  ./mvnw spring-boot:run
```

Flyway automatically applies versioned migrations from
`src/main/resources/db/migration`. Due times are stored as instants. Foreign keys
protect category associations; deleting a category atomically uncategorizes its
todos without deleting them.

## Build and test

```sh
# Build, run all tests, and package the application
./mvnw clean verify

# Run tests without a clean build
./mvnw test

# Run the executable JAR after packaging
java -jar target/todoapp-0.0.1-SNAPSHOT.jar
```

Tests use isolated in-memory databases, with temporary file databases for
restart coverage, rather than the local application's `data/` directory.

## Interactive acceptance checklist

These checks complement automated persistence, time-handling, and MVC tests:

- Navigate between todos and categories using only the keyboard. Check visible
  focus, labels, error messages, and deletion confirmation/cancel controls.
- Create a todo without a category, then create a category and assign it while
  editing the todo. Rename the category and confirm its updated label.
- Enter a multiline description and a past due time; check line breaks, the
  displayed zone, and textual overdue status. Complete and reopen the todo.
- Try completion and category filters together, including uncategorized todos
  and combinations with no results.
- Submit invalid names or incomplete due times and confirm entered values are
  preserved. Clear both date and time to remove a deadline.
- Cancel a category deletion, then confirm it. Its todos must remain and become
  uncategorized.
- Restart the app from the same directory and verify saved values and completion
  states remain.