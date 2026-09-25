package com.claudecodecoach.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.claudecodecoach.config.CoachProperties;
import com.claudecodecoach.ingestion.IngestionService;

/**
 * {@code POST /api/admin/reindex} with {@code Authorization: Bearer $ADMIN_TOKEN}. Disabled (403)
 * when ADMIN_TOKEN is not set.
 */
@RestController
public class AdminController {

    public record ReindexResponse(boolean started, IngestionService.Status status) {
    }

    private final IngestionService ingestion;
    private final String adminToken;

    public AdminController(IngestionService ingestion, CoachProperties properties) {
        this.ingestion = ingestion;
        this.adminToken = properties.adminToken();
    }

    @PostMapping("/api/admin/reindex")
    public ResponseEntity<?> reindex(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (adminToken == null || adminToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", "Admin endpoint is disabled: set ADMIN_TOKEN to enable it."));
        }
        String presented = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).strip() : "";
        if (!MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                adminToken.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of("UNAUTHORIZED", "Missing or wrong admin token."));
        }
        boolean started = ingestion.startAsync();
        return ResponseEntity.status(started ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT)
            .body(new ReindexResponse(started, ingestion.status()));
    }
}
