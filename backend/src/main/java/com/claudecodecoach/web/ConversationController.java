package com.claudecodecoach.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.claudecodecoach.conversation.ConversationService;
import com.claudecodecoach.conversation.ConversationViews.Detail;
import com.claudecodecoach.conversation.ConversationViews.Summary;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    public record RenameRequest(String title) {
    }

    private final ConversationService conversations;

    public ConversationController(ConversationService conversations) {
        this.conversations = conversations;
    }

    @GetMapping
    public List<Summary> list() {
        return conversations.list();
    }

    @GetMapping("/{id}")
    public Detail get(@PathVariable String id) {
        return conversations.get(id);
    }

    @PatchMapping("/{id}")
    public Summary rename(@PathVariable String id, @RequestBody RenameRequest request) {
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new BadRequestException("Title must not be empty.");
        }
        return conversations.rename(id, request.title());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        conversations.delete(id);
        return ResponseEntity.noContent().build();
    }
}
