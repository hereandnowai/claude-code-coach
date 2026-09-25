package com.claudecodecoach.ingestion;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import com.claudecodecoach.config.CoachProperties;
import com.claudecodecoach.retrieval.LuceneIndexService;

/**
 * Downloads the Claude Code docs listed in llms.txt, chunks them and rebuilds the Lucene index.
 * Runs on startup when no index exists, on a daily cron, and on demand via the admin endpoint.
 * Only one ingestion runs at a time; a run that fetches too few pages keeps the existing index.
 */
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    /** Refuse to replace a good index when more than half the pages failed to download. */
    private static final double MIN_SUCCESS_RATIO = 0.5;

    public record Status(boolean running, Instant lastStartedAt, Instant lastFinishedAt, String lastError,
            int lastPagesFetched, int lastPagesFailed) {
    }

    private final CoachProperties.Ingestion settings;
    private final DocsFetcher fetcher;
    private final LuceneIndexService index;
    private final MarkdownChunker chunker;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Status status = new Status(false, null, null, null, 0, 0);

    public IngestionService(CoachProperties.Ingestion settings, DocsFetcher fetcher, LuceneIndexService index) {
        this.settings = settings;
        this.fetcher = fetcher;
        this.index = index;
        this.chunker = new MarkdownChunker(settings.targetChunkTokens(), settings.maxChunkTokens(),
                settings.overlapTokens());
    }

    public Status status() {
        return status;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ingestOnStartupIfMissing() {
        if (settings.onStartupIfMissing() && !index.isReady()) {
            log.info("No Lucene index found at {}; ingesting Claude Code docs in the background",
                    index.indexDir());
            startAsync();
        }
    }

    @Scheduled(cron = "${coach.ingestion.cron}")
    public void scheduledRefresh() {
        log.info("Scheduled docs refresh starting");
        runNow();
    }

    /** Starts an ingestion on a virtual thread. Returns false if one is already running. */
    public boolean startAsync() {
        if (running.get()) {
            return false;
        }
        Thread.ofVirtual().name("docs-ingestion").start(this::runNow);
        return true;
    }

    /** Runs an ingestion on the calling thread. Returns false if one was already running. */
    public boolean runNow() {
        if (!running.compareAndSet(false, true)) {
            log.info("Ingestion already running; skipping");
            return false;
        }
        Instant started = Instant.now();
        status = new Status(true, started, status.lastFinishedAt(), null, 0, 0);
        try {
            String llmsTxt = fetcher.fetch(settings.llmsTxtUrl());
            List<DocPage> pages = LlmsTxtParser.parse(llmsTxt)
                .stream()
                .filter(p -> p.markdownUrl().startsWith(settings.pageUrlPrefix()))
                .toList();
            if (pages.isEmpty()) {
                throw new IllegalStateException("llms.txt listed no pages under " + settings.pageUrlPrefix());
            }
            log.info("llms.txt lists {} pages; fetching with concurrency {}", pages.size(),
                    settings.fetchConcurrency());

            List<DocChunk> chunks = Collections.synchronizedList(new ArrayList<>());
            int failed = fetchAndChunk(pages, chunks);
            int fetched = pages.size() - failed;
            if (fetched < pages.size() * MIN_SUCCESS_RATIO) {
                throw new IllegalStateException(
                        "Only " + fetched + " of " + pages.size() + " pages downloaded; keeping the existing index");
            }
            List<DocChunk> ordered = new ArrayList<>(chunks);
            ordered.sort((a, b) -> a.id().compareTo(b.id()));
            index.rebuild(ordered, fetched);
            status = new Status(false, started, Instant.now(), null, fetched, failed);
            log.info("Ingestion finished in {}s: {} pages, {} failed, {} chunks",
                    Duration.between(started, Instant.now()).toSeconds(), fetched, failed, ordered.size());
            return true;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = new Status(false, started, Instant.now(), "interrupted", 0, 0);
            return true;
        }
        catch (Exception e) {
            log.error("Ingestion failed: {}", e.getMessage(), e);
            status = new Status(false, started, Instant.now(), e.getMessage(), 0, 0);
            return true;
        }
        finally {
            running.set(false);
        }
    }

    private int fetchAndChunk(List<DocPage> pages, List<DocChunk> sink) throws InterruptedException {
        Semaphore permits = new Semaphore(Math.max(1, settings.fetchConcurrency()));
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (DocPage page : pages) {
                results.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        String markdown = fetchWithRetry(page.markdownUrl());
                        sink.addAll(chunker.chunk(page, truncate(markdown)));
                        return true;
                    }
                    catch (Exception e) {
                        log.warn("Skipping {}: {}", page.markdownUrl(), e.getMessage());
                        return false;
                    }
                    finally {
                        permits.release();
                    }
                }));
            }
        }
        int failed = 0;
        for (Future<Boolean> f : results) {
            try {
                if (!f.get()) {
                    failed++;
                }
            }
            catch (java.util.concurrent.ExecutionException e) {
                failed++;
            }
        }
        return failed;
    }

    private String fetchWithRetry(String url) throws Exception {
        try {
            return fetcher.fetch(url);
        }
        catch (java.io.IOException first) {
            Thread.sleep(1000);
            return fetcher.fetch(url);
        }
    }

    private String truncate(String markdown) {
        if (markdown.length() <= settings.maxPageChars()) {
            return markdown;
        }
        int cut = markdown.lastIndexOf('\n', settings.maxPageChars());
        return markdown.substring(0, cut > 0 ? cut : settings.maxPageChars());
    }
}
