package com.example.todoapp.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.Objects;

import org.springframework.stereotype.Service;

@Service
public class DueTimeService {

	private static final DateTimeFormatter INPUT_TIME = new DateTimeFormatterBuilder()
			.appendPattern("HH:mm")
			.optionalStart().appendPattern(":ss")
			.optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
			.optionalEnd().toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

	private final ZoneId zone;
	private final Clock clock;

	public DueTimeService(ZoneId zone, Clock clock) {
		this.zone = Objects.requireNonNull(zone, "Application time zone is required");
		this.clock = Objects.requireNonNull(clock, "Clock is required");
	}

	/**
	 * Parses an ISO date and HH:mm[:ss[.fraction]] in the configured zone.
	 * A blank pair clears the due time; ambiguous and nonexistent local times are rejected.
	 */
	public Instant parse(String dueDate, String dueTime) {
		boolean dateMissing = dueDate == null || dueDate.isBlank();
		boolean timeMissing = dueTime == null || dueTime.isBlank();
		if (dateMissing && timeMissing) {
			return null;
		}
		if (dateMissing) {
			throw new DueTimeValidationException("dueDate", "Enter a due date together with the due time.");
		}
		if (timeMissing) {
			throw new DueTimeValidationException("dueTime", "Enter a due time together with the due date.");
		}

		LocalDate date;
		LocalTime time;
		try {
			date = LocalDate.parse(dueDate.strip(), DateTimeFormatter.ISO_LOCAL_DATE);
		}
		catch (DateTimeParseException ex) {
			throw new DueTimeValidationException("dueDate", "Enter a valid due date in YYYY-MM-DD format.");
		}
		try {
			time = LocalTime.parse(dueTime.strip(), INPUT_TIME);
		}
		catch (DateTimeParseException ex) {
			throw new DueTimeValidationException("dueTime",
					"Enter a valid due time in HH:mm format, optionally including seconds and fractional seconds.");
		}

		LocalDateTime local = LocalDateTime.of(date, time);
		var offsets = zone.getRules().getValidOffsets(local);
		if (offsets.isEmpty()) {
			throw new DueTimeValidationException("dueTime",
					"This due time does not exist in " + zone.getId()
							+ " because of a clock change. Choose another time.");
		}
		if (offsets.size() != 1) {
			throw new DueTimeValidationException("dueTime",
					"This due time is ambiguous in " + zone.getId()
							+ " because of a clock change. Choose another time.");
		}
		return local.toInstant(offsets.getFirst());
	}

	public String format(Instant dueAt) {
		return dueAt == null ? "" : dateValue(dueAt) + " " + timeValue(dueAt) + " " + getZoneId();
	}

	public String dateValue(Instant dueAt) {
		return dueAt == null ? "" : DateTimeFormatter.ISO_LOCAL_DATE.format(dueAt.atZone(zone));
	}

	public String timeValue(Instant dueAt) {
		return dueAt == null ? "" : DateTimeFormatter.ISO_LOCAL_TIME.format(dueAt.atZone(zone));
	}

	public String getZoneId() {
		return zone.getId();
	}

	public boolean isOverdue(Instant dueAt, boolean completed) {
		return !completed && dueAt != null && dueAt.isBefore(clock.instant());
	}
}
