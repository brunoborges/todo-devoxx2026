package com.example.todoapp.time;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class TimeConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(TimeConfiguration.class, DueTimeService.class);

	@Test
	void injectsConfiguredZoneAndClockIntoService() {
		contextRunner.withPropertyValues("app.time-zone=Europe/Paris").run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(ZoneId.class).hasSingleBean(Clock.class)
					.hasSingleBean(DueTimeService.class);
			assertThat(context.getBean(ZoneId.class)).isEqualTo(ZoneId.of("Europe/Paris"));
			assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of("Europe/Paris"));
			assertThat(context.getBean(DueTimeService.class).getZoneId()).isEqualTo("Europe/Paris");
		});
	}

	@Test
	void acceptsAnInjectedClockInsteadOfSystemClock() {
		Instant now = Instant.parse("2026-10-05T12:00:00Z");
		Clock fixed = Clock.fixed(now, ZoneOffset.UTC);
		contextRunner.withPropertyValues("app.time-zone=UTC").withBean(Clock.class, () -> fixed)
				.run(context -> {
					assertThat(context).hasNotFailed().hasSingleBean(Clock.class);
					assertThat(context.getBean(Clock.class)).isSameAs(fixed);
					var service = context.getBean(DueTimeService.class);
					assertThat(service.isOverdue(now.minusNanos(1), false)).isTrue();
					assertThat(service.isOverdue(now, false)).isFalse();
				});
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "Mars/Olympus", "utc" })
	void invalidZoneFailsStartupExplicitly(String zoneId) {
		contextRunner.withPropertyValues("app.time-zone=" + zoneId).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("Invalid app.time-zone");
		});
	}

	@Test
	void missingZoneDoesNotFallBackToHostZone() {
		contextRunner.run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("app.time-zone");
		});
	}
}
