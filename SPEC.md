# Basic Todo App Specification

## Purpose

Build a simple web application for tracking tasks with a name, description, due
date and time, and category. Users can manage both their todos and the categories
used to organize them.

## Scope and defaults

The initial version supports creating, viewing, editing, completing, and deleting
todos, plus creating, renaming, and deleting categories.

The following are proposed defaults for this basic version:

- A single shared task list, with no accounts or authentication.
- A todo requires a name; description, due date/time, and category are optional.
- Each todo belongs to at most one category.
- Deleting a category keeps its todos and makes them uncategorized.
- Todos and categories persist across application restarts.

Recurring tasks, reminders, notifications, subtasks, attachments, collaboration,
and calendar integrations are out of scope.

## Data model

### Todo

| Field | Requirement | Behavior |
| --- | --- | --- |
| ID | Required, system-generated | Stable identifier; not editable by the user. |
| Name | Required | Trim surrounding whitespace; reject empty or whitespace-only names. |
| Description | Optional | Multiline plain text; preserve line breaks when displayed. |
| Due date/time | Optional | One valid date and time together, or neither. |
| Category | Optional | Reference an existing category; no selection means uncategorized. |
| Completed | Required | Boolean; defaults to false. |

Duplicate todo names are allowed. Plain-text fields must be rendered as text,
not interpreted as HTML.

### Category

| Field | Requirement | Behavior |
| --- | --- | --- |
| ID | Required, system-generated | Stable identifier used by todos. |
| Name | Required | Trim surrounding whitespace; reject blank names and case-insensitive duplicates. |

Categories are a flat list, without parent categories. "Uncategorized" is the
display label for a todo without a category, not a stored category; reserve this
name to avoid ambiguity.

## Todo behavior

Users can view all todos and create or edit a todo using a form containing its
name, description, due date/time, and category. The category selector lists
existing categories alphabetically and includes an uncategorized option.

Users can mark a todo complete and reopen it without losing its other fields.
Deleting a todo requires confirmation and permanently removes it.

The task list shows each todo's name, due date/time when set, category, and
completion state. The description is available in the detail or edit view.
Support filtering by completion state (all, active, completed) and category
(all categories, a specific category, uncategorized); filters work together.

Default ordering is active todos first, then completed todos. Within each group,
sort by due date/time ascending, put undated todos last, and use ID as a stable
tie-breaker.

### Due date and time

Use one explicitly configured application time zone for entering, displaying,
and comparing due times. Show the zone beside the due-date/time input and wherever
due times are displayed; do not depend silently on the server's local time zone.
Persist due times as instants so they can be compared consistently.

Allow past due times. An active todo is overdue when its due time is earlier than
the current time. Completed and undated todos are never shown as overdue.
Users can change or clear an existing due time. Reject incomplete, invalid, or
daylight-saving-transition ambiguous/nonexistent local times with a clear
validation message rather than silently adjusting them.

## Category management

Provide a category-management page reachable from the task list, with a list of
existing categories and controls to create, rename, and delete them.

Renaming a category updates the label for every todo referencing it without
changing the todos' category association. Apply uniqueness validation on both
creation and rename, excluding the category being renamed.

Before deletion, show how many todos use the category and explain that those
todos will become uncategorized. Require confirmation. Category deletion and
removal of its todo associations must succeed together or leave the data unchanged.

## User experience and error handling

Provide a task-list page, a create/edit todo form, and a category-management page.
Use clear empty states when there are no todos, no filter matches, or no categories.
Creating a todo must remain possible when there are no categories.

Forms use visible labels, keyboard-accessible controls, and inline validation
messages. Preserve entered values after validation failures. Show completion
and overdue status with text as well as visual styling.

Show clear success feedback after saved changes. Report failed operations
explicitly without claiming success or losing form input. Requests for missing
todos or categories show a not-found message. Reject references to categories
that no longer exist.

## Technical constraints

Use the repository's Java 25, Maven wrapper, Spring Boot, Spring MVC, and Thymeleaf
stack. Keep Java components under `com.example.todoapp`, templates under
`src/main/resources/templates`, and static assets under
`src/main/resources/static`.

Use persistent storage with referential integrity for category associations.
The database choice and schema-migration tooling are implementation decisions,
not prescribed by this specification.

## Acceptance criteria

1. A user can create a todo with only a name and see it as active and uncategorized.
2. A user can create and edit a todo with all four requested fields, and saved
   values are displayed correctly.
3. Blank todo names, blank or duplicate category names, and invalid due times
   produce useful errors without discarding form input or saving partial changes.
4. A user can complete and reopen a todo while retaining its other fields.
5. Past-due active todos are marked overdue; completed and undated todos are not.
6. Completion and category filters combine correctly, and ordering follows the
   specified rules.
7. A new category becomes available in todo forms; renaming it updates existing
   todos' displayed category names.
8. Canceling a deletion leaves data unchanged. Confirming todo deletion removes
   that todo. Confirming category deletion keeps its todos as uncategorized.
9. Todos, completion states, due times, categories, and their associations survive
   an application restart.
10. Empty states, missing records, failed saves, and keyboard-only form use are
    handled as described above.
