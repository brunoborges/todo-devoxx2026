package com.example.todoapp.time;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {

	@Bean
	public ZoneId applicationZoneId(@Value("${app.time-zone}") String zoneId) {
		try {
			return ZoneId.of(zoneId);
		}
		catch (DateTimeException ex) {
			throw new IllegalArgumentException("Invalid app.time-zone '" + zoneId
					+ "'. Configure a valid time-zone ID such as UTC or Europe/Paris.", ex);
		}
	}

	@Bean
	@ConditionalOnMissingBean(Clock.class)
	public Clock applicationClock(ZoneId applicationZoneId) {
		return Clock.system(applicationZoneId);
	}
}
