package com.example.todoapp;

import java.time.Instant;

import com.example.todoapp.persistence.CategoryFilter;
import com.example.todoapp.persistence.CategoryRepository;
import com.example.todoapp.persistence.CompletionFilter;
import com.example.todoapp.persistence.NotFoundException;
import com.example.todoapp.persistence.TodoRepository;
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

@SpringBootTest(properties = "app.time-zone=Europe/Paris")
@AutoConfigureMockMvc
@Transactional
class ApplicationWorkflowTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	CategoryRepository categories;

	@Autowired
	TodoRepository todos;

	@Test
	void categoriesAndTodosWorkTogetherThroughTheirWebForms() throws Exception {
		mvc.perform(post("/categories").param("name", " Projects "))
				.andExpect(redirectedUrl("/categories"));
		var category = categories.findAll().getFirst();
		mvc.perform(get("/todos/new"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(">Projects</option>")))
				.andExpect(content().string(containsString("Europe/Paris")));
		mvc.perform(post("/todos").param("name", "Prepare talk")
				.param("description", "<script>alert('test')</script>\nSecond line")
				.param("dueDate", "2026-10-05").param("dueTime", "12:30:01.123456789")
				.param("categoryId", Long.toString(category.id())))
				.andExpect(redirectedUrl("/"));
		var todo = todos.findAll(CompletionFilter.ALL, CategoryFilter.all()).getFirst();
		assertThat(todo.dueAt()).isEqualTo(Instant.parse("2026-10-05T10:30:01.123456789Z"));
		mvc.perform(get("/todos/{id}/edit", todo.id()))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("&lt;script&gt;")))
				.andExpect(content().string(containsString("Second line")));

		mvc.perform(post("/categories/{id}/edit", category.id()).param("name", "Conference"))
				.andExpect(redirectedUrl("/categories"));
		mvc.perform(get("/").param("completion", "active").param("category", Long.toString(category.id())))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Conference")))
				.andExpect(content().string(containsString("Prepare talk")));
		mvc.perform(post("/todos/{id}/completion", todo.id()).param("completed", "true"))
				.andExpect(redirectedUrl("/"));

		mvc.perform(get("/categories/{id}/delete", category.id()))
				.andExpect(status().isOk()).andExpect(model().attribute("todoCount", 1L));
		mvc.perform(get("/categories")).andExpect(status().isOk());
		assertThat(todos.get(todo.id()).categoryId()).isEqualTo(category.id());
		mvc.perform(post("/categories/{id}/delete", category.id()).param("confirm", "true"))
				.andExpect(redirectedUrl("/categories"));
		var uncategorized = todos.get(todo.id());
		assertThat(uncategorized.categoryId()).isNull();
		assertThat(uncategorized.completed()).isTrue();
		assertThat(uncategorized.dueAt()).isEqualTo(todo.dueAt());
		assertThat(uncategorized.description()).isEqualTo(todo.description());
		mvc.perform(get("/").param("completion", "completed").param("category", "uncategorized"))
				.andExpect(status().isOk()).andExpect(content().string(containsString("Prepare talk")));

		mvc.perform(post("/todos/{id}/completion", todo.id()).param("completed", "false"))
				.andExpect(redirectedUrl("/"));
		mvc.perform(post("/todos/{id}", todo.id()).param("name", todo.name())
				.param("description", todo.description()).param("dueDate", "").param("dueTime", ""))
				.andExpect(redirectedUrl("/"));
		assertThat(todos.get(todo.id()).dueAt()).isNull();
		assertThat(todos.get(todo.id()).completed()).isFalse();
		mvc.perform(get("/todos/{id}/delete", todo.id())).andExpect(status().isOk());
		mvc.perform(get("/")).andExpect(status().isOk());
		assertThat(todos.get(todo.id()).name()).isEqualTo(todo.name());
		mvc.perform(post("/todos/{id}/delete", todo.id()).param("confirmed", "true"))
				.andExpect(redirectedUrl("/"));
		assertThatThrownBy(() -> todos.get(todo.id())).isInstanceOf(NotFoundException.class);
	}

	@Test
	void daylightSavingValidationIsWiredToFormsWithoutPartialUpdates() throws Exception {
		mvc.perform(post("/todos").param("name", "Keep original")).andExpect(redirectedUrl("/"));
		var original = todos.findAll(CompletionFilter.ALL, CategoryFilter.all()).getFirst();
		for (String date : new String[] {"2026-03-29", "2026-10-25"}) {
			mvc.perform(post("/todos/{id}", original.id()).param("name", "Unsaved name")
					.param("description", "Keep these entries").param("dueDate", date).param("dueTime", "02:30"))
					.andExpect(status().isBadRequest())
					.andExpect(model().attributeHasFieldErrors("todoForm", "dueTime"))
					.andExpect(content().string(containsString("Unsaved name")))
					.andExpect(content().string(containsString("Keep these entries")))
					.andExpect(content().string(containsString("Europe/Paris")));
			assertThat(todos.get(original.id())).isEqualTo(original);
		}
	}

	@Test
	void aDeletedCategoryCannotBeReassignedFromAnOldForm() throws Exception {
		mvc.perform(post("/categories").param("name", "Temporary")).andExpect(redirectedUrl("/categories"));
		long categoryId = categories.findAll().getFirst().id();
		mvc.perform(get("/todos/new")).andExpect(status().isOk());
		mvc.perform(post("/categories/{id}/delete", categoryId).param("confirm", "true"))
				.andExpect(redirectedUrl("/categories"));
		mvc.perform(post("/todos").param("name", "Keep my draft").param("description", "Draft details")
				.param("categoryId", Long.toString(categoryId)))
				.andExpect(status().isBadRequest())
				.andExpect(model().attributeHasFieldErrors("todoForm", "categoryId"))
				.andExpect(content().string(containsString("Keep my draft")))
				.andExpect(content().string(containsString("Draft details")));
		assertThat(todos.findAll(CompletionFilter.ALL, CategoryFilter.all())).isEmpty();
	}
}
