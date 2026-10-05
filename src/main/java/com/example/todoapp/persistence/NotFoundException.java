package com.example.todoapp.persistence;

public class NotFoundException extends RuntimeException {

	private final String entity;
	private final long id;

	public NotFoundException(String entity, long id) {
		super(entity + " " + id + " was not found.");
		this.entity = entity;
		this.id = id;
	}

	public String entity() {
		return entity;
	}

	public long id() {
		return id;
	}
}
