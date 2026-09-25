package com.claudecodecoach.ingestion;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** JDK HttpClient based fetcher; follows redirects and fails on non-2xx responses. */
public class HttpDocsFetcher implements DocsFetcher {

    private final HttpClient client;
    private final Duration timeout;

    public HttpDocsFetcher(Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(timeout)
            .build();
    }

    @Override
    public String fetch(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("User-Agent", "claude-code-coach/0.1 (docs indexer)")
            .header("Accept", "text/markdown, text/plain;q=0.9, */*;q=0.1")
            .GET()
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("GET " + url + " returned HTTP " + response.statusCode());
        }
        String body = response.body();
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        String head = body.stripLeading();
        // A bot-protection interstitial or error page comes back as HTML with HTTP 200.
        if (contentType.contains("text/html") || head.regionMatches(true, 0, "<!DOCTYPE", 0, 9)
                || head.regionMatches(true, 0, "<html", 0, 5)) {
            throw new IOException("GET " + url + " returned HTML instead of Markdown");
        }
        return body;
    }
}
