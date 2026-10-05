package com.example.todoapp.time;

public class DueTimeValidationException extends IllegalArgumentException {

	private final String field;

	public DueTimeValidationException(String field, String message) {
		super(message);
		this.field = field;
	}

	public String getField() {
		return field;
	}
}
