package com.claudecodecoach.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns Spring AI's generic "Incomplete Google GenAI configuration" startup error into a clear fix. */
public class MissingApiKeyFailureAnalyzer extends AbstractFailureAnalyzer<IllegalStateException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, IllegalStateException cause) {
        if (cause.getMessage() == null || !cause.getMessage().contains("Incomplete Google GenAI configuration")) {
            return null;
        }
        return new FailureAnalysis("GEMINI_API_KEY is not set, so the Google AI Studio client cannot be created.",
                "Create an API key at https://aistudio.google.com/apikey, then either export GEMINI_API_KEY=... "
                        + "before starting, or put the line GEMINI_API_KEY=... in .env at the repo root (cp .env.example .env).",
                cause);
    }
}
