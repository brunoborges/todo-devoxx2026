package com.example.todoapp.todo;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import com.example.todoapp.persistence.Category;
import com.example.todoapp.persistence.CategoryFilter;
import com.example.todoapp.persistence.CategoryRepository;
import com.example.todoapp.persistence.CompletionFilter;
import com.example.todoapp.persistence.NotFoundException;
import com.example.todoapp.persistence.Todo;
import com.example.todoapp.persistence.TodoRepository;
import com.example.todoapp.persistence.ValidationException;
import com.example.todoapp.time.DueTimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(TodoController.class)
@Import(TodoMvcTests.TimeConfig.class)
class TodoMvcTests {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	TodoRepository todos;

	@MockitoBean
	CategoryRepository categories;

	@BeforeEach
	void defaults() {
		when(categories.findAll()).thenReturn(List.of());
		when(todos.findAll(any(), any())).thenReturn(List.of());
	}

	@Test
	void nameOnlyFormWorksWithoutCategoriesAndIgnoresOverposting() throws Exception {
		mvc.perform(get("/todos/new")).andExpect(status().isOk())
				.andExpect(content().string(containsString("No categories yet")))
				.andExpect(content().string(containsString("Uncategorized")))
				.andExpect(content().string(containsString("step=\"any\"")))
				.andExpect(content().string(containsString("UTC")));
		mvc.perform(post("/todos").param("name", "Write").param("id", "888").param("completed", "true"))
				.andExpect(redirectedUrl("/")).andExpect(flash().attribute("success", "Task created."));
		verify(todos).create("Write", "", null, null);
		verify(todos, never()).setCompleted(anyLong(), anyBoolean());
	}

	@Test
	void fullCreateSupportsNanosecondsAndCategory() throws Exception {
		when(categories.get(7)).thenReturn(new Category(7, "Work"));
		mvc.perform(post("/todos").param("name", "Full").param("description", "First\nSecond")
				.param("dueDate", "2026-10-06").param("dueTime", "09:00:12.123456789")
				.param("categoryId", "7")).andExpect(redirectedUrl("/"));
		verify(todos).create("Full", "First\nSecond", Instant.parse("2026-10-06T09:00:12.123456789Z"), 7L);
	}

	@Test
	void editDisplaysEscapedMultilineTextAndExactDueValue() throws Exception {
		when(todos.get(1)).thenReturn(new Todo(1, "<script>name</script>", "<b>First</b>\nSecond",
				Instant.parse("2026-10-06T09:00:12.123456789Z"), null, false));
		mvc.perform(get("/todos/1/edit")).andExpect(status().isOk())
				.andExpect(content().string(containsString("&lt;script&gt;name&lt;/script&gt;")))
				.andExpect(content().string(containsString("&lt;b&gt;First&lt;/b&gt;\nSecond")))
				.andExpect(content().string(not(containsString("<b>First</b>"))))
				.andExpect(content().string(containsString("09:00:12.123456789")))
				.andExpect(content().string(containsString("UTC")));
	}

	@Test
	void validationPreservesAllEntriesAndDoesNotSave() throws Exception {
		when(categories.get(7)).thenReturn(new Category(7, "Work"));
		when(categories.findAll()).thenReturn(List.of(new Category(7, "Work")));
		mvc.perform(post("/todos").param("name", "   ").param("description", "<img>\nkeep")
				.param("dueDate", "2026-02-30").param("dueTime", "09:20").param("categoryId", "7"))
				.andExpect(status().isBadRequest()).andExpect(view().name("todos/form"))
				.andExpect(model().attributeHasFieldErrors("todoForm", "name", "dueDate"))
				.andExpect(content().string(containsString("&lt;img&gt;\nkeep")))
				.andExpect(content().string(containsString("Entered date: <span>2026-02-30</span>")))
				.andExpect(content().string(containsString("value=\"7\" selected=\"selected\"")));
		verify(todos, never()).create(anyString(), any(), any(), any());
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"''|09:30|dueDate",
			"2026-10-06|''|dueTime",
			"2026-10-06|25:30|dueTime",
			"2026-10-06|not-a-time|dueTime"
	})
	void incompleteAndInvalidTimesStayOnForm(String date, String time, String field) throws Exception {
		mvc.perform(post("/todos").param("name", "Keep").param("description", "My notes")
				.param("dueDate", date).param("dueTime", time))
				.andExpect(status().isBadRequest()).andExpect(view().name("todos/form"))
				.andExpect(model().attributeHasFieldErrors("todoForm", field))
				.andExpect(content().string(containsString("My notes")))
				.andExpect(content().string(containsString(time)));
		verify(todos, never()).create(anyString(), any(), any(), any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"bad", "-1", "9223372036854775808", "404"})
	void rejectsInvalidAndMissingCategoryAndRetainsSelection(String category) throws Exception {
		when(categories.get(404)).thenThrow(new NotFoundException("Category", 404));
		mvc.perform(post("/todos").param("name", "Keep").param("categoryId", category))
				.andExpect(status().isBadRequest())
				.andExpect(model().attributeHasFieldErrors("todoForm", "categoryId"))
				.andExpect(content().string(containsString("Unavailable selection: " + category)));
		verify(todos, never()).create(anyString(), any(), any(), any());
	}

	@Test
	void catchesCategoryDisappearanceDuringSave() throws Exception {
		when(categories.get(7)).thenReturn(new Category(7, "Work"));
		when(todos.create("Keep", "", null, 7L))
				.thenThrow(new ValidationException("categoryId", "The selected category no longer exists."));
		mvc.perform(post("/todos").param("name", "Keep").param("categoryId", "7"))
				.andExpect(status().isBadRequest())
				.andExpect(model().attributeHasFieldErrors("todoForm", "categoryId"))
				.andExpect(content().string(containsString("no longer exists")));
	}

	@Test
	void saveFailurePreservesInputAndDoesNotClaimSuccess() throws Exception {
		when(todos.update(1, "Keep", "One\nTwo", null, null)).thenThrow(storageFailure());
		mvc.perform(post("/todos/1").param("name", "Keep").param("description", "One\nTwo"))
				.andExpect(status().isServiceUnavailable()).andExpect(view().name("todos/form"))
				.andExpect(content().string(containsString("One\nTwo")))
				.andExpect(content().string(containsString("Your task could not be saved")))
				.andExpect(flash().attributeCount(0));
	}

	@Test
	void creationAndCategoryCheckFailuresNeverClaimSuccess() throws Exception {
		when(todos.create("Keep", "", null, null)).thenThrow(storageFailure());
		mvc.perform(post("/todos").param("name", "Keep")).andExpect(status().isServiceUnavailable())
				.andExpect(content().string(containsString("Your task could not be saved")))
				.andExpect(flash().attributeCount(0));
		when(categories.get(7)).thenThrow(storageFailure());
		mvc.perform(post("/todos").param("name", "Other").param("categoryId", "7"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().string(containsString("Categories could not be checked")))
				.andExpect(content().string(containsString("Unavailable selection: 7")))
				.andExpect(flash().attributeCount(0));
		verify(todos, never()).create("Other", "", null, 7L);
	}

	@Test
	void categoryLoadingFailurePreservesFormRatherThanPretendingThereAreNoCategories() throws Exception {
		when(categories.findAll()).thenThrow(storageFailure());
		mvc.perform(post("/todos").param("name", "").param("description", "Keep this").param("categoryId", "7"))
				.andExpect(status().isServiceUnavailable()).andExpect(view().name("todos/form"))
				.andExpect(content().string(containsString("Categories could not be loaded")))
				.andExpect(content().string(containsString("Keep this")))
				.andExpect(content().string(not(containsString("No categories yet"))));
		verify(todos, never()).create(anyString(), any(), any(), any());
	}

	@Test
	void listCombinesFiltersAndShowsExplicitStatusesAndZone() throws Exception {
		when(categories.get(7)).thenReturn(new Category(7, "<Work>"));
		when(categories.findAll()).thenReturn(List.of(new Category(7, "<Work>")));
		when(todos.findAll(CompletionFilter.ACTIVE, CategoryFilter.category(7))).thenReturn(List.of(
				new Todo(1, "<Read>", null, Instant.parse("2026-10-04T12:00:00Z"), 7L, false),
				new Todo(2, "Undated", null, null, 7L, false)));
		mvc.perform(get("/").param("completion", "active").param("category", "7"))
				.andExpect(status().isOk()).andExpect(content().string(containsString("&lt;Read&gt;")))
				.andExpect(content().string(containsString("&lt;Work&gt;")))
				.andExpect(content().string(containsString(">Active</span>")))
				.andExpect(content().string(containsString(">Overdue</span>")))
				.andExpect(content().string(containsString("2026-10-04 12:00:00 UTC")))
				.andExpect(content().string(containsString("No due date")));
		verify(todos).findAll(CompletionFilter.ACTIVE, CategoryFilter.category(7));
	}

	@Test
	void completedAndUndatedAreNeverOverdue() throws Exception {
		when(todos.findAll(any(), any())).thenReturn(List.of(
				new Todo(1, "Done", null, Instant.parse("2000-01-01T00:00:00Z"), null, true),
				new Todo(2, "Undated", null, null, null, false)));
		mvc.perform(get("/")).andExpect(status().isOk())
				.andExpect(content().string(containsString(">Completed</span>")))
				.andExpect(content().string(not(containsString(">Overdue</span>"))));
	}

	@Test
	void emptyAndFilteredEmptyStatesDiffer() throws Exception {
		mvc.perform(get("/")).andExpect(content().string(containsString("A fresh start")));
		mvc.perform(get("/").param("completion", "completed").param("category", "uncategorized"))
				.andExpect(content().string(containsString("No matching tasks")));
		verify(todos).findAll(CompletionFilter.COMPLETED, CategoryFilter.uncategorized());
	}

	@Test
	void deleteRequiresConfirmationAndGetNeverMutates() throws Exception {
		when(todos.get(1)).thenReturn(new Todo(1, "<Keep>", null, null, null, false));
		mvc.perform(get("/todos/1/delete")).andExpect(status().isOk())
				.andExpect(content().string(containsString("&lt;Keep&gt;")))
				.andExpect(content().string(containsString("Cancel")));
		mvc.perform(get("/")).andExpect(status().isOk());
		mvc.perform(post("/todos/1/delete")).andExpect(status().isBadRequest())
				.andExpect(content().string(containsString("Confirm deletion")));
		verify(todos, never()).delete(1);
		mvc.perform(post("/todos/1/delete").param("confirmed", "true")).andExpect(redirectedUrl("/"))
				.andExpect(flash().attribute("success", "Task deleted."));
		verify(todos).delete(1);
	}

	@Test
	void deleteFailureStaysOnConfirmationWithoutSuccess() throws Exception {
		when(todos.get(1)).thenReturn(new Todo(1, "Keep", null, null, null, false));
		doThrow(storageFailure()).when(todos).delete(1);
		mvc.perform(post("/todos/1/delete").param("confirmed", "true"))
				.andExpect(status().isServiceUnavailable()).andExpect(view().name("todos/delete"))
				.andExpect(content().string(containsString("could not be deleted")))
				.andExpect(flash().attributeCount(0));
	}

	@Test
	void completionUsesExplicitDesiredStateAndCannotOverpostFields() throws Exception {
		mvc.perform(post("/todos/1/completion").param("completed", "true").param("name", "Replace"))
				.andExpect(redirectedUrl("/")).andExpect(flash().attribute("success", "Task completed."));
		mvc.perform(post("/todos/1/completion").param("completed", "false"))
				.andExpect(redirectedUrl("/")).andExpect(flash().attribute("success", "Task reopened."));
		verify(todos).setCompleted(1, true);
		verify(todos).setCompleted(1, false);
		mvc.perform(post("/todos/1/completion").param("completed", "whatever"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/todos/1/completion")).andExpect(status().isBadRequest());
		verify(todos, never()).update(anyLong(), any(), any(), any(), any());
	}

	@Test
	void failedCompletionAndListLoadingShowExplicitErrors() throws Exception {
		when(todos.setCompleted(1, true)).thenThrow(storageFailure());
		mvc.perform(post("/todos/1/completion").param("completed", "true"))
				.andExpect(status().isServiceUnavailable()).andExpect(view().name("todos/error"))
				.andExpect(flash().attributeCount(0));
		when(todos.findAll(any(), any())).thenThrow(storageFailure());
		mvc.perform(get("/")).andExpect(status().isServiceUnavailable())
				.andExpect(content().string(containsString("storage is unavailable")));
	}

	@Test
	void missingTodosAndStaleFiltersShowNotFound() throws Exception {
		when(todos.get(404)).thenThrow(new NotFoundException("Todo", 404));
		when(todos.update(404, "Keep", "", null, null)).thenThrow(new NotFoundException("Todo", 404));
		when(categories.get(404)).thenThrow(new NotFoundException("Category", 404));
		mvc.perform(get("/todos/404/edit")).andExpect(status().isNotFound());
		mvc.perform(get("/todos/404/delete")).andExpect(status().isNotFound());
		mvc.perform(post("/todos/404").param("name", "Keep")).andExpect(status().isNotFound())
				.andExpect(view().name("todos/form"))
				.andExpect(content().string(containsString("Your entries are preserved")))
				.andExpect(content().string(containsString("value=\"Keep\"")));
		mvc.perform(get("/").param("category", "404")).andExpect(status().isNotFound());
	}

	@ParameterizedTest
	@ValueSource(strings = {"/todos/invalid/edit", "/todos/-1/edit", "/todos/9223372036854775808/edit",
			"/?completion=invalid", "/?category=invalid", "/?category=-2"})
	void malformedIdsAndFiltersReturnUsefulBadRequest(String path) throws Exception {
		mvc.perform(get(path)).andExpect(status().isBadRequest()).andExpect(view().name("todos/error"))
				.andExpect(content().string(containsString("Back to tasks")));
	}

	@Test
	void updateCanClearTimeAndCategoryWithoutBindingCompletion() throws Exception {
		mvc.perform(post("/todos/1").param("name", "Keep").param("description", "Text")
				.param("dueDate", "").param("dueTime", "").param("categoryId", "").param("completed", "false"))
				.andExpect(redirectedUrl("/"));
		verify(todos).update(1, "Keep", "Text", null, null);
	}

	private static DataAccessResourceFailureException storageFailure() {
		return new DataAccessResourceFailureException("Simulated storage failure");
	}

	@TestConfiguration
	static class TimeConfig {
		@Bean
		DueTimeService dueTimeService() {
			return new DueTimeService(ZoneId.of("UTC"),
					Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC));
		}
	}
}
