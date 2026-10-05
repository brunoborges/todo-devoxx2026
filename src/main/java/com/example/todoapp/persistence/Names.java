package com.example.todoapp.persistence;

final class Names {

	private Names() {
	}

	static String required(String name) {
		String normalizedName = name == null ? "" : name.strip();
		if (normalizedName.isBlank()) {
			throw new ValidationException("name", "Name is required.");
		}
		return normalizedName;
	}
}
