package tech.starryapi.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;
import tech.starryapi.assistant.AssistantDocsIndex.Chunk;
import tech.starryapi.assistant.AssistantDocsIndex.Retrieval;
import tech.starryapi.documentation.DocumentationDtos.Document;

/**
 * The standard the assistant's retrieval is measured against: typical questions of people using the console, each a
 * conversation of one or two user messages, asked of the tutorials that a fresh installation publishes (V23 and V24, read
 * by {@link SeededTutorials}). A case names the page that has to be among the first three excerpts, and where it
 * matters the section; the page has to be cited in the sources, in the language the reader writes. A pasted error
 * identifier has to bring its page first. A question the tutorials do not cover has to retrieve nothing, so that the
 * assistant is handed the list of pages and says it is not sure instead of being given a page that only sounds alike,
 * whichever side of the topic the verb of the question stands on. A follow-up that changes the topic has to leave the
 * page of the first question out of the excerpts and the sources. The few questions that are known to fail are not
 * dropped: they are listed apart in {@link #knownGaps()}.
 *
 * <p>The cases are everyday wording, not the wording of the tutorials, because that is what people type. When the
 * retrieval is changed they are the check that a better rule for one question has not made another worse; they are not
 * to be bent to fit, and a rule that only some case needs does not belong in the retrieval.
 */
class AssistantDocsBenchmarkTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int EXCERPTS_THAT_COUNT = 3;
    private static final List<Document> PAGES = SeededTutorials.pages();
    private static final AssistantDocsIndex INDEX = new AssistantDocsIndex(Path.of("does-not-exist"),
        () -> Mono.just(PAGES), new AssistantDocsIndexTest.TestClock());

    /**
     * @param conversation the user's messages, the oldest first; the last one is the question
     * @param page         the page that has to be retrieved; null when the tutorials do not cover the question
     * @param section      part of the heading of the section that has to be among the excerpts; null when any will do
     * @param language     the language of the page that is quoted and cited
     * @param first        whether the page has to come first, not just among the first three
     * @param notCited     a page that must be neither among the excerpts nor in the sources: the topic that a follow-up
     *                     leaves, or the page that only a stray word of the message would bring in; null when none
     */
    record Case(List<String> conversation, String page, String section, String language, boolean first, String notCited) {

        @Override
        public String toString() {
            return String.join(" → ", conversation) + (page == null ? " (not covered)" : " (" + page + ")");
        }

        String question() {
            return conversation.getLast();
        }

        List<String> earlier() {
            List<String> latestFirst = new ArrayList<>(conversation.subList(0, conversation.size() - 1));
            Collections.reverse(latestFirst);
            return latestFirst;
        }
    }

    private static Case zh(String question, String page, String section) {
        return new Case(List.of(question), page, section, "zh", false, null);
    }

    private static Case en(String question, String page, String section) {
        return new Case(List.of(question), page, section, "en", false, null);
    }

    /** A pasted identifier: its page has to be the first thing quoted. */
    private static Case exact(String question, String page, String section) {
        return new Case(List.of(question), page, section, "zh", true, null);
    }

    private static Case followUp(String first, String second, String page, String section) {
        return new Case(List.of(first, second), page, section, "zh", false, null);
    }

    /** The follow-up changes the topic: the page of the first question must not be quoted or cited any more. */
    private static Case switchTo(String first, String second, String page, String left) {
        return new Case(List.of(first, second), page, null, "zh", true, left);
    }

    private static Case uncovered(String... conversation) {
        return new Case(List.of(conversation), null, null, null, false, null);
    }

    static List<Case> cases() {
        return List.of(
            // Chinese, in everyday wording
            zh("怎么充值", "billing", "在线充值"),
            zh("余额怎么扣", "billing", "余额是怎么扣的"),
            zh("兑换码在哪里用", "billing", "使用兑换码"),
            zh("付了款余额没变怎么回事", "billing", "付了款但余额没变"),
            zh("密钥怎么删", "api-keys", "列表里的操作"),
            zh("工单怎么关", "tickets", "状态的含义"),
            zh("怎么改密码", "faq", "怎么修改密码"),
            zh("密钥泄露了咋办", "faq", "怀疑密钥泄露了"),
            zh("403 怎么解决", "faq", "提示 403"),
            zh("401 怎么解决", "faq", "提示 401"),
            zh("402 怎么解决", "billing", "两种计费途径"),
            zh("429 啥意思", "faq", "429，请求太频繁"),
            zh("503 怎么回事", "errors", "503 的几种情况"),
            zh("待定价是什么意思", "models-and-pricing", "待定价"),
            zh("Coding Plan 额度用完了能不能扣余额", "coding-plan", "额度用完了"),
            zh("额度用完了会不会自动扣余额", "coding-plan", "需要知道的规则"),
            new Case(List.of("好的，那怎么删除密钥"), "api-keys", "列表里的操作", "zh", true, "tickets"),
            zh("一个密钥能不能同时用 Claude 和 GPT", "api-keys", "模型分类"),
            zh("累计调用在哪里看", "console-tour", "数据统计怎么读"),
            zh("怎么查看当前余额", "console-tour", null),
            zh("怎么配置 Claude Code", "claude-code", null),
            zh("Codex 提示 refresh token 怎么办", "codex-cli", "常见问题"),
            zh("Base URL 要不要带 /v1", "faq", "Base URL"),
            zh("CC Switch 打不开怎么办", "cc-switch", "浏览器没有打开"),
            zh("Python 怎么调用", "manual-api", "在 Python 中调用"),
            zh("连接有问题", "faq", "连接失败"),
            // The topic first and a verb of one character after it
            zh("协议选哪个", "manual-api", "各接口的地址"),
            zh("请求头填什么", "manual-api", "认证方式"),
            zh("模型名填什么", "manual-api", "准备地址与密钥"),
            zh("截图传不上去", "tickets", "提交一张工单"),
            // The title of the page has 订阅, so any retrieval at all would put the page among the first three: the section
            // that tells how to get a plan (and where its validity is shown) is what the case is about.
            zh("订阅能用多久", "coding-plan", "开通订阅"),
            exact("SYSTEM_MAINTENANCE", "errors", "503 的几种情况"),
            exact("config.toml", "codex-cli", null),
            // A pasted name the tutorials do not have, with a verb of one character: its parts are the topic, the verb only
            // orders the chunks that have one, and the sections about passwords or the wallet are not an answer.
            exact("API_KEY_INVALID 怎么改", "faq", "提示 401"),
            new Case(List.of("config.yaml 怎么改"), "codex-cli", "config.toml", "zh", false, "getting-started"),
            zh("401", "faq", "提示 401"),
            // English
            en("redeeming a code", "billing", "Redeem a code"),
            en("how am I billed", "billing", null),
            en("verifying Claude Code", "claude-code", "Verify"),
            en("Connection problems", "faq", "Connection failure"),
            en("How do I delete an API key?", "api-keys", "Actions in the list"),
            en("changing my password", "faq", "change my password"),
            en("How do I sign out?", "console-tour", "Top bar"),
            en("What does 429 mean?", "faq", "429, too many requests"),
            en("How do I subscribe to a Coding Plan?", "coding-plan", "Activate a plan"),
            new Case(List.of("What does the Resolved status mean?"), "tickets", "What the statuses mean", "en", true, null),
            en("Where can I see model prices?", "models-and-pricing", "Where to see models"),
            // A participle of a word of wording that the tutorials lack is wording, not the topic word "cause" or "view".
            en("What caused my 401?", "faq", "I get 401"),
            new Case(List.of("Where is my balance viewed?"), "billing", null, "en", false, "tickets"),
            en("How many attachments can I add to a ticket?", "tickets", "Submit a ticket"),
            // Follow-ups: the second message stays on the topic of the first, or changes it
            followUp("怎么创建 API Key", "那怎么改？", "api-keys", "列表里的操作"),
            followUp("怎么充值", "具体步骤呢？", "billing", "在线充值"),
            followUp("Claude Code 报错 401", "还是不行", "claude-code", "常见问题"),
            followUp("怎么充值", "可以", "billing", "在线充值"),
            switchTo("怎么配置 Claude Code", "那 Codex 呢？", "codex-cli", "claude-code"),
            new Case(List.of("How do I set up Claude Code?", "401"), "claude-code", "Common problems", "en", false, null),
            // Not covered by the tutorials: the verb before the topic, after it, and in English
            uncovered("怎么开发票"),
            uncovered("发票怎么开"),
            uncovered("怎么申请退款"),
            uncovered("怎么导出请求日志"),
            uncovered("How do I invite a teammate?")
        );
    }

    /**
     * Questions about something the tutorials do not have that the retrieval still answers with a page that only
     * sounds alike. They are not dropped from the standard, only listed apart, so that a passing benchmark does not
     * rest on questions chosen to pass: keyword retrieval cannot tell them from a real match, because every word of them
     * is in a page (the words of "request logs" in one table, "修改" in the page on the password) and it has no way to
     * tell that the words do not belong together. The test below fails as soon as one of them is answered correctly, so
     * that it is moved up into {@link #cases()}. One of them is a question the tutorials do answer, whose page keyword
     * retrieval does not put among the first three any more (see below).
     */
    static List<Case> knownGaps() {
        return List.of(
            uncovered("How do I view the request logs?"),
            uncovered("怎么修改昵称"),
            uncovered("Can I change my username?"),
            // Without a word that asks how in front of it, "开发票" cannot be told from a topic and a verb ("协议选"): the
            // last pair is the join of the two, so the page that has "开发" is found.
            uncovered("能开发票吗"),
            // "put" is a word of no meaning in a question ("where do I put it"), so what is left is "api key" and "codex", and
            // the pages about API keys outweigh the one about Codex, which is the 4th. Before "put" was dropped the page was
            // 2nd, through the sentence "do not put it in a project's .codex/config.toml": by chance, not by the question.
            en("Where do I put the API key in Codex?", "codex-cli", null)
        );
    }

    static Stream<Case> caseStream() {
        return cases().stream();
    }

    /** Why a case fails, or null when it passes. */
    static String verdict(Case benchmark) {
        Retrieval retrieval = INDEX.retrieveFor(benchmark.question(), benchmark.earlier()).block(TIMEOUT);
        List<Chunk> chunks = retrieval.chunks();
        if (benchmark.page() == null) {
            return chunks.isEmpty() && retrieval.sources().isEmpty() && !retrieval.pageTitles().isEmpty()
                ? null : "expected nothing, got " + describe(chunks);
        }
        List<Chunk> counted = chunks.subList(0, Math.min(chunks.size(), benchmark.first() ? 1 : EXCERPTS_THAT_COUNT));
        boolean found = counted.stream().anyMatch(chunk -> chunk.pageId().equals(benchmark.page())
            && (benchmark.section() == null || chunk.section().contains(benchmark.section())));
        if (!found) {
            return "expected " + benchmark.page() + (benchmark.section() == null ? "" : " § " + benchmark.section())
                + " in the first " + counted.size() + ", got " + describe(chunks);
        }
        String source = source(benchmark.page(), benchmark.language());
        if (!retrieval.sources().contains(source)) {
            return "expected the source " + source + ", got " + retrieval.sources();
        }
        if (benchmark.notCited() != null) {
            String left = source(benchmark.notCited(), benchmark.language());
            if (chunks.stream().anyMatch(chunk -> benchmark.notCited().equals(chunk.pageId())) || retrieval.sources().contains(left)) {
                return "expected no " + benchmark.notCited() + " at all, got " + describe(chunks) + " " + retrieval.sources();
            }
        }
        return null;
    }

    private static String source(String pageId, String language) {
        Document page = PAGES.stream().filter(candidate -> candidate.id().equals(pageId)).findFirst().orElseThrow();
        return "en".equals(language) ? "Docs: " + page.content().english().title() : "使用文档《" + page.content().title() + "》";
    }

    private static String describe(List<Chunk> chunks) {
        return chunks.stream().map(chunk -> chunk.pageId() + " § " + chunk.section() + " [" + chunk.language() + "]").toList().toString();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caseStream")
    void theTutorialsAnswerTheQuestion(Case benchmark) {
        assertThat(verdict(benchmark)).isNull();
    }

    static Stream<Case> gapStream() {
        return knownGaps().stream();
    }

    @ParameterizedTest(name = "known gap: {0}")
    @MethodSource("gapStream")
    void aKnownGapIsStillAGap(Case gap) {
        assertThat(verdict(gap)).as("it is answered correctly now: move it from knownGaps() to cases()").isNotNull();
    }

    @Test
    void theBenchmarkHasTheMixItWasMeantToHave() {
        List<Case> all = cases();
        assertThat(all).hasSizeGreaterThanOrEqualTo(40);
        assertThat(all.stream().filter(benchmark -> benchmark.conversation().size() == 1 && "zh".equals(benchmark.language())))
            .hasSizeGreaterThanOrEqualTo(24);
        assertThat(all.stream().filter(benchmark -> benchmark.conversation().size() == 1 && "en".equals(benchmark.language())))
            .hasSizeGreaterThanOrEqualTo(9);
        assertThat(all.stream().filter(benchmark -> benchmark.conversation().size() == 2)).hasSizeGreaterThanOrEqualTo(4);
        assertThat(all.stream().filter(benchmark -> benchmark.page() == null)).hasSizeBetween(3, 6);
        assertThat(all.stream().filter(benchmark -> benchmark.page() == null).map(Case::question))
            .as("the verb after the topic, which the verb before it does not cover").contains("怎么开发票", "发票怎么开");
    }
}
