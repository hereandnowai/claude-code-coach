package com.claudecodecoach.retrieval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.claudecodecoach.ingestion.DocChunk;

/**
 * Owns the on-disk Lucene index. Searches use BM25 over title (boost 3), headings (boost 2) and
 * content (boost 1), analysed with Lucene's English analyzer (stemming) and a stop list that also
 * drops question words and corpus-wide words. A proximity field adds a bonus when adjacent query
 * words appear next to each other ("install claude" favours the Quickstart over plugin install pages).
 *
 * <p>Rebuilds write a brand-new index generation with {@code OpenMode.CREATE}; readers keep serving
 * the previous commit until the rebuild commits, so a reindex never leaves search empty.
 */
public class LuceneIndexService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LuceneIndexService.class);

    static final String F_ID = "id";
    static final String F_TITLE = "title";
    static final String F_HEADINGS = "headings";
    static final String F_HEADING_PATH = "headingPath";
    static final String F_URL = "url";
    static final String F_CONTENT = "content";
    /** Title + headings + content, indexed with positions for adjacent-word (bigram) matching. */
    static final String F_PROXIMITY = "proximity";
    /** Title + headings only, with positions: adjacent query words inside a heading are a strong signal. */
    static final String F_HEADING_PROXIMITY = "headingProximity";
    private static final float PROXIMITY_BOOST = 2.0f;
    private static final float HEADING_PROXIMITY_BOOST = 4.0f;
    private static final int PROXIMITY_SLOP = 2;

    private static final String META_INDEXED_AT = "indexedAt";
    private static final String META_PAGES = "pages";

    private static final Map<String, Float> BOOSTS = Map.of(F_TITLE, 3.0f, F_HEADINGS, 2.0f, F_CONTENT, 1.0f);

    public record Hit(DocChunk chunk, float score) {
    }

    public record Stats(boolean ready, int chunks, int pages, Instant indexedAt) {
    }

    /** Lucene's default English stop words. */
    private static final List<String> BASE_STOP = List.of("a", "an", "and", "are", "as", "at", "be", "but", "by",
            "for", "if", "in", "into", "is", "it", "no", "not", "of", "on", "or", "such", "that", "the", "their",
            "then", "there", "these", "they", "this", "to", "was", "will", "with");

    /** Question and filler words: they carry no topic, but match titles like "What's new". */
    static final Set<String> FILLER = Set.of("what", "whats", "what's", "how", "why", "when", "where", "which", "who",
            "whom", "do", "does", "did", "doing", "done", "can", "could", "should", "would", "i", "me", "my", "we",
            "our", "you", "your", "it's", "am", "been", "being", "have", "has", "had", "get", "got", "give", "go",
            "goes", "make", "want", "need", "please", "tell", "explain", "about", "some", "any", "all", "there's",
            "way", "ways", "work", "works", "working", "thing", "things", "just", "also", "using", "use", "used",
            "like", "from", "so", "up", "out", "new", "one", "much", "many", "more", "most", "hi", "hello", "hey",
            "thanks", "thank");

    /** Words on nearly every page of this corpus ("Claude Code ..." titles). "claude.md" is one token. */
    static final Set<String> DOMAIN = Set.of("claude", "code", "claude's", "anthropic");

    private static final Set<String> BASE_STOP_SET = Set.copyOf(BASE_STOP);

    /**
     * Generic verbs/modifiers still count as keywords but never start or end a bigram: "create custom"
     * would otherwise match every "Create a custom X" heading.
     */
    private static final Set<String> NO_BIGRAM = Set.of("create", "creating", "custom", "add", "adding", "configure",
            "configuring", "set", "setup", "enable", "change", "write", "own", "better", "best", "good", "right",
            "project", "file", "files", "or");

    private final Path indexDir;
    /** Keyword fields: base + filler + domain stop words, English stemming. */
    private final Analyzer keywordAnalyzer = new EnglishAnalyzer(stopSet(true));
    /** Proximity field: keeps "claude"/"code" so "install claude code" can match as a phrase. */
    private final Analyzer proximityAnalyzer = new EnglishAnalyzer(stopSet(false));
    private final Analyzer analyzer = new PerFieldAnalyzerWrapper(keywordAnalyzer,
            Map.of(F_PROXIMITY, proximityAnalyzer, F_HEADING_PROXIMITY, proximityAnalyzer));
    private final Object writeLock = new Object();
    private volatile Directory directory;
    private volatile SearcherManager searcherManager;

    public LuceneIndexService(Path indexDir) {
        this.indexDir = indexDir;
        try {
            Files.createDirectories(indexDir);
            this.directory = FSDirectory.open(indexDir);
            if (DirectoryReader.indexExists(directory)) {
                this.searcherManager = newSearcherManager();
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException("Cannot open Lucene index at " + indexDir, e);
        }
    }

    public boolean isReady() {
        return searcherManager != null;
    }

    public Path indexDir() {
        return indexDir;
    }

    /** Replaces the whole index with the given chunks in one commit. */
    public void rebuild(List<DocChunk> chunks, int pageCount) throws IOException {
        synchronized (writeLock) {
            IndexWriterConfig config = new IndexWriterConfig(analyzer)
                    .setOpenMode(IndexWriterConfig.OpenMode.CREATE)
                    .setSimilarity(new BM25Similarity());
            try (IndexWriter writer = new IndexWriter(directory, config)) {
                for (DocChunk c : chunks) {
                    writer.addDocument(toLucene(c));
                }
                Map<String, String> meta = new HashMap<>();
                meta.put(META_INDEXED_AT, Instant.now().toString());
                meta.put(META_PAGES, Integer.toString(pageCount));
                writer.setLiveCommitData(meta.entrySet());
                writer.commit();
            }
            if (searcherManager == null) {
                searcherManager = newSearcherManager();
            }
            else {
                searcherManager.maybeRefreshBlocking();
            }
            log.info("Lucene index rebuilt: {} chunks from {} pages at {}", chunks.size(), pageCount, indexDir);
        }
    }

    public List<Hit> search(String queryText, int topK) {
        SearcherManager manager = searcherManager;
        if (manager == null || queryText == null || queryText.isBlank()) {
            return List.of();
        }
        Query query = parse(queryText);
        if (query == null) {
            return List.of();
        }
        IndexSearcher searcher = null;
        try {
            searcher = manager.acquire();
            searcher.setSimilarity(new BM25Similarity());
            TopDocs top = searcher.search(query, topK);
            List<Hit> hits = new ArrayList<>(top.scoreDocs.length);
            var storedFields = searcher.storedFields();
            for (ScoreDoc sd : top.scoreDocs) {
                Document d = storedFields.document(sd.doc);
                hits.add(new Hit(fromLucene(d), sd.score));
            }
            return hits;
        }
        catch (IOException e) {
            throw new UncheckedIOException("Lucene search failed", e);
        }
        finally {
            if (searcher != null) {
                try {
                    manager.release(searcher);
                }
                catch (IOException e) {
                    log.warn("Failed to release Lucene searcher", e);
                }
            }
        }
    }

    public Stats stats() {
        SearcherManager manager = searcherManager;
        if (manager == null) {
            return new Stats(false, 0, 0, null);
        }
        IndexSearcher searcher = null;
        try {
            searcher = manager.acquire();
            DirectoryReader reader = (DirectoryReader) searcher.getIndexReader();
            Map<String, String> meta = reader.getIndexCommit().getUserData();
            int pages = Integer.parseInt(meta.getOrDefault(META_PAGES, "0"));
            Instant indexedAt = meta.containsKey(META_INDEXED_AT) ? Instant.parse(meta.get(META_INDEXED_AT)) : null;
            return new Stats(reader.numDocs() > 0, reader.numDocs(), pages, indexedAt);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        finally {
            if (searcher != null) {
                try {
                    manager.release(searcher);
                }
                catch (IOException e) {
                    log.warn("Failed to release Lucene searcher", e);
                }
            }
        }
    }

    private Query parse(String text) {
        String[] fields = { F_TITLE, F_HEADINGS, F_CONTENT };
        MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, keywordAnalyzer, BOOSTS);
        parser.setDefaultOperator(QueryParser.Operator.OR);
        Query keywords;
        try {
            keywords = parser.parse(QueryParser.escape(text));
        }
        catch (ParseException e) {
            log.debug("Unparseable query '{}': {}", text, e.getMessage());
            return null;
        }
        if (keywords instanceof BooleanQuery bq && bq.clauses().isEmpty()) {
            // Only filler/domain words ("What is Claude Code?"): fall back to the proximity field,
            // which keeps "claude" and "code".
            return fallback(text);
        }
        List<String[]> pairs = bigrams(text);
        if (pairs.isEmpty()) {
            return keywords;
        }
        BooleanQuery.Builder combined = new BooleanQuery.Builder().add(keywords, BooleanClause.Occur.MUST);
        for (String[] pair : pairs) {
            combined.add(new BoostQuery(new PhraseQuery(PROXIMITY_SLOP, F_PROXIMITY, pair), PROXIMITY_BOOST),
                    BooleanClause.Occur.SHOULD);
            combined.add(new BoostQuery(new PhraseQuery(1, F_HEADING_PROXIMITY, pair), HEADING_PROXIMITY_BOOST),
                    BooleanClause.Occur.SHOULD);
        }
        return combined.build();
    }

    private Query fallback(String text) {
        MultiFieldQueryParser parser = new MultiFieldQueryParser(new String[] { F_TITLE, F_PROXIMITY },
                proximityAnalyzer, Map.of(F_TITLE, 3.0f, F_PROXIMITY, 1.0f));
        try {
            // A question made only of generic words ("What is Claude Code?") is best served by the
            // Overview page, whose title has no distinctive terms, so include it explicitly.
            Query q = parser.parse(QueryParser.escape(text) + " overview");
            return q instanceof BooleanQuery bq && bq.clauses().isEmpty() ? null : q;
        }
        catch (ParseException e) {
            return null;
        }
    }

    /** Each pair of adjacent topical words (filler and base stop words removed), analysed for the proximity fields. */
    List<String[]> bigrams(String text) {
        List<String> terms = new ArrayList<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9._'-]+")) {
            String w = word.replaceAll("^[.'-]+|[.'-]+$", "");
            if (w.isEmpty() || FILLER.contains(w) || BASE_STOP_SET.contains(w) || NO_BIGRAM.contains(w)) {
                continue;
            }
            String term = analyzeSingle(w);
            if (term != null) {
                terms.add(term);
            }
        }
        List<String[]> pairs = new ArrayList<>();
        for (int i = 0; i + 1 < terms.size(); i++) {
            pairs.add(new String[] { terms.get(i), terms.get(i + 1) });
        }
        return pairs;
    }

    private String analyzeSingle(String word) {
        try (TokenStream ts = proximityAnalyzer.tokenStream(F_PROXIMITY, word)) {
            CharTermAttribute attr = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            String term = ts.incrementToken() ? attr.toString() : null;
            ts.end();
            return term;
        }
        catch (IOException e) {
            return null;
        }
    }

    private static CharArraySet stopSet(boolean includeDomainWords) {
        List<String> words = new ArrayList<>(BASE_STOP);
        words.addAll(FILLER);
        if (includeDomainWords) {
            words.addAll(DOMAIN);
        }
        return new CharArraySet(words, true);
    }

    private SearcherManager newSearcherManager() throws IOException {
        return new SearcherManager(directory, null);
    }

    private static Document toLucene(DocChunk c) {
        Document d = new Document();
        d.add(new StringField(F_ID, c.id(), Field.Store.YES));
        d.add(new TextField(F_TITLE, c.pageTitle(), Field.Store.YES));
        d.add(new TextField(F_HEADINGS, c.headings(), Field.Store.YES));
        d.add(new StoredField(F_HEADING_PATH, c.headingPath()));
        d.add(new StringField(F_URL, c.url(), Field.Store.YES));
        d.add(new TextField(F_CONTENT, c.text(), Field.Store.YES));
        d.add(new TextField(F_PROXIMITY, c.pageTitle() + "\n" + c.headings() + "\n" + c.text(), Field.Store.NO));
        d.add(new TextField(F_HEADING_PROXIMITY, c.pageTitle() + " | " + c.headings(), Field.Store.NO));
        return d;
    }

    private static DocChunk fromLucene(Document d) {
        return new DocChunk(d.get(F_ID), d.get(F_TITLE), d.get(F_URL), d.get(F_HEADING_PATH), d.get(F_HEADINGS),
                d.get(F_CONTENT));
    }

    @Override
    public void close() throws IOException {
        if (searcherManager != null) {
            searcherManager.close();
        }
        directory.close();
    }
}
