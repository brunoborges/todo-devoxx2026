package com.example.todoapp.category;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.todoapp.persistence.Category;
import com.example.todoapp.persistence.CategoryRepository;
import com.example.todoapp.persistence.NotFoundException;
import com.example.todoapp.persistence.ValidationException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(CategoryController.class)
class CategoryControllerTests {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	CategoryRepository categories;

	@Test
	void emptyListHasAccessibleFormAndNavigation() throws Exception {
		mvc.perform(get("/categories"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("No categories yet")))
				.andExpect(content().string(containsString("href=\"/\"")))
				.andExpect(content().string(containsString("href=\"/styles.css\"")))
				.andExpect(content().string(containsString("label for=\"name\"")))
				.andExpect(content().string(containsString("action=\"/categories\"")));
	}

	@Test
	void listEscapesNamesAndProvidesPerCategoryActions() throws Exception {
		when(categories.findAll()).thenReturn(List.of(new Category(7, "<script>alert('x')</script>")));
		mvc.perform(get("/categories"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("&lt;script&gt;")))
				.andExpect(content().string(not(containsString("<script>"))))
				.andExpect(content().string(containsString("/categories/7/edit")))
				.andExpect(content().string(containsString("/categories/7/delete")));
	}

	@Test
	void creationOnlyBindsNameAndRedirectsAfterSaving() throws Exception {
		mvc.perform(post("/categories").param("name", " Work ").param("id", "99")
						.param("completed", "true").param("categoryId", "12"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/categories"))
				.andExpect(flash().attribute("successMessage", "Category created."));
		verify(categories).create(" Work ");
	}

	@Test
	void validationMapsToInlineErrorAndPreservesEscapedInput() throws Exception {
		String input = " <b>Work</b> ";
		when(categories.create(input)).thenThrow(new ValidationException("name", "A category with this name already exists."));
		mvc.perform(post("/categories").param("name", input))
				.andExpect(status().isBadRequest())
				.andExpect(model().attributeHasFieldErrors("categoryForm", "name"))
				.andExpect(content().string(containsString("value=\" &lt;b&gt;Work&lt;/b&gt; \"")))
				.andExpect(content().string(containsString("aria-invalid=\"true\"")))
				.andExpect(content().string(containsString("A category with this name already exists.")))
				.andExpect(flash().attributeCount(0));
	}

	@ParameterizedTest
	@ValueSource(strings = {"/categories", "/categories/7/edit"})
	void databaseUniquenessConflictsBecomeFieldErrors(String route) throws Exception {
		when(categories.create("Work")).thenThrow(new DuplicateKeyException("internal SQL"));
		when(categories.rename(7, "Work")).thenThrow(new DuplicateKeyException("internal SQL"));
		mvc.perform(post(route).param("name", "Work"))
				.andExpect(status().isBadRequest())
				.andExpect(model().attributeHasFieldErrors("categoryForm", "name"))
				.andExpect(content().string(containsString("value=\"Work\"")))
				.andExpect(content().string(not(containsString("internal SQL"))))
				.andExpect(flash().attributeCount(0));
	}

	@ParameterizedTest
	@ValueSource(strings = {"/categories", "/categories/7/edit"})
	void failedSavePreservesInputAndDoesNotClaimSuccess(String route) throws Exception {
		when(categories.create(" Draft ")).thenThrow(databaseFailure());
		when(categories.rename(7, " Draft ")).thenThrow(databaseFailure());
		mvc.perform(post(route).param("name", " Draft "))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().string(containsString("value=\" Draft \"")))
				.andExpect(content().string(containsString("could not be saved")))
				.andExpect(content().string(not(containsString("internal SQL"))))
				.andExpect(flash().attributeCount(0));
	}

	@Test
	void failedListIsNotShownAsEmptyAndRetainsFailedCreateInput() throws Exception {
		when(categories.findAll()).thenThrow(databaseFailure());
		when(categories.create("Draft")).thenThrow(databaseFailure());
		mvc.perform(post("/categories").param("name", "Draft"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().string(containsString("value=\"Draft\"")))
				.andExpect(content().string(containsString("Categories could not be loaded")))
				.andExpect(content().string(not(containsString("No categories yet"))));
		mvc.perform(get("/categories"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().string(containsString("Categories could not be loaded")));
	}

	@Test
	void renameFormEscapesNameAndSameNameRenameSucceeds() throws Exception {
		when(categories.get(7)).thenReturn(new Category(7, "<b>Work</b>"));
		mvc.perform(get("/categories/7/edit"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("value=\"&lt;b&gt;Work&lt;/b&gt;\"")))
				.andExpect(content().string(containsString("action=\"/categories/7/edit\"")));
		mvc.perform(post("/categories/7/edit").param("name", "<b>Work</b>").param("id", "99"))
				.andExpect(status().is3xxRedirection())
				.andExpect(flash().attribute("successMessage", "Category renamed."));
		verify(categories).rename(7, "<b>Work</b>");
		verify(categories, never()).rename(org.mockito.ArgumentMatchers.eq(99L), anyString());
	}

	@Test
	void deletionConfirmationShowsCountEscapesNameAndCancelIsReadOnly() throws Exception {
		when(categories.get(7)).thenReturn(new Category(7, "<b>Work</b>"));
		when(categories.countTodos(7)).thenReturn(3L);
		mvc.perform(get("/categories/7/delete").param("confirm", "true"))
				.andExpect(status().isOk())
				.andExpect(model().attribute("todoCount", 3L))
				.andExpect(content().string(containsString("Associated todos: <strong>3</strong>")))
				.andExpect(content().string(containsString("&lt;b&gt;Work&lt;/b&gt;")))
				.andExpect(content().string(containsString("will be kept and become uncategorized")))
				.andExpect(content().string(containsString("name=\"confirm\" value=\"true\"")))
				.andExpect(content().string(containsString("href=\"/categories\">Cancel")));
		mvc.perform(get("/categories")).andExpect(status().isOk());
		verify(categories, never()).delete(anyLong());
		verify(categories, never()).create(anyString());
		verify(categories, never()).rename(anyLong(), anyString());
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "false", "on", "TRUE"})
	void deletionRequiresExplicitConfirmation(String confirm) throws Exception {
		when(categories.get(7)).thenReturn(new Category(7, "Work"));
		mvc.perform(post("/categories/7/delete").param("confirm", confirm))
				.andExpect(status().isBadRequest())
				.andExpect(content().string(containsString("Select the confirmation checkbox")));
		verify(categories, never()).delete(anyLong());
	}

	@Test
	void confirmedDeletionCallsAtomicRepositoryOperation() throws Exception {
		mvc.perform(post("/categories/7/delete").param("confirm", "true"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/categories"))
				.andExpect(flash().attribute("successMessage", "Category deleted. Its todos are now uncategorized."));
		verify(categories).delete(7);
	}

	@Test
	void failedDeleteShowsErrorAndNoSuccess() throws Exception {
		when(categories.get(7)).thenReturn(new Category(7, "Work"));
		when(categories.countTodos(7)).thenReturn(2L);
		doThrow(databaseFailure()).when(categories).delete(7);
		mvc.perform(post("/categories/7/delete").param("confirm", "true"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(model().attribute("todoCount", 2L))
				.andExpect(content().string(containsString("could not be deleted")))
				.andExpect(flash().attributeCount(0));
	}

	@ParameterizedTest
	@ValueSource(strings = {"/categories/7/edit", "/categories/7/delete"})
	void missingCategoryShowsUsefulNotFoundPage(String route) throws Exception {
		when(categories.get(7)).thenThrow(new NotFoundException("Category", 7));
		when(categories.rename(7, "Work")).thenThrow(new NotFoundException("Category", 7));
		doThrow(new NotFoundException("Category", 7)).when(categories).delete(7);
		mvc.perform(get(route)).andExpect(status().isNotFound()).andExpect(view().name("categories/error"));
		mvc.perform(post(route).param("name", "Work").param("confirm", "true"))
				.andExpect(status().isNotFound())
				.andExpect(content().string(containsString("Back to categories")))
				.andExpect(flash().attributeCount(0));
	}

	@ParameterizedTest
	@ValueSource(strings = {"abc", "-1", "0", "999999999999999999999999"})
	void malformedIdsHaveUsefulErrorsWithoutRepositoryAccess(String id) throws Exception {
		for (String action : List.of("edit", "delete")) {
			mvc.perform(get("/categories/" + id + "/" + action))
					.andExpect(status().isBadRequest())
					.andExpect(content().string(containsString("Invalid category ID")));
			mvc.perform(post("/categories/" + id + "/" + action).param("confirm", "true"))
					.andExpect(status().isBadRequest());
		}
		verifyNoInteractions(categories);
	}

	@Test
	void readFailureShowsUnavailablePageWithoutInternalDetails() throws Exception {
		when(categories.get(7)).thenThrow(databaseFailure());
		mvc.perform(get("/categories/7/edit"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(view().name("categories/error"))
				.andExpect(content().string(containsString("could not be loaded")))
				.andExpect(content().string(not(containsString("internal SQL"))));
	}

	private DataAccessResourceFailureException databaseFailure() {
		return new DataAccessResourceFailureException("internal SQL");
	}
}
