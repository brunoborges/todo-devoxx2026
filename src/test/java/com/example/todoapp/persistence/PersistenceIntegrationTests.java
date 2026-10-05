package com.example.todoapp.persistence;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest(classes = PersistenceTestConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PersistenceIntegrationTests {

	@Autowired
	private TodoRepository todos;

	@Autowired
	private CategoryRepository categories;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@BeforeEach
	void clearDatabase() {
		jdbc.update("DELETE FROM todos");
		jdbc.update("DELETE FROM categories");
	}

	@Test
	void createsMinimalTodosWithGeneratedStableIdsAndAllowsDuplicateNames() {
		Todo first = todos.create("  Same name  ", null, null, null);
		Todo second = todos.create("Same name", null, null, null);

		assertThat(first.id()).isPositive();
		assertThat(second.id()).isGreaterThan(first.id());
		assertThat(first).isEqualTo(new Todo(first.id(), "Same name", null, null, null, false));
		assertThat(todos.get(first.id())).isEqualTo(first);
		assertThat(allTodos()).containsExactly(first, second);
	}

	@Test
	void trimsWhitespaceIncludingUnicodeAroundNames() {
		Category category = categories.create("\u2003\tWork\n\u2003");
		Todo todo = todos.create("\n\u2003 Task \t", null, null, category.id());
		assertThat(category.name()).isEqualTo("Work");
		assertThat(todo.name()).isEqualTo("Task");
		assertNameValidation(() -> categories.create("\u2003work\u2003"));
		assertNameValidation(() -> categories.create("\u2003Uncategorized\u2003"));
	}

	@Test
	void editsAllFieldsAndClearsOptionalFieldsWithoutLosingCompletionOrId() {
		Category firstCategory = categories.create("First");
		Category secondCategory = categories.create("Second");
		Todo original = todos.create("Original", "First\nSecond\r\nThird",
				Instant.parse("2020-01-01T12:34:56.123456789Z"), firstCategory.id());
		Todo completed = todos.setCompleted(original.id(), true);
		assertThat(completed).isEqualTo(new Todo(original.id(), original.name(), original.description(),
				original.dueAt(), original.categoryId(), true));

		Instant due = Instant.parse("2031-02-03T01:02:03.987654321Z");
		Todo edited = todos.update(original.id(), "  Edited  ", "<script>\nPlain text\n", due, secondCategory.id());
		assertThat(edited).isEqualTo(new Todo(original.id(), "Edited", "<script>\nPlain text\n",
				due, secondCategory.id(), true));
		Todo reopened = todos.setCompleted(original.id(), false);
		assertThat(reopened).isEqualTo(new Todo(original.id(), edited.name(), edited.description(),
				due, secondCategory.id(), false));
		assertThat(todos.setCompleted(original.id(), false)).isEqualTo(reopened);

		assertThat(todos.update(original.id(), "Cleared", null, null, null))
				.isEqualTo(new Todo(original.id(), "Cleared", null, null, null, false));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t\r\n", "\u2003"})
	void rejectsBlankTodoAndCategoryNamesWithoutSavingAnything(String name) {
		assertNameValidation(() -> todos.create(name, null, null, null));
		assertNameValidation(() -> categories.create(name));
		assertThat(allTodos()).isEmpty();
		assertThat(categories.findAll()).isEmpty();
	}

	@Test
	void failedTodoEditKeepsEveryExistingField() {
		Category category = categories.create("Existing");
		Todo original = todos.create("Original", "Keep\nthis", Instant.parse("2000-01-01T00:00:00Z"), category.id());
		assertNameValidation(() -> todos.update(original.id(), " \n ", "Changed", null, null));
		assertThat(todos.get(original.id())).isEqualTo(original);

		ValidationException missing = catchThrowableOfType(ValidationException.class,
				() -> todos.update(original.id(), "Changed", null, null, Long.MAX_VALUE));
		assertThat(missing.field()).isEqualTo("categoryId");
		assertThat(todos.get(original.id())).isEqualTo(original);
	}

	@Test
	void rejectsMissingAndDeletedCategoryReferencesWithFieldValidation() {
		Category deleted = categories.create("Temporary");
		categories.delete(deleted.id());

		ValidationException exception = catchThrowableOfType(ValidationException.class,
				() -> todos.create("Invalid", null, null, deleted.id()));
		assertThat(exception.field()).isEqualTo("categoryId");
		assertThat(exception).hasMessageContaining("no longer exists");
		assertThat(allTodos()).isEmpty();
	}

	@Test
	void categoriesAreTrimmedSortedAndRenamedWithoutChangingAssociations() {
		Category zebra = categories.create(" zebra ");
		Category alpha = categories.create("Alpha");
		Category beta = categories.create("beta");
		Todo first = todos.create("First", null, null, zebra.id());
		Todo second = todos.create("Second", null, null, zebra.id());
		todos.setCompleted(second.id(), true);
		todos.create("Uncategorized", null, null, null);

		assertThat(categories.findAll()).containsExactly(alpha, beta, zebra);
		assertThat(categories.countTodos(zebra.id())).isEqualTo(2);
		assertThat(categories.countTodos(alpha.id())).isZero();
		assertThat(categories.rename(zebra.id(), " ZEBRA ")).isEqualTo(new Category(zebra.id(), "ZEBRA"));
		assertThat(todos.get(first.id())).isEqualTo(first);
		assertThat(categories.get(first.categoryId()).name()).isEqualTo("ZEBRA");
	}

	@ParameterizedTest
	@ValueSource(strings = {"Uncategorized", "  uNcAtEgOrIzEd  "})
	void rejectsReservedCategoryNamesForCreateAndRename(String name) {
		Category original = categories.create("Original");
		assertNameValidation(() -> categories.create(name));
		assertNameValidation(() -> categories.rename(original.id(), name));
		assertThat(categories.findAll()).containsExactly(original);
	}

	@Test
	void rejectsNormalizedDuplicateCategoriesAndRollsBackRename() {
		Category first = categories.create(" Work ");
		Category second = categories.create("Home");
		assertNameValidation(() -> categories.create(" wOrK "));
		assertNameValidation(() -> categories.rename(second.id(), " WORK "));
		assertNameValidation(() -> categories.rename(second.id(), " \t "));
		assertThat(categories.get(second.id())).isEqualTo(second);
		assertThat(categories.findAll()).containsExactly(second, first);
	}

	@Test
	void databaseEnforcesForeignKeysOnInsertAndUpdate() {
		Todo todo = todos.create("Uncategorized", null, null, null);
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO todos (name, category_id) VALUES ('Invalid', ?)", Long.MAX_VALUE))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE todos SET category_id = ? WHERE id = ?",
				Long.MAX_VALUE, todo.id())).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(todos.get(todo.id())).isEqualTo(todo);

		Category category = categories.create("Referenced");
		todos.update(todo.id(), todo.name(), null, null, category.id());
		assertThatThrownBy(() -> jdbc.update("DELETE FROM categories WHERE id = ?", category.id()))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(categories.get(category.id())).isEqualTo(category);
	}

	@Test
	void databaseEnforcesNormalizedUniquenessEvenOutsideRepositories() {
		Category work = categories.create("Work");
		Category home = categories.create("Home");
		assertThatThrownBy(() -> jdbc.update("INSERT INTO categories (name) VALUES ('wOrK')"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("INSERT INTO categories (name) VALUES (' Work ')"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE categories SET name = 'WORK' WHERE id = ?", home.id()))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(categories.findAll()).containsExactly(home, work);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "Uncategorized", "uNCategorized"})
	void databaseRejectsBlankAndReservedCategoryNames(String name) {
		assertThatThrownBy(() -> jdbc.update("INSERT INTO categories (name) VALUES (?)", name))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void categoryDeletionDetachesTodosPreservingAllOtherFieldsAndOtherCategories() {
		Category deleted = categories.create("Deleted");
		Category retained = categories.create("Retained");
		Todo first = todos.create("First", "Multi\nline", Instant.parse("2025-01-01T12:00:00Z"), deleted.id());
		Todo second = todos.setCompleted(todos.create("Second", null, null, deleted.id()).id(), true);
		Todo third = todos.create("Third", "Retained", null, retained.id());
		Todo fourth = todos.create("Fourth", null, null, null);
		categories.delete(deleted.id());

		assertThat(todos.get(first.id())).isEqualTo(withoutCategory(first));
		assertThat(todos.get(second.id())).isEqualTo(withoutCategory(second));
		assertThat(todos.get(third.id())).isEqualTo(third);
		assertThat(todos.get(fourth.id())).isEqualTo(fourth);
		assertThat(categories.findAll()).containsExactly(retained);
		assertThatThrownBy(() -> categories.get(deleted.id())).isInstanceOf(NotFoundException.class);
		categories.delete(retained.id());
		assertThat(allTodos()).hasSize(4);
	}

	@Test
	void deleteFailureAfterDetachingRollsBackBothCategoryAndTodoAssociations() {
		Category category = categories.create("Blocked");
		Todo todo = todos.create("Keep", "Do not change\nthis", Instant.parse("2025-06-01T00:00:00Z"), category.id());
		jdbc.execute("CREATE TABLE deletion_guard (category_id BIGINT REFERENCES categories(id))");
		try {
			jdbc.update("INSERT INTO deletion_guard (category_id) VALUES (?)", category.id());
			assertThatThrownBy(() -> categories.delete(category.id()))
					.isInstanceOf(DataIntegrityViolationException.class);
			assertThat(categories.get(category.id())).isEqualTo(category);
			assertThat(todos.get(todo.id())).isEqualTo(todo);
		}
		finally {
			jdbc.execute("DROP TABLE deletion_guard");
		}
	}

	@Test
	void repositoryMutationsParticipateInCallerTransactions() {
		Category category = categories.create("Keep");
		Todo todo = todos.create("Original", null, null, category.id());
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
			todos.update(todo.id(), "Changed", "Changed", null, category.id());
			todos.setCompleted(todo.id(), true);
			categories.delete(category.id());
			throw new IllegalStateException("Forced rollback");
		})).isInstanceOf(IllegalStateException.class).hasMessage("Forced rollback");
		assertThat(todos.get(todo.id())).isEqualTo(todo);
		assertThat(categories.get(category.id())).isEqualTo(category);
	}

	@Test
	void filtersCombineForEveryCompletionAndCategoryModeAndKeepStableOrdering() {
		Category work = categories.create("Work");
		Category home = categories.create("Home");
		Category empty = categories.create("Empty");
		Instant early = Instant.parse("2000-01-01T00:00:00Z");
		Instant late = Instant.parse("2040-01-01T00:00:00Z");
		Todo activeUndated = todos.create("Active undated", null, null, work.id());
		Todo completedEarly = todos.setCompleted(todos.create("Completed early", null, early, work.id()).id(), true);
		Todo activeLate = todos.create("Active late", null, late, null);
		Todo activeTieOne = todos.create("Tie one", null, early, work.id());
		Todo activeTieTwo = todos.create("Tie two", null, early, home.id());
		Todo completedUndated = todos.setCompleted(todos.create("Completed undated", null, null, null).id(), true);
		Todo activeUndatedTwo = todos.create("Active undated two", null, null, null);
		Todo completedEarlyTwo = todos.setCompleted(todos.create("Completed early two", null, early, home.id()).id(), true);
		Todo completedLate = todos.setCompleted(todos.create("Completed late", null, late, null).id(), true);
		Todo completedUndatedTwo = todos.setCompleted(todos.create("Completed undated two", null, null, home.id()).id(), true);
		List<Todo> expected = List.of(activeTieOne, activeTieTwo, activeLate, activeUndated, activeUndatedTwo,
				completedEarly, completedEarlyTwo, completedLate, completedUndated, completedUndatedTwo);
		assertThat(allTodos()).containsExactlyElementsOf(expected);

		for (CompletionFilter completion : CompletionFilter.values()) {
			for (CategoryFilter category : List.of(CategoryFilter.all(), CategoryFilter.uncategorized(),
					CategoryFilter.category(work.id()), CategoryFilter.category(home.id()),
					CategoryFilter.category(empty.id()), CategoryFilter.category(Long.MAX_VALUE))) {
				Stream<Todo> matching = expected.stream();
				if (completion != CompletionFilter.ALL) {
					matching = matching.filter(todo -> todo.completed() == (completion == CompletionFilter.COMPLETED));
				}
				matching = switch (category.mode()) {
					case ALL -> matching;
					case UNCATEGORIZED -> matching.filter(todo -> todo.categoryId() == null);
					case CATEGORY -> matching.filter(todo -> category.categoryId().equals(todo.categoryId()));
				};
				assertThat(todos.findAll(completion, category))
						.as("completion=%s category=%s", completion, category)
						.containsExactlyElementsOf(matching.toList());
			}
		}
	}

	@Test
	void rejectsInvalidFilterContractsRatherThanSilentlyChangingTheQuery() {
		assertThatThrownBy(() -> new CategoryFilter(CategoryFilter.Mode.CATEGORY, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CategoryFilter(CategoryFilter.Mode.ALL, 1L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CategoryFilter(CategoryFilter.Mode.UNCATEGORIZED, 1L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CategoryFilter(null, null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> todos.findAll(null, CategoryFilter.all())).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> todos.findAll(CompletionFilter.ALL, null)).isInstanceOf(NullPointerException.class);
	}

	@Test
	void missingRecordsReportNotFoundForEveryTargetedOperation() {
		long id = Long.MAX_VALUE;
		NotFoundException exception = catchThrowableOfType(NotFoundException.class, () -> todos.get(id));
		assertThat(exception.entity()).isEqualTo("Todo");
		assertThat(exception.id()).isEqualTo(id);
		assertThat(exception).hasMessageContaining("not found");
		assertThatThrownBy(() -> todos.update(id, "Missing", null, null, null)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> todos.setCompleted(id, true)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> todos.delete(id)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> categories.get(id)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> categories.rename(id, "Missing")).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> categories.countTodos(id)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> categories.delete(id)).isInstanceOf(NotFoundException.class);
	}

	@Test
	void todoDeletionDoesNotDeleteItsCategoryOrReuseItsId() {
		Category category = categories.create("Keep");
		Todo deleted = todos.create("Deleted", null, null, category.id());
		todos.delete(deleted.id());
		assertThat(allTodos()).isEmpty();
		assertThat(categories.countTodos(category.id())).isZero();
		assertThat(categories.get(category.id())).isEqualTo(category);
		assertThatThrownBy(() -> todos.get(deleted.id())).isInstanceOf(NotFoundException.class);
		assertThat(todos.create("New", null, null, category.id()).id()).isGreaterThan(deleted.id());
	}

	private List<Todo> allTodos() {
		return todos.findAll(CompletionFilter.ALL, CategoryFilter.all());
	}

	private static Todo withoutCategory(Todo todo) {
		return new Todo(todo.id(), todo.name(), todo.description(), todo.dueAt(), null, todo.completed());
	}

	private static void assertNameValidation(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
		ValidationException exception = catchThrowableOfType(ValidationException.class, operation);
		assertThat(exception.field()).isEqualTo("name");
		assertThat(exception).hasMessageNotContaining("SQL");
	}
}
