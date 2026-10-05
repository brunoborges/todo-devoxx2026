package com.example.todoapp.todo;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.example.todoapp.persistence.Category;
import com.example.todoapp.persistence.CategoryFilter;
import com.example.todoapp.persistence.CategoryRepository;
import com.example.todoapp.persistence.CompletionFilter;
import com.example.todoapp.persistence.NotFoundException;
import com.example.todoapp.persistence.Todo;
import com.example.todoapp.persistence.TodoRepository;
import com.example.todoapp.persistence.ValidationException;
import com.example.todoapp.time.DueTimeService;
import com.example.todoapp.time.DueTimeValidationException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class TodoController {

	private static final Logger log = LoggerFactory.getLogger(TodoController.class);
	private final TodoRepository todos;
	private final CategoryRepository categories;
	private final DueTimeService dueTime;

	public TodoController(TodoRepository todos, CategoryRepository categories, DueTimeService dueTime) {
		this.todos = todos;
		this.categories = categories;
		this.dueTime = dueTime;
	}

	@InitBinder("todoForm")
	void bindForm(WebDataBinder binder) {
		binder.setAllowedFields("name", "description", "dueDate", "dueTime", "categoryId");
	}

	@GetMapping("/")
	String list(@RequestParam(defaultValue = "all") String completion,
			@RequestParam(defaultValue = "all") String category, Model model) {
		CompletionFilter completionFilter = switch (completion) {
			case "all" -> CompletionFilter.ALL;
			case "active" -> CompletionFilter.ACTIVE;
			case "completed" -> CompletionFilter.COMPLETED;
			default -> throw new InvalidRequestException("Choose All, Active, or Completed tasks.");
		};
		CategoryFilter categoryFilter;
		if ("all".equals(category)) {
			categoryFilter = CategoryFilter.all();
		}
		else if ("uncategorized".equals(category)) {
			categoryFilter = CategoryFilter.uncategorized();
		}
		else {
			long categoryId = parseId(category, "Choose an existing category, All categories, or Uncategorized.");
			categories.get(categoryId);
			categoryFilter = CategoryFilter.category(categoryId);
		}
		List<Category> options = categories.findAll();
		Map<Long, String> names = options.stream().collect(Collectors.toMap(Category::id, Category::name));
		List<TodoRow> rows = todos.findAll(completionFilter, categoryFilter).stream()
				.map(todo -> new TodoRow(todo, todo.categoryId() == null ? "Uncategorized"
						: names.getOrDefault(todo.categoryId(), "Category no longer available"),
						dueTime.format(todo.dueAt()), dueTime.isOverdue(todo.dueAt(), todo.completed())))
				.toList();
		model.addAttribute("rows", rows);
		model.addAttribute("categories", options);
		model.addAttribute("completion", completion);
		model.addAttribute("category", category);
		model.addAttribute("filtered", !"all".equals(completion) || !"all".equals(category));
		model.addAttribute("zone", dueTime.getZoneId());
		return "todos/list";
	}

	@GetMapping("/todos/new")
	String newTodo(Model model, HttpServletResponse response) {
		model.addAttribute("todoForm", new TodoForm());
		return form(null, model, response);
	}

	@GetMapping("/todos/{id}/edit")
	String edit(@PathVariable long id, Model model, HttpServletResponse response) {
		Todo todo = todos.get(validId(id));
		TodoForm form = new TodoForm();
		form.setName(todo.name());
		form.setDescription(todo.description());
		form.setDueDate(dueTime.dateValue(todo.dueAt()));
		form.setDueTime(dueTime.timeValue(todo.dueAt()));
		form.setCategoryId(todo.categoryId() == null ? "" : todo.categoryId().toString());
		model.addAttribute("todoForm", form);
		return form(id, model, response);
	}

	@PostMapping("/todos")
	String create(@ModelAttribute TodoForm todoForm, BindingResult errors, Model model,
			HttpServletResponse response, RedirectAttributes redirect) {
		return save(null, todoForm, errors, model, response, redirect);
	}

	@PostMapping("/todos/{id}")
	String update(@PathVariable long id, @ModelAttribute TodoForm todoForm, BindingResult errors,
			Model model, HttpServletResponse response, RedirectAttributes redirect) {
		return save(validId(id), todoForm, errors, model, response, redirect);
	}

	private String save(Long id, TodoForm input, BindingResult errors, Model model,
			HttpServletResponse response, RedirectAttributes redirect) {
		if (input.getName() == null || input.getName().isBlank()) {
			errors.rejectValue("name", "required", "Enter a task name.");
		}
		Instant dueAt = null;
		try {
			dueAt = dueTime.parse(input.getDueDate(), input.getDueTime());
		}
		catch (DueTimeValidationException ex) {
			errors.rejectValue(ex.getField(), "invalid", ex.getMessage());
		}
		Long categoryId = null;
		try {
			if (input.getCategoryId() != null && !input.getCategoryId().isBlank()) {
				categoryId = parseId(input.getCategoryId(), "Choose an existing category or Uncategorized.");
				categories.get(categoryId);
			}
		}
		catch (InvalidRequestException | NotFoundException ex) {
			errors.rejectValue("categoryId", "invalid",
					"The selected category is invalid or no longer exists. Choose another category or Uncategorized.");
		}
		catch (DataAccessException ex) {
			log.error("Unable to check category while saving task {}", id, ex);
			errors.reject("storage", "Categories could not be checked. Your task was not saved. Please try again.");
			response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
		}
		if (!errors.hasErrors()) {
			try {
				if (id == null) {
					todos.create(input.getName(), input.getDescription(), dueAt, categoryId);
				}
				else {
					todos.update(id, input.getName(), input.getDescription(), dueAt, categoryId);
				}
				redirect.addFlashAttribute("success", id == null ? "Task created." : "Task saved.");
				return "redirect:/";
			}
			catch (ValidationException ex) {
				errors.rejectValue(ex.field(), "invalid", ex.getMessage());
			}
			catch (NotFoundException ex) {
				errors.reject("missing", ex.getMessage()
						+ " It may have been deleted. Your entries are preserved, but this task cannot be saved.");
				response.setStatus(HttpStatus.NOT_FOUND.value());
			}
			catch (DataAccessException ex) {
				log.error("Unable to save task {}", id, ex);
				errors.reject("storage", "Your task could not be saved. Your entries are still here. Please try again.");
				response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
			}
		}
		if (response.getStatus() == HttpStatus.OK.value()) {
			response.setStatus(HttpStatus.BAD_REQUEST.value());
		}
		return form(id, model, response);
	}

	private String form(Long id, Model model, HttpServletResponse response) {
		model.addAttribute("todoId", id);
		model.addAttribute("zone", dueTime.getZoneId());
		model.addAttribute("categoriesAvailable", true);
		try {
			List<Category> options = categories.findAll();
			model.addAttribute("categories", options);
			TodoForm input = (TodoForm) model.getAttribute("todoForm");
			String selected = input.getCategoryId();
			model.addAttribute("unavailableCategory", selected != null && !selected.isBlank()
					&& options.stream().noneMatch(option -> Long.toString(option.id()).equals(selected)));
		}
		catch (DataAccessException ex) {
			log.error("Unable to load task form categories", ex);
			model.addAttribute("categoriesAvailable", false);
			model.addAttribute("categories", List.of());
			model.addAttribute("unavailableCategory", true);
			model.addAttribute("error", "Categories could not be loaded. Your entries are preserved. Please try again.");
			response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
		}
		return "todos/form";
	}

	@PostMapping("/todos/{id}/completion")
	String completion(@PathVariable long id, @RequestParam String completed, RedirectAttributes redirect) {
		if (!"true".equals(completed) && !"false".equals(completed)) {
			throw new InvalidRequestException("Choose Complete or Reopen to change a task's status.");
		}
		boolean state = Boolean.parseBoolean(completed);
		todos.setCompleted(validId(id), state);
		redirect.addFlashAttribute("success", state ? "Task completed." : "Task reopened.");
		return "redirect:/";
	}

	@GetMapping("/todos/{id}/delete")
	String confirmDelete(@PathVariable long id, Model model) {
		model.addAttribute("todo", todos.get(validId(id)));
		return "todos/delete";
	}

	@PostMapping("/todos/{id}/delete")
	String delete(@PathVariable long id, @RequestParam(defaultValue = "") String confirmed,
			Model model, HttpServletResponse response, RedirectAttributes redirect) {
		Todo todo = todos.get(validId(id));
		model.addAttribute("todo", todo);
		if (!"true".equals(confirmed)) {
			model.addAttribute("error", "Confirm deletion to permanently remove this task.");
			response.setStatus(HttpStatus.BAD_REQUEST.value());
			return "todos/delete";
		}
		try {
			todos.delete(id);
		}
		catch (DataAccessException ex) {
			log.error("Unable to delete task {}", id, ex);
			model.addAttribute("error", "The task could not be deleted. Please try again.");
			response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
			return "todos/delete";
		}
		redirect.addFlashAttribute("success", "Task deleted.");
		return "redirect:/";
	}

	@ExceptionHandler(NotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	String notFound(NotFoundException ex, Model model) {
		model.addAttribute("title", "Record not found");
		model.addAttribute("error", ex.getMessage() + " It may have been deleted. Return to the task list to continue.");
		return "todos/error";
	}

	@ExceptionHandler({InvalidRequestException.class, MethodArgumentTypeMismatchException.class,
			MissingServletRequestParameterException.class})
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	String invalidRequest(Exception ex, Model model) {
		model.addAttribute("title", "Invalid request");
		model.addAttribute("error", ex instanceof InvalidRequestException ? ex.getMessage()
				: "A task ID or required action value is invalid. Return to the task list and try again.");
		return "todos/error";
	}

	@ExceptionHandler(DataAccessException.class)
	@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
	String storageFailure(DataAccessException ex, Model model) {
		log.error("Unable to process task request", ex);
		model.addAttribute("title", "Tasks temporarily unavailable");
		model.addAttribute("error", "The operation could not be completed because storage is unavailable. Please try again.");
		return "todos/error";
	}

	private static long parseId(String value, String message) {
		try {
			return validId(Long.parseLong(value));
		}
		catch (NumberFormatException | InvalidRequestException ex) {
			throw new InvalidRequestException(message);
		}
	}

	private static long validId(long id) {
		if (id <= 0) {
			throw new InvalidRequestException("Task and category IDs must be positive numbers.");
		}
		return id;
	}

	private static class InvalidRequestException extends RuntimeException {
		InvalidRequestException(String message) {
			super(message);
		}
	}

	public record TodoRow(Todo todo, String categoryName, String dueLabel, boolean overdue) {
	}
}
