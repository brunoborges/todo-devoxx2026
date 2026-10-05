package com.example.todoapp.persistence;

import java.time.Instant;

public record Todo(long id, String name, String description, Instant dueAt, Long categoryId, boolean completed) {
}
