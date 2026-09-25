package com.claudecodecoach.retrieval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import com.claudecodecoach.config.CoachProperties;
import com.claudecodecoach.ingestion.DocChunk;

/**
 * Spring AI {@link DocumentRetriever} backed by the Lucene BM25 index (no embeddings).
 *
 * <p>BM25 scores are unbounded, so relevance is gated twice: an absolute floor ({@code min-score})
 * removes hits that only matched a stray common word, and a relative cutoff drops hits far below the
 * best one. An empty result makes the RAG advisor switch to its "not in the docs" prompt.
 */
public class LuceneDocumentRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(LuceneDocumentRetriever.class);

    public static final String META_TITLE = "title";
    public static final String META_URL = "url";
    public static final String META_HEADING_PATH = "headingPath";

    private final LuceneIndexService index;
    private final CoachProperties.Retrieval settings;

    public LuceneDocumentRetriever(LuceneIndexService index, CoachProperties.Retrieval settings) {
        this.index = index;
        this.settings = settings;
    }

    @Override
    public List<Document> retrieve(Query query) {
        List<LuceneIndexService.Hit> hits = diversify(index.search(query.text(), settings.topK() * 10));
        if (hits.isEmpty()) {
            log.info("Retrieval: 0 hits");
            log.debug("Retrieval query with no hits: '{}'", abbreviate(query.text()));
            return List.of();
        }
        float best = hits.getFirst().score();
        float floor = Math.max(settings.minScore(), best * settings.relativeCutoff());
        List<Document> docs = hits.stream()
            .filter(h -> h.score() >= floor)
            .map(LuceneDocumentRetriever::toDocument)
            .toList();
        log.info("Retrieval: {} of {} hits kept (best={}, floor={})", docs.size(), hits.size(),
                String.format("%.2f", best), String.format("%.2f", floor));
        log.debug("Retrieval query: '{}'", abbreviate(query.text()));
        return docs;
    }

    /** Keeps at most {@code maxChunksPerPage} chunks from any one page, so one long page can't crowd out the rest. */
    private List<LuceneIndexService.Hit> diversify(List<LuceneIndexService.Hit> hits) {
        Map<String, Integer> perPage = new HashMap<>();
        List<LuceneIndexService.Hit> kept = new ArrayList<>(settings.topK());
        for (LuceneIndexService.Hit h : hits) {
            if (perPage.merge(h.chunk().url(), 1, Integer::sum) <= settings.maxChunksPerPage()) {
                kept.add(h);
                if (kept.size() == settings.topK()) {
                    break;
                }
            }
        }
        return kept;
    }

    private static Document toDocument(LuceneIndexService.Hit hit) {
        DocChunk c = hit.chunk();
        return Document.builder()
            .id(c.id())
            .text(c.text())
            .metadata(Map.of(META_TITLE, c.pageTitle(), META_URL, c.url(), META_HEADING_PATH, c.headingPath()))
            .score((double) hit.score())
            .build();
    }

    private static String abbreviate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 117) + "...";
    }
}
