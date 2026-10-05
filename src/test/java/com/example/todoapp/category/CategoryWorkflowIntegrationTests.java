package com.example.todoapp.category;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.todoapp.persistence.CategoryRepository;
import com.example.todoapp.persistence.NotFoundException;
import com.example.todoapp.persistence.TodoRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CategoryWorkflowIntegrationTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	CategoryRepository categories;

	@Autowired
	TodoRepository todos;

	@Test
	void creationTrimsAndListingIsAlphabetical() throws Exception {
		mvc.perform(post("/categories").param("name", " zebra ")).andExpect(status().is3xxRedirection());
		mvc.perform(post("/categories").param("name", " Alpha ")).andExpect(status().is3xxRedirection());
		assertThat(categories.findAll()).extracting(category -> category.name()).containsExactly("Alpha", "zebra");
		String html = mvc.perform(get("/categories")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(html.indexOf(">Alpha</span>")).isLessThan(html.indexOf(">zebra</span>"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "Uncategorized", " uncATEGorized ", " wOrK "})
	void invalidCreateAndRenamePreserveInputAndData(String input) throws Exception {
		categories.create("Work");
		var category = categories.create("Personal");
		mvc.perform(post("/categories").param("name", input))
				.andExpect(status().isBadRequest())
				.andExpect(model().attributeHasFieldErrors("categoryForm", "name"))
				.andExpect(flash().attributeCount(0));
		var result = mvc.perform(post("/categories/" + category.id() + "/edit").param("name", input))
				.andExpect(status().isBadRequest())
				.andExpect(model().attributeHasFieldErrors("categoryForm", "name"))
				.andExpect(flash().attributeCount(0)).andReturn();
		var form = (CategoryForm) result.getModelAndView().getModel().get("categoryForm");
		assertThat(form.getName()).isEqualTo(input);
		assertThat(categories.get(category.id()).name()).isEqualTo("Personal");
		assertThat(categories.findAll()).hasSize(2);
	}

	@Test
	void renameKeepsStableTodoAssociationIncludingSameNameAndCaseOnlyRename() throws Exception {
		var category = categories.create("Work");
		var todo = todos.create("Plan talk", "Keep details", null, category.id());
		for (String name : new String[] {" Work ", "work", "Conference"}) {
			mvc.perform(post("/categories/" + category.id() + "/edit").param("name", name))
					.andExpect(status().is3xxRedirection())
					.andExpect(flash().attribute("successMessage", "Category renamed."));
			assertThat(todos.get(todo.id())).isEqualTo(todo);
			assertThat(categories.get(todos.get(todo.id()).categoryId()).name()).isEqualTo(name.trim());
		}
	}

	@Test
	void cancelAndUnconfirmedDeleteKeepDataThenExplicitDeleteRetainsTodos() throws Exception {
		var category = categories.create("Work");
		var todo = todos.create("Plan talk", "Keep details", null, category.id());
		var other = todos.create("Other task", "", null, category.id());
		mvc.perform(get("/categories/" + category.id() + "/delete"))
				.andExpect(status().isOk())
				.andExpect(model().attribute("todoCount", 2L))
				.andExpect(content().string(containsString("will be kept and become uncategorized")));
		mvc.perform(get("/categories")).andExpect(status().isOk());
		mvc.perform(post("/categories/" + category.id() + "/delete")).andExpect(status().isBadRequest());
		assertThat(todos.get(todo.id())).isEqualTo(todo);
		assertThat(categories.get(category.id())).isEqualTo(category);
		mvc.perform(post("/categories/" + category.id() + "/delete").param("confirm", "true"))
				.andExpect(status().is3xxRedirection());
		assertThatThrownBy(() -> categories.get(category.id())).isInstanceOf(NotFoundException.class);
		assertThat(todos.get(todo.id())).usingRecursiveComparison().ignoringFields("categoryId").isEqualTo(todo);
		assertThat(todos.get(todo.id()).categoryId()).isNull();
		assertThat(todos.get(other.id()).categoryId()).isNull();
	}
}
