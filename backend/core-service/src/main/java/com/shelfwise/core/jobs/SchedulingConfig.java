package com.shelfwise.core.jobs;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns the schedule on (off in tests, which run the jobs directly). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "shelfwise.jobs.enabled", havingValue = "true")
class SchedulingConfig {
}
