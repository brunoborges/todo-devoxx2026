package com.example.todoapp.persistence;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Objects;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CategoryRepository {

	private static final RowMapper<Category> ROW_MAPPER =
			(rs, row) -> new Category(rs.getLong("id"), rs.getString("name"));

	private final JdbcTemplate jdbc;

	public CategoryRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Category get(long id) {
		return jdbc.query("SELECT id, name FROM categories WHERE id = ?", ROW_MAPPER, id)
				.stream().findFirst().orElseThrow(() -> new NotFoundException("Category", id));
	}

	public List<Category> findAll() {
		return jdbc.query("SELECT id, name FROM categories ORDER BY name_key, id", ROW_MAPPER);
	}

	@Transactional
	public Category create(String name) {
		String normalizedName = categoryName(name);
		var keys = new GeneratedKeyHolder();
		try {
			jdbc.update(connection -> {
				PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO categories (name) VALUES (?)", new String[] {"id"});
				statement.setString(1, normalizedName);
				return statement;
			}, keys);
		}
		catch (DuplicateKeyException exception) {
			throw duplicateName(exception);
		}
		return get(Objects.requireNonNull(keys.getKey(), "Missing generated category ID").longValue());
	}

	@Transactional
	public Category rename(long id, String name) {
		get(id);
		String normalizedName = categoryName(name);
		try {
			if (jdbc.update("UPDATE categories SET name = ? WHERE id = ?", normalizedName, id) == 0) {
				throw new NotFoundException("Category", id);
			}
		}
		catch (DuplicateKeyException exception) {
			throw duplicateName(exception);
		}
		return get(id);
	}

	public long countTodos(long id) {
		get(id);
		return Objects.requireNonNull(jdbc.queryForObject(
				"SELECT COUNT(*) FROM todos WHERE category_id = ?", Long.class, id));
	}

	@Transactional
	public void delete(long id) {
		if (jdbc.query("SELECT id FROM categories WHERE id = ? FOR UPDATE",
				(rs, row) -> rs.getLong("id"), id).isEmpty()) {
			throw new NotFoundException("Category", id);
		}
		jdbc.update("UPDATE todos SET category_id = NULL WHERE category_id = ?", id);
		jdbc.update("DELETE FROM categories WHERE id = ?", id);
	}

	private static String categoryName(String name) {
		String normalizedName = Names.required(name);
		if ("Uncategorized".equalsIgnoreCase(normalizedName)) {
			throw new ValidationException("name", "Uncategorized is reserved for todos without a category.");
		}
		return normalizedName;
	}

	private static ValidationException duplicateName(DuplicateKeyException cause) {
		return new ValidationException("name", "A category with this name already exists.", cause);
	}
}
