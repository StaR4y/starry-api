package tech.starryapi.assistant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tech.starryapi.documentation.DocumentationDtos.Content;
import tech.starryapi.documentation.DocumentationDtos.Document;
import tech.starryapi.documentation.DocumentationDtos.Translation;
import tech.starryapi.documentation.DocumentationPublicationChanged;
import tech.starryapi.documentation.DocumentationService;

/**
 * What the whale assistant can look things up in: the published pages of the in-console 使用文档 (Chinese and, where a
 * page has one, English) and, for development checkouts, README.md and docs/*.md under the working directory.
 *
 * <p>The pages are read through R2DBC into one immutable snapshot shared by all questions. The snapshot is rebuilt
 * lazily, by a single load however many questions arrive meanwhile, when {@link DocumentationService} reports a
 * publish, unpublish or delete that has committed (this node) or a restore clears the caches ({@link #invalidate()}),
 * and, for changes made on other nodes, when it is older than {@link #CACHE_TTL} <em>and</em> the cheap fingerprint of
 * the published pages has changed. If loading fails the assistant carries on with the file chunks only and tries
 * again shortly.
 *
 * <p>Both language versions of a page are matched as one document and the one in the reader's language is quoted.
 * Questions are scored on the parallel scheduler, never on the thread that delivered the snapshot, which is a database
 * event loop.
 */
@Component
public class AssistantDocsIndex {

    private static final Logger log = LoggerFactory.getLogger(AssistantDocsIndex.class);

    /** How long a loaded snapshot is trusted; changes made on another node show up within this time. */
    static final long CACHE_TTL = 60_000;
    /** After a failed load the assistant answers without the pages for this long, then tries again. */
    static final long RETRY_AFTER = 10_000;
    private static final long WARN_INTERVAL = 60_000;

    static final int MAX_RESULTS = 6;
    static final int MIN_RESULTS = 4;
    /** Per page (or file) at most this many chunks in a first pass, then up to {@link #MAX_PER_SOURCE} to fill up. */
    private static final int PREFERRED_PER_SOURCE = 2;
    private static final int MAX_PER_SOURCE = 3;
    /** Total characters of excerpts, labels included, that may be put into one prompt. */
    static final int EXCERPT_BUDGET = 8000;
    /** Bounds the memory and the per-question scan however many large pages there are; shared out fairly. */
    static final int MAX_PAGE_CHUNKS = 2000;
    private static final int MAX_FILE_CHUNKS = 80;
    private static final int MAX_TITLE_CHARS = 100;
    /** The words of one question that are searched for; a pasted log is cut down to the ones the documents know. */
    static final int MAX_QUERY_TERMS = 32;
    private static final int MAX_PREVIOUS_TERMS = 16;
    /** A message with more terms than this has a topic of its own; the earlier question is not searched for as well. */
    private static final int MAX_TERMS_FOR_PREVIOUS = 16;
    /** How many earlier questions are looked at to find the one a bare follow-up is about. */
    static final int MAX_EARLIER_MESSAGES = 6;
    private static final int MAX_PHRASES = 8;
    /** How many terms of a message are looked at before the ones to search for are chosen. */
    private static final int MAX_CANDIDATE_TERMS = 600;

    // What a mention of a word is worth in points, before the rarity of the word is taken into account: one in a
    // title or a heading counts far more than one in running text, of which up to three are counted.
    private static final int TITLE_POINTS = 6;
    private static final int SECTION_POINTS = 5;
    private static final int CATEGORY_POINTS = 3;
    private static final int SUMMARY_POINTS = 2;
    private static final int BODY_POINTS = 1;
    private static final int MAX_BODY_HITS = 3;
    /** A chunk that matches the page only (title, category, summary) and not its own text counts half. */
    private static final double PAGE_ONLY_MATCH = 0.5;

    // How much each kind of term of a question counts.
    private static final double WORD_WEIGHT = 1.0;
    /** A lone Chinese character left over after the question wording is removed ("改" in "那怎么改"). */
    private static final double UNIGRAM_WEIGHT = 0.6;
    /** A whole identifier such as API_KEY_MODEL_PROVIDER_FORBIDDEN or config.toml counts far more than its parts. */
    private static final double IDENTIFIER_WEIGHT = 4.0;
    private static final double IDENTIFIER_PART_WEIGHT = 0.3;
    /** Two neighbouring words of the question that neighbour each other in a document ("claude code"). */
    private static final double PHRASE_WEIGHT = 1.0;
    /** The previous question only helps to find the topic of a follow-up, so its words count for less. */
    private static final double PREVIOUS_QUESTION_WEIGHT = 0.5;
    /**
     * What a word of the wording of a request ("操作", "步骤", "详细", "steps", or a "看" in front of the topic) counts: enough
     * to separate chunks that are otherwise equal, never enough to carry one. Such words sit in many headings but in few
     * chunks, so by rarity alone they would outweigh the topic.
     */
    private static final double GENERIC_WEIGHT = 0.2;
    /** A term this heavy is part of what the question is about: a chunk has to contain one of them to be an answer. */
    private static final double ANCHOR_WEIGHT = 0.5;
    /** A message of at most this many characters that no page has a word of, after a question, is about that question. */
    private static final int MAX_FALLBACK_CHARS = 8;
    /** A chunk that answers a follow-up has to be at least this close to the best answer to the earlier question. */
    private static final double TOPIC_SHARE = 0.25;
    /** A pair of Chinese characters that no document has counts this much of what the question asks for. */
    private static final double LACKED_PAIR = 0.5;
    /** A chunk is not expected to cover more of a very long question than this, so that a pasted log can still be answered. */
    private static final double MAX_IDEAL = 16;

    /**
     * What a chunk has to reach, per unit of what the question is worth, to be quoted at all. A chunk that mentions
     * every word of the question once in its text reaches 1; one that has hardly any of the words, or only a passing
     * mention of them, falls short, and the documents are then taken not to cover the question.
     */
    static final double MIN_FIT = 0.7;
    /** A chunk must reach this share of the best score; the fifth and sixth have to be closer than the first four. */
    private static final double FLOOR_FOR_FIRST_RESULTS = 0.20;
    private static final double FLOOR_FOR_LATER_RESULTS = 0.40;
    /** Taking a second chunk of a page stops being worth it once it scores this much below a skipped third one. */
    private static final double DIVERSITY_FLOOR = 0.5;

    private static final int ZH = 1;
    private static final int EN = 2;

    private static final List<String> FILES = List.of(
        "README.md",
        "docs/API.md",
        "docs/ARCHITECTURE.md",
        "docs/CONFIGURATION.md",
        "docs/OPERATIONS.md",
        "docs/TROUBLESHOOTING.md",
        "docs/INSTALL_SERVER.md",
        "docs/PLUGINS.md",
        "docs/SUPPORT_TICKETS.md",
        "webui/README.md"
    );

    // Question wording that says nothing about the topic. Removed from the query before it is cut into words, so that
    // the seams between it and the topic ("看用" in "怎么看用量") are not words either. Longer wordings come first.
    private static final Pattern CJK_FILLER = Pattern.compile(
        "怎么回事|咋回事|啥意思|什么情况|你是谁|是谁|什么时候|的时候|时候|怎么样|怎么办|怎么|怎样|如何|请问下|请问|请教|麻烦|帮我|给我|帮忙|我想要|我想|我要|想问|问一下|问下|"
            + "能不能|可不可以|是不是|有没有|是什么意思|什么意思|是什么|什么是|有什么区别|什么区别|有什么|有哪些|哪些|哪个|什么|多少|"
            + "为什么|为何|为啥|可以|能否|是否|"
            + "在哪里看|在哪儿看|在哪看|去哪里看|去哪看|能看到|看看|哪里能|在哪里|在哪儿|去哪里|去哪儿|去哪|在哪|哪看|哪里|哪儿|这个|那个|"
            + "这么多|那么多|这么|那么|一下|你好|您好|谢谢|还有呢|还有吗|然后呢|然后|的话|一直|这边|我的|那|咋|啥|谁|吗|呢|吧|啊|嘛|呀"
    );
    // Words of the structure of a document or of a request for more ("steps", "details", "an example"). They are in many
    // headings, so they would get a high weight by rarity; they count GENERIC_WEIGHT and a message made of nothing else
    // is a follow-up. Longer words first; the text is folded.
    private static final Pattern GENERIC_CJK = Pattern.compile(
        "举个例子|举个例|举例子|举例|例子|示例|样例|实例|具体|详细|详情|细节|步骤|操作|验证|常见问题|问题|下一步|注意|规则|方法|办法|流程|"
            + "说明|介绍|展开|继续|更多|其他|别的|完整|仔细|讲一讲|说一说|讲讲|说说|解释|补充|报错|出错"
    );
    // A verb of looking for something in front of the topic ("看用量", "查余额"): the pair it forms with the first
    // character of the topic is a seam, not a word.
    private static final String LIGHT_VERBS = "看查找搜问";
    // Single characters that are grammar, not topic; any other lone character left in a question is a (weak) term.
    private static final String CJK_FUNCTION_CHARACTERS = "的了是在我你他她它们和与或及把被就也都还又才吗呢吧啊哦嗯呀哈那这个些么有无没不";
    private static final Set<String> STOP_WORDS = Set.of(
        "the", "how", "what", "why", "when", "where", "which", "who", "can", "could", "would", "should", "does", "did",
        "you", "your", "are", "was", "were", "for", "this", "that", "these", "those", "with", "and", "from", "have",
        "has", "not", "but", "please", "hello", "thanks", "thank", "is", "it", "in", "on", "to", "of", "do", "be", "an",
        "as", "at", "by", "or", "if", "my", "me", "we", "us", "so", "no", "am",
        "want", "know", "get", "about", "tell", "way", "there", "any", "see", "give", "more", "show", "also", "just"
    );
    // The same kind of words in English, as they are after "singular".
    private static final Set<String> GENERIC_WORDS = Set.of(
        "step", "detail", "example", "verify", "verification", "rule", "note", "operation", "problem", "issue", "common"
    );
    // The words of the reason phrases of HTTP status codes, as they are after "singular": "502 Bad Gateway" says
    // 502; whether the documents happen to spell the phrase the same way is not what the question asks about.
    private static final Set<String> REASON_WORDS = Set.of(
        "bad", "gateway", "request", "unauthorized", "payment", "required", "forbidden", "found", "method", "allowed",
        "acceptable", "proxy", "authentication", "timeout", "conflict", "gone", "length", "precondition", "failed",
        "payload", "content", "too", "large", "uri", "long", "unsupported", "media", "type", "range", "satisfiable",
        "expectation", "misdirected", "unprocessable", "entity", "locked", "dependency", "early", "upgrade", "many",
        "header", "field", "unavailable", "legal", "reason", "internal", "server", "error", "implemented", "service",
        "version", "supported", "insufficient", "storage", "loop", "detected", "network", "ok", "created", "accepted"
    );
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // Endings a Latin word may carry and still count as the same word: key / keys, create / created.
    private static final Set<String> WORD_ENDINGS = Set.of("s", "es", "d", "ed", "ing");

    /** Where the published pages come from: the pages themselves, and a cheap value that changes when they do. */
    private final Supplier<Mono<List<Document>>> pages;
    private final Supplier<Mono<String>> fingerprint;
    private final List<Entry> fileEntries;
    private final Clock clock;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
    private final AtomicReference<Load> loading = new AtomicReference<>();
    private final AtomicLong lastWarning = new AtomicLong(Long.MIN_VALUE / 2);
    /** The thread the last snapshot was built on; the build is CPU work and must not run on a database event loop. */
    volatile Thread lastBuildThread;
    /** The thread the last question was scored on, for the same reason. */
    volatile Thread lastSearchThread;

    /**
     * How old a snapshot is, how long to wait before trying again and when a warning was last logged are measured on
     * the monotonic clock. A wall clock that is stepped back (a VM resumed from a snapshot, an NTP correction) would keep
     * a snapshot, and with it a page withdrawn on another node, for the length of the step on top of the time to live.
     */
    static final Clock MONOTONIC = new MonotonicClock();

    private static final class MonotonicClock extends Clock {

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public long millis() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }
    }

    @Autowired
    public AssistantDocsIndex(DocumentationService documentation) {
        this(Path.of(System.getProperty("user.dir", ".")), documentation::publishedPages,
            documentation::publishedFingerprint, MONOTONIC);
    }

    /** Files only; there are no 使用文档 pages. */
    AssistantDocsIndex(Path root) {
        this(root, () -> Mono.just(List.of()), MONOTONIC);
    }

    /** Pages without a fingerprint: every expiry of the time to live reads them again. */
    AssistantDocsIndex(Path root, Supplier<Mono<List<Document>>> pages, Clock clock) {
        this(root, pages, Mono::empty, clock);
    }

    AssistantDocsIndex(Path root, Supplier<Mono<List<Document>>> pages, Supplier<Mono<String>> fingerprint,
        Clock clock) {
        this.fileEntries = loadFiles(root);
        this.pages = pages;
        this.fingerprint = fingerprint;
        this.clock = clock;
    }

    /** What a question found: the chunks to quote, and the page titles to point at when there are none. */
    public record Retrieval(List<Chunk> chunks, List<String> pageTitles) {

        static final Retrieval EMPTY = new Retrieval(List.of(), List.of());

        /** Short display strings for the chunks that were used, best first, without repeats. */
        public List<String> sources() {
            return chunks.stream().map(Chunk::source).distinct().toList();
        }
    }

    /**
     * One searchable piece of a documentation page or file.
     *
     * @param pageId    the documentation page's id; null for a file chunk
     * @param pageTitle the page title in the chunk's language; the relative path for a file chunk
     * @param section   the headings the text sits under, the parent first; empty before the first heading
     * @param language  "zh" or "en"
     */
    public record Chunk(
        String pageId,
        String pageTitle,
        String category,
        String summary,
        String section,
        String language,
        String body
    ) {

        /** What an excerpt's label adds to its text in the prompt. */
        private static final int LABEL_OVERHEAD = 16;

        public boolean isPage() {
            return pageId != null;
        }

        /** The short string shown to the user as the source of an answer. */
        public String source() {
            if (!isPage()) {
                return pageTitle;
            }
            return "en".equals(language) ? "Docs: " + pageTitle : "使用文档《" + pageTitle + "》";
        }

        /** How many characters of the prompt this excerpt takes, its label included. */
        public int cost() {
            return body.length() + pageTitle.length() + section.length() + LABEL_OVERHEAD;
        }
    }

    /**
     * Finds the chunks that best answer a question. The previous user question is only used to find the topic of a
     * follow-up such as "那怎么改？", and, with the question, to tell which language the reader writes. Never fails:
     * a problem loading the pages just means fewer or no chunks.
     */
    public Mono<Retrieval> retrieve(String question, String previousQuestion) {
        return retrieveFor(question, previousQuestion == null || previousQuestion.isBlank()
            ? List.of() : List.of(previousQuestion));
    }

    /**
     * The same with all earlier user questions, the latest first: a follow-up that says nothing about its topic is
     * about the latest earlier question that has one, so "还有呢" after "那怎么改？" after "怎么创建 API Key" is still
     * about the API key.
     */
    public Mono<Retrieval> retrieveFor(String question, List<String> earlierQuestions) {
        if (question == null || question.isBlank()) {
            return Mono.just(Retrieval.EMPTY);
        }
        List<String> earlier = earlierQuestions == null ? List.of() : earlierQuestions.stream()
            .filter(message -> message != null && !message.isBlank()).limit(MAX_EARLIER_MESSAGES).toList();
        // The snapshot is delivered on whatever thread finished loading it, in production a database event loop;
        // scoring is CPU work and happens on the parallel scheduler instead.
        return snapshot()
            .publishOn(Schedulers.parallel())
            .map(current -> {
                lastSearchThread = Thread.currentThread();
                return search(current, question, earlier);
            })
            .onErrorResume(error -> {
                warn("The assistant could not search its documentation", error);
                return Mono.just(Retrieval.EMPTY);
            });
    }

    /** Called once a publish, re-publish, unpublish or delete has committed on this node. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPublicationChanged(DocumentationPublicationChanged event) {
        invalidate();
    }

    /** Makes the next question read the pages again, whatever their fingerprint says. */
    public void invalidate() {
        generation.incrementAndGet();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Snapshot: loaded lazily, shared, refreshed by one load at a time
    // ---------------------------------------------------------------------------------------------------------

    /**
     * @param twins       for each entry the index of the same place in the other language version of its page, or -1
     * @param fingerprint what the published pages looked like when they were read; null when unknown or not read
     */
    private record Snapshot(List<Entry> entries, int[] twins, List<String> pageTitles, String fingerprint,
        long generation, long expiresAt, Map<String, Integer> vocabulary) {

        Snapshot renewed(long until) {
            return new Snapshot(entries, twins, pageTitles, fingerprint, generation, until, vocabulary);
        }
    }

    private record Load(long generation, Mono<Snapshot> result) {
    }

    private Mono<Snapshot> snapshot() {
        while (true) {
            long wanted = generation.get();
            Snapshot ready = snapshot.get();
            if (ready != null && ready.generation() >= wanted && clock.millis() < ready.expiresAt()) {
                return Mono.just(ready);
            }
            // A load that began before the latest change may have read the old pages, so it is not shared.
            Load running = loading.get();
            if (running != null && running.generation() >= wanted) {
                return running.result();
            }
            Load next = new Load(wanted, load(wanted, ready));
            if (loading.compareAndSet(running, next)) {
                return next.result();
            }
        }
    }

    private Mono<Snapshot> load(long wanted, Snapshot previous) {
        // Only the time to live ran out, nothing was reported: the pages are read again only if they changed.
        boolean expiredOnly = previous != null && previous.generation() >= wanted && previous.fingerprint() != null;
        return Mono.defer(fingerprint)
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .flatMap(current -> {
                if (expiredOnly && current.isPresent() && current.get().equals(previous.fingerprint())) {
                    return Mono.just(previous.renewed(clock.millis() + CACHE_TTL));
                }
                // The fingerprint is read first: a publish in between makes the next check reload once more.
                return Mono.defer(pages)
                    .publishOn(Schedulers.boundedElastic())
                    .map(loaded -> buildSafely(loaded, wanted, current.orElse(null)));
            })
            .onErrorResume(error -> {
                warn("Could not load the published documentation pages for the assistant; answering without them", error);
                return Mono.just(withoutPages(wanted, clock.millis() + RETRY_AFTER, null));
            })
            .doOnNext(loaded -> snapshot.accumulateAndGet(loaded, (old, next) ->
                old == null || next.generation() >= old.generation() ? next : old))
            .doFinally(signal -> loading.updateAndGet(running ->
                running != null && running.generation() == wanted ? null : running))
            .cache();
    }

    private Snapshot withoutPages(long wanted, long until, String fingerprint) {
        List<Entry> entries = List.copyOf(fileEntries);
        int[] twins = new int[entries.size()];
        Arrays.fill(twins, -1);
        return new Snapshot(entries, twins, List.of(), fingerprint, wanted, until, vocabularyOf(entries));
    }

    /** A failure of the build itself would repeat on every attempt, so it is not retried until something changes. */
    private Snapshot buildSafely(List<Document> documents, long wanted, String fingerprint) {
        lastBuildThread = Thread.currentThread();
        try {
            return build(documents, wanted, fingerprint);
        } catch (RuntimeException error) {
            warn("Could not index the published documentation pages for the assistant; answering without them", error);
            return withoutPages(wanted, clock.millis() + CACHE_TTL, fingerprint);
        }
    }

    /** One published page, chunked in the languages it has. */
    private record PageText(String id, String title, String category, String summary,
        AssistantDocsChunker.Parsed chinese, Translation english, AssistantDocsChunker.Parsed englishParsed) {
    }

    private Snapshot build(List<Document> documents, long wanted, String fingerprint) {
        List<PageText> texts = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (Document document : documents) {
            try {
                PageText page = pageText(document);
                if (page == null) {
                    warn("A published documentation page has no usable content and is left out of the assistant"
                        + (document == null ? "" : " (page " + document.id() + ")"), null);
                    continue;
                }
                texts.add(page);
                titles.add(page.english() == null || page.english().title().equals(page.title())
                    ? page.title() : page.title() + " / " + abbreviate(page.english().title()));
            } catch (RuntimeException error) {
                warn("Could not read a published documentation page for the assistant; leaving it out"
                    + (document == null ? "" : " (page " + document.id() + ")"), error);
            }
        }
        int limit = fairLimit(texts);
        List<Entry> entries = new ArrayList<>();
        List<int[]> twinPairs = new ArrayList<>();
        for (PageText page : texts) {
            int languages = (page.chinese().sections().isEmpty() ? 0 : ZH)
                | (page.englishParsed() == null || page.englishParsed().sections().isEmpty() ? 0 : EN);
            Map<Integer, List<Integer>> chinese = addChunks(entries, page, page.title(), page.category(), page.chinese(),
                "zh", languages, limit);
            if (page.englishParsed() != null) {
                Translation english = page.english();
                Map<Integer, List<Integer>> translated = addChunks(entries, page, abbreviate(english.title()),
                    english.category(), page.englishParsed(), "en", languages, limit);
                if (page.chinese().headings() == page.englishParsed().headings()) {
                    pairTwins(chinese, translated, twinPairs);
                }
            }
        }
        entries.addAll(fileEntries);
        int[] twins = new int[entries.size()];
        Arrays.fill(twins, -1);
        for (int[] pair : twinPairs) {
            twins[pair[0]] = pair[1];
            twins[pair[1]] = pair[0];
        }
        List<Entry> all = List.copyOf(entries);
        return new Snapshot(all, twins, List.copyOf(titles), fingerprint, wanted, clock.millis() + CACHE_TTL,
            vocabularyOf(all));
    }

    private PageText pageText(Document document) {
        Content page = document == null ? null : document.content();
        if (page == null || document.id() == null || page.title() == null || page.title().isBlank()) {
            return null;
        }
        Translation english = page.english();
        boolean translated = english != null && english.title() != null && !english.title().isBlank()
            && english.body() != null && !english.body().isBlank();
        return new PageText(document.id(), abbreviate(page.title()), nullToEmpty(page.category()),
            AssistantDocsChunker.clean(nullToEmpty(page.summary())), AssistantDocsChunker.parse(page.body(), 1),
            translated ? english : null,
            translated ? AssistantDocsChunker.parse(english.body(), 1) : null);
    }

    /** Adds a page's chunks in one language and returns, for each heading, where its chunks went. */
    private Map<Integer, List<Integer>> addChunks(List<Entry> entries, PageText page, String title, String category,
        AssistantDocsChunker.Parsed parsed, String language, int languages, int limit) {
        Map<Integer, List<Integer>> byOrdinal = new HashMap<>();
        String summary = "zh".equals(language)
            ? page.summary() : AssistantDocsChunker.clean(nullToEmpty(page.english().summary()));
        List<AssistantDocsChunker.Section> sections = parsed.sections();
        if (sections.size() > limit) {
            warn("The published documentation is too large to index completely; only the first " + limit
                + " passages of '" + title + "' are searched by the assistant", null);
            sections = sections.subList(0, limit);
        }
        for (AssistantDocsChunker.Section section : sections) {
            byOrdinal.computeIfAbsent(section.ordinal(), key -> new ArrayList<>()).add(entries.size());
            entries.add(Entry.of(new Chunk(page.id(), title, nullToEmpty(category), summary, section.heading(),
                language, section.body()), languages));
        }
        return byOrdinal;
    }

    /**
     * The two language versions of a page with the same number of headings say the same thing in the same order:
     * the n-th chunk under a heading of one version is the twin of the n-th under that heading of the other.
     */
    private static void pairTwins(Map<Integer, List<Integer>> chinese, Map<Integer, List<Integer>> english,
        List<int[]> pairs) {
        chinese.forEach((ordinal, mine) -> {
            List<Integer> theirs = english.get(ordinal);
            if (theirs != null) {
                for (int at = 0; at < Math.max(mine.size(), theirs.size()); at++) {
                    pairs.add(new int[] {mine.get(Math.min(at, mine.size() - 1)), theirs.get(Math.min(at, theirs.size() - 1))});
                }
            }
        });
    }

    /** The largest number of chunks any page may contribute so that all together stay within the cap. */
    private static int fairLimit(List<PageText> pages) {
        List<Integer> sizes = new ArrayList<>();
        long total = 0;
        for (PageText page : pages) {
            sizes.add(page.chinese().sections().size());
            if (page.englishParsed() != null) {
                sizes.add(page.englishParsed().sections().size());
            }
        }
        for (int size : sizes) {
            total += size;
        }
        if (total <= MAX_PAGE_CHUNKS) {
            return Integer.MAX_VALUE;
        }
        int low = 1;
        int high = sizes.stream().mapToInt(Integer::intValue).max().orElse(1);
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            long used = 0;
            for (int size : sizes) {
                used += Math.min(size, middle);
            }
            if (used <= MAX_PAGE_CHUNKS) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String abbreviate(String title) {
        if (title.length() <= MAX_TITLE_CHARS) {
            return title;
        }
        int end = MAX_TITLE_CHARS;
        if (Character.isHighSurrogate(title.charAt(end - 1))) {
            end--;
        }
        return title.substring(0, end).stripTrailing() + "…";
    }

    /**
     * The words of the index, each with the number of chunks it occurs in. A question's term that is not in it cannot
     * match anything, so it is neither looked for nor, in a very long question, kept. A Latin word is there in the
     * forms a question may use to find it: with its ending taken off ("created" is found by "create").
     */
    private static Map<String, Integer> vocabularyOf(List<Entry> entries) {
        Map<String, Integer> vocabulary = new HashMap<>();
        for (Entry entry : entries) {
            Set<String> words = new HashSet<>();
            for (String text : List.of(entry.title(), entry.section(), entry.category(), entry.summary(), entry.body())) {
                words.addAll(scan(text, Integer.MAX_VALUE, false).terms().keySet());
                addStems(words, text);
            }
            words.forEach(word -> vocabulary.merge(word, 1, Integer::sum));
        }
        return vocabulary;
    }

    private static void addStems(Set<String> words, String text) {
        int index = 0;
        int length = text.length();
        while (index < length) {
            if (!isWordChar(text.charAt(index))) {
                index++;
                continue;
            }
            int end = index;
            while (end < length && isWordChar(text.charAt(end))) {
                end++;
            }
            if (end - index >= 2) {
                String word = text.substring(index, end);
                words.add(word);
                for (String ending : WORD_ENDINGS) {
                    if (word.endsWith(ending) && word.length() - ending.length() >= 2) {
                        words.add(word.substring(0, word.length() - ending.length()));
                    }
                }
            }
            index = end;
        }
    }

    /**
     * Whether a term can occur in any chunk: a letter pair or a word has to be a word of the index. A single character
     * and a phrase are not words of it, and an identifier may stand in front of a longer one ("config.toml.bak").
     */
    private static boolean mayOccur(String term, Map<String, Integer> vocabulary) {
        return term.length() < 2 || term.indexOf(' ') >= 0 || isCompound(term) || vocabulary.containsKey(term);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Files (development checkouts)
    // ---------------------------------------------------------------------------------------------------------

    private static List<Entry> loadFiles(Path root) {
        List<Entry> loaded = new ArrayList<>();
        for (String relative : FILES) {
            Path file = root.resolve(relative);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                loaded.addAll(split(relative, text).stream().map(chunk -> Entry.of(chunk, 0)).toList());
            } catch (IOException ignored) {
                // Skip unreadable files; chat still works from the system prompt.
            }
            if (loaded.size() >= MAX_FILE_CHUNKS) {
                break;
            }
        }
        return List.copyOf(loaded.subList(0, Math.min(loaded.size(), MAX_FILE_CHUNKS)));
    }

    static List<Chunk> split(String path, String text) {
        return AssistantDocsChunker.split(text, 40).stream()
            .map(section -> new Chunk(null, path, "", "", section.heading(),
                containsCjk(section.body()) ? "zh" : "en", section.body()))
            .toList();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------------------------------------------

    /**
     * A chunk with its searchable text folded once, so that a question costs no more than substring scans.
     *
     * @param languages which language versions its page has (a bit each for Chinese and English); 0 for a file
     */
    private record Entry(Chunk chunk, String title, String section, String category, String summary, String body,
        int languages) {

        static Entry of(Chunk chunk, int languages) {
            return new Entry(chunk,
                chunk.isPage() ? foldForSearch(chunk.pageTitle()) : "",
                foldForSearch(chunk.section()), foldForSearch(chunk.category()), foldForSearch(chunk.summary()),
                foldForSearch(chunk.body()), languages);
        }

        /** Whether the reader is shown this chunk: the version in their language, or the only one there is. */
        boolean quotedTo(int reader) {
            return !chunk.isPage() || (languages & reader) == 0 || (chunk.language().equals("zh") ? ZH : EN) == reader;
        }
    }

    private record Scored(Entry entry, int row, long score) {
    }

    /**
     * One word, letter pair or phrase of a question, as it is searched for.
     *
     * @param previous true for a term of the secondary message, which only adds to the score of chunks that the primary
     *                 terms found: the earlier question in an ordinary search, the latest message in a follow-up
     * @param soft     a term the documents may lack without that saying anything about what they cover (wording, the
     *                 reason phrase of a status code, a tag of pasted HTML)
     * @param adjacent for a pair of Chinese characters, the pairs next to it in the message; each shares a character
     */
    private record Term(String text, double weight, boolean previous, boolean phrase, boolean soft,
        Set<String> adjacent) {

        static Term of(String text, Candidate candidate, boolean previous, double factor) {
            return new Term(text, candidate.weight() * factor, previous, false, candidate.soft(), candidate.adjacent());
        }

        static Term phrase(String text) {
            return new Term(text, PHRASE_WEIGHT, false, true, true, Set.of());
        }

        /** Whether a chunk has to contain this term: it says what the question is about, and is not wording. */
        boolean anchor() {
            return !phrase && !soft && weight >= ANCHOR_WEIGHT;
        }
    }

    /** What every term finds in every chunk (points, before the rarity of the term), and in how many chunks it occurs. */
    private record Matches(int columns, byte[] inText, byte[] inPage, int[] frequency) {

        static Matches of(List<Entry> entries, List<Term> terms, Map<String, Integer> vocabulary) {
            int columns = terms.size();
            byte[] inText = new byte[entries.size() * columns];
            byte[] inPage = new byte[entries.size() * columns];
            int[] frequency = new int[columns];
            // A term that is not a word of the documents is found nowhere; looking for it would only cost time.
            boolean[] possible = new boolean[columns];
            for (int column = 0; column < columns; column++) {
                possible[column] = mayOccur(terms.get(column).text(), vocabulary);
            }
            for (int row = 0; row < entries.size(); row++) {
                Entry entry = entries.get(row);
                for (int column = 0; column < columns; column++) {
                    if (!possible[column]) {
                        continue;
                    }
                    String word = terms.get(column).text();
                    // At most 5 + 3 points in the text and 6 + 3 + 2 in the page: a byte holds them.
                    int text = SECTION_POINTS * (contains(entry.section(), word) ? 1 : 0)
                        + BODY_POINTS * occurrences(entry.body(), word, MAX_BODY_HITS);
                    int page = TITLE_POINTS * (contains(entry.title(), word) ? 1 : 0)
                        + CATEGORY_POINTS * (contains(entry.category(), word) ? 1 : 0)
                        + SUMMARY_POINTS * (contains(entry.summary(), word) ? 1 : 0);
                    inText[row * columns + column] = (byte) text;
                    inPage[row * columns + column] = (byte) page;
                    if (text + page > 0) {
                        frequency[column]++;
                    }
                }
            }
            return new Matches(columns, inText, inPage, frequency);
        }

        /** Points of a term in the text of a chunk (heading 5, body 1 per mention, at most 3); when the chunk has a twin, the better of the two. */
        int text(int row, int twin, int column) {
            int own = inText[row * columns + column];
            return twin >= 0 ? Math.max(own, inText[twin * columns + column]) : own;
        }

        /** Points of a term in the page of a chunk (title 6, category 3, summary 2). */
        int page(int row, int twin, int column) {
            int own = inPage[row * columns + column];
            return twin >= 0 ? Math.max(own, inPage[twin * columns + column]) : own;
        }
    }

    /**
     * What the question is worth: the value of each term (its weight times its rarity), and the total that a chunk
     * could cover, which is what chunks are measured against.
     *
     * @param ideal    what the documents know of the question, plus what they lack of it up to as much again
     * @param anchored whether the documents know a term that says what the question is about; a chunk then has to
     *                 contain one, however many of the generic words ("操作", "steps") it has
     */
    private record Worth(double[] value, double ideal, boolean anchored) {

        static Worth of(List<Term> terms, Matches matches, int rows) {
            double[] value = new double[terms.size()];
            Map<String, Integer> frequencies = new HashMap<>();
            for (int column = 0; column < value.length; column++) {
                Term term = terms.get(column);
                int frequency = matches.frequency()[column];
                value[column] = term.weight() * Math.log(1.0 + (double) rows / (1 + frequency));
                if (!term.previous() && !term.phrase()) {
                    frequencies.put(term.text(), frequency);
                }
            }
            double known = 0;
            double knownTopic = 0;
            double lacked = 0;
            boolean anchored = false;
            for (int column = 0; column < value.length; column++) {
                Term term = terms.get(column);
                if (term.previous() || term.phrase()) {
                    continue;
                }
                if (matches.frequency()[column] > 0) {
                    known += value[column];
                    knownTopic += term.soft() ? 0 : value[column];
                    anchored |= term.anchor();
                } else if (!isCompound(term.text()) && !term.soft() && !isSeam(term, frequencies)) {
                    // What the question asks for that the documents lack counts too: a word for all of its weight, a
                    // pair of Chinese characters for half, as it may be just the seam between two words. A whole
                    // identifier they never mention says nothing about what they do cover.
                    lacked += isCjk(term.text().charAt(0)) ? value[column] * LACKED_PAIR : value[column];
                }
            }
            // However much of a pasted log the documents lack, it cannot outweigh what they know of the question.
            double expected = knownTopic > 0 ? knownTopic : known;
            return new Worth(value, Math.min(expected + Math.min(lacked, expected), MAX_IDEAL), anchored);
        }

        /**
         * A pair of Chinese characters that the documents lack and that shares a character with a pair they have is the
         * join between a word and the wording around it ("看用" in "怎么看用量", "额在" in "余额在"), or between two
         * words ("款之" in "付款之后"), not something the question asks for.
         */
        private static boolean isSeam(Term term, Map<String, Integer> frequencies) {
            if (!isCjk(term.text().charAt(0))) {
                return false;
            }
            for (String neighbour : term.adjacent()) {
                if (frequencies.getOrDefault(neighbour, 0) > 0) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * How the terms of a query are used.
     * <ul>
     * <li>ASKED: the latest message is the question; the earlier question's terms only add to chunks that it found.
     * <li>FOLLOW_UP: the latest message says nothing about a topic of its own ("具体步骤呢"); the chunks are those that
     * answer the earlier question, and the latest message's words only order them.
     * <li>FOLLOW_UP_WORD: the same, and the chunk has to contain the one word the latest message does have ("那怎么改").
     * </ul>
     */
    private enum Mode {
        ASKED, FOLLOW_UP, FOLLOW_UP_WORD
    }

    private record Query(List<Term> terms, Mode mode) {
    }

    /** The query to try first and, when it finds nothing, a follow-up reading of the same message. */
    private record Plan(Query main, Query fallback) {
    }

    private Retrieval search(Snapshot current, String question, List<String> earlier) {
        List<Entry> entries = current.entries();
        Plan plan = plan(question, earlier, current);
        if (entries.isEmpty() || plan.main() == null || plan.main().terms().isEmpty()) {
            return new Retrieval(List.of(), current.pageTitles());
        }
        // A reader who writes Chinese gets the Chinese version, even when this message is a pasted English error.
        int reader = containsCjk(question) || earlier.stream().limit(2).anyMatch(AssistantDocsIndex::containsCjk) ? ZH : EN;
        Ranking ranking = rank(current, plan.main(), reader);
        // A short message none of whose words any page has ("再讲讲") is about the question before it.
        if (!ranking.known() && plan.fallback() != null) {
            ranking = rank(current, plan.fallback(), reader);
        }
        return new Retrieval(select(ranking.chunks()), current.pageTitles());
    }

    /** @param known whether the documents have any word of the question at all */
    private record Ranking(List<Scored> chunks, boolean known) {
    }

    /** The chunks that answer the question well enough to be quoted, best first. */
    private static Ranking rank(Snapshot current, Query query, int reader) {
        List<Entry> entries = current.entries();
        List<Term> terms = query.terms();
        Matches matches = Matches.of(entries, terms, current.vocabulary());
        Worth worth = Worth.of(terms, matches, entries.size());
        if (worth.ideal() <= 0) {
            return new Ranking(List.of(), false);
        }
        boolean followUp = query.mode() != Mode.ASKED;
        double[] value = worth.value();
        int[] twins = current.twins();
        List<Scored> ranked = new ArrayList<>();
        // A follow-up's chunks are those that answer the earlier question about as well as its best answer does.
        double bestTopic = 0;
        List<Scored> pool = new ArrayList<>();
        List<Double> topics = new ArrayList<>();
        for (int row = 0; row < entries.size(); row++) {
            Entry entry = entries.get(row);
            if (!entry.quotedTo(reader)) {
                continue;
            }
            // The version in the reader's language is scored together with its twin in the other language.
            int twin = twins[row];
            double nowText = 0;
            double nowPage = 0;
            double beforeText = 0;
            double beforePage = 0;
            double matched = 0;
            boolean anchorMatched = false;
            for (int column = 0; column < terms.size(); column++) {
                int text = matches.text(row, twin, column);
                int page = matches.page(row, twin, column);
                Term term = terms.get(column);
                if (term.previous()) {
                    // In an ordinary search this is the earlier question: what it found in a heading says what that
                    // answer was, not what this is about. In a follow-up it is the message's own word, and counts.
                    int counted = followUp || text < SECTION_POINTS ? text : text - SECTION_POINTS;
                    beforeText += value[column] * counted;
                    beforePage += value[column] * page;
                } else {
                    // The same the other way round: when the one word of a follow-up is what the chunk has to contain,
                    // a heading that merely repeats the earlier question is not an answer to it.
                    int counted = query.mode() == Mode.FOLLOW_UP_WORD && text >= SECTION_POINTS ? text - SECTION_POINTS : text;
                    nowText += value[column] * counted;
                    nowPage += value[column] * page;
                    if (!term.phrase() && counted + page > 0) {
                        matched += value[column];
                        anchorMatched |= term.anchor();
                    }
                }
            }
            double now = nowText + (nowText > 0 ? nowPage : nowPage * PAGE_ONLY_MATCH);
            double before = beforeText + (beforeText > 0 ? beforePage : beforePage * PAGE_ONLY_MATCH);
            // The question has to match on its own words, and on one that says what it is about, not only on words like
            // "操作" or "steps" that sit in a heading of every page.
            if (matched <= 0 || (worth.anchored() && !anchorMatched)) {
                continue;
            }
            double covered = Math.min(1.0, matched / worth.ideal());
            if (followUp) {
                // The chunk has to answer the earlier question as well as that question itself would have been
                // answered; the message's own words only order the chunks that do.
                double topic = now * covered;
                if (topic / worth.ideal() < MIN_FIT) {
                    continue;
                }
                bestTopic = Math.max(bestTopic, topic);
                if (query.mode() == Mode.FOLLOW_UP_WORD && before <= 0) {
                    continue;
                }
                pool.add(new Scored(entry, row, Math.round((topic + before) * 1_000_000)));
                topics.add(topic);
                continue;
            }
            // The earlier question only decides among the chunks the question itself found.
            double score = (now + Math.min(before, now)) * covered;
            if (score / worth.ideal() < MIN_FIT) {
                continue;
            }
            // Scores that differ by rounding noise are ties; ties go to the earlier chunk (pages before files).
            ranked.add(new Scored(entry, row, Math.round(score * 1_000_000)));
        }
        for (int index = 0; index < pool.size(); index++) {
            if (topics.get(index) >= bestTopic * TOPIC_SHARE) {
                ranked.add(pool.get(index));
            }
        }
        ranked.sort(Comparator.comparingLong(Scored::score).reversed().thenComparingInt(Scored::row));
        return new Ranking(ranked, true);
    }

    /** Takes the best chunks: few from the same page, the version in the reader's language, within the budget. */
    private static List<Chunk> select(List<Scored> ranked) {
        if (ranked.isEmpty()) {
            return List.of();
        }
        long top = ranked.getFirst().score();
        List<Scored> picked = new ArrayList<>();
        Set<Integer> taken = new HashSet<>();
        Map<String, Integer> perSource = new HashMap<>();
        int budget = EXCERPT_BUDGET;
        for (int allowed = PREFERRED_PER_SOURCE; allowed <= MAX_PER_SOURCE && picked.size() < MAX_RESULTS; allowed++) {
            long skipped = 0;
            for (Scored candidate : ranked) {
                if (picked.size() >= MAX_RESULTS) {
                    break;
                }
                double floor = picked.size() < MIN_RESULTS ? FLOOR_FOR_FIRST_RESULTS : FLOOR_FOR_LATER_RESULTS;
                if (candidate.score() < top * floor) {
                    break;
                }
                // Other pages are preferred, but not at any price: a much weaker chunk waits for the next pass.
                if (allowed == PREFERRED_PER_SOURCE && candidate.score() < skipped * DIVERSITY_FLOOR) {
                    break;
                }
                Chunk chunk = candidate.entry().chunk();
                String source = chunk.isPage() ? chunk.pageId() : chunk.pageTitle();
                if (taken.contains(candidate.row())) {
                    continue;
                }
                if (perSource.getOrDefault(source, 0) >= allowed) {
                    skipped = Math.max(skipped, candidate.score());
                    continue;
                }
                if (chunk.cost() > budget) {
                    continue;
                }
                taken.add(candidate.row());
                picked.add(candidate);
                perSource.merge(source, 1, Integer::sum);
                budget -= chunk.cost();
            }
        }
        picked.sort(Comparator.comparingLong(Scored::score).reversed().thenComparingInt(Scored::row));
        return inReadingOrder(picked).stream().map(item -> item.entry().chunk()).toList();
    }

    /**
     * The best chunk first, but the pieces of one section together and in the order of the section: the second half of
     * a table or a list is no use read before its beginning.
     */
    private static List<Scored> inReadingOrder(List<Scored> bestFirst) {
        Map<String, List<Scored>> sections = new LinkedHashMap<>();
        for (Scored item : bestFirst) {
            Chunk chunk = item.entry().chunk();
            String key = (chunk.isPage() ? chunk.pageId() : chunk.pageTitle()) + '\u0000' + chunk.language() + '\u0000'
                + chunk.section();
            sections.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
        }
        List<Scored> ordered = new ArrayList<>(bestFirst.size());
        for (List<Scored> pieces : sections.values()) {
            pieces.sort(Comparator.comparingInt(Scored::row));
            ordered.addAll(pieces);
        }
        return ordered;
    }

    // ---------------------------------------------------------------------------------------------------------
    // The terms of a question
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The words, letter pairs and phrases that a question is searched for. The words of the latest message count in
     * full, the earlier question's at half, and a message too long to search for everything is cut down to the words
     * the documents contain, rarest first, rather than to its first words.
     *
     * <p>A message that has no topic of its own ("具体步骤呢", "那怎么改", "举个例子") is about the latest earlier question
     * that has one. So is a very short message none of whose words any page has, such as "再讲讲".
     */
    private static Plan plan(String question, List<String> earlier, Snapshot current) {
        Scanned now = scanMessage(question);
        Scanned topic = null;
        for (String message : earlier) {
            Scanned candidate = scanMessage(message);
            if (candidate.content()) {
                topic = candidate;
                break;
            }
        }
        if (now.content()) {
            Map<String, Candidate> latest = now.terms();
            Map<String, Candidate> before = new LinkedHashMap<>();
            if (topic != null && latest.size() <= MAX_TERMS_FOR_PREVIOUS) {
                before.putAll(topic.terms());
            }
            before.keySet().removeAll(latest.keySet());
            List<Term> terms = new ArrayList<>();
            keep(latest, MAX_QUERY_TERMS, current).forEach((text, candidate) ->
                terms.add(Term.of(text, candidate, false, 1.0)));
            keep(before, MAX_PREVIOUS_TERMS, current).forEach((text, candidate) ->
                terms.add(Term.of(text, candidate, true, PREVIOUS_QUESTION_WEIGHT)));
            now.phrases().stream().distinct().limit(MAX_PHRASES).forEach(phrase -> terms.add(Term.phrase(phrase)));
            Query fallback = topic != null && !now.latin() && now.size() <= MAX_FALLBACK_CHARS
                ? followUp(topic, now, Mode.FOLLOW_UP, current) : null;
            return new Plan(new Query(terms, Mode.ASKED), fallback);
        }
        if (topic != null) {
            return new Plan(followUp(topic, now, now.lone() ? Mode.FOLLOW_UP_WORD : Mode.FOLLOW_UP, current), null);
        }
        if (now.terms().isEmpty()) {
            return new Plan(null, null);
        }
        // Nothing to follow up on: whatever the message does have is searched for.
        List<Term> terms = new ArrayList<>();
        keep(now.terms(), MAX_QUERY_TERMS, current).forEach((text, candidate) ->
            terms.add(Term.of(text, candidate, false, 1.0)));
        return new Plan(new Query(terms, Mode.ASKED), null);
    }

    /** The earlier question's terms are the topic and the latest message's only order what they find. */
    private static Query followUp(Scanned topic, Scanned now, Mode mode, Snapshot current) {
        List<Term> terms = new ArrayList<>();
        keep(topic.terms(), MAX_QUERY_TERMS, current).forEach((text, candidate) ->
            terms.add(Term.of(text, candidate, false, 1.0)));
        Map<String, Candidate> latest = new LinkedHashMap<>(now.terms());
        latest.keySet().removeAll(topic.terms().keySet());
        Map<String, Candidate> kept = keep(latest, MAX_PREVIOUS_TERMS, current);
        kept.forEach((text, candidate) -> terms.add(Term.of(text, candidate, true, 1.0)));
        topic.phrases().stream().distinct().limit(MAX_PHRASES).forEach(phrase -> terms.add(Term.phrase(phrase)));
        return new Query(terms, mode == Mode.FOLLOW_UP_WORD && kept.isEmpty() ? Mode.FOLLOW_UP : mode);
    }

    private static Scanned scanMessage(String message) {
        return scan(CJK_FILLER.matcher(fold(message)).replaceAll(" "), MAX_CANDIDATE_TERMS, true);
    }

    /** At most {@code limit} of the terms, in their order of appearance. */
    private static Map<String, Candidate> keep(Map<String, Candidate> terms, int limit, Snapshot current) {
        if (terms.size() <= limit) {
            return terms;
        }
        // A word no document contains cannot match anything, so it goes first; the others count by weight and
        // rarity, and by being later in the message when those are equal.
        Map<String, Integer> vocabulary = current.vocabulary();
        int total = Math.max(1, current.entries().size());
        List<Map.Entry<String, Candidate>> ranked = new ArrayList<>();
        for (Map.Entry<String, Candidate> entry : terms.entrySet()) {
            if (vocabulary.getOrDefault(entry.getKey(), 0) > 0) {
                ranked.add(entry);
            }
        }
        ranked.sort(Comparator
            .comparingDouble((Map.Entry<String, Candidate> entry) ->
                entry.getValue().weight() * Math.log(1.0 + (double) total / (1 + vocabulary.get(entry.getKey())))).reversed()
            .thenComparing(Comparator.comparingInt((Map.Entry<String, Candidate> entry) -> entry.getValue().position()).reversed()));
        Set<String> chosen = new HashSet<>();
        ranked.stream().limit(limit).forEach(entry -> chosen.add(entry.getKey()));
        Map<String, Candidate> result = new LinkedHashMap<>();
        terms.forEach((text, candidate) -> {
            if (chosen.contains(text)) {
                result.put(text, candidate);
            }
        });
        return result;
    }

    private record Candidate(double weight, int position, boolean soft, Set<String> adjacent) {
    }

    /**
     * The searchable terms of a text, with their weights, and its pairs of neighbouring words.
     *
     * @param content whether it has a term that says what it is about: a message without one is a follow-up
     * @param lone    whether the terms are lone Chinese characters, the one word of a message like "那怎么改"
     * @param latin   whether the text has any Latin word or number
     * @param size    its length without white space
     */
    private record Scanned(Map<String, Candidate> terms, List<String> phrases, boolean content, boolean lone,
        boolean latin, int size) {
    }

    /**
     * Cuts text into terms: Latin words of at least two letters, whole identifiers (names with underscores or dots
     * inside, or with hyphens when they have a digit, such as claude-opus-4.5, which count for more) and their parts,
     * every pair of neighbouring Chinese characters, and a lone Chinese character that is not grammar. The text must
     * have been folded. With {@code question} set, the wording of a request is told from the topic and each pair is
     * given the pairs next to it; the documents themselves are not scanned for that.
     */
    private static Scanned scan(String folded, int limit, boolean question) {
        Map<String, Candidate> terms = new LinkedHashMap<>();
        Map<String, Candidate> lone = new LinkedHashMap<>();
        Map<String, Candidate> loose = new LinkedHashMap<>();
        List<String> phrases = new ArrayList<>();
        boolean[] generic = question ? genericCharacters(folded) : null;
        int length = folded.length();
        int index = 0;
        String lastWord = null;
        int lastEnd = -1;
        // Whether the last word was an HTTP status code or the reason phrase that follows one.
        boolean afterStatus = false;
        boolean latin = false;
        while (index < length && terms.size() < limit) {
            char first = folded.charAt(index);
            if (isCjk(first)) {
                int end = index;
                while (end < length && isCjk(folded.charAt(end))) {
                    end++;
                }
                if (end - index == 1) {
                    if (CJK_FUNCTION_CHARACTERS.indexOf(first) < 0 && !(question && generic[index])) {
                        add(lone, String.valueOf(first), UNIGRAM_WEIGHT, index, false, Set.of());
                    }
                } else {
                    for (int at = index; at + 1 < end && terms.size() < limit; at++) {
                        addPair(terms, folded, index, end, at, generic);
                    }
                    if (question) {
                        addLooseCharacters(loose, folded, index, end, generic);
                    }
                }
                lastWord = null;
                afterStatus = false;
                index = end;
            } else if (isWordChar(first)) {
                latin = true;
                int end = index;
                while (end < length) {
                    char value = folded.charAt(end);
                    if (isWordChar(value) || value == '_'
                        || ((value == '.' || value == '-') && end + 1 < length && isWordChar(folded.charAt(end + 1)))) {
                        end++;
                    } else {
                        break;
                    }
                }
                String token = folded.substring(index, end);
                int hyphen = token.indexOf('-');
                if (hyphen >= 0 && !hasDigit(token)) {
                    // Only a name with a digit in it is one identifier ("gpt-5.5"); "built-in" is two words.
                    end = index + hyphen;
                    token = folded.substring(index, end);
                }
                while (token.endsWith("_")) {
                    token = token.substring(0, token.length() - 1);
                }
                if (isCompound(token)) {
                    if (token.length() >= 4 || hasDigit(token)) {
                        add(terms, token, IDENTIFIER_WEIGHT, index, false, Set.of());
                    }
                    addIdentifierParts(terms, token, index);
                    lastWord = null;
                    afterStatus = false;
                } else if (token.length() >= 2 && !STOP_WORDS.contains(token)) {
                    String word = singular(token);
                    boolean reason = afterStatus && REASON_WORDS.contains(word);
                    boolean wording = question && GENERIC_WORDS.contains(word);
                    boolean tag = question && isTagName(folded, index);
                    add(terms, word, wording || reason || tag ? GENERIC_WEIGHT : WORD_WEIGHT, index, wording || reason || tag, Set.of());
                    if (lastWord != null && isOnlyWhitespace(folded, lastEnd, index)) {
                        phrases.add(lastWord + " " + word);
                    }
                    lastWord = word;
                    lastEnd = end;
                    afterStatus = isStatusCode(token) || reason;
                } else {
                    // "404 Not Found": the stop word does not end the reason phrase.
                    lastWord = null;
                }
                index = end;
            } else {
                index++;
            }
        }
        boolean content = false;
        for (Candidate candidate : terms.values()) {
            content |= candidate.weight() >= ANCHOR_WEIGHT && !candidate.soft();
        }
        // A lone character is a weak term and only stands in when the question has nothing else ("那怎么改？"). One
        // such character is a follow-up's word; several ("扣了这么多钱": 扣, 钱) are what the question is about.
        boolean loneOnly = false;
        if (!content) {
            Map<String, Candidate> weak = new LinkedHashMap<>(lone);
            weak.putAll(loose);
            terms.putAll(weak);
            content = weak.size() >= 2;
            loneOnly = !content && !weak.isEmpty();
        }
        return new Scanned(terms, phrases, content, loneOnly, latin, question ? nonBlankLength(folded) : 0);
    }

    private static void addPair(Map<String, Candidate> terms, String folded, int runStart, int runEnd, int at,
        boolean[] generic) {
        String pair = folded.substring(at, at + 2);
        if (generic == null) {
            add(terms, pair, WORD_WEIGHT, at, false, Set.of());
            return;
        }
        char left = folded.charAt(at);
        char right = folded.charAt(at + 1);
        boolean wording = (generic[at] && generic[at + 1]) || (at == runStart && LIGHT_VERBS.indexOf(left) >= 0);
        boolean grammar = CJK_FUNCTION_CHARACTERS.indexOf(left) >= 0 || CJK_FUNCTION_CHARACTERS.indexOf(right) >= 0;
        Set<String> adjacent = new HashSet<>(2);
        if (at > runStart) {
            adjacent.add(folded.substring(at - 1, at + 1));
        }
        if (at + 2 < runEnd) {
            adjacent.add(folded.substring(at + 1, at + 3));
        }
        add(terms, pair, wording ? GENERIC_WEIGHT : WORD_WEIGHT, at, wording || grammar, adjacent);
    }

    /**
     * The characters of a run that stand between grammar and grammar ("扣" in "扣了"): the pairs they form with it are
     * grammar, so they would be nothing, although the character may be all the question has to say.
     */
    private static void addLooseCharacters(Map<String, Candidate> loose, String folded, int start, int end,
        boolean[] generic) {
        for (int at = start; at < end; at++) {
            char value = folded.charAt(at);
            if (CJK_FUNCTION_CHARACTERS.indexOf(value) >= 0 || generic[at]) {
                continue;
            }
            boolean grammarBefore = at == start || CJK_FUNCTION_CHARACTERS.indexOf(folded.charAt(at - 1)) >= 0;
            boolean grammarAfter = at + 1 == end || CJK_FUNCTION_CHARACTERS.indexOf(folded.charAt(at + 1)) >= 0;
            if (grammarBefore && grammarAfter) {
                add(loose, String.valueOf(value), UNIGRAM_WEIGHT, at, false, Set.of());
            }
        }
    }

    /** Which characters belong to the wording of a request ("具体", "步骤", "例子"). */
    private static boolean[] genericCharacters(String folded) {
        boolean[] mask = new boolean[folded.length()];
        Matcher matcher = GENERIC_CJK.matcher(folded);
        while (matcher.find()) {
            Arrays.fill(mask, matcher.start(), matcher.end(), true);
        }
        return mask;
    }

    /** The parts of an identifier; a version such as 4.5 stays whole, because 4.5 and 4.8 are different models. */
    private static void addIdentifierParts(Map<String, Candidate> terms, String token, int base) {
        int from = 0;
        for (int at = 0; at <= token.length(); at++) {
            if (at == token.length() || token.charAt(at) == '_' || token.charAt(at) == '-') {
                String segment = token.substring(from, at);
                if (segment.length() < token.length() && segment.indexOf('.') >= 0 && hasDigit(segment)) {
                    add(terms, segment, IDENTIFIER_PART_WEIGHT, base + from, false, Set.of());
                } else {
                    addDottedParts(terms, segment, base + from);
                }
                from = at + 1;
            }
        }
    }

    private static void addDottedParts(Map<String, Candidate> terms, String segment, int base) {
        int from = 0;
        for (int at = 0; at <= segment.length(); at++) {
            if (at == segment.length() || segment.charAt(at) == '.') {
                String part = segment.substring(from, at);
                if (part.length() >= 2 && !STOP_WORDS.contains(part)) {
                    add(terms, singular(part), IDENTIFIER_PART_WEIGHT, base + from, false, Set.of());
                }
                from = at + 1;
            }
        }
    }

    private static void add(Map<String, Candidate> terms, String text, double weight, int position, boolean soft,
        Set<String> adjacent) {
        terms.merge(text, new Candidate(weight, position, soft, adjacent), (old, added) -> new Candidate(
            Math.max(old.weight(), added.weight()), Math.min(old.position(), added.position()),
            old.soft() && added.soft(), union(old.adjacent(), added.adjacent())));
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        if (second.isEmpty()) {
            return first;
        }
        if (first.isEmpty()) {
            return second;
        }
        Set<String> both = new HashSet<>(first);
        both.addAll(second);
        return both;
    }

    private static boolean hasDigit(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (Character.isDigit(text.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static int nonBlankLength(String text) {
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            if (!Character.isWhitespace(text.charAt(index))) {
                count++;
            }
        }
        return count;
    }

    /** Three digits from 100 to 599: an HTTP status code. */
    private static boolean isStatusCode(String token) {
        return token.length() == 3 && token.charAt(0) >= '1' && token.charAt(0) <= '5'
            && Character.isDigit(token.charAt(1)) && Character.isDigit(token.charAt(2));
    }

    /** Whether the word at {@code index} is the name of an HTML tag: pasted error pages carry plenty of them. */
    private static boolean isTagName(String text, int index) {
        return (index > 0 && text.charAt(index - 1) == '<')
            || (index > 1 && text.charAt(index - 1) == '/' && text.charAt(index - 2) == '<');
    }

    private static boolean isOnlyWhitespace(String text, int from, int to) {
        for (int index = from; index < to; index++) {
            if (!Character.isWhitespace(text.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /** The searchable words of a text, as {@link #scan} finds them, without the question wording. */
    static Set<String> terms(String text) {
        return new LinkedHashSet<>(termWeights(text).keySet());
    }

    /** The same words with the weight each counts for in a question: a whole identifier counts for more than its parts. */
    static Map<String, Double> termWeights(String text) {
        Map<String, Double> weights = new LinkedHashMap<>();
        if (text != null) {
            scanMessage(text).terms().forEach((term, candidate) -> weights.put(term, candidate.weight()));
        }
        return weights;
    }

    /** The pairs of neighbouring Latin words of a text, which count for more where a document has them together. */
    static List<String> phrases(String text) {
        return text == null ? List.of() : scanMessage(text).phrases();
    }

    /** "keys" becomes "key"; a document that says "keys" still matches it, because a trailing "s" is allowed. */
    private static String singular(String word) {
        return word.length() > 3 && word.endsWith("s") && !word.endsWith("ss") ? word.substring(0, word.length() - 1) : word;
    }

    /** An identifier: a name with underscores, dots or (when it has a digit) hyphens inside. */
    private static boolean isCompound(String term) {
        return term.indexOf('_') >= 0 || term.indexOf('.') >= 0 || term.indexOf('-') >= 0;
    }

    private static boolean contains(String text, String term) {
        return occurrences(text, term, 1) > 0;
    }

    /**
     * How often the term occurs, counting up to {@code limit}. A Latin word has to be a word of the text, possibly
     * with an ending such as "s" or "ed": "key" matches "keys", but "ai" does not match "email" and "code" does not
     * match "codex". A whole identifier has to stand alone: "api_key" is not found inside "api_key_id".
     */
    private static int occurrences(String text, String term, int limit) {
        if (text.isEmpty()) {
            return 0;
        }
        boolean latin = !isCjk(term.charAt(0));
        boolean whole = latin && isCompound(term);
        int found = 0;
        int from = 0;
        while (found < limit) {
            int at = text.indexOf(term, from);
            if (at < 0) {
                break;
            }
            from = at + 1;
            if (latin) {
                int end = at + term.length();
                boolean startsWord = at > 0 && (isWordChar(text.charAt(at - 1)) || (whole && text.charAt(at - 1) == '_'));
                boolean endsWord = whole
                    ? end >= text.length()
                        || !(isWordChar(text.charAt(end)) || text.charAt(end) == '_' || continuesName(text, end, term))
                    : wordEndsAt(text, end);
                if (startsWord || !endsWord) {
                    continue;
                }
            }
            found++;
            from = at + term.length();
        }
        return found;
    }

    /**
     * Whether the name goes on with a hyphen and a word: "gpt-5.5" is not found in "gpt-5.5-mini", which is another
     * model. A hyphen and a number ("claude-opus-4.5-20251101") is the same model under a dated name.
     */
    private static boolean continuesName(String text, int end, String term) {
        return text.charAt(end) == '-' && term.indexOf('-') >= 0 && end + 1 < text.length()
            && Character.isLetter(text.charAt(end + 1));
    }

    private static boolean wordEndsAt(String text, int end) {
        if (end >= text.length() || !isWordChar(text.charAt(end))) {
            return true;
        }
        int stop = end;
        while (stop < text.length() && stop - end <= 3 && isWordChar(text.charAt(stop))) {
            stop++;
        }
        return stop - end <= 3 && (stop == text.length() || !isWordChar(text.charAt(stop)))
            && WORD_ENDINGS.contains(text.substring(end, stop));
    }

    static String fold(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    /** Folded, with every run of white space a single space, so that two words of a question can be found together. */
    private static String foldForSearch(String text) {
        return WHITESPACE.matcher(fold(text)).replaceAll(" ");
    }

    private static boolean isWordChar(char value) {
        return Character.isLetterOrDigit(value) && !isCjk(value);
    }

    private static boolean containsCjk(String text) {
        return text != null && text.chars().anyMatch(value -> isCjk((char) value));
    }

    private static boolean isCjk(char value) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(value);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    /** Logs at WARN at most once a minute, so a database outage does not flood the log with every question. */
    private void warn(String message, Throwable error) {
        long now = clock.millis();
        long last = lastWarning.get();
        if (now - last >= WARN_INTERVAL && lastWarning.compareAndSet(last, now)) {
            if (error == null) {
                log.warn(message);
            } else {
                log.warn(message, error);
            }
        }
    }
}
