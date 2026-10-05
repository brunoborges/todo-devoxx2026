package com.example.todoapp.persistence;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TodoRepository {

	private static final RowMapper<Todo> ROW_MAPPER = (rs, row) -> {
		OffsetDateTime dueAt = rs.getObject("due_at", OffsetDateTime.class);
		return new Todo(rs.getLong("id"), rs.getString("name"), rs.getString("description"),
				dueAt == null ? null : dueAt.toInstant(), rs.getObject("category_id", Long.class),
				rs.getBoolean("completed"));
	};

	private final JdbcTemplate jdbc;

	public TodoRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Todo get(long id) {
		return jdbc.query("SELECT * FROM todos WHERE id = ?", ROW_MAPPER, id)
				.stream().findFirst().orElseThrow(() -> new NotFoundException("Todo", id));
	}

	public List<Todo> findAll(CompletionFilter completion, CategoryFilter category) {
		Objects.requireNonNull(completion, "Completion filter is required");
		Objects.requireNonNull(category, "Category filter is required");
		StringBuilder sql = new StringBuilder("SELECT * FROM todos WHERE 1 = 1");
		List<Object> parameters = new ArrayList<>();
		if (completion != CompletionFilter.ALL) {
			sql.append(" AND completed = ?");
			parameters.add(completion == CompletionFilter.COMPLETED);
		}
		switch (category.mode()) {
			case ALL -> {
			}
			case UNCATEGORIZED -> sql.append(" AND category_id IS NULL");
			case CATEGORY -> {
				sql.append(" AND category_id = ?");
				parameters.add(category.categoryId());
			}
		}
		sql.append(" ORDER BY completed ASC, due_at ASC NULLS LAST, id ASC");
		return jdbc.query(sql.toString(), ROW_MAPPER, parameters.toArray());
	}

	@Transactional
	public Todo create(String name, String description, Instant dueAt, Long categoryId) {
		String normalizedName = Names.required(name);
		requireCategory(categoryId);
		var keys = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(
					"INSERT INTO todos (name, description, due_at, category_id) VALUES (?, ?, ?, ?)",
					new String[] {"id"});
			statement.setString(1, normalizedName);
			statement.setString(2, description);
			statement.setObject(3, offset(dueAt));
			statement.setObject(4, categoryId);
			return statement;
		}, keys);
		return get(Objects.requireNonNull(keys.getKey(), "Missing generated todo ID").longValue());
	}

	@Transactional
	public Todo update(long id, String name, String description, Instant dueAt, Long categoryId) {
		get(id);
		String normalizedName = Names.required(name);
		requireCategory(categoryId);
		if (jdbc.update("UPDATE todos SET name = ?, description = ?, due_at = ?, category_id = ? WHERE id = ?",
				normalizedName, description, offset(dueAt), categoryId, id) == 0) {
			throw new NotFoundException("Todo", id);
		}
		return get(id);
	}

	@Transactional
	public Todo setCompleted(long id, boolean completed) {
		if (jdbc.update("UPDATE todos SET completed = ? WHERE id = ?", completed, id) == 0) {
			throw new NotFoundException("Todo", id);
		}
		return get(id);
	}

	public void delete(long id) {
		if (jdbc.update("DELETE FROM todos WHERE id = ?", id) == 0) {
			throw new NotFoundException("Todo", id);
		}
	}

	private void requireCategory(Long categoryId) {
		if (categoryId != null && jdbc.query("SELECT id FROM categories WHERE id = ? FOR UPDATE",
				(rs, row) -> rs.getLong("id"), categoryId).isEmpty()) {
			throw new ValidationException("categoryId", "The selected category no longer exists.");
		}
	}

	private static OffsetDateTime offset(Instant instant) {
		return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
	}
}
