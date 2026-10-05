package com.example.todoapp.todo;

import java.time.Instant;
import java.util.List;

import com.example.todoapp.persistence.Category;
import com.example.todoapp.persistence.CategoryFilter;
import com.example.todoapp.persistence.CategoryRepository;
import com.example.todoapp.persistence.CompletionFilter;
import com.example.todoapp.persistence.NotFoundException;
import com.example.todoapp.persistence.Todo;
import com.example.todoapp.persistence.TodoRepository;
import com.example.todoapp.time.DueTimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TodoWorkflowIntegrationTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	TodoRepository todos;

	@Autowired
	CategoryRepository categories;

	@Autowired
	DueTimeService time;

	@Test
	void nameOnlyCreationIsTrimmedActiveUncategorizedAndDuplicateNamesAllowed() throws Exception {
		assertThat(categories.findAll()).isEmpty();
		for (int index = 0; index < 2; index++) {
			mvc.perform(post("/todos").param("name", "  Read  ").param("completed", "true").param("id", "999"))
					.andExpect(redirectedUrl("/"));
		}
		List<Todo> saved = todos.findAll(CompletionFilter.ALL, CategoryFilter.all());
		assertThat(saved).hasSize(2).allSatisfy(todo -> {
			assertThat(todo.name()).isEqualTo("Read");
			assertThat(todo.completed()).isFalse();
			assertThat(todo.categoryId()).isNull();
			assertThat(todo.dueAt()).isNull();
			assertThat(todo.id()).isNotEqualTo(999);
		});
		mvc.perform(get("/")).andExpect(status().isOk())
				.andExpect(content().string(containsString("Read")))
				.andExpect(content().string(containsString("Uncategorized")));
	}

	@Test
	void fullCrudCompletionReopenAndClearPreserveFields() throws Exception {
		Category category = categories.create("Work");
		mvc.perform(post("/todos").param("name", "Build").param("description", "<b>One</b>\nTwo")
				.param("dueDate", "2026-10-06").param("dueTime", "10:11:12.123456789")
				.param("categoryId", Long.toString(category.id()))).andExpect(redirectedUrl("/"));
		Todo original = todos.findAll(CompletionFilter.ALL, CategoryFilter.all()).getFirst();
		assertThat(original.dueAt()).isEqualTo(Instant.parse("2026-10-06T10:11:12.123456789Z"));
		mvc.perform(post("/todos/{id}/completion", original.id()).param("completed", "true"))
				.andExpect(redirectedUrl("/"));
		assertThat(todos.get(original.id())).isEqualTo(new Todo(original.id(), original.name(),
				original.description(), original.dueAt(), original.categoryId(), true));
		mvc.perform(post("/todos/{id}", original.id()).param("name", " Updated ")
				.param("description", "New\nDescription").param("dueDate", "2027-01-02")
				.param("dueTime", "03:04").param("categoryId", Long.toString(category.id()))
				.param("completed", "false")).andExpect(redirectedUrl("/"));
		Todo edited = todos.get(original.id());
		assertThat(edited.completed()).isTrue();
		assertThat(edited.name()).isEqualTo("Updated");
		assertThat(edited.description()).isEqualTo("New\nDescription");
		assertThat(edited.dueAt()).isEqualTo(Instant.parse("2027-01-02T03:04:00Z"));
		mvc.perform(post("/todos/{id}/completion", original.id()).param("completed", "false"))
				.andExpect(redirectedUrl("/"));
		assertThat(todos.get(original.id())).isEqualTo(new Todo(edited.id(), edited.name(),
				edited.description(), edited.dueAt(), edited.categoryId(), false));
		mvc.perform(post("/todos/{id}", original.id()).param("name", "Updated")
				.param("description", "New\nDescription").param("dueDate", "").param("dueTime", "")
				.param("categoryId", "")).andExpect(redirectedUrl("/"));
		assertThat(todos.get(original.id()).dueAt()).isNull();
		assertThat(todos.get(original.id()).categoryId()).isNull();
		mvc.perform(get("/todos/{id}/delete", original.id())).andExpect(status().isOk());
		mvc.perform(get("/")).andExpect(status().isOk());
		assertThat(todos.get(original.id())).isNotNull();
		mvc.perform(post("/todos/{id}/delete", original.id()).param("confirmed", "true"))
				.andExpect(redirectedUrl("/"));
		assertThatThrownBy(() -> todos.get(original.id())).isInstanceOf(NotFoundException.class);
	}

	@Test
	void filtersCombineAndRepositoryOrderingIsPreservedInRenderedRows() throws Exception {
		Category category = categories.create("Work");
		Todo undated = todos.create("Undated active", "", null, category.id());
		Todo later = todos.create("Later active", "", Instant.parse("2027-01-01T00:00:00Z"), category.id());
		Todo earlier = todos.create("Earlier active", "", Instant.parse("2020-01-01T00:00:00Z"), category.id());
		Todo tied = todos.create("Tied active", "", earlier.dueAt(), category.id());
		Todo doneUndated = todos.create("Undated complete", "", null, category.id());
		todos.setCompleted(doneUndated.id(), true);
		Todo done = todos.create("Dated complete", "", Instant.parse("2000-01-01T00:00:00Z"), category.id());
		todos.setCompleted(done.id(), true);
		Todo other = todos.create("Other active", "", null, null);
		mvc.perform(get("/")).andExpect(status().isOk())
				.andExpect(model().attribute("rows", rows(earlier, tied, later, undated, other,
						todos.get(done.id()), todos.get(doneUndated.id()))));
		mvc.perform(get("/").param("completion", "completed").param("category", Long.toString(category.id())))
				.andExpect(status().isOk()).andExpect(model().attribute("rows",
						rows(todos.get(done.id()), todos.get(doneUndated.id()))));
		mvc.perform(get("/").param("completion", "active").param("category", "uncategorized"))
				.andExpect(status().isOk()).andExpect(model().attribute("rows", rows(other)));
	}

	@Test
	void currentCategoryLabelsAndAlphabeticalOptionsComeFromRepository() throws Exception {
		categories.create("Zebra");
		Category category = categories.create("Alpha");
		todos.create("Categorized", "", null, category.id());
		String form = mvc.perform(get("/todos/new")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(form.indexOf(">Alpha</option>")).isLessThan(form.indexOf(">Zebra</option>"));
		categories.rename(category.id(), "Renamed");
		mvc.perform(get("/")).andExpect(content().string(containsString("Renamed")));
		categories.delete(category.id());
		mvc.perform(get("/")).andExpect(content().string(containsString("Uncategorized")));
		mvc.perform(post("/todos").param("name", "Keep").param("categoryId", Long.toString(category.id())))
				.andExpect(status().isBadRequest())
				.andExpect(model().attributeHasFieldErrors("todoForm", "categoryId"));
		assertThat(todos.findAll(CompletionFilter.ALL, CategoryFilter.all())).hasSize(1);
	}

	@Test
	void invalidUpdateDoesNotPartiallySaveAndPreservesUserEntries() throws Exception {
		Todo original = todos.create("Original", "Description", null, null);
		mvc.perform(post("/todos/{id}", original.id()).param("name", "Changed")
				.param("description", "Keep\nall").param("dueDate", "2026-01-01").param("dueTime", ""))
				.andExpect(status().isBadRequest()).andExpect(model().attributeHasFieldErrors("todoForm", "dueTime"))
				.andExpect(content().string(containsString("Keep\nall")));
		assertThat(todos.get(original.id())).isEqualTo(original);
	}

	private List<TodoController.TodoRow> rows(Todo... items) {
		return java.util.Arrays.stream(items).map(todo -> new TodoController.TodoRow(todo,
				todo.categoryId() == null ? "Uncategorized" : categories.get(todo.categoryId()).name(),
				time.format(todo.dueAt()), time.isOverdue(todo.dueAt(), todo.completed()))).toList();
	}
}
