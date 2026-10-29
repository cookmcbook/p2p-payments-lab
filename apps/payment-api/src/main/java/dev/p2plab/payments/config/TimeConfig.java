package dev.p2plab.payments.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides a single injectable clock so tests can control time instead of calling Instant.now(). */
@Configuration
public class TimeConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
