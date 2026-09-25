package com.claudecodecoach;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * claude-code-coach: answers Claude Code questions from the official docs (Spring AI + Gemma 4 + Lucene).
 *
 * <p>This app was built using Claude Code with Opus 5.5 by Ruthran Raghavan, Chief AI Scientist,
 * https://ruthranraghavan.com
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CoachApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoachApplication.class, args);
    }
}
