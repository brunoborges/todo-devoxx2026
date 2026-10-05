package com.example.todoapp.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceRestartTests {

	@TempDir
	Path directory;

	@Test
	void fileDatabaseSurvivesFullContextAndConnectionPoolCloseAndReopen() throws Exception {
		String url = "jdbc:h2:file:" + directory.resolve("restart").toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE";
		Category category;
		Todo completed;
		Todo active;
		DataSource originalDataSource;
		try (ConfigurableApplicationContext first = open(url)) {
			originalDataSource = first.getBean(DataSource.class);
			CategoryRepository categories = first.getBean(CategoryRepository.class);
			TodoRepository todos = first.getBean(TodoRepository.class);
			category = categories.create("Persistent");
			completed = todos.setCompleted(todos.create("Saved", "First\nSecond\r\nThird",
					Instant.parse("2026-10-25T00:30:00.123456789Z"), category.id()).id(), true);
			active = todos.create("Undated", null, null, null);
			assertThat(first.getBean(Flyway.class).info().applied()).hasSize(1);
		}

		assertThat(Files.exists(directory.resolve("restart.mv.db"))).isTrue();
		try (ConfigurableApplicationContext second = open(url)) {
			assertThat(second.getBean(DataSource.class)).isNotSameAs(originalDataSource);
			CategoryRepository categories = second.getBean(CategoryRepository.class);
			TodoRepository todos = second.getBean(TodoRepository.class);
			assertThat(categories.get(category.id())).isEqualTo(category);
			assertThat(categories.countTodos(category.id())).isEqualTo(1);
			assertThat(todos.get(completed.id())).isEqualTo(completed);
			assertThat(todos.get(active.id())).isEqualTo(active);
			assertThat(todos.findAll(CompletionFilter.ALL, CategoryFilter.all())).containsExactly(active, completed);
			assertThat(todos.create("After restart", null, null, category.id()).id()).isGreaterThan(active.id());
			assertThat(categories.create("After restart").id()).isGreaterThan(category.id());
			assertThat(second.getBean(Flyway.class).info().applied()).hasSize(1);
			assertThat(second.getBean(Flyway.class).info().pending()).isEmpty();
		}
	}

	private ConfigurableApplicationContext open(String url) {
		return new SpringApplicationBuilder(PersistenceTestConfiguration.class)
				.web(WebApplicationType.NONE)
				.run("--spring.datasource.url=" + url, "--spring.datasource.username=sa",
						"--spring.datasource.password=", "--spring.flyway.enabled=true",
						"--spring.main.banner-mode=off");
	}
}
