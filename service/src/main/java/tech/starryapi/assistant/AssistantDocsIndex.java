package tech.starryapi.assistant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tech.starryapi.documentation.DocumentationDtos.Content;
import tech.starryapi.documentation.DocumentationDtos.Document;
import tech.starryapi.documentation.DocumentationDtos.Translation;
import tech.starryapi.documentation.DocumentationPublicationChanged;
import tech.starryapi.documentation.DocumentationService;
import tech.starryapi.documentation.DocumentationService.PublishedStream;

/**
 * What the whale assistant can look things up in: the published pages of the in-console 使用文档 (Chinese and, where a
 * page has one, English) and, for development checkouts, README.md and docs/*.md under the working directory.
 *
 * <p>The pages are read through R2DBC, one at a time, each turned into its chunks as it arrives (so that the text of all
 * of them never has to be in the heap at once, see {@link Pages}), into one immutable snapshot shared by all questions.
 * The snapshot is rebuilt
 * lazily, by a single load however many questions arrive meanwhile, when {@link DocumentationService} reports a
 * publish, unpublish or delete that has committed (this node) or a restore clears the caches ({@link #invalidate()}),
 * and, for changes made on other nodes, when it is older than {@link #CACHE_TTL} <em>and</em> the cheap fingerprint of
 * the published pages has changed. Only one load runs at a time: changes that come while it does cost one more load
 * after it, not one each. If loading fails, or a read gets nothing for {@link #READ_TIMEOUT} (or takes longer than
 * {@link #READ_BUDGET} in all, or the load as a whole outlasts its deadline, which {@link #BUILD_BUDGET} for turning the
 * pages into a snapshot makes a limit too), the assistant carries on with the file chunks only and tries again shortly.
 *
 * <p>Both language versions of a page are matched as one document and the one in the reader's language is quoted.
 * Questions are scored on threads of their own (see {@link #SEARCH_THREADS}), never on the thread that delivered the
 * snapshot, which is a database event loop, nor on Reactor's shared parallel scheduler.
 */
@Component
public class AssistantDocsIndex {

    private static final Logger log = LoggerFactory.getLogger(AssistantDocsIndex.class);

    /** How long a loaded snapshot is trusted; changes made on another node show up within this time. */
    static final long CACHE_TTL = 60_000;
    /** After a failed load the assistant answers without the pages for this long, then tries again. */
    static final long RETRY_AFTER = 10_000;
    /**
     * How long a read may go without an answer: the whole fingerprint check, and the pages between two rows. Well under
     * the database's own statement timeout, which a connection that is gone never enforces. A limit on the whole read of
     * the pages would also cut off one that is slow but healthy (tens of megabytes over a remote database).
     */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
    /** The most that reading the pages, and turning each into chunks as it arrives, may take in all, however steadily the rows keep coming. */
    static final Duration READ_BUDGET = Duration.ofMinutes(5);
    /**
     * The most that turning the chunks of the pages into a snapshot may take once they are read. It is CPU work on a worker thread and
     * covered by no timeout of its own; this is what keeps a build that never reports back (see {@link Load}) from holding
     * every question for good.
     */
    static final Duration BUILD_BUDGET = Duration.ofMinutes(2);
    private static final long WARN_INTERVAL = 60_000;
    /**
     * How many questions may be scored at the same time: half the cores, at least one. Scoring is CPU work that a hostile
     * question can make long, and it must neither take every core nor run on Reactor's shared parallel scheduler, whose
     * workers also run the timers of the whole application (every {@code timeout} and {@code delay} of the egress, the
     * subscriptions and this index): held there, it would delay all of them.
     */
    static final int SEARCH_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    /** How many questions may wait for a thread, for each thread; one more is answered without excerpts, as after a timeout. */
    static final int SEARCH_QUEUE_PER_THREAD = 64;
    /**
     * The threads that score questions. They are daemon threads that go when they have been idle for a minute. A task that
     * is cancelled while it waits is dropped; one that has begun runs to its end, which the limits on a question's terms
     * make a matter of milliseconds.
     */
    private static final Scheduler SEARCH = Schedulers.newBoundedElastic(
        SEARCH_THREADS, SEARCH_QUEUE_PER_THREAD, "assistant-search", 60, true);

    static final int MAX_RESULTS = 6;
    static final int MIN_RESULTS = 4;
    /** Per page (or file) at most this many chunks in a first pass, then up to {@link #MAX_PER_SOURCE} to fill up. */
    private static final int PREFERRED_PER_SOURCE = 2;
    private static final int MAX_PER_SOURCE = 3;
    /** Total characters of excerpts, markers and labels included, that may be put into one prompt; the fixed rules come on top. */
    static final int EXCERPT_BUDGET = 8000;
    /** Bounds the memory and the per-question scan however many large pages there are; shared out fairly. */
    static final int MAX_PAGE_CHUNKS = 2000;
    private static final int MAX_FILE_CHUNKS = 80;
    private static final int MAX_TITLE_CHARS = 100;
    /**
     * Normalization can make a text far longer than it was (U+FDFA becomes 18 characters), so limiting the raw text of a
     * chunk does not limit what is kept to search in. The folded text of each field is cut to this many times what the
     * raw text of that field may have; ordinary text, which folds to about its own length, never reaches it.
     */
    private static final int FOLDED_FACTOR = 2;
    private static final int FOLDED_BODY_MAX = FOLDED_FACTOR * AssistantDocsChunker.MAX_CHUNK_CHARS;
    /** A title is cut to {@link #MAX_TITLE_CHARS} and an ellipsis. */
    private static final int FOLDED_TITLE_MAX = FOLDED_FACTOR * (MAX_TITLE_CHARS + 1);
    private static final int FOLDED_LEAF_MAX = FOLDED_FACTOR * (AssistantDocsChunker.MAX_HEADING_CHARS + 1);
    /** Three headings at most, each cut like a title, and what joins them. */
    private static final int FOLDED_SECTION_MAX = FOLDED_FACTOR
        * (3 * (AssistantDocsChunker.MAX_HEADING_CHARS + 1) + 2 * AssistantDocsChunker.PATH_SEPARATOR.length());
    /** The limits of the documentation's own content: a category of 60 characters, a summary of 240. */
    private static final int FOLDED_CATEGORY_MAX = FOLDED_FACTOR * 60;
    private static final int FOLDED_SUMMARY_MAX = FOLDED_FACTOR * 240;
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
    /**
     * A word or identifier of a question longer than this is not looked for, and no phrase is made of it: it is ignored,
     * not cut (a prefix would find what the question did not ask for). The longest name a document has is far shorter (the
     * longest key of the settings has 58 characters), and the cost of looking for a term grows with its length.
     */
    static final int MAX_TERM_CHARS = 64;
    /**
     * A term longer than this, and any identifier (see {@link #isLinearTerm}), counts against {@link #MAX_LONG_TERMS}. Every
     * term that is not a pair or a single character of ideographs (see {@link #usesLinearMatcher}), short words and phrases
     * included, is looked for with a matcher whose time is linear in the text, whatever the text is. A substring search
     * costs the length of the text times that of the term where a long prefix of the term matches at many places and the
     * whole term at none, as in pages that have long runs of one letter (a placeholder, a line to fill in, pasted data), with
     * or without the speed the JIT gives it; the vocabulary does not rule such a word out when a page lists it.
     */
    static final int LINEAR_TERM_CHARS = 16;
    /**
     * How many terms one query looks for that are longer than {@link #LINEAR_TERM_CHARS} or are identifiers (see
     * {@link #isLinearTerm}). Each costs a pass over all the text of the index, however short the pass is made; the ones of
     * least weight, and the later of equal weight, are left out.
     */
    static final int MAX_LONG_TERMS = 12;

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
    /**
     * A lone Chinese character left over after the question wording is removed: the whole question when it is a follow-up
     * ("改" in "那怎么改"), otherwise the verb that says what is wanted of the topic ("删" in "文件怎么删").
     */
    private static final double UNIGRAM_WEIGHT = 0.6;
    /**
     * The same character next to a topic: an answer to "文件怎么删" has the verb, so it counts for more than a stand-in. It is a
     * soft term, which orders the chunks of a topic the documents have and can neither make a chunk an answer nor make the
     * documents cover a topic they lack: "保险怎么买" is not answered by whatever has a "买" in its heading.
     *
     * <p>The same holds when the topic is a pasted identifier that the documents have only in parts ("CODEX_HOME 怎么改":
     * "codex" and "home" at {@link #IDENTIFIER_PART_WEIGHT}, no term heavy enough to be an anchor). A chunk then has to
     * contain one of the parts, what it covers is measured by the parts alone, and the verb is worth no more than the
     * strongest part; otherwise it would outweigh them all and the chunks that merely have a "改" would lead.
     */
    private static final double VERB_WEIGHT = 2.0;
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
    /**
     * The most that the soft terms of a question (the verb, the wording) add to a chunk, as a multiple of what its other
     * terms are worth. A verb orders the chunks of a topic, and for that it may outweigh a few mentions of the topic ("密钥怎么删"
     * is answered by the section that has "删除", not by the one that has "密钥" most often), but a chunk that happens to use
     * the verb in every sentence must not lead over the chunk that is about the topic: "报" in "调用的时候报 400" is in
     * "不会报错" twice in a section about choosing a tier, which then outscored the error reference that lists the 400. Up to
     * this limit the verb counts as it did; beyond it the topic decides. A question with no topic the documents know
     * ("怎么验证", "规则", "Common problems") has nothing for its wording to outweigh: there the wording alone orders the
     * chunks, as it did before the limit, instead of being capped at nothing.
     */
    private static final double SOFT_SHARE = 2.0;
    /** A heading longer than this does not just name a kind of section ({@link Entry#kindOfSection}). */
    private static final int MAX_KIND_HEADING_CHARS = 40;
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
        "README.zh-CN.md",
        "docs/API.md",
        "docs/ARCHITECTURE.md",
        "docs/CONFIGURATION.md",
        "docs/OPERATIONS.md",
        "docs/SOURCE_TIERS.md",
        "docs/TROUBLESHOOTING.md",
        "docs/DEPLOYMENT.md",
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
    // Wording that asks how and is followed by the verb and then the topic ("怎么买保险", "如何设置"). Where it is removed
    // from a question a VERB_FIRST_MARK is left instead of a space, so that the run of characters after it can be told from
    // one that has its topic first ("接口选哪个", "地址填什么"): see addPair. The fold of a message (NFKC) turns an em space into
    // a plain one, so the mark is never in a message itself.
    private static final Set<String> VERB_FIRST_WORDING = Set.of("怎么", "怎样", "如何", "咋");
    private static final char VERB_FIRST_MARK = '\u2003';
    // Words of the structure of a document, of a request for more ("steps", "details", "an example"), of what is asked
    // about a problem ("solve", "fix", "what does it mean", "why") and of a complaint that it still does not work
    // ("没用了"). They are rare in the running text but sit in headings and in the header rows of tables (a section on
    // what to do when nothing helps, a column of causes and remedies, a list of what the statuses mean), so by rarity they
    // would outweigh the topic of the question: "<status code> 怎么解决" would be answered by the section on what to do
    // when nothing helps rather than by the ones that name the code. They count GENERIC_WEIGHT, and a message made of
    // nothing else is a follow-up. The rule is the kind of word, not the page: add a word here when it says how the user
    // wants the topic treated and is not itself a topic. Longer words first; the text is folded.
    private static final Pattern GENERIC_CJK = Pattern.compile(
        "举个例子|举个例|举例子|举例|例子|示例|样例|实例|具体|详细|详情|细节|步骤|操作|验证|常见问题|问题|下一步|注意|规则|方法|办法|流程|"
            + "说明|介绍|展开|继续|更多|其他|别的|完整|仔细|讲一讲|说一说|讲讲|说说|解释|补充|报错|出错|"
            + "解决|处理|修复|排查|搞定|含义|意思|原因|"
            + "不管用|没反应|没效果|不好使|不行|不对|没用"
    );
    // A verb of looking for something in front of the topic ("看用量", "查余额"): the pair it forms with the first
    // character of the topic is a seam, not a word.
    private static final String LIGHT_VERBS = "看查找搜问";
    // Verbs so general that, left alone beside a topic ("怎么用", "怎么做", "怎么办"), they ask for nothing in particular and
    // are in nearly every chunk; next to a topic they are not terms. A verb that names an action ("删", "关", "改", "扣")
    // is one, and says which chunks of the topic answer.
    private static final String GENERAL_VERBS = "用做弄搞整办干看查找搜问";
    // Single characters that are grammar, not topic; any other lone character left in a question is a (weak) term.
    private static final String CJK_FUNCTION_CHARACTERS = "的了是在我你他她它们和与或及把被就也都还又才吗呢吧啊哦嗯呀哈那这个些么有无没不";
    private static final Set<String> STOP_WORDS = Set.of(
        "the", "how", "what", "why", "when", "where", "which", "who", "can", "could", "would", "should", "does", "did",
        "you", "your", "are", "was", "were", "for", "this", "that", "these", "those", "with", "and", "from", "have",
        "has", "not", "but", "please", "hello", "thanks", "thank", "is", "it", "in", "on", "to", "of", "do", "be", "an",
        "as", "at", "by", "or", "if", "my", "me", "we", "us", "so", "no", "am",
        "want", "know", "get", "about", "tell", "way", "there", "any", "see", "give", "more", "show", "also", "just",
        "need", "much", "than", "put", "some", "then", "its", "into"
    );
    // The same kind of words in English, in the form the endings are taken off to ("solving" is "solve"; a participle such
    // as "resolved" is not, because the documents use it as the name of a status). "one" ("how do I buy one") belongs here
    // and not among the stop words, which are dropped altogether, because a heading may be about it ("one provider per
    // key"). No word that the sentences about a topic need ("out" in "credits run out") may be a stop word.
    private static final Set<String> GENERIC_WORDS = Set.of(
        "step", "detail", "example", "verify", "verification", "rule", "note", "operation", "problem", "issue", "common",
        "mean", "meaning", "solve", "resolve", "fix", "troubleshoot", "cause", "reason", "view", "one"
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
    // The repetitions of a group are possessive: java.util.regex calls itself once for each repetition of a greedy group,
    // and a message of "a.a.a.…" a few thousand characters long overflows a small thread stack with it. Reactor takes a
    // StackOverflowError for fatal, so the question would not be answered at all. The separators and the parts are
    // different characters, so nothing is lost by not backing off.
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_]+(?:[.-][\\p{L}\\p{N}_]+)*+");
    private static final Pattern ENGLISH_WORD = Pattern.compile("\\p{IsLatin}{2,}(?:-\\p{IsLatin}+)*+");
    // Thanks, a greeting, a farewell, a sign that the answer was understood and the question "who are you": a message made
    // of nothing else closes the conversation and is no follow-up. Folded. Possessive below, so that a long message that
    // is not one is rejected in linear time; a longer word is listed before a shorter one that starts it.
    private static final String CLOSING_WORDS = "(?:(?:非常|十分|特别|真的|太|多)?(?:谢谢|多谢|谢了|感谢|辛苦了|明白了|明白|知道了|知道|收到|了解了|了解|"
        + "懂了|清楚了|没问题|没事了|不用了|哦哦|哦|再见|拜拜|你好|您好|哈喽|你是谁|您是谁|"
        + "thanks|thank you|thx|got it|noted|great|nice|cool|hello|hi|hey|goodbye|bye|good|understood|i see|who are you))";
    // The answer yes. It is usually the reply to the assistant's own "要我详细说说吗？", so it is about the topic before it:
    // taking it for a close would tell the model that nothing was found at the very moment the user said "go on".
    private static final String CONSENT_WORDS = "(?:好的|好吧|好嘞|好|行|可以|嗯嗯|嗯|要的|要|需要|是的|对的|对|okay|ok|sure|yes|yeah|yep)";
    private static final String PARTICLES = "[啊吧哦呀啦哈嘛哒你您~]*+";
    private static final Pattern CLOSING_OR_CONSENT = Pattern.compile(
        "(?:(?:" + CLOSING_WORDS + "|" + CONSENT_WORDS + ")" + PARTICLES + "[\\s\\p{P}\\p{S}]*+)++");
    private static final Pattern CONSENT = Pattern.compile("(?:" + CONSENT_WORDS + PARTICLES + "[\\s\\p{P}\\p{S}]*+)++");
    // The same words, or "那好", at the head of a message that goes on ("好的，那怎么停用密钥"): they are not what it asks about.
    private static final Pattern OPENING = Pattern.compile(
        "^(?:(?:" + CLOSING_WORDS + "|" + CONSENT_WORDS + "|那好)" + PARTICLES + "[\\s\\p{P}\\p{S}]++)++(?=\\S)");

    // Endings a Latin word may carry and still count as the same word: key / keys, create / created.
    private static final Set<String> WORD_ENDINGS = Set.of("s", "es", "d", "ed", "ing");

    /**
     * Where the published pages come from: the pages themselves, either all at once (a test's way) or, as the database
     * delivers them, one at a time ({@link #pageStream}; then this is null), and a cheap value that changes when they do.
     */
    private final Supplier<Mono<List<Document>>> pages;
    private final Supplier<Mono<PublishedStream>> pageStream;
    private final Supplier<Mono<String>> fingerprint;
    private final List<Entry> fileEntries;
    private final Clock clock;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
    /** Guards {@link #running} and {@link #queued}; held only to look at them or to change them. */
    private final Object loadLock = new Object();
    /** The load that is reading the pages, or has been handed to the first question and will begin on subscription. */
    private Load running;
    /** The one load waiting for {@link #running} to finish because a change came after that one began. */
    private Load queued;
    /** How long the fingerprint may take to read; a test shortens it. The pages are held to it where they are read. */
    volatile Duration readTimeout = READ_TIMEOUT;
    /** How long reading the pages may take in all; a test shortens it. */
    volatile Duration readBudget = READ_BUDGET;
    /** How long building the snapshot may take once the pages are read; a test shortens it. */
    volatile Duration buildBudget = BUILD_BUDGET;
    private final AtomicLong lastWarning = new AtomicLong(Long.MIN_VALUE / 2);
    /** The thread the last snapshot was built on; the build is CPU work and must not run on a database event loop. */
    volatile Thread lastBuildThread;
    /** The thread the last question was scored on, for the same reason. */
    volatile Thread lastSearchThread;
    /** Where questions are scored; a test replaces it. */
    volatile Scheduler searchScheduler = SEARCH;
    /** Called with every page as it is turned into chunks, where a failure is the page's; a test makes it fail. */
    volatile Consumer<Document> pageHook = document -> { };
    /** Called with each term that the chunks are gone through for, in the order of the searches; a test counts them. */
    volatile Consumer<String> searchHook = term -> { };

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
        this(Path.of(System.getProperty("user.dir", ".")), null, documentation::publishedStream,
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
        this(root, pages, null, fingerprint, clock);
    }

    /**
     * Pages as the database delivers them, one at a time: each is turned into chunks as it arrives and nothing but the
     * chunks is kept (see {@link Pages}). The read fails when no page comes for {@link #readTimeout}, which a connection
     * that is gone without a reset does; it does not fail because it takes long, as a large documentation over a slow link
     * does.
     */
    static AssistantDocsIndex streaming(Path root, Supplier<Flux<Document>> pageStream,
        Supplier<Mono<String>> fingerprint, Clock clock) {
        return streamingWithFingerprint(root, () -> Mono.fromSupplier(() -> new PublishedStream(null, pageStream.get())), fingerprint, clock);
    }

    /**
     * The same for a source that says which pages it listed: the fingerprint it delivers with them is the one kept with the
     * snapshot (see {@link PublishedStream}), as in production.
     */
    static AssistantDocsIndex streamingWithFingerprint(Path root, Supplier<Mono<PublishedStream>> pageStream,
        Supplier<Mono<String>> fingerprint, Clock clock) {
        return new AssistantDocsIndex(root, null, pageStream, fingerprint, clock);
    }

    private AssistantDocsIndex(Path root, Supplier<Mono<List<Document>>> pages, Supplier<Mono<PublishedStream>> pageStream,
        Supplier<Mono<String>> fingerprint, Clock clock) {
        this.fileEntries = loadFiles(root);
        this.pages = pages;
        this.pageStream = pageStream;
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

        /**
         * What an excerpt's markers and label add to its text in the prompt (see {@code AssistantChatService.systemPrompt}):
         * the opening marker with its number and the 12-character code, the closing one, the quotation marks round the
         * title and the separator before the section, and the line breaks. Six excerpts at most, so one digit.
         */
        private static final int LABEL_OVERHEAD = 56;

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

        /** How many characters of the prompt this excerpt takes, its markers and label included. */
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
        // scoring is CPU work and happens on threads of its own instead (see SEARCH).
        return snapshot()
            .publishOn(searchScheduler)
            .map(current -> {
                lastSearchThread = Thread.currentThread();
                return search(current, question, earlier);
            })
            .onErrorResume(error -> {
                warn("The assistant could not search its documentation", error);
                return Mono.just(Retrieval.EMPTY);
            });
    }

    /** How many characters of folded text the current snapshot keeps to search in, shared strings counted once; for the tests. */
    long searchCharacters() {
        Snapshot current = snapshot.get();
        if (current == null) {
            return 0;
        }
        Set<String> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
        current.entries().forEach(entry -> entry.collect(distinct));
        return distinct.stream().mapToLong(String::length).sum();
    }

    /** How many characters of the chunks' own (unfolded) text the current snapshot keeps for quoting; for the tests. */
    long retainedCharacters() {
        Snapshot current = snapshot.get();
        return current == null ? 0 : current.entries().stream().mapToLong(entry -> entry.chunk().body().length()).sum();
    }

    /** Whether a term can occur in any chunk of the current snapshot, as the search decides it; for the tests. */
    boolean mayOccur(String term) {
        Snapshot current = snapshot.get();
        return current != null && mayOccur(term, current.vocabulary());
    }

    /** How many words are kept as strings and how many pairs of Chinese characters as ints; for the tests. */
    int[] vocabularySizes() {
        Snapshot current = snapshot.get();
        return current == null ? new int[2] : current.vocabulary().sizes();
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
     * @param twins        for each entry the index of the same text in the other language version of its page, or -1:
     *                     the n-th piece of a section, where both versions cut the section into as many pieces
     * @param headingTwins for each entry the index of a chunk under the same heading in the other language version of
     *                     its page, or -1: the twin where there is one, otherwise the first piece of the section in the
     *                     other version, whose heading is the same and whose text is not
     * @param fingerprint  what the published pages looked like when they were read; null when unknown or not read
     */
    private record Snapshot(List<Entry> entries, int[] twins, int[] headingTwins, List<String> pageTitles,
        String fingerprint, long generation, long expiresAt, Vocabulary vocabulary) {

        Snapshot renewed(long until) {
            return new Snapshot(entries, twins, headingTwins, pageTitles, fingerprint, generation, until, vocabulary);
        }
    }

    /**
     * One reading of the pages. It reads the generation it serves when it begins, not when it is created, so that a load
     * queued behind another one is for whatever is wanted by the time it runs.
     *
     * <p>A load waits for the one before it on that load's {@link #done} signal, which carries nothing, and not on its
     * result: the result caches the snapshot, so a chain of results would keep every earlier snapshot alive for as long as
     * loads keep queueing behind each other.
     *
     * <p>Every load has a deadline that does not depend on it reporting back. Reactor rethrows an error that is fatal to
     * the JVM (an {@code OutOfMemoryError}, a {@code StackOverflowError}, a {@code LinkageError}) where it was thrown
     * instead of passing it on, so a load that meets one would never finish, {@code finished} would never run, no later
     * load would ever begin and every question would wait for it for good. The timer fires whatever the load does.
     */
    private final class Load {

        /** The generation it reads; {@link Long#MAX_VALUE} until it begins, which any question may therefore join. */
        volatile long serving = Long.MAX_VALUE;
        /** Completes, empty, when the load is over, however it ended. */
        final Sinks.Empty<Void> done = Sinks.empty();
        final Mono<Snapshot> result;

        /** @param after the load to wait for first; null to begin as soon as the first question subscribes */
        Load(Load after) {
            Mono<Snapshot> begin = Mono.defer(() -> {
                long wanted = generation.get();
                serving = wanted;
                synchronized (loadLock) {
                    running = this;
                    if (queued == this) {
                        queued = null;
                    }
                }
                return load(wanted, snapshot.get()).timeout(loadDeadline(), Mono.fromSupplier(() -> abandoned(wanted)));
            }).doFinally(signal -> finished(this));
            result = (after == null ? begin : after.done.asMono().then(begin)).cache();
        }
    }

    /** The most a load may take in all: the fingerprint, the pages and the build, each at its own limit. */
    private Duration loadDeadline() {
        return readTimeout.plus(readBudget).plus(buildBudget);
    }

    /** What a load that did not finish within its deadline leaves behind: the files only, until it is tried again. */
    private Snapshot abandoned(long wanted) {
        warn("Loading the published documentation pages for the assistant did not finish in time; answering without them", null);
        Snapshot loaded = withoutPages(wanted, clock.millis() + RETRY_AFTER, null);
        remember(loaded);
        return loaded;
    }

    private void remember(Snapshot loaded) {
        snapshot.accumulateAndGet(loaded, (old, next) -> old == null || next.generation() >= old.generation() ? next : old);
    }

    private void finished(Load load) {
        synchronized (loadLock) {
            if (running == load) {
                running = queued;
                queued = null;
            }
        }
        load.done.tryEmitEmpty();
    }

    /**
     * The snapshot to answer with. Only one load runs at a time, however many publishes there are: a question that
     * wants a generation newer than the running load's waits for one more load, queued behind it and shared by all such
     * questions, which begins when the running one is done and reads whatever is current then. A burst of changes costs
     * at most two loads, not one per change in parallel, each holding all the pages in memory at once.
     */
    private Mono<Snapshot> snapshot() {
        long wanted = generation.get();
        Snapshot ready = snapshot.get();
        if (ready != null && ready.generation() >= wanted && clock.millis() < ready.expiresAt()) {
            return Mono.just(ready);
        }
        synchronized (loadLock) {
            if (running == null) {
                running = new Load(null);
                return running.result;
            }
            // A load that began before the latest change may have read the old pages, so it is not shared.
            if (running.serving >= wanted) {
                return running.result;
            }
            if (queued == null) {
                queued = new Load(running);
            }
            return queued.result;
        }
    }

    private Mono<Snapshot> load(long wanted, Snapshot previous) {
        // Only the time to live ran out, nothing was reported: the pages are read again only if they changed.
        boolean expiredOnly = previous != null && previous.generation() >= wanted && previous.fingerprint() != null;
        // A read that never returns (a connection that died without a reset) is a failure like any other: without
        // a limit it would hold every question of this node for as long as the operating system keeps the socket. The
        // fingerprint is a few bytes and is limited in all; the pages are limited where their rows arrive (see
        // streamed), so that a large documentation over a slow link is still read, and here only by a generous budget.
        return Mono.defer(fingerprint)
            .timeout(readTimeout)
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .flatMap(current -> {
                if (expiredOnly && current.isPresent() && current.get().equals(previous.fingerprint())) {
                    return Mono.just(previous.renewed(clock.millis() + CACHE_TTL));
                }
                // The fingerprint that is kept with the pages is the one of the pages that were read (see streamed), not
                // this one, which another node's publish or withdrawal can make a description of other pages: what is
                // kept then equals the fingerprint of a later state, and the snapshot is taken for current for good.
                return pageStream != null
                    ? streamed(wanted, current.orElse(null))
                    : listed(wanted, current.orElse(null));
            })
            .onErrorResume(error -> {
                warn("Could not load the published documentation pages for the assistant; answering without them", error);
                return Mono.just(withoutPages(wanted, clock.millis() + RETRY_AFTER, null));
            })
            .doOnNext(this::remember);
    }

    private Snapshot withoutPages(long wanted, long until, String fingerprint) {
        List<Entry> entries = List.copyOf(fileEntries);
        int[] twins = new int[entries.size()];
        Arrays.fill(twins, -1);
        return new Snapshot(entries, twins, twins, List.of(), fingerprint, wanted, until, Vocabulary.of(entries));
    }

    /** The pages, all of them at once from a supplier that limits its own read; they are turned into a snapshot on a worker thread. */
    private Mono<Snapshot> listed(long wanted, String fingerprint) {
        return Mono.defer(pages)
            .timeout(readBudget)
            .publishOn(Schedulers.boundedElastic())
            .map(loaded -> completed(() -> {
                Pages collected = new Pages();
                for (Document document : loaded) {
                    collected.add(document);
                }
                return collected.snapshot(wanted, fingerprint);
            }, wanted, fingerprint));
    }

    /**
     * The pages as the database delivers them. Each is turned into chunks on a worker thread (not the database
     * connection's event loop) as soon as it arrives, and only one is in hand at a time: the text of all of them together
     * may be far more than the heap has room for, while the chunks that are kept are limited to
     * {@link #MAX_PAGE_CHUNKS}. The read fails when no page comes for {@link #readTimeout}, not because it takes long.
     */
    private Mono<Snapshot> streamed(long wanted, String read) {
        return Mono.defer(pageStream)
            .timeout(readTimeout)
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("The source of the documentation pages delivered nothing")))
            .flatMap(stream -> {
                // The fingerprint of the pages whose ids were listed, made by the statement that lists them; it describes
                // what is read, whatever happens to the published pages meanwhile. The one read before the load, which a
                // source that cannot say (a test's) falls back to, can describe other pages: a page published after it
                // and before the list is in the snapshot but not in it, and when the page is withdrawn again the
                // published pages match it once more, so that every check finds the snapshot current and the page is
                // quoted until another change comes or the node restarts.
                String fingerprint = stream.fingerprint() != null ? stream.fingerprint() : read;
                return stream.pages()
                    .timeout(readTimeout)
                    .publishOn(Schedulers.boundedElastic(), 1)
                    .reduceWith(Pages::new, Pages::addGuarded)
                    .map(collected -> completed(() -> collected.snapshot(wanted, fingerprint), wanted, fingerprint));
            })
            .timeout(readBudget);
    }

    /**
     * A failure of the build that the text of the pages decides would repeat on every attempt, so it is not retried until
     * something changes. Running out of memory is not decided by the pages alone: it is the heap of the moment, which other
     * work may have been filling, so it is retried like any failure to load. The snapshot then has no fingerprint, and the
     * pages are read again instead of being taken for unchanged.
     */
    private Snapshot completed(Supplier<Snapshot> build, long wanted, String fingerprint) {
        lastBuildThread = Thread.currentThread();
        try {
            return build.get();
        } catch (OutOfMemoryError error) {
            // Reactor would rethrow it where it is thrown and the load would never finish.
            warn("Ran out of memory indexing the published documentation pages for the assistant; answering without them", error);
            return withoutPages(wanted, clock.millis() + RETRY_AFTER, null);
        } catch (RuntimeException | StackOverflowError | LinkageError error) {
            // Reactor would rethrow an error like the last two where it is thrown and the load would never finish.
            warn("Could not index the published documentation pages for the assistant; answering without them", error);
            return withoutPages(wanted, clock.millis() + CACHE_TTL, fingerprint);
        }
    }

    /**
     * One published page, chunked in the languages it has. Of the English version it keeps what the snapshot uses and
     * not the version itself: its body is parsed and then of no more use, and 200 of them are tens of megabytes held for
     * nothing until the last page is read.
     *
     * @param englishTitle    the title of the English version; empty when there is none
     * @param englishCategory the category of the English version; empty when there is none
     * @param englishSummary  the summary of the English version, cleaned; empty when there is none
     * @param englishParsed   the English version's chunks; null when the page has no English version
     * @param chineseTotal    how many chunks the Chinese text had before {@link Pages} cut it down to what can be kept
     * @param englishTotal    the same for the English text; 0 when there is none
     */
    private record PageText(String id, String title, String category, String summary,
        AssistantDocsChunker.Parsed chinese, String englishTitle, String englishCategory, String englishSummary,
        AssistantDocsChunker.Parsed englishParsed, int chineseTotal, int englishTotal) {

        /** The same page with at most {@code limit} chunks of each language. */
        PageText limitedTo(int limit) {
            AssistantDocsChunker.Parsed zh = limited(chinese, limit);
            AssistantDocsChunker.Parsed en = limited(englishParsed, limit);
            return zh == chinese && en == englishParsed ? this : new PageText(id, title, category, summary, zh,
                englishTitle, englishCategory, englishSummary, en, chineseTotal, englishTotal);
        }

        private static AssistantDocsChunker.Parsed limited(AssistantDocsChunker.Parsed parsed, int limit) {
            // A copy, so that the longer list, and the text of the chunks cut off, can go.
            return parsed == null || parsed.sections().size() <= limit ? parsed
                : new AssistantDocsChunker.Parsed(List.copyOf(parsed.sections().subList(0, limit)), parsed.headings());
        }
    }

    /**
     * The pages as they are turned into chunks, one after the other. The most that can be kept in the end is
     * {@link #MAX_PAGE_CHUNKS} chunks, shared out fairly ({@link #fairLimit}), and the share only shrinks as pages are
     * added. So after each page the chunks beyond the share are dropped: what is held is about what the snapshot will keep
     * plus the page in hand, however many pages there are and however long. Waiting for all of them before cutting would
     * hold the whole text of the documentation (up to 200 pages of two languages of 100,000 characters each) in the heap
     * at once, along with its rows.
     */
    private final class Pages {

        private final List<PageText> texts = new ArrayList<>();
        private final List<String> titles = new ArrayList<>();
        /** How many chunks each language version of each page had; the share is worked out from them. */
        private final List<Integer> sizes = new ArrayList<>();
        private int limit = Integer.MAX_VALUE;
        /** The error the JVM cannot recover from that stopped the pages from being read, if one did. */
        private Error failure;

        /** For a stream of pages: an error that Reactor would rethrow where it is thrown is kept for the end instead. */
        Pages addGuarded(Document document) {
            if (failure == null) {
                try {
                    add(document);
                } catch (Error fatal) {
                    failure = fatal;
                    texts.clear();
                    titles.clear();
                    sizes.clear();
                }
            }
            return this;
        }

        void add(Document document) {
            PageText page;
            try {
                pageHook.accept(document);
                page = pageText(document);
                if (page == null) {
                    warn("A published documentation page has no usable content and is left out of the assistant"
                        + (document == null ? "" : " (page " + document.id() + ")"), null);
                    return;
                }
            } catch (RuntimeException | StackOverflowError error) {
                warn("Could not read a published documentation page for the assistant; leaving it out"
                    + (document == null ? "" : " (page " + document.id() + ")"), error);
                return;
            }
            texts.add(page);
            titles.add(page.englishParsed() == null || page.englishTitle().equals(page.title())
                ? page.title() : page.title() + " / " + page.englishTitle());
            sizes.add(page.chineseTotal());
            if (page.englishParsed() != null) {
                sizes.add(page.englishTotal());
            }
            limit = fairLimit(sizes);
            if (limit != Integer.MAX_VALUE) {
                texts.replaceAll(text -> text.limitedTo(limit));
            }
        }

        Snapshot snapshot(long wanted, String fingerprint) {
            if (failure != null) {
                throw failure;
            }
            List<Entry> entries = new ArrayList<>();
            List<int[]> twinPairs = new ArrayList<>();
            for (PageText page : texts) {
                int languages = (page.chinese().sections().isEmpty() ? 0 : ZH)
                    | (page.englishParsed() == null || page.englishParsed().sections().isEmpty() ? 0 : EN);
                Map<Integer, List<Integer>> chinese = addChunks(entries, page, page.title(), page.category(),
                    page.summary(), page.chinese(), page.chineseTotal(), "zh", languages, limit);
                if (page.englishParsed() != null) {
                    Map<Integer, List<Integer>> translated = addChunks(entries, page, page.englishTitle(),
                        page.englishCategory(), page.englishSummary(), page.englishParsed(), page.englishTotal(), "en",
                        languages, limit);
                    if (page.chinese().headings() == page.englishParsed().headings()) {
                        pairTwins(chinese, translated, twinPairs);
                    }
                }
            }
            entries.addAll(fileEntries);
            int[] twins = new int[entries.size()];
            int[] headingTwins = new int[entries.size()];
            Arrays.fill(twins, -1);
            Arrays.fill(headingTwins, -1);
            for (int[] pair : twinPairs) {
                headingTwins[pair[0]] = pair[1];
                if (pair[2] == TWIN_TEXT) {
                    twins[pair[0]] = pair[1];
                }
            }
            List<Entry> all = List.copyOf(entries);
            return new Snapshot(all, twins, headingTwins, List.copyOf(titles), fingerprint, wanted,
                clock.millis() + CACHE_TTL, Vocabulary.of(all));
        }
    }

    private PageText pageText(Document document) {
        Content page = document == null ? null : document.content();
        if (page == null || document.id() == null || page.title() == null) {
            return null;
        }
        String title = pageTitle(page.title());
        if (title.isBlank()) {
            return null;
        }
        Translation english = page.english();
        String englishTitle = english == null || english.title() == null ? "" : pageTitle(english.title());
        boolean translated = english != null && !englishTitle.isBlank()
            && english.body() != null && !english.body().isBlank();
        AssistantDocsChunker.Parsed chinese = AssistantDocsChunker.parse(page.body(), 1);
        AssistantDocsChunker.Parsed englishParsed = translated ? AssistantDocsChunker.parse(english.body(), 1) : null;
        return new PageText(document.id(), title, nullToEmpty(page.category()),
            AssistantDocsChunker.clean(nullToEmpty(page.summary())), chinese, translated ? englishTitle : "",
            translated ? nullToEmpty(english.category()) : "",
            translated ? AssistantDocsChunker.clean(nullToEmpty(english.summary())) : "", englishParsed,
            chinese.sections().size(), englishParsed == null ? 0 : englishParsed.sections().size());
    }

    /**
     * A page title as the prompt and the sources show it: without an attachment reference or a signed media address, which
     * must never reach either wherever the administrator put them, and cut to {@link #MAX_TITLE_CHARS}. The reference is
     * removed first, so that a cut cannot leave half of an id behind.
     */
    private static String pageTitle(String title) {
        return abbreviate(AssistantDocsChunker.withoutMedia(title).strip());
    }

    /** Adds a page's chunks in one language and returns, for each heading, where its chunks went. */
    private Map<Integer, List<Integer>> addChunks(List<Entry> entries, PageText page, String title, String category,
        String summary, AssistantDocsChunker.Parsed parsed, int total, String language, int languages, int limit) {
        Map<Integer, List<Integer>> byOrdinal = new HashMap<>();
        List<AssistantDocsChunker.Section> sections = parsed.sections();
        if (total > limit) {
            warn("The published documentation is too large to index completely; only the first " + limit
                + " passages of '" + title + "' are searched by the assistant", null);
        }
        if (sections.size() > limit) {
            sections = sections.subList(0, limit);
        }
        // What the page says about itself is folded once and shared by all its chunks, not copied into each of them.
        PageFold fold = PageFold.of(title, nullToEmpty(category), summary);
        for (AssistantDocsChunker.Section section : sections) {
            byOrdinal.computeIfAbsent(section.ordinal(), key -> new ArrayList<>()).add(entries.size());
            entries.add(Entry.of(new Chunk(page.id(), title, nullToEmpty(category), summary, section.heading(),
                language, section.body()), languages, fold));
        }
        return byOrdinal;
    }

    /** A pair of chunks that say the same: the text of one is the translation of the text of the other. */
    private static final int TWIN_TEXT = 1;
    /** A pair of chunks under the same heading whose texts are not the same: they are cut into pieces differently. */
    private static final int TWIN_HEADING = 0;

    /**
     * The two language versions of a page with the same number of headings say the same thing in the same order. Under
     * a heading that both cut into the same number of pieces, the n-th piece of one version is the twin of the n-th of
     * the other: they are scored together, each by whichever has more of a word ({@link Matches#better}).
     *
     * <p>Where the two versions cut a section into a different number of pieces (a table of the same rows is longer in
     * English, so it is cut in three where the Chinese is cut in one), nothing says which piece is the translation of
     * which. Pairing them anyway, the last piece with all the rest, would give every English piece the words of the
     * whole Chinese section, and a question about the 401 row would quote the pieces that hold the rows of 429 and 502.
     * The pieces of such a section are paired with the first piece of the other version for the heading only, which is
     * the same under all of them, and each is scored on its own text.
     */
    private static void pairTwins(Map<Integer, List<Integer>> chinese, Map<Integer, List<Integer>> english,
        List<int[]> pairs) {
        chinese.forEach((ordinal, mine) -> {
            List<Integer> theirs = english.get(ordinal);
            if (theirs == null) {
                return;
            }
            int kind = mine.size() == theirs.size() ? TWIN_TEXT : TWIN_HEADING;
            for (int at = 0; at < mine.size(); at++) {
                pairs.add(new int[] {mine.get(at), theirs.get(kind == TWIN_TEXT ? at : 0), kind});
            }
            for (int at = 0; at < theirs.size(); at++) {
                pairs.add(new int[] {theirs.get(at), mine.get(kind == TWIN_TEXT ? at : 0), kind});
            }
        });
    }

    /** The largest number of chunks any page may contribute so that all together stay within the cap. */
    private static int fairLimit(List<Integer> sizes) {
        long total = 0;
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
     *
     * <p>The pairs of Chinese characters are most of it: a page of varied text has some hundred thousand different ones,
     * and as strings with a boxed count each they would take several times the memory of the text they describe, twice
     * over while a new snapshot is built beside the old one. They are kept as the two characters in one {@code int}, in a
     * sorted array with a count array beside it. Only Latin words and identifiers are strings.
     */
    private static final class Vocabulary {

        private final Map<String, Integer> words;
        private final int[] pairs;
        private final int[] pairCounts;

        private Vocabulary(Map<String, Integer> words, int[] pairs, int[] pairCounts) {
            this.words = words;
            this.pairs = pairs;
            this.pairCounts = pairCounts;
        }

        static Vocabulary of(List<Entry> entries) {
            Map<String, Integer> words = new HashMap<>();
            int[] all = new int[1024];
            int size = 0;
            for (Entry entry : entries) {
                Set<String> inEntry = new HashSet<>();
                int[] pairsInEntry = new int[64];
                int pairCount = 0;
                for (String text : List.of(entry.title(), entry.section(), entry.leaf(), entry.category(), entry.summary(), entry.body())) {
                    for (String term : scan(text, Integer.MAX_VALUE, false).terms().keySet()) {
                        if (isPair(term)) {
                            if (pairCount == pairsInEntry.length) {
                                pairsInEntry = Arrays.copyOf(pairsInEntry, pairCount * 2);
                            }
                            pairsInEntry[pairCount++] = key(term);
                        } else {
                            inEntry.add(term);
                        }
                    }
                    addStems(inEntry, text);
                }
                inEntry.forEach(word -> words.merge(word, 1, Integer::sum));
                // Each pair counts once per chunk, however often it occurs in it.
                Arrays.sort(pairsInEntry, 0, pairCount);
                for (int at = 0; at < pairCount; at++) {
                    if (at == 0 || pairsInEntry[at] != pairsInEntry[at - 1]) {
                        if (size == all.length) {
                            all = Arrays.copyOf(all, size * 2);
                        }
                        all[size++] = pairsInEntry[at];
                    }
                }
            }
            Arrays.sort(all, 0, size);
            int[] pairs = new int[size];
            int[] counts = new int[size];
            int distinct = 0;
            for (int at = 0; at < size; at++) {
                if (distinct > 0 && pairs[distinct - 1] == all[at]) {
                    counts[distinct - 1]++;
                } else {
                    pairs[distinct] = all[at];
                    counts[distinct++] = 1;
                }
            }
            return new Vocabulary(words, Arrays.copyOf(pairs, distinct), Arrays.copyOf(counts, distinct));
        }

        /** In how many chunks the word or pair occurs; 0 when in none. */
        int count(String term) {
            if (isPair(term)) {
                int at = Arrays.binarySearch(pairs, key(term));
                return at < 0 ? 0 : pairCounts[at];
            }
            return words.getOrDefault(term, 0);
        }

        boolean contains(String term) {
            return count(term) > 0;
        }

        /** How many words are kept as strings, and how many pairs of Chinese characters as ints. */
        int[] sizes() {
            return new int[] {words.size(), pairs.length};
        }

        private static boolean isPair(String term) {
            return term.length() == 2 && isCjk(term.charAt(0)) && isCjk(term.charAt(1));
        }

        private static int key(String pair) {
            return (pair.charAt(0) << 16) | pair.charAt(1);
        }
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
     * Whether a term can occur in any chunk: a letter pair or a word has to be a word of the index. A phrase can only
     * occur where its two words do (the second with an ending, which the index keeps the stem of too). A single
     * character is not a word of it, and an identifier may stand in front of a longer one ("config.toml.bak").
     */
    private static boolean mayOccur(String term, Vocabulary vocabulary) {
        if (term.length() < 2) {
            return true;
        }
        int space = term.indexOf(' ');
        if (space >= 0) {
            return vocabulary.contains(term.substring(0, space)) && vocabulary.contains(term.substring(space + 1));
        }
        return isCompound(term) ? partsAreWords(term, vocabulary) : vocabulary.contains(term);
    }

    /**
     * Whether every run of two or more letters and digits in an identifier is a word of the index. A whole identifier is
     * found only where it begins a word and ends one, so each run in it is a whole run of the text, and every run of
     * two or more characters of every field is a word of the vocabulary. A run of one character is not (a word has two),
     * so "a_b_c" is not ruled out by it. An identifier may stand in front of a longer one ("config.toml.bak"), which
     * adds runs after it and none to it.
     */
    private static boolean partsAreWords(String name, Vocabulary vocabulary) {
        int length = name.length();
        int index = 0;
        while (index < length) {
            if (!isWordChar(name.charAt(index))) {
                index++;
                continue;
            }
            int end = index + 1;
            while (end < length && isWordChar(name.charAt(end))) {
                end++;
            }
            if (end - index >= 2 && !vocabulary.contains(name.substring(index, end))) {
                return false;
            }
            index = end;
        }
        return true;
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
                loaded.addAll(split(relative, text).stream().map(chunk -> Entry.of(chunk, 0, PageFold.NONE)).toList());
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

    /** The title, category and summary of a page, folded and bounded once for all its chunks in one language. */
    private record PageFold(String title, String category, String summary) {

        static final PageFold NONE = new PageFold("", "", "");

        static PageFold of(String title, String category, String summary) {
            return new PageFold(foldedWithin(title, FOLDED_TITLE_MAX), foldedWithin(category, FOLDED_CATEGORY_MAX),
                foldedWithin(summary, FOLDED_SUMMARY_MAX));
        }
    }

    /**
     * A chunk with its searchable text folded once, so that a question costs no more than substring scans.
     *
     * @param languages which language versions its page has (a bit each for Chinese and English); 0 for a file
     * @param kindOfSection whether its own heading says nothing but what kind of section it is ("Verify", "Common
     *                     problems", "验证", "常见问题"), every word of the heading being wording
     */
    private record Entry(Chunk chunk, String title, String section, String leaf, String category, String summary,
        String body, BitSet bodyWords, int languages, boolean kindOfSection) {

        /** @param page what the page says about itself, folded; {@link PageFold#NONE} for a file chunk */
        static Entry of(Chunk chunk, int languages, PageFold page) {
            String path = chunk.section();
            int at = path.lastIndexOf(AssistantDocsChunker.PATH_SEPARATOR);
            String body = foldedWithin(chunk.body(), FOLDED_BODY_MAX);
            String leaf = foldedWithin(at < 0 ? path : path.substring(at + AssistantDocsChunker.PATH_SEPARATOR.length()), FOLDED_LEAF_MAX);
            return new Entry(chunk, page.title(), foldedWithin(path, FOLDED_SECTION_MAX), leaf,
                page.category(), page.summary(), body, wordMask(body), languages, namesAKind(leaf));
        }

        /**
         * Whether a heading says nothing but what kind of section it is ("Verify", "Common problems", "验证"): every word of
         * it is wording. A question that asks for that kind of section ("verifying Claude Code") is answered by it.
         */
        private static boolean namesAKind(String leaf) {
            if (leaf.isEmpty() || leaf.length() > MAX_KIND_HEADING_CHARS) {
                return false;
            }
            Map<String, Candidate> words = scanMessage(leaf).terms();
            return !words.isEmpty() && words.values().stream().allMatch(Candidate::soft);
        }

        /** The characters that are kept to search in, each distinct string counted once. */
        void collect(Set<String> distinct) {
            distinct.add(title);
            distinct.add(section);
            distinct.add(leaf);
            distinct.add(category);
            distinct.add(summary);
            distinct.add(body);
        }

        /** Whether the reader is shown this chunk: the version in their language, or the only one there is. */
        boolean quotedTo(int reader) {
            return !chunk.isPage() || (languages & reader) == 0 || (chunk.language().equals("zh") ? ZH : EN) == reader;
        }
    }

    /**
     * The best score first. Chunks with the same score are told apart by what their own heading has of the question,
     * so that the section that answers comes before its siblings that only share the heading above them, and after
     * that by their place, which keeps pages in catalog order and puts pages before files.
     */
    private static final Comparator<Scored> BEST_FIRST = Comparator.comparingLong(Scored::score).reversed()
        .thenComparing(Comparator.comparingLong(Scored::own).reversed()).thenComparingInt(Scored::row);

    /** @param own what the chunk's own heading has of the question, to tell it from siblings that have the same words from a heading above */
    private record Scored(Entry entry, int row, long score, long own) {
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
    record Term(String text, double weight, boolean previous, boolean phrase, boolean soft,
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

    /**
     * What one term finds in every chunk (points, before the rarity of the term), and in how many chunks it occurs. The
     * heading and the body of a chunk are counted apart, because the one word of a follow-up counts only the body.
     *
     * @param inHeading    for each chunk: at most 5
     * @param inOwnHeading for each chunk: whether the term is in the chunk's own heading and not only in one above it
     * @param inBody       for each chunk: at most 3
     * @param inPage       for each chunk: at most 6 + 3 + 2; a byte holds each
     */
    private record Column(byte[] inHeading, boolean[] inOwnHeading, byte[] inBody, byte[] inPage, int frequency) {

        static Column none(int rows) {
            return new Column(new byte[rows], new boolean[rows], new byte[rows], new byte[rows], 0);
        }

        static Column of(List<Entry> entries, String word, int[] failure) {
            int rows = entries.size();
            byte[] inHeading = new byte[rows];
            boolean[] inOwnHeading = new boolean[rows];
            byte[] inBody = new byte[rows];
            byte[] inPage = new byte[rows];
            int frequency = 0;
            // The page's title, category and summary are the same strings in all its chunks (see PageFold), and a section's
            // heading is the same in the chunks it is cut into; these follow one another, so each is searched once for the
            // run of chunks that has it, not once for each of up to 2000 chunks.
            Seen leaf = new Seen();
            Seen section = new Seen();
            Seen title = new Seen();
            Seen category = new Seen();
            Seen summary = new Seen();
            for (int row = 0; row < rows; row++) {
                Entry entry = entries.get(row);
                boolean own = leaf.in(entry.leaf(), word, failure);
                int heading = own || section.in(entry.section(), word, failure) ? SECTION_POINTS : 0;
                int body = BODY_POINTS * occurrences(entry.body(), entry.bodyWords(), word, MAX_BODY_HITS, failure);
                int page = (title.in(entry.title(), word, failure) ? TITLE_POINTS : 0)
                    + (category.in(entry.category(), word, failure) ? CATEGORY_POINTS : 0)
                    + (summary.in(entry.summary(), word, failure) ? SUMMARY_POINTS : 0);
                inHeading[row] = (byte) heading;
                inOwnHeading[row] = own;
                inBody[row] = (byte) body;
                inPage[row] = (byte) page;
                if (heading + body + page > 0) {
                    frequency++;
                }
            }
            return new Column(inHeading, inOwnHeading, inBody, inPage, frequency);
        }
    }

    /** Whether a term is in a field of the chunks, kept for the chunks that follow with the same text in it. */
    private static final class Seen {

        private String text;
        private boolean found;

        boolean in(String field, String term, int[] failure) {
            if (text == null || !text.equals(field)) {
                text = field;
                found = contains(field, term, failure);
            }
            return found;
        }
    }

    /**
     * What the terms of one question find in the chunks, term by term and each term once: the question and the follow-up
     * reading of the same message ({@link Plan}) look for the same words of the topic, and a word costs a pass over the
     * text of the whole index, which for a long name is the greater part of the cost of the question. So the cost of a
     * question is that of its distinct terms however many readings of it there are.
     *
     * <p>Of the terms longer than {@link #LINEAR_TERM_CHARS} and the identifiers that could occur at most
     * {@link #MAX_LONG_TERMS} are looked for in all, the readings together (see {@link #searchable}).
     */
    private static final class Lookup {

        private final List<Entry> entries;
        private final Vocabulary vocabulary;
        private final Map<String, Column> columns = new HashMap<>();
        /** The long terms and identifiers ({@link #isLinearTerm}) that the question looks for, whichever reading they belong to. */
        private final Set<String> longTerms = new HashSet<>();
        private final Consumer<String> searched;

        /** @param searched told each term that the chunks are gone through for, which is once however many readings ask for it */
        Lookup(List<Entry> entries, Vocabulary vocabulary, Consumer<String> searched) {
            this.entries = entries;
            this.vocabulary = vocabulary;
            this.searched = searched;
        }

        /** The terms of a reading that the question looks for; see {@link #searchable}. */
        List<Term> searchable(List<Term> terms) {
            return AssistantDocsIndex.searchable(terms, text -> mayOccur(text, vocabulary), longTerms);
        }

        Column column(String text) {
            return columns.computeIfAbsent(text, word -> {
                // A term that is not a word of the documents is found nowhere; looking for it would only cost time.
                if (!mayOccur(word, vocabulary)) {
                    return Column.none(entries.size());
                }
                searched.accept(word);
                // The failure table of the term, made once for the question: see LINEAR_TERM_CHARS.
                return Column.of(entries, word, usesLinearMatcher(word) ? failureTable(word) : null);
            });
        }
    }

    /** What the terms of one reading of a question find, a {@link Column} for each. */
    private record Matches(Column[] columns) {

        static Matches of(List<Term> terms, Lookup lookup) {
            Column[] columns = new Column[terms.size()];
            for (int column = 0; column < columns.length; column++) {
                columns[column] = lookup.column(terms.get(column).text());
            }
            return new Matches(columns);
        }

        /** In how many chunks each term occurs, in the order of the terms. */
        int[] frequency() {
            int[] frequency = new int[columns.length];
            for (int column = 0; column < frequency.length; column++) {
                frequency[column] = columns[column].frequency();
            }
            return frequency;
        }

        /**
         * The chunk whose text says more about the term: the chunk itself or, when it has one, its twin in the other
         * language. Heading and body points are read from that one.
         */
        int better(int row, int twin, int column) {
            return twin >= 0 && heading(twin, column) + body(twin, column) > heading(row, column) + body(row, column)
                ? twin : row;
        }

        /** Like {@link #better}, but for a twin that shares the heading and not the text: the heading says more, or not. */
        int betterHeading(int row, int twin, int column) {
            return twin >= 0 && heading(twin, column) > heading(row, column) ? twin : row;
        }

        /** Points of a term in the headings a chunk sits under, its own included: 5. */
        int heading(int row, int column) {
            return columns[column].inHeading()[row];
        }

        /** Whether the term is in the chunk's own heading and not only in one above it. */
        boolean ownHeading(int row, int column) {
            return columns[column].inOwnHeading()[row];
        }

        /** Points of a term in the body of a chunk: 1 per mention, at most 3. */
        int body(int row, int column) {
            return columns[column].inBody()[row];
        }

        /** Points of a term in the page of a chunk (title 6, category 3, summary 2). */
        int page(int row, int twin, int column) {
            int own = columns[column].inPage()[row];
            return twin >= 0 ? Math.max(own, columns[column].inPage()[twin]) : own;
        }
    }

    /**
     * What the question is worth: the value of each term (its weight times its rarity), and the total that a chunk
     * could cover, which is what chunks are measured against.
     *
     * @param ideal    what the documents know of the question, plus what they lack of it up to as much again
     * @param anchored whether the documents know a term that says what the question is about; a chunk then has to
     *                 contain one, however many of the generic words ("操作", "steps") it has
     * @param topical  whether the documents know a term of the topic that is not soft; without an anchor, a chunk has to
     *                 contain one of those, and what it covers is counted by them alone
     */
    private record Worth(double[] value, double ideal, boolean anchored, boolean topical) {

        static Worth of(List<Term> terms, Matches matches, int rows) {
            double[] value = new double[terms.size()];
            int[] occurring = matches.frequency();
            Map<String, Integer> frequencies = new HashMap<>();
            double strongest = 0;
            boolean anchorKnown = false;
            for (int column = 0; column < value.length; column++) {
                Term term = terms.get(column);
                int frequency = occurring[column];
                value[column] = term.weight() * Math.log(1.0 + (double) rows / (1 + frequency));
                if (!term.previous() && !term.phrase()) {
                    frequencies.put(term.text(), frequency);
                    if (!term.soft() && frequency > 0) {
                        strongest = Math.max(strongest, value[column]);
                        anchorKnown |= term.anchor();
                    }
                }
            }
            // Where the documents know the topic only in weak parts (the pieces of a pasted identifier), a soft term such as
            // the verb of "CODEX_HOME 怎么改" is worth no more than the strongest of them. At its own weight it would
            // outweigh them all and decide which chunks come first.
            if (!anchorKnown && strongest > 0) {
                for (int column = 0; column < value.length; column++) {
                    Term term = terms.get(column);
                    if (!term.previous() && !term.phrase() && term.soft()) {
                        value[column] = Math.min(value[column], strongest);
                    }
                }
            }
            double known = 0;
            double knownTopic = 0;
            double lacked = 0;
            boolean lackedTopic = false;
            boolean anchored = false;
            for (int column = 0; column < value.length; column++) {
                Term term = terms.get(column);
                if (term.previous() || term.phrase()) {
                    continue;
                }
                if (occurring[column] > 0) {
                    known += value[column];
                    knownTopic += term.soft() ? 0 : value[column];
                    anchored |= term.anchor();
                } else if (!isCompound(term.text()) && !term.soft() && !isSeam(term, frequencies)) {
                    // What the question asks for that the documents lack counts too: a word for all of its weight, a
                    // pair of Chinese characters for half, as it may be just the seam between two words. A whole
                    // identifier they never mention says nothing about what they do cover.
                    lackedTopic = true;
                    lacked += isCjk(term.text().charAt(0)) ? value[column] * LACKED_PAIR : value[column];
                }
            }
            // A soft term (the verb of "保险怎么买", a word of wording) only orders the chunks of a topic the documents have.
            // When the topic is one they lack and all they know of the question is such terms, they do not cover it.
            if (knownTopic <= 0 && lackedTopic) {
                return new Worth(value, 0, anchored, false);
            }
            // However much of a pasted log the documents lack, it cannot outweigh what they know of the question.
            double expected = knownTopic > 0 ? knownTopic : known;
            return new Worth(value, Math.min(expected + Math.min(lacked, expected), MAX_IDEAL), anchored, knownTopic > 0);
        }

        /**
         * A pair of Chinese characters that the documents lack and that shares a character with a pair they have is the
         * join between a word and the wording around it ("看用" in "怎么看用量", "额在" in "余额在"), or between two
         * words ("款之" in "付款之后"), not something the question asks for. The last pair of a run that follows wording
         * which asks how is never one (see {@code addPair}): "保险" next to "买保" in "怎么买保险" is the end of a word the
         * documents lack. When the topic comes first, the last pair may be a join, as at any other place.
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
        // "谢谢" after a question is not a follow-up: the excerpts of the question before would only be sent again. Nothing
        // is looked for, and the model is not told that nothing was found either: there was nothing to find.
        if (isClosing(question)) {
            return Retrieval.EMPTY;
        }
        Plan plan = plan(question, earlier, current);
        if (entries.isEmpty() || plan.main() == null || plan.main().terms().isEmpty()) {
            return new Retrieval(List.of(), current.pageTitles());
        }
        int reader = readerOf(question, earlier);
        // The two readings of a message look for the same words of the topic; each word is looked for once.
        Lookup lookup = new Lookup(entries, current.vocabulary(), searchHook);
        Ranking ranking = rank(current, plan.main(), reader, lookup);
        // A short message none of whose words any page has ("再讲讲") is about the question before it.
        if (!ranking.known() && plan.fallback() != null) {
            ranking = rank(current, plan.fallback(), reader, lookup);
        }
        return new Retrieval(select(ranking.chunks()), current.pageTitles());
    }

    /**
     * The language of the version of a page that is quoted. A reader who writes Chinese gets the Chinese version, even
     * when this message is a pasted English error. Otherwise the reader gets English only if the message, or the ones
     * before it, have an ordinary English word. A status code, a file name or an error identifier says nothing about the
     * language: such a message follows the conversation, the latest earlier question that does say, and with nothing
     * to follow it is read in Chinese, the language the pages were written in (the request does not tell the console's).
     */
    private static int readerOf(String question, List<String> earlier) {
        List<String> recent = new ArrayList<>(3);
        recent.add(question);
        earlier.stream().limit(2).forEach(recent::add);
        if (recent.stream().anyMatch(AssistantDocsIndex::containsCjk)) {
            return ZH;
        }
        if (recent.stream().anyMatch(AssistantDocsIndex::hasEnglishWord)) {
            return EN;
        }
        for (String message : earlier.stream().skip(2).toList()) {
            if (containsCjk(message)) {
                return ZH;
            }
            if (hasEnglishWord(message)) {
                return EN;
            }
        }
        return ZH;
    }

    /**
     * Whether the text has a run of letters that is not a number, a status code or an identifier (underscore, dot, or
     * hyphen with a digit). Package-private for the test of the stack it needs.
     */
    static boolean hasEnglishWord(String text) {
        Matcher tokens = TOKEN.matcher(text);
        while (tokens.find()) {
            if (ENGLISH_WORD.matcher(tokens.group()).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Whether the message is nothing but thanks, a greeting or the like; a bare "yes" is not, see {@link #isConsent}. */
    private static boolean isClosing(String message) {
        String folded = fold(message).strip();
        return CLOSING_OR_CONSENT.matcher(folded).matches() && !CONSENT.matcher(folded).matches();
    }

    /** Whether the message is nothing but "好的" or "ok": the answer yes, to the offer of more about the earlier topic. */
    private static boolean isConsent(String folded) {
        return CONSENT.matcher(folded.strip()).matches();
    }

    /** @param known whether the documents have any word of the question at all */
    private record Ranking(List<Scored> chunks, boolean known) {
    }

    /** The chunks that answer the question well enough to be quoted, best first. */
    private static Ranking rank(Snapshot current, Query query, int reader, Lookup lookup) {
        List<Entry> entries = current.entries();
        List<Term> terms = lookup.searchable(query.terms());
        Matches matches = Matches.of(terms, lookup);
        Worth worth = Worth.of(terms, matches, entries.size());
        if (worth.ideal() <= 0) {
            return new Ranking(List.of(), false);
        }
        boolean followUp = query.mode() != Mode.ASKED;
        double[] value = worth.value();
        int[] twins = current.twins();
        int[] headingTwins = current.headingTwins();
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
            // The version in the reader's language is scored together with its twin in the other language: the text with
            // the text it translates, the heading and the page with those of the other version.
            int twin = twins[row];
            int near = headingTwins[row];
            double nowText = 0;
            double nowPage = 0;
            double softText = 0;
            double softPage = 0;
            double kindText = 0;
            double beforeText = 0;
            double beforePage = 0;
            double matched = 0;
            double matchedTopic = 0;
            boolean anchorMatched = false;
            boolean anchorInText = false;
            double ownHeading = 0;
            for (int column = 0; column < terms.size(); column++) {
                int source = matches.better(row, twin, column);
                int headingSource = twin >= 0 ? source : matches.betterHeading(row, near, column);
                int heading = matches.heading(headingSource, column);
                int body = matches.body(source, column);
                int page = matches.page(row, near, column);
                Term term = terms.get(column);
                if (term.previous()) {
                    // In an ordinary search this is the earlier question: what it found in a heading says what that
                    // answer was, not what this is about. In a follow-up it is the message's own word, and counts.
                    int counted = followUp ? heading + body : body;
                    beforeText += value[column] * counted;
                    beforePage += value[column] * page;
                } else {
                    // The same the other way round: when the one word of a follow-up is what the chunk has to contain,
                    // a heading that merely repeats the earlier question is not an answer to it.
                    int counted = query.mode() == Mode.FOLLOW_UP_WORD ? body : heading + body;
                    // The terms that are the topic and the soft ones (a verb, a word of wording) are added up apart: see SOFT_SHARE.
                    if (term.soft() && !term.phrase()) {
                        if (query.mode() != Mode.FOLLOW_UP_WORD && heading > 0 && matches.ownHeading(headingSource, column)
                            && entries.get(headingSource).kindOfSection()) {
                            kindText += value[column] * heading;
                            softText += value[column] * body;
                        } else {
                            softText += value[column] * counted;
                        }
                        softPage += value[column] * page;
                    } else {
                        nowText += value[column] * counted;
                        nowPage += value[column] * page;
                    }
                    if (!term.phrase() && query.mode() != Mode.FOLLOW_UP_WORD && matches.ownHeading(headingSource, column)) {
                        ownHeading += value[column];
                    }
                    if (!term.phrase() && counted + page > 0) {
                        matched += value[column];
                        matchedTopic += term.soft() ? 0 : value[column];
                        anchorMatched |= term.anchor();
                        anchorInText |= counted > 0 && term.anchor();
                    }
                }
            }
            // A chunk whose own text has only wording words ("下一步" in a heading) is on a page that is about the topic,
            // not an answer about it: it counts as a match of the page only.
            double pageShare = anchorInText ? 1.0 : PAGE_ONLY_MATCH;
            // A heading that says only what kind of section it is ("Verify", "Common problems") and is the kind that the
            // question asked for ("verifying Claude Code") answers it as surely as a topic word in the heading would. The
            // wording word counts GENERIC_WEIGHT because it is in a heading of every page and would outweigh the topic; here
            // the topic is in the chunk too, so what the chunk is a section of decides among the sections of the page, which
            // otherwise differ by how often they mention it. Without the topic in its own text ("Next steps" of a page that
            // mentions it once in the summary) the heading is no more than wording.
            if (anchorInText) {
                nowText += kindText * (WORD_WEIGHT / GENERIC_WEIGHT);
            } else {
                softText += kindText;
            }
            double now = nowText + nowPage * pageShare;
            double soft = softText + softPage * pageShare;
            now += worth.topical() ? Math.min(soft, SOFT_SHARE * now) : soft;
            double before = beforeText + (beforeText > 0 ? beforePage : beforePage * PAGE_ONLY_MATCH);
            // The question has to match on its own words, and on one that says what it is about, not only on words like
            // "操作" or "steps" that sit in a heading of every page. Where the documents know the topic but nothing heavy
            // enough to be an anchor (the parts of a pasted identifier), the verb or the wording must not stand in for it:
            // a chunk has to have a word of the topic, and is measured by those words alone.
            double basis = worth.topical() && !worth.anchored() ? matchedTopic : matched;
            if (basis <= 0 || (worth.anchored() && !anchorMatched)) {
                continue;
            }
            double covered = Math.min(1.0, basis / worth.ideal());
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
                pool.add(new Scored(entry, row, Math.round((topic + before) * 1_000_000), Math.round(ownHeading * 1_000_000)));
                topics.add(topic);
                continue;
            }
            // The earlier question only decides among the chunks the question itself found.
            double score = (now + Math.min(before, now)) * covered;
            if (score / worth.ideal() < MIN_FIT) {
                continue;
            }
            // Scores that differ by rounding noise are ties; ties go to the chunk whose own heading has the words, then to
            // the earlier chunk (pages before files).
            ranked.add(new Scored(entry, row, Math.round(score * 1_000_000), Math.round(ownHeading * 1_000_000)));
        }
        for (int index = 0; index < pool.size(); index++) {
            if (topics.get(index) >= bestTopic * TOPIC_SHARE) {
                ranked.add(pool.get(index));
            }
        }
        ranked.sort(BEST_FIRST);
        return new Ranking(ranked, true);
    }

    /**
     * The terms of a query that are looked for: all of them, except that of the terms that could occur and are longer than
     * {@link #LINEAR_TERM_CHARS} or are identifiers only {@link #MAX_LONG_TERMS} are kept, the heaviest first and, of equal weight, the
     * earlier in the query. A term that is left out is not part of the question any more, not one that the documents lack.
     */
    static List<Term> searchable(List<Term> terms, Predicate<String> mayOccur) {
        return searchable(terms, mayOccur, new HashSet<>());
    }

    /**
     * The same for a question that is read more than once (the message, and the follow-up reading of it): the limit holds
     * for the question, not for each reading. {@code admitted} holds the long terms of the earlier readings, which are
     * kept, since looking for them again costs nothing, and takes the long terms of this one that are kept besides, up to
     * the limit.
     */
    static List<Term> searchable(List<Term> terms, Predicate<String> mayOccur, Set<String> admitted) {
        List<Integer> newTerms = new ArrayList<>();
        for (int column = 0; column < terms.size(); column++) {
            String text = terms.get(column).text();
            if (isLinearTerm(text) && !admitted.contains(text) && mayOccur.test(text)) {
                newTerms.add(column);
            }
        }
        int room = Math.max(0, MAX_LONG_TERMS - admitted.size());
        if (newTerms.size() <= room) {
            newTerms.forEach(column -> admitted.add(terms.get(column).text()));
            return terms;
        }
        newTerms.sort(Comparator.comparingDouble((Integer column) -> terms.get(column).weight()).reversed()
            .thenComparingInt(Integer::intValue));
        newTerms.subList(0, room).forEach(column -> admitted.add(terms.get(column).text()));
        Set<Integer> left = new HashSet<>(newTerms.subList(room, newTerms.size()));
        List<Term> kept = new ArrayList<>(terms.size() - left.size());
        for (int column = 0; column < terms.size(); column++) {
            if (!left.contains(column)) {
                kept.add(terms.get(column));
            }
        }
        return kept;
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
        Set<String> sectionsTaken = new HashSet<>();
        int budget = EXCERPT_BUDGET;
        // Two chunks of a page, then a third; in each of them a section that has none yet before a second piece of one that has:
        // the pieces of a long section are one answer cut for size, and the second piece of it is not another answer. The last
        // pass fills what is left of the pages' three with second pieces.
        for (int pass = 0; pass <= MAX_PER_SOURCE - PREFERRED_PER_SOURCE + 1 && picked.size() < MAX_RESULTS; pass++) {
            int allowed = Math.min(PREFERRED_PER_SOURCE + pass, MAX_PER_SOURCE);
            boolean furtherPieces = pass > MAX_PER_SOURCE - PREFERRED_PER_SOURCE;
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
                String section = source + '\u0000' + chunk.language() + '\u0000' + chunk.section();
                if (!furtherPieces && sectionsTaken.contains(section)) {
                    skipped = Math.max(skipped, candidate.score());
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
                sectionsTaken.add(section);
                perSource.merge(source, 1, Integer::sum);
                budget -= chunk.cost();
            }
        }
        picked.sort(BEST_FIRST);
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
        Scanned now = scanMessage(question, current.vocabulary());
        Scanned topic = null;
        for (String message : earlier) {
            Scanned candidate = scanMessage(message, current.vocabulary());
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
        String folded = fold(message);
        // A bare "好的" has no words of its own, and is about the topic before it; "好的，" at the head of a question is no
        // more than a way to begin, and "好" must not become the verb that the question asks of its topic.
        String text = isConsent(folded) ? "" : OPENING.matcher(folded).replaceFirst("");
        return scan(CJK_FILLER.matcher(text).replaceAll(wording ->
            VERB_FIRST_WORDING.contains(wording.group()) ? String.valueOf(VERB_FIRST_MARK) : " "), MAX_CANDIDATE_TERMS, true);
    }

    /**
     * The message with each English word that the documents do not have as such put in the form they do have. A participle
     * of a word of wording that they lack ("viewed", "caused", "detailed") is that word of wording, as "viewing" is: the
     * rule that a participle is no wording protects the labels the documents really use ("Resolved"), and says nothing
     * about one they do not have. Left to the base-form lookup it would become the topic word "view" at full weight.
     */
    private static Scanned scanMessage(String message, Vocabulary vocabulary) {
        Scanned scanned = scanMessage(message);
        Map<String, Candidate> terms = new LinkedHashMap<>();
        boolean demoted = false;
        for (Map.Entry<String, Candidate> entry : scanned.terms().entrySet()) {
            Candidate candidate = entry.getValue();
            String wording = candidate.soft() ? null : wordingFormOf(entry.getKey(), vocabulary);
            if (wording == null) {
                terms.merge(baseFormIn(entry.getKey(), vocabulary), candidate, AssistantDocsIndex::merged);
            } else {
                demoted = true;
                terms.merge(wording, new Candidate(GENERIC_WEIGHT, candidate.position(), true, candidate.adjacent()),
                    AssistantDocsIndex::merged);
            }
        }
        boolean content = scanned.content();
        if (demoted) {
            // The word that made the message a question about a topic may have been the one that is wording after all.
            content = false;
            for (Candidate candidate : terms.values()) {
                content |= candidate.weight() >= ANCHOR_WEIGHT && !candidate.soft();
            }
        }
        return new Scanned(terms, scanned.phrases(), content, scanned.lone(), scanned.latin(), scanned.size());
    }

    /** The word of wording that an English word with an ending comes from, when the documents lack the word itself. */
    private static String wordingFormOf(String word, Vocabulary vocabulary) {
        if (vocabulary.count(word) > 0) {
            return null;
        }
        for (String form : baseForms(word)) {
            if (GENERIC_WORDS.contains(form)) {
                return form;
            }
        }
        return null;
    }

    /**
     * A question word with an ending is looked for in the form the documents use. The document side already lets a word
     * carry s, es, d, ed or ing, so the stem finds the word that was asked and its other forms too: "opened" is looked for
     * as "open" and finds "opening". A word that the documents have stays as it is, unless its stem is in more of their
     * chunks ("settings" stays, because "sett" is no word of theirs). A word they do not have is looked for as the first
     * of its possible base forms that they do have: "publishing" as "publish", "creating" as "create".
     */
    private static String baseFormIn(String word, Vocabulary vocabulary) {
        List<String> forms = baseForms(word);
        if (forms.isEmpty()) {
            return word;
        }
        int known = vocabulary.count(word);
        // Never a stop word: "noted" is not "not", although the documents have that word everywhere.
        if (known > 0) {
            String stem = forms.getFirst();
            return !STOP_WORDS.contains(stem) && vocabulary.count(stem) > known ? stem : word;
        }
        for (String form : forms) {
            if (vocabulary.contains(form) && !STOP_WORDS.contains(form)) {
                return form;
            }
        }
        return word;
    }

    private static boolean isPlainWord(String word) {
        if (word.length() < 4) {
            return false;
        }
        for (int index = 0; index < word.length(); index++) {
            if (word.charAt(index) < 'a' || word.charAt(index) > 'z') {
                return false;
            }
        }
        return true;
    }

    /**
     * The words an English word with an ending may come from, the likeliest first: the stem ("opened" from "open",
     * "applied" from "apply"), the stem without a doubled last letter ("running" from "run"), and the stem with the "e"
     * that the ending replaced ("using" from "use"). A word without an ending has none.
     */
    static List<String> baseForms(String word) {
        List<String> forms = new ArrayList<>(3);
        if (!isPlainWord(word)) {
            return forms;
        }
        String stem = null;
        String restored = null;
        if (word.endsWith("ing") && word.length() >= 6) {
            stem = word.substring(0, word.length() - 3);
            restored = stem + "e";
        } else if (word.endsWith("ied") && word.length() >= 5) {
            forms.add(word.substring(0, word.length() - 3) + "y");
            stem = word.substring(0, word.length() - 2);
        } else if (word.endsWith("ed") && word.length() >= 5) {
            stem = word.substring(0, word.length() - 2);
            restored = word.substring(0, word.length() - 1);
        }
        if (stem != null) {
            forms.add(stem);
            if (stem.length() >= 3 && stem.charAt(stem.length() - 1) == stem.charAt(stem.length() - 2)) {
                forms.add(stem.substring(0, stem.length() - 1));
            }
        }
        if (restored != null) {
            forms.add(restored);
        }
        return forms;
    }

    /**
     * Whether the word is one of the words of wording, or the -ing form of one ("fixing 401"). A participle is not, as
     * far as this can tell: the documents use it as a label or a status ("Resolved"), which a question may be about. It
     * is told from the vocabulary, see {@link #scanMessage(String, Vocabulary)}.
     */
    private static boolean isWording(String word) {
        if (GENERIC_WORDS.contains(word)) {
            return true;
        }
        if (!word.endsWith("ing")) {
            return false;
        }
        for (String form : baseForms(word)) {
            if (GENERIC_WORDS.contains(form)) {
                return true;
            }
        }
        return false;
    }

    /** At most {@code limit} of the terms, in their order of appearance. */
    private static Map<String, Candidate> keep(Map<String, Candidate> terms, int limit, Snapshot current) {
        if (terms.size() <= limit) {
            return terms;
        }
        // A word no document contains cannot match anything, so it goes first; the others count by weight and
        // rarity, and by being later in the message when those are equal.
        Vocabulary vocabulary = current.vocabulary();
        int total = Math.max(1, current.entries().size());
        List<Map.Entry<String, Candidate>> ranked = new ArrayList<>();
        for (Map.Entry<String, Candidate> entry : terms.entrySet()) {
            if (vocabulary.contains(entry.getKey())) {
                ranked.add(entry);
            }
        }
        ranked.sort(Comparator
            .comparingDouble((Map.Entry<String, Candidate> entry) ->
                entry.getValue().weight() * Math.log(1.0 + (double) total / (1 + vocabulary.count(entry.getKey())))).reversed()
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
        // The run of word characters, underscores, dots and hyphens that the current word is cut from, found once: a run
        // of many hyphenated words is walked word by word within it, not measured again from each word to its end.
        int runEnd = -1;
        boolean runHasDigit = false;
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
                if (index >= runEnd) {
                    int stop = index;
                    while (stop < length) {
                        char value = folded.charAt(stop);
                        if (isWordChar(value) || value == '_'
                            || ((value == '.' || value == '-') && stop + 1 < length && isWordChar(folded.charAt(stop + 1)))) {
                            stop++;
                        } else {
                            break;
                        }
                    }
                    runEnd = stop;
                    runHasDigit = hasDigit(folded, index, stop);
                }
                int end = runEnd;
                if (!runHasDigit) {
                    // Only a name with a digit in it is one identifier ("gpt-5.5"); "built-in" is two words. A run
                    // without a digit has none anywhere, so the words after the first hyphen are cut the same way.
                    int hyphen = folded.indexOf('-', index, end);
                    if (hyphen >= 0) {
                        end = hyphen;
                    }
                }
                int tokenEnd = end;
                while (tokenEnd > index + 1 && folded.charAt(tokenEnd - 1) == '_') {
                    tokenEnd--;
                }
                if (question && tokenEnd - index > MAX_TERM_CHARS) {
                    // Not looked for, and no phrase is made of it or across it: see MAX_TERM_CHARS.
                    lastWord = null;
                    afterStatus = false;
                    index = end;
                    continue;
                }
                String token = folded.substring(index, tokenEnd);
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
                    boolean wording = question && isWording(word);
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
        // A lone character is a weak term. With nothing else in the question it stands in for the topic ("那怎么改？"):
        // one such character is a follow-up's word, several ("扣了这么多钱": 扣, 钱) are what the question is about. Next
        // to a topic it is the verb of "文件怎么删", which leaves one character once 怎么 is gone, and says which chunks of
        // the topic answer; as a soft term it only orders them, no chunk needs it and the documents may lack it.
        boolean loneOnly = false;
        Map<String, Candidate> weak = new LinkedHashMap<>(lone);
        weak.putAll(loose);
        if (!content) {
            terms.putAll(weak);
            content = weak.size() >= 2;
            loneOnly = !content && !weak.isEmpty();
        } else if (question) {
            for (Map.Entry<String, Candidate> verb : weak.entrySet()) {
                if (terms.size() < limit && GENERAL_VERBS.indexOf(verb.getKey().charAt(0)) < 0) {
                    terms.merge(verb.getKey(), new Candidate(VERB_WEIGHT, verb.getValue().position(), true, Set.of()),
                        AssistantDocsIndex::merged);
                }
            }
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
        // Which pairs a pair may be the join of: the pair before it and the pair after it, each of which the documents may
        // have as a word of their own. After wording that asks how ("怎么买保险") the verb stands in front of its topic, so
        // the first pair of the run joins the verb to the topic and the last one is certainly the end of the topic: it is a
        // word the documents may lack, not the join of the word before it ("保险" after "买保"), and a pair that joins nothing
        // is not forgiven. Anywhere else the topic may come first ("接口选哪个", "超时填多少"), and then the last pair is the
        // join of the topic and a verb of one character ("口选", "时填"), forgiven like any other.
        boolean afterVerbFirst = runStart > 0 && folded.charAt(runStart - 1) == VERB_FIRST_MARK;
        Set<String> adjacent = new HashSet<>(2);
        if (at > runStart && (at + 2 < runEnd || !afterVerbFirst)) {
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
        terms.merge(text, new Candidate(weight, position, soft, adjacent), AssistantDocsIndex::merged);
    }

    /** The same term found twice counts once, for the better of its weights and as soft only if both were. */
    private static Candidate merged(Candidate old, Candidate added) {
        return new Candidate(Math.max(old.weight(), added.weight()), Math.min(old.position(), added.position()),
            old.soft() && added.soft(), union(old.adjacent(), added.adjacent()));
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
        return hasDigit(text, 0, text.length());
    }

    private static boolean hasDigit(String text, int from, int to) {
        for (int index = from; index < to; index++) {
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

    /**
     * A term that counts against {@link #MAX_LONG_TERMS}: one longer than {@link #LINEAR_TERM_CHARS}, and any identifier.
     * The vocabulary rules out a word that no page has, but can say little about an identifier (see {@link #partsAreWords}:
     * nothing of its single characters, and a page may list the rest), so its length says nothing of what looking for it
     * costs: a name of 15 characters made of one letter and underscores is found at every other character of a text that
     * is made of them.
     */
    private static boolean isLinearTerm(String term) {
        return term.length() > LINEAR_TERM_CHARS || isCompound(term);
    }

    /**
     * Whether a term is looked for with the linear matcher: every term but a pair or a single character of ideographs, which
     * the string search compares two characters at a time at most. The vocabulary says only that some page has a word, not
     * that the text of the others lacks its beginning, and a page can list the words: 31 words of 16 letters that differ in the
     * last one are all words of the index, and the string search compares 16 characters at every place of a text made of runs
     * of that letter, for each of them. The linear matcher costs about the same as the string search on text of any other kind.
     */
    static boolean usesLinearMatcher(String term) {
        return !isCjk(term.charAt(0)) || isLinearTerm(term);
    }

    private static boolean contains(String text, String term, int[] failure) {
        return occurrences(text, null, term, 1, failure) > 0;
    }

    /** Marks the word characters (see {@link #isWordChar}) of a text, one bit each, so that a run of them is crossed 64 at a time. */
    static BitSet wordMask(String text) {
        BitSet mask = new BitSet(text.length());
        for (int index = 0; index < text.length(); index++) {
            if (isWordChar(text.charAt(index))) {
                mask.set(index);
            }
        }
        return mask;
    }

    /**
     * Where the run of word characters that contains {@code from} ends: the index of the first character at or after
     * it that is not one, or the length of the text.
     *
     * @param words the {@link #wordMask} of the text, or null to look at the characters
     */
    private static int endOfWordRun(String text, BitSet words, int from) {
        if (words != null) {
            return Math.min(words.nextClearBit(from), text.length());
        }
        int end = from;
        while (end < text.length() && isWordChar(text.charAt(end))) {
            end++;
        }
        return end;
    }

    /**
     * Where the run of word characters and underscores that contains {@code from} ends: the part of an identifier that no
     * whole identifier can begin inside of.
     */
    private static int endOfNameRun(String text, BitSet words, int from) {
        int end = from;
        while (true) {
            end = endOfWordRun(text, words, end);
            if (end >= text.length() || text.charAt(end) != '_') {
                return end;
            }
            end++;
        }
    }

    /**
     * How often the term occurs, counting up to {@code limit}. A Latin word has to be a word of the text, possibly
     * with an ending such as "s" or "ed": "key" matches "keys", but "ai" does not match "email" and "code" does not
     * match "codex". A whole identifier has to stand alone: "api_key" is not found inside "api_key_id".
     *
     * <p>A hit that is not a match could be one at any of the next characters of a run of letters ("aa" in a hundred
     * "a"), and trying each of them would cost the length of the run for every term of the question in every chunk. No
     * word starts inside a run, so the search goes on after it.
     *
     * @param words the {@link #wordMask} of the text, which makes crossing a long run cheap; null for a short text
     */
    static int occurrences(String text, BitSet words, String term, int limit) {
        return occurrences(text, words, term, limit, null);
    }

    /**
     * @param failure the {@link #failureTable} of the term, to find it in time linear in the length of the text; null to
     *                use {@link String#indexOf}, which is faster for a short term but, where the text is a long run of
     *                the term's first letters, takes the length of the text times that of the term
     */
    static int occurrences(String text, BitSet words, String term, int limit, int[] failure) {
        if (text.isEmpty()) {
            return 0;
        }
        boolean latin = !isCjk(term.charAt(0));
        boolean whole = latin && isCompound(term);
        if (failure != null) {
            return latin && isSingleWord(term)
                ? wordOccurrences(text, words, term, limit)
                : linearOccurrences(text, words, term, failure, latin, whole, limit);
        }
        int found = 0;
        int from = 0;
        while (found < limit) {
            int at = text.indexOf(term, from);
            if (at < 0) {
                break;
            }
            if (latin && (startsInsideWord(text, at, whole) || !endsWord(text, at, term, whole))) {
                from = endOfWordRun(text, words, at) + 1;
                continue;
            }
            found++;
            from = at + term.length();
        }
        return found;
    }

    /** Whether a Latin term found at {@code at} begins in the middle of a word (or of an identifier, if it is one). */
    private static boolean startsInsideWord(String text, int at, boolean whole) {
        return at > 0 && (isWordChar(text.charAt(at - 1)) || (whole && text.charAt(at - 1) == '_'));
    }

    /** Whether a Latin term found at {@code at} ends a word (possibly with an ending), or is a whole identifier. */
    private static boolean endsWord(String text, int at, String term, boolean whole) {
        int end = at + term.length();
        return whole
            ? end >= text.length()
                || !(isWordChar(text.charAt(end)) || text.charAt(end) == '_' || continuesName(text, end, term))
            : wordEndsAt(text, end);
    }

    /**
     * The same count in one pass over the text, whatever the text is (Knuth, Morris and Pratt), for a phrase, an identifier
     * or a term of any other shape; a term that is one word has {@link #wordOccurrences}. It finds what
     * {@link #occurrences} finds with the string search: every place where the term is found that starts a word and ends
     * one, those that begin inside the previous match passed over.
     *
     * <p>Whatever the text is written in, a character is looked at once or, where nothing is begun, not at all:
     * <ul>
     * <li>while no partial match is alive the search jumps to the next place where the term's first character is
     * ({@link String#indexOf(int, int)}), so that a text without it, or with it rarely, is crossed at the speed of that
     * search and no character of it costs a test of what kind it is;
     * <li>a match that begins inside a word is no match, so no partial match is kept that began there, and once none is
     * alive the rest of the word is crossed in one step (by the mask of the text, if it has one): a long run of letters
     * costs the number of its words, not its length. For a whole identifier the run is that of word characters and
     * underscores, since nothing can begin after either: "a_a_a_a_…" is crossed in one step as well.
     * </ul>
     */
    private static int linearOccurrences(String text, BitSet words, String term, int[] failure, boolean latin,
        boolean whole, int limit) {
        int length = term.length();
        char first = term.charAt(0);
        int found = 0;
        int free = 0;
        int matched = 0;
        for (int index = 0; index < text.length(); index++) {
            if (matched == 0) {
                index = text.indexOf(first, index);
                if (index < 0) {
                    break;
                }
            }
            char value = text.charAt(index);
            while (matched > 0
                && (term.charAt(matched) != value || (latin && startsInsideWord(text, index - matched, whole)))) {
                matched = failure[matched - 1];
            }
            if (value == term.charAt(matched) && (matched > 0 || !latin || !startsInsideWord(text, index, whole))) {
                matched++;
            }
            if (matched == length) {
                int at = index - length + 1;
                matched = failure[length - 1];
                if (at >= free && (!latin || endsWord(text, at, term, whole))) {
                    if (++found >= limit) {
                        break;
                    }
                    free = at + length;
                }
            } else if (matched == 0 && latin
                && ((words != null ? words.get(index) : isWordChar(value)) || (whole && value == '_'))) {
                index = (whole ? endOfNameRun(text, words, index) : endOfWordRun(text, words, index)) - 1;
            }
        }
        return found;
    }

    /**
     * The same count for a term that is one word (see {@link #isSingleWord}), in time linear in the text as well. A word of
     * the text begins at the start of a run of word characters and nowhere inside one, so a place that is not the start of a
     * run is no match, and neither is a start where the term is not found: the rest of that run is crossed in one step
     * whether the term was found there or not, and a run costs at most the length of the term whatever its own length is.
     */
    private static int wordOccurrences(String text, BitSet words, String term, int limit) {
        char first = term.charAt(0);
        int found = 0;
        int from = 0;
        while (found < limit) {
            int at = text.indexOf(first, from);
            if (at < 0) {
                break;
            }
            if (!startsInsideWord(text, at, false) && text.startsWith(term, at) && wordEndsAt(text, at + term.length())) {
                found++;
            }
            from = endOfWordRun(text, words, at);
        }
        return found;
    }

    /** Whether a term is one word: nothing but letters and digits (see {@link #isWordChar}), so that it cannot span two words. */
    private static boolean isSingleWord(String term) {
        for (int index = 0; index < term.length(); index++) {
            if (!isWordChar(term.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /** For each prefix of the term, the length of its longest proper prefix that is also its suffix. */
    static int[] failureTable(String term) {
        int[] failure = new int[term.length()];
        int matched = 0;
        for (int index = 1; index < term.length(); index++) {
            while (matched > 0 && term.charAt(index) != term.charAt(matched)) {
                matched = failure[matched - 1];
            }
            if (term.charAt(index) == term.charAt(matched)) {
                matched++;
            }
            failure[index] = matched;
        }
        return failure;
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

    /** {@link #foldForSearch} cut to at most {@code max} characters, never inside a surrogate pair. */
    private static String foldedWithin(String text, int max) {
        String folded = foldForSearch(text);
        if (folded.length() <= max) {
            return folded;
        }
        int end = max;
        if (Character.isHighSurrogate(folded.charAt(end - 1))) {
            end--;
        }
        return folded.substring(0, end);
    }

    private static boolean isWordChar(char value) {
        if (value < 0x80) {
            return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') || (value >= '0' && value <= '9');
        }
        return Character.isLetterOrDigit(value) && !isCjk(value);
    }

    private static boolean containsCjk(String text) {
        return text != null && text.chars().anyMatch(value -> isCjk((char) value));
    }

    /**
     * The ideographs: the blocks CJK Unified Ideographs and CJK Compatibility Ideographs. Two comparisons, not
     * {@link Character.UnicodeBlock#of}, which is a binary search and costs some twenty times as much per character; this
     * is asked of every character of the text the matchers go through.
     */
    static boolean isCjk(char value) {
        return (value >= 0x4E00 && value <= 0x9FFF) || (value >= 0xF900 && value <= 0xFAFF);
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
