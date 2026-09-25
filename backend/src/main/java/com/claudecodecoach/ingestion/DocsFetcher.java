package com.claudecodecoach.ingestion;

import java.io.IOException;

/** Fetches a URL's body as text. An interface so ingestion can be tested without the network. */
@FunctionalInterface
public interface DocsFetcher {

    String fetch(String url) throws IOException, InterruptedException;
}
