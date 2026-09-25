package com.claudecodecoach.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a documentation page into retrievable chunks.
 *
 * <ol>
 * <li>Cleans the Mintlify MDX: drops the llms.txt preamble and embedded React components, turns
 * {@code <Step title="...">}/{@code <Tab title="...">} into bold labels and callouts such as
 * {@code <Warning>} into "Warning:" lines, and removes all other JSX tags. Code fences are never
 * touched.</li>
 * <li>Splits on Markdown headings (outside code fences), tracking the heading path.</li>
 * <li>Packs consecutive small sections together up to the target size; splits oversized sections on
 * paragraph boundaries, carrying a small tail of the previous piece forward as overlap.</li>
 * </ol>
 * Token counts are estimated at ~4 characters per token, which is close enough for sizing.
 */
public final class MarkdownChunker {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");
    private static final Pattern TITLED_TAG = Pattern.compile("^\\s*<(Step|Tab|Accordion|Card|Update)\\b[^>]*?(?:title|label)=\"([^\"]*)\"[^>]*>\\s*$");
    private static final Pattern CALLOUT_TAG = Pattern.compile("^\\s*<(Warning|Note|Tip|Info|Callout|Check)\\b[^>]*>\\s*$");
    private static final Pattern JSX_LINE = Pattern.compile("^\\s*</?[A-Z][A-Za-z]*\\b[^>]*/?>\\s*$");
    private static final Pattern INLINE_JSX = Pattern.compile("</?[A-Z][A-Za-z]*\\b[^>]*/?>");

    private final int targetTokens;
    private final int maxTokens;
    private final int overlapTokens;

    public MarkdownChunker(int targetTokens, int maxTokens, int overlapTokens) {
        this.targetTokens = targetTokens;
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
    }

    public static int estimateTokens(String text) {
        return (text.length() + 3) / 4;
    }

    public List<DocChunk> chunk(DocPage page, String markdown) {
        List<Section> sections = splitSections(clean(markdown), page.title());
        List<DocChunk> chunks = new ArrayList<>();
        List<Section> pending = new ArrayList<>();
        int pendingTokens = 0;

        for (Section section : sections) {
            int tokens = estimateTokens(section.text());
            if (tokens > maxTokens) {
                flush(page, pending, chunks);
                pending.clear();
                pendingTokens = 0;
                for (String piece : splitLarge(section.text())) {
                    chunks.add(toChunk(page, chunks.size(), section.path(), List.of(section), piece));
                }
                continue;
            }
            if (!pending.isEmpty() && pendingTokens + tokens > targetTokens) {
                flush(page, pending, chunks);
                pending.clear();
                pendingTokens = 0;
            }
            pending.add(section);
            pendingTokens += tokens;
        }
        flush(page, pending, chunks);
        return chunks;
    }

    // ---------------------------------------------------------------- cleaning

    static String clean(String markdown) {
        String[] lines = markdown.split("\\R", -1);
        StringBuilder out = new StringBuilder(markdown.length());
        boolean inFence = false;
        boolean inPreamble = true;
        boolean inComponent = false;

        for (String line : lines) {
            if (inComponent) {
                if (line.equals("};") || line.equals("}")) {
                    inComponent = false;
                }
                continue;
            }
            if (FENCE.matcher(line).find()) {
                inFence = !inFence;
                out.append(line).append('\n');
                continue;
            }
            if (inFence) {
                out.append(line).append('\n');
                continue;
            }
            // llms.txt preamble: a leading block quote before the first heading.
            if (inPreamble) {
                if (line.startsWith(">") || line.isBlank()) {
                    continue;
                }
                inPreamble = false;
            }
            if (line.startsWith("export ") || line.startsWith("import ")) {
                inComponent = !line.trim().endsWith(";");
                continue;
            }
            Matcher titled = TITLED_TAG.matcher(line);
            if (titled.matches()) {
                String label = titled.group(2).trim();
                if (!label.isEmpty()) {
                    out.append("**").append(label).append("**\n");
                }
                continue;
            }
            Matcher callout = CALLOUT_TAG.matcher(line);
            if (callout.matches()) {
                out.append(callout.group(1)).append(": ");
                continue;
            }
            if (JSX_LINE.matcher(line).matches()) {
                continue;
            }
            out.append(INLINE_JSX.matcher(line).replaceAll("")).append('\n');
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    // ---------------------------------------------------------------- sections

    private record Section(List<String> path, String heading, String text) {
    }

    private static List<Section> splitSections(String markdown, String pageTitle) {
        List<Section> sections = new ArrayList<>();
        String[] stack = new String[7];
        List<String> currentPath = List.of(pageTitle);
        String currentHeading = pageTitle;
        StringBuilder body = new StringBuilder();
        boolean inFence = false;

        for (String line : markdown.split("\n", -1)) {
            if (FENCE.matcher(line).find()) {
                inFence = !inFence;
            }
            Matcher h = inFence ? null : HEADING.matcher(line);
            if (h != null && h.matches()) {
                addSection(sections, currentPath, currentHeading, body);
                body.setLength(0);
                int level = h.group(1).length();
                String title = h.group(2).replaceAll("[`*_]", "").trim();
                stack[level] = title;
                for (int i = level + 1; i < stack.length; i++) {
                    stack[i] = null;
                }
                List<String> path = new ArrayList<>();
                path.add(pageTitle);
                for (int i = 1; i <= level; i++) {
                    if (stack[i] != null && !stack[i].equalsIgnoreCase(pageTitle)) {
                        path.add(stack[i]);
                    }
                }
                currentPath = List.copyOf(path);
                currentHeading = title;
            }
            body.append(line).append('\n');
        }
        addSection(sections, currentPath, currentHeading, body);
        return sections;
    }

    private static void addSection(List<Section> sections, List<String> path, String heading, StringBuilder body) {
        String text = body.toString().strip();
        // A section that is only its heading line carries no content worth indexing on its own.
        if (text.isEmpty() || (HEADING.matcher(text).matches() && !text.contains("\n"))) {
            return;
        }
        sections.add(new Section(path, heading, text));
    }

    // ---------------------------------------------------------------- packing

    private void flush(DocPage page, List<Section> pending, List<DocChunk> chunks) {
        if (pending.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (Section s : pending) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append(s.text());
        }
        chunks.add(toChunk(page, chunks.size(), pending.getFirst().path(), pending, text.toString()));
    }

    private static DocChunk toChunk(DocPage page, int ordinal, List<String> path, List<Section> sections, String text) {
        List<String> headings = new ArrayList<>();
        for (Section s : sections) {
            headings.add(s.heading());
        }
        return new DocChunk(page.pageUrl() + "#" + ordinal, page.title(), page.pageUrl(), String.join(" > ", path),
                String.join(" | ", headings), text);
    }

    /** Splits an oversized section on blank lines outside code fences, with a small overlap. */
    private List<String> splitLarge(String text) {
        List<String> blocks = paragraphs(text);
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String tail = "";

        for (String block : blocks) {
            for (String part : hardSplit(block)) {
                if (!current.isEmpty() && estimateTokens(current.toString()) + estimateTokens(part) > targetTokens) {
                    String piece = current.toString().strip();
                    pieces.add(piece);
                    tail = tailOf(piece);
                    current.setLength(0);
                    if (!tail.isEmpty()) {
                        current.append(tail).append("\n\n");
                    }
                }
                current.append(part).append("\n\n");
            }
        }
        String last = current.toString().strip();
        if (!last.isEmpty() && !last.equals(tail.strip())) {
            pieces.add(last);
        }
        return pieces;
    }

    private static List<String> paragraphs(String text) {
        List<String> blocks = new ArrayList<>();
        StringBuilder block = new StringBuilder();
        boolean inFence = false;
        for (String line : text.split("\n", -1)) {
            if (FENCE.matcher(line).find()) {
                inFence = !inFence;
            }
            if (!inFence && line.isBlank()) {
                if (!block.isEmpty()) {
                    blocks.add(block.toString().strip());
                    block.setLength(0);
                }
                continue;
            }
            block.append(line).append('\n');
        }
        if (!block.isEmpty()) {
            blocks.add(block.toString().strip());
        }
        return blocks;
    }

    /** A single paragraph or code block larger than the hard maximum is cut on line boundaries. */
    private List<String> hardSplit(String block) {
        if (estimateTokens(block) <= maxTokens) {
            return List.of(block);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        int maxChars = maxTokens * 4;
        for (String line : block.split("\n")) {
            if (!part.isEmpty() && estimateTokens(part.toString()) + estimateTokens(line) > maxTokens) {
                parts.add(part.toString().strip());
                part.setLength(0);
            }
            // A single line longer than a whole chunk (minified content) is cut by characters.
            while (line.length() > maxChars) {
                parts.add(line.substring(0, maxChars));
                line = line.substring(maxChars);
            }
            part.append(line).append('\n');
        }
        if (!part.isEmpty()) {
            parts.add(part.toString().strip());
        }
        return parts;
    }

    /** The last paragraph(s) of a piece, up to the overlap budget, never a partial code fence. */
    private String tailOf(String piece) {
        if (overlapTokens <= 0) {
            return "";
        }
        List<String> blocks = paragraphs(piece);
        StringBuilder tail = new StringBuilder();
        for (int i = blocks.size() - 1; i >= 0; i--) {
            String b = blocks.get(i);
            if (estimateTokens(b) + estimateTokens(tail.toString()) > overlapTokens) {
                break;
            }
            tail.insert(0, b + "\n\n");
        }
        return tail.toString().strip();
    }
}
