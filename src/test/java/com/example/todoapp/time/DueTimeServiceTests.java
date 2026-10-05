package com.example.todoapp.time;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DueTimeServiceTests {

	private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

	private final DueTimeService service = new DueTimeService(ZoneId.of("Europe/Paris"),
			Clock.fixed(NOW, ZoneOffset.UTC));

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "\t\n" })
	void blankInputsLeaveOrClearDueTime(String blank) {
		assertNull(service.parse(blank, blank));
		assertNull(service.parse(blank, null));
		assertNull(service.parse(null, blank));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "\t\n" })
	void partialInputsIdentifyMissingField(String blank) {
		assertValidation("dueDate", blank, "12:30", "due date");
		assertValidation("dueTime", "2026-10-05", blank, "due time");
	}

	@ParameterizedTest
	@ValueSource(strings = { "not a date", "2026-02-29", "2024-02-30", "2026-04-31",
			"2026-13-01", "2026-00-01", "2026-01-00", "05/10/2026", "2026-1-1",
			"2026-10-05T12:00" })
	void rejectsMalformedAndStrictlyInvalidDates(String date) {
		assertValidation("dueDate", date, "12:30", "valid due date");
	}

	@ParameterizedTest
	@ValueSource(strings = { "not a time", "24:00", "12:60", "12:30:60", "1:30", "12",
			"12:30Z", "12:30+02:00", "12:30:00.1234567890", "12:30:00.", "12:30.5" })
	void rejectsMalformedAndStrictlyInvalidTimes(String time) {
		assertValidation("dueTime", "2026-10-05", time, "valid due time");
	}

	@Test
	void parsesLeapDayAndPastValuesWithoutRestriction() {
		assertEquals(Instant.parse("2024-02-29T11:30:00Z"), service.parse("2024-02-29", "12:30"));
		assertEquals(Instant.parse("2000-01-01T00:00:00Z"), service.parse("2000-01-01", "01:00"));
	}

	@Test
	void stripsSurroundingWhitespace() {
		assertEquals(Instant.parse("2026-10-05T10:30:00Z"), service.parse(" 2026-10-05 ", "\t12:30 "));
	}

	@ParameterizedTest
	@CsvSource({
			"12:34, 2026-10-05T10:34:00Z, 12:34:00",
			"12:34:56, 2026-10-05T10:34:56Z, 12:34:56",
			"12:34:56.1, 2026-10-05T10:34:56.1Z, 12:34:56.1",
			"12:34:56.123, 2026-10-05T10:34:56.123Z, 12:34:56.123",
			"12:34:56.123456789, 2026-10-05T10:34:56.123456789Z, 12:34:56.123456789",
			"00:00, 2026-10-04T22:00:00Z, 00:00:00"
	})
	void conversionAndEditableValuesRoundTripWithoutPrecisionLoss(String inputTime, String instant,
			String outputTime) {
		Instant dueAt = service.parse("2026-10-05", inputTime);
		assertEquals(Instant.parse(instant), dueAt);
		assertEquals("2026-10-05", service.dateValue(dueAt));
		assertEquals(outputTime, service.timeValue(dueAt));
		assertEquals(dueAt, service.parse(service.dateValue(dueAt), service.timeValue(dueAt)));
		assertEquals("2026-10-05 " + outputTime + " Europe/Paris", service.format(dueAt));
		assertEquals("Europe/Paris", service.getZoneId());
	}

	@Test
	void nullInstantHasEmptyDisplayAndEditableValues() {
		assertEquals("", service.format(null));
		assertEquals("", service.dateValue(null));
		assertEquals("", service.timeValue(null));
		assertNull(service.parse(service.dateValue(null), service.timeValue(null)));
	}

	@Test
	void rejectsNonexistentDstLocalTime() {
		assertValidation("dueTime", "2026-03-29", "02:30", "does not exist");
	}

	@Test
	void rejectsAmbiguousDstLocalTime() {
		assertValidation("dueTime", "2026-10-25", "02:30", "ambiguous");
	}

	@Test
	void acceptsUnambiguousTimesOnBothSidesOfDstTransitions() {
		assertEquals(Instant.parse("2026-03-29T00:59:59Z"), service.parse("2026-03-29", "01:59:59"));
		assertEquals(Instant.parse("2026-03-29T01:00:00Z"), service.parse("2026-03-29", "03:00"));
		assertEquals(Instant.parse("2026-10-24T23:59:59Z"), service.parse("2026-10-25", "01:59:59"));
		assertEquals(Instant.parse("2026-10-25T02:00:00Z"), service.parse("2026-10-25", "03:00"));
	}

	@Test
	@ResourceLock(Resources.TIME_ZONE)
	void neverUsesHostDefaultZoneOrClockZoneForConversion() {
		TimeZone original = TimeZone.getDefault();
		try {
			TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
			var configured = new TimeConfiguration();
			var zone = configured.applicationZoneId("Asia/Tokyo");
			var tokyo = new DueTimeService(zone, Clock.fixed(NOW, ZoneId.of("America/New_York")));
			assertEquals(Instant.parse("2026-10-05T03:30:00Z"), tokyo.parse("2026-10-05", "12:30"));
			assertEquals("2026-10-05 21:00:00 Asia/Tokyo", tokyo.format(NOW));
			assertEquals(zone, configured.applicationClock(zone).getZone());
			assertTrue(tokyo.isOverdue(NOW.minusNanos(1), false));
		}
		finally {
			TimeZone.setDefault(original);
		}
	}

	@Test
	void overdueRequiresStrictlyBeforeNow() {
		assertTrue(service.isOverdue(NOW.minusNanos(1), false));
		assertFalse(service.isOverdue(NOW, false));
		assertFalse(service.isOverdue(NOW.plusNanos(1), false));
	}

	@Test
	void completedAndUndatedAreNeverOverdue() {
		assertFalse(service.isOverdue(NOW.minusSeconds(60), true));
		assertFalse(service.isOverdue(NOW, true));
		assertFalse(service.isOverdue(NOW.plusSeconds(60), true));
		assertFalse(service.isOverdue(null, false));
		assertFalse(service.isOverdue(null, true));
	}

	private void assertValidation(String field, String date, String time, String message) {
		var error = assertThrows(DueTimeValidationException.class, () -> service.parse(date, time));
		assertEquals(field, error.getField());
		assertTrue(error.getMessage().contains(message), error.getMessage());
	}
}
