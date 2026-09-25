package com.claudecodecoach.chat;

import java.util.List;

import com.claudecodecoach.conversation.SourceRef;

/**
 * Where a streamed answer goes. The web layer implements it over SSE; tests capture events.
 * Implementations throw {@link ClientGoneException} once the client has disconnected.
 */
public interface StreamSink {

    record Meta(String conversationId, String title, boolean created, int userMessageIndex) {
    }

    record Done(String conversationId, int messageIndex) {
    }

    void meta(Meta meta);

    void token(String text);

    void sources(List<SourceRef> sources);

    void done(Done done);

    void error(ChatError error);

    /** The browser went away (Stop button, closed tab). Treated as a cancel, not a failure. */
    class ClientGoneException extends RuntimeException {

        public ClientGoneException(Throwable cause) {
            super("Client disconnected", cause);
        }
    }
}
