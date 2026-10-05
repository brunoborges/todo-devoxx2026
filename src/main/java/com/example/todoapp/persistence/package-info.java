/**
 * JDBC persistence backed by Flyway's versioned schema. Inject {@link
 * com.example.todoapp.persistence.TodoRepository} and {@link
 * com.example.todoapp.persistence.CategoryRepository}; mutation methods participate
 * in Spring transactions, and category deletion detaches references atomically.
 *
 * <p>Todo description, dueAt, and categoryId may be null. Descriptions are preserved
 * verbatim; names are trimmed. Updates preserve completion; setCompleted preserves
 * every other field. Category names are case-insensitively unique and may not be
 * "Uncategorized". Lists sort categories case-insensitively by name, and todos by
 * active first, due instant ascending (null last), then ID. Filters combine with
 * AND; a specific category filter with no matches returns an empty list.
 *
 * <p>Missing get/update/delete targets throw {@link
 * com.example.todoapp.persistence.NotFoundException}. Invalid names and unavailable
 * category references throw {@link com.example.todoapp.persistence.ValidationException},
 * whose field() is "name" or "categoryId". Other database failures propagate as
 * Spring data-access exceptions, never successful results.
 *
 * <p>The local default is jdbc:h2:file:./data/todoapp, relative to the process
 * working directory. The data directory is ignored by Git; retain it across
 * restarts. Flyway applies migrations on startup. No separate database server is
 * needed. Standard Spring datasource properties can override the connection.
 * Tests use unique in-memory databases, except restart tests using temporary files.
 */
package com.example.todoapp.persistence;
