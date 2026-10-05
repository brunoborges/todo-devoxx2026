package com.example.todoapp.persistence;

public class ValidationException extends RuntimeException {

	private final String field;

	public ValidationException(String field, String message) {
		super(message);
		this.field = field;
	}

	public ValidationException(String field, String message, Throwable cause) {
		super(message, cause);
		this.field = field;
	}

	public String field() {
		return field;
	}
}
