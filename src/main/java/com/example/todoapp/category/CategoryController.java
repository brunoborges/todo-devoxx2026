package com.example.todoapp.category;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.todoapp.persistence.CategoryRepository;
import com.example.todoapp.persistence.NotFoundException;
import com.example.todoapp.persistence.ValidationException;

@Controller
@RequestMapping("/categories")
public class CategoryController {

	private static final Logger logger = LoggerFactory.getLogger(CategoryController.class);
	private final CategoryRepository categories;

	public CategoryController(CategoryRepository categories) {
		this.categories = categories;
	}

	@InitBinder("categoryForm")
	void bindForm(WebDataBinder binder) {
		binder.setAllowedFields("name");
	}

	@GetMapping
	String list(Model model, HttpServletResponse response) {
		model.addAttribute("categoryForm", new CategoryForm());
		return categoryList(model, response);
	}

	@PostMapping
	String create(@ModelAttribute("categoryForm") CategoryForm form, BindingResult errors,
			Model model, HttpServletResponse response, RedirectAttributes redirect) {
		try {
			categories.create(form.getName());
		}
		catch (ValidationException exception) {
			errors.rejectValue(exception.field(), "invalid", exception.getMessage());
		}
		catch (DuplicateKeyException exception) {
			errors.rejectValue("name", "duplicate", "A category with this name already exists.");
		}
		catch (DataAccessException exception) {
			saveFailure(exception, errors, response);
			return categoryList(model, response);
		}
		if (errors.hasErrors()) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			return categoryList(model, response);
		}
		redirect.addFlashAttribute("successMessage", "Category created.");
		return "redirect:/categories";
	}

	@GetMapping("/{id}/edit")
	String edit(@PathVariable String id, Model model) {
		var category = categories.get(categoryId(id));
		model.addAttribute("category", category);
		model.addAttribute("categoryId", category.id());
		var form = new CategoryForm();
		form.setName(category.name());
		model.addAttribute("categoryForm", form);
		return "categories/edit";
	}

	@PostMapping("/{id}/edit")
	String rename(@PathVariable String id, @ModelAttribute("categoryForm") CategoryForm form,
			BindingResult errors, Model model, HttpServletResponse response, RedirectAttributes redirect) {
		long categoryId = categoryId(id);
		model.addAttribute("categoryId", categoryId);
		try {
			categories.rename(categoryId, form.getName());
		}
		catch (ValidationException exception) {
			errors.rejectValue(exception.field(), "invalid", exception.getMessage());
		}
		catch (DuplicateKeyException exception) {
			errors.rejectValue("name", "duplicate", "A category with this name already exists.");
		}
		catch (DataAccessException exception) {
			saveFailure(exception, errors, response);
			return "categories/edit";
		}
		if (errors.hasErrors()) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			return "categories/edit";
		}
		redirect.addFlashAttribute("successMessage", "Category renamed.");
		return "redirect:/categories";
	}

	@GetMapping("/{id}/delete")
	String confirmDelete(@PathVariable String id, Model model) {
		return deletionPage(categoryId(id), model);
	}

	@PostMapping("/{id}/delete")
	String delete(@PathVariable String id, @RequestParam(defaultValue = "false") String confirm,
			Model model, HttpServletResponse response, RedirectAttributes redirect) {
		long categoryId = categoryId(id);
		if (!"true".equals(confirm)) {
			model.addAttribute("errorMessage", "Select the confirmation checkbox to delete this category.");
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			return deletionPage(categoryId, model);
		}
		try {
			categories.delete(categoryId);
		}
		catch (DataAccessException exception) {
			logger.error("Failed to delete category {}", categoryId, exception);
			model.addAttribute("errorMessage", "The category could not be deleted. Please try again.");
			response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			return deletionPage(categoryId, model);
		}
		redirect.addFlashAttribute("successMessage", "Category deleted. Its todos are now uncategorized.");
		return "redirect:/categories";
	}

	@ExceptionHandler(NotFoundException.class)
	String notFound(NotFoundException exception, Model model, HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_NOT_FOUND);
		model.addAttribute("errorMessage", exception.getMessage());
		return "categories/error";
	}

	@ExceptionHandler(InvalidCategoryIdException.class)
	String invalidId(Model model, HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
		model.addAttribute("errorMessage", "Invalid category ID. Choose a category from the category list.");
		return "categories/error";
	}

	@ExceptionHandler(DataAccessException.class)
	String unavailable(DataAccessException exception, Model model, HttpServletResponse response) {
		logger.error("Failed to load category data", exception);
		response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		model.addAttribute("errorMessage", "Category data could not be loaded. Please try again.");
		return "categories/error";
	}

	private String categoryList(Model model, HttpServletResponse response) {
		try {
			model.addAttribute("categories", categories.findAll());
		}
		catch (DataAccessException exception) {
			logger.error("Failed to list categories", exception);
			response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			model.addAttribute("categoriesUnavailable", true);
			model.addAttribute("errorMessage", "Categories could not be loaded. Please try again.");
		}
		return "categories/list";
	}

	private String deletionPage(long id, Model model) {
		model.addAttribute("category", categories.get(id));
		model.addAttribute("todoCount", categories.countTodos(id));
		return "categories/delete";
	}

	private void saveFailure(DataAccessException exception, BindingResult errors, HttpServletResponse response) {
		logger.error("Failed to save category", exception);
		errors.reject("saveFailed", "The category could not be saved. Your input has been kept; please try again.");
		response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
	}

	private long categoryId(String value) {
		try {
			long id = Long.parseLong(value);
			if (id > 0) {
				return id;
			}
		}
		catch (NumberFormatException exception) {
			throw new InvalidCategoryIdException();
		}
		throw new InvalidCategoryIdException();
	}

	private static class InvalidCategoryIdException extends RuntimeException {
	}
}
