package com.example.todoapp.persistence;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

@TestConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import({TodoRepository.class, CategoryRepository.class})
class PersistenceTestConfiguration {
}
