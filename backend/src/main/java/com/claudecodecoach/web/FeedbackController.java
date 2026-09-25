package com.claudecodecoach.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.claudecodecoach.feedback.FeedbackService;
import com.claudecodecoach.feedback.FeedbackService.FeedbackRequest;

@RestController
public class FeedbackController {

    private final FeedbackService feedback;

    public FeedbackController(FeedbackService feedback) {
        this.feedback = feedback;
    }

    @PostMapping("/api/feedback")
    public ResponseEntity<Void> submit(@RequestBody FeedbackRequest request) {
        if (request == null || request.conversationId() == null || request.rating() == null) {
            throw new BadRequestException("conversationId, messageIndex and rating (UP or DOWN) are required.");
        }
        feedback.record(request);
        return ResponseEntity.noContent().build();
    }
}
