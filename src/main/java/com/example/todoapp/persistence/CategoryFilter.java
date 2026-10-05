package com.example.todoapp.persistence;

import java.util.Objects;

public record CategoryFilter(Mode mode, Long categoryId) {

	public enum Mode {
		ALL, UNCATEGORIZED, CATEGORY
	}

	public CategoryFilter {
		Objects.requireNonNull(mode, "Category filter mode is required");
		if ((mode == Mode.CATEGORY) != (categoryId != null)) {
			throw new IllegalArgumentException("Only a specific category filter must have a category ID");
		}
	}

	public static CategoryFilter all() {
		return new CategoryFilter(Mode.ALL, null);
	}

	public static CategoryFilter uncategorized() {
		return new CategoryFilter(Mode.UNCATEGORIZED, null);
	}

	public static CategoryFilter category(long categoryId) {
		return new CategoryFilter(Mode.CATEGORY, categoryId);
	}
}
