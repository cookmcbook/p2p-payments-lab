package dev.p2plab.payments.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} methods. The only one so far purges expired idempotency keys (Chapter 4). */
@Configuration
@EnableScheduling
class SchedulingConfig {
}
