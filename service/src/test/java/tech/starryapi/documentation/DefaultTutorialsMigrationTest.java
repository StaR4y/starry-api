package tech.starryapi.documentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DefaultTutorialsMigrationTest {
    private static final String TUTORIALS = "/db/migration/V23__documentation_tutorials.sql";
    private static final String USER_FOCUS = "/db/migration/V24__documentation_user_focus.sql";
    private static final String SOURCE_TIERS = "/db/migration/V30__documentation_source_tiers.sql";
    private static final String POLISH = "/db/migration/V33__documentation_trim_and_polish.sql";
    private static final Pattern TUTORIAL = Pattern.compile("(?m)^    \\('([a-z0-9-]+)', (?:'[0-9a-f]{32}', )?jsonb_build_object\\(");
    private static final Pattern BODY = Pattern.compile("\\$doc\\$(.*?)\\$doc\\$", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("#/documentation/([a-z0-9-]+)");
    private static final Pattern OPERATIONS_WORDING = Pattern.compile(
        "127\\.0\\.0\\.1|上游|部署在远程|管理员专属|倍率|upstream|hosted remotely|administrator-only|multiplier");
    /** What a reader of the tutorials is not told: how a tier is served is the administrator's business (design 6.4). */
    private static final Pattern HOW_TIERS_ARE_SERVED = Pattern.compile(
        "中转|跨协议|wire(?!_api)|OPENAI_CHAT|Chat Completions|relay|cross-protocol|Kiro|kiro|Codex 账号|Codex account");
    /** What polishing the wording must not touch: inline code and headings (anchors depend on them). */
    private static final Pattern PROTECTED = Pattern.compile("`[^`\\n]+`|(?m)^#{1,6} .*$");
    private static final Pattern LINK_TARGET = Pattern.compile("\\]\\(([^)\\s]+)\\)");
    /** The two 开始使用 pages V33 rewrites as short pages instead of deleting them. */
    private static final List<String> REWRITTEN = List.of("billing", "api-keys");
    /** A page V33 deletes: ('id', 'hash') after WITH removed(. */
    private static final Pattern REMOVED_ENTRY = Pattern.compile("(?m)^    \\('([a-z0-9-]+)', '[0-9a-f]{32}'\\)");
    private static final Pattern PREVIOUS_HASH = Pattern.compile("(?m)^    \\('([a-z0-9-]+)', '([0-9a-f]{32})', jsonb_build_object\\(");

    private static String read(String resource) throws IOException {
        try (var in = DefaultTutorialsMigrationTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> ids(String sql) {
        List<String> ids = new ArrayList<>();
        Matcher tutorial = TUTORIAL.matcher(sql);
        while (tutorial.find()) ids.add(tutorial.group(1));
        return ids;
    }

    private static List<String> bodies(String sql) {
        List<String> bodies = new ArrayList<>();
        Matcher body = BODY.matcher(sql);
        while (body.find()) bodies.add(body.group(1));
        return bodies;
    }

    @Test
    void everyTutorialHasChineseAndEnglishBodiesWithinTheLimit() throws IOException {
        for (String resource : List.of(TUTORIALS, USER_FOCUS, SOURCE_TIERS, POLISH)) {
            String sql = read(resource);
            List<String> ids = ids(sql);
            assertThat(ids).as(resource).isNotEmpty().doesNotHaveDuplicates();
            assertThat(bodies(sql)).hasSize(ids.size() * 2)
                .allSatisfy(text -> assertThat(text.length()).isBetween(200, 100_000));
            assertThat(sql).as("Flyway placeholders must not appear in tutorial text").doesNotContain("${");
        }
        assertThat(ids(read(TUTORIALS))).hasSize(14);
    }

    @Test
    void userFocusRevisionOnlyTouchesTutorialsInstalledByTheEarlierMigration() throws IOException {
        assertThat(ids(read(TUTORIALS))).containsAll(ids(read(USER_FOCUS)));
    }

    @Test
    void sourceTierRevisionOnlyTouchesTutorialsInstalledByTheEarlierMigrationsAndNamesTheHashItReplaces() throws IOException {
        String sql = read(SOURCE_TIERS);
        assertThat(ids(read(TUTORIALS))).containsAll(ids(sql));
        assertThat(ids(sql)).containsExactlyInAnyOrder(
            "api-keys", "models-and-pricing", "errors", "claude-code", "codex-cli", "opencode");
        Matcher hashed = PREVIOUS_HASH.matcher(sql);
        List<String> withHash = new ArrayList<>();
        while (hashed.find()) withHash.add(hashed.group(1));
        assertThat(withHash).as("every replaced tutorial names the hash of the version it replaces").isEqualTo(ids(sql));
        // Like V24, only a tutorial that is still the seed (published, identical draft, the hash of that draft) is replaced.
        assertThat(sql).contains("AND p.deleted = FALSE AND p.published IS NOT NULL", "AND p.draft = p.published",
            "AND md5(p.draft::text) = tutorials.previous_hash");
        // The other tutorials do not change, and neither does anything but the pages: the migration is one UPDATE.
        assertThat(BODY.matcher(sql).replaceAll("")).doesNotContainPattern("(?im)^\\s*(INSERT|DELETE|ALTER|CREATE|DROP)\\b");
    }

    @Test
    void sourceTierRevisionTeachesTheClientsAndTheErrorReferenceInBothLanguages() throws IOException {
        String sql = read(SOURCE_TIERS);
        List<String> bodies = bodies(sql);
        List<String> ids = ids(sql);
        for (int i = 0; i < ids.size(); i++) {
            String zh = bodies.get(i * 2);
            String en = bodies.get(i * 2 + 1);
            assertThat(zh).as("%s in Chinese", ids.get(i)).contains("号池");
            assertThat(en).as("%s in English", ids.get(i)).containsIgnoringCase("tier");
        }
        // The error reference names every error of the tier selectors, with the status a reader sees, in both languages.
        String errorsZh = bodies.get(ids.indexOf("errors") * 2);
        String errorsEn = bodies.get(ids.indexOf("errors") * 2 + 1);
        for (String body : List.of(errorsZh, errorsEn)) {
            assertThat(body).contains("SOURCE_TIER_UNKNOWN", "SOURCE_TIER_NOT_OFFERED", "SOURCE_TIER_NOT_SELECTABLE",
                "SOURCE_TIER_FEATURE_UNSUPPORTED", "API_KEY_TIER_PREFERENCE_INVALID", "RESPONSE_OWNER_TIER_MISMATCH",
                "SOURCE_TIER_UNAVAILABLE", "Retry-After", "API_KEY_MODEL_PROVIDER_FORBIDDEN");
            assertThat(body).containsPattern("\\| `SOURCE_TIER_UNKNOWN` \\| `400` \\|")
                .containsPattern("\\| `SOURCE_TIER_NOT_OFFERED` \\| `400` \\|")
                .containsPattern("\\| `SOURCE_TIER_NOT_SELECTABLE` \\| `403` \\|")
                .containsPattern("\\| `SOURCE_TIER_FEATURE_UNSUPPORTED` \\| `400` \\|")
                .containsPattern("\\| `API_KEY_TIER_PREFERENCE_INVALID` \\| `400` \\|")
                .containsPattern("\\| `RESPONSE_OWNER_TIER_MISMATCH` \\| `409` \\|")
                .containsPattern("\\| `SOURCE_TIER_UNAVAILABLE` \\| `503` \\|");
        }
        // The 403 row used to say that every 403 is a model-category mismatch; the not-selectable suffix is the other 403.
        assertThat(errorsZh).containsPattern("\\| `403` \\|[^\\n]*SOURCE_TIER_NOT_SELECTABLE[^\\n]*去掉模型名后面的 `@标识`");
        assertThat(errorsEn).containsPattern("\\| `403` \\|[^\\n]*SOURCE_TIER_NOT_SELECTABLE[^\\n]*remove the `@tier`");
        // The three client tutorials say how to choose a tier by header and by suffix, with the client's own spelling.
        assertThat(bodies.get(ids.indexOf("claude-code") * 2)).contains("ANTHROPIC_CUSTOM_HEADERS='X-Starry-Tier: ", "--model ");
        assertThat(bodies.get(ids.indexOf("codex-cli") * 2)).contains("http_headers = { \"X-Starry-Tier\" = ", "model = \"gpt-5.5@");
        assertThat(bodies.get(ids.indexOf("opencode") * 2)).contains("\"headers\": { \"X-Starry-Tier\": ", "starry/gpt-5.5@");
        // The key form and the model page explain the preference and the suffix.
        assertThat(bodies.get(ids.indexOf("api-keys") * 2)).contains("号源偏好", "及以下", "X-Starry-Tier-Status", "ignored");
        assertThat(bodies.get(ids.indexOf("api-keys") * 2 + 1)).contains("Tier preference", "and below", "X-Starry-Tier-Status");
        assertThat(bodies.get(ids.indexOf("models-and-pricing") * 2)).contains("`模型@标识`", "`模型@auto`", "× 实际服务这个请求的号池的系数");
        assertThat(bodies.get(ids.indexOf("models-and-pricing") * 2 + 1)).contains("`model@auto`", "the factor of the tier that served the request");
    }

    @Test
    void sourceTierRevisionDoesNotTellAReaderHowTheTiersAreServed() throws IOException {
        for (String resource : List.of(SOURCE_TIERS, POLISH)) {
            assertThat(bodies(read(resource))).as(resource).isNotEmpty()
                .allSatisfy(text -> assertThat(HOW_TIERS_ARE_SERVED.matcher(text).find()).as("how a tier is served").isFalse());
        }
    }

    @Test
    void v33TrimsTheGettingStartedTutorialsAndOnlyRewordsTheOthers() throws IOException {
        String sql = read(POLISH);
        int removedAt = sql.indexOf("WITH removed(");
        assertThat(removedAt).as("V33 lists the tutorials it deletes").isPositive();
        String replaced = sql.substring(0, removedAt);
        String removedPart = sql.substring(removedAt);
        List<String> ids = ids(replaced);
        List<String> removed = new ArrayList<>();
        Matcher entry = REMOVED_ENTRY.matcher(removedPart);
        while (entry.find()) removed.add(entry.group(1));
        // Four pages of the 开始使用 category go and its other two are rewritten; no page is both replaced and deleted.
        assertThat(removed).containsExactlyInAnyOrder("getting-started", "console-tour", "coding-plan", "models-and-pricing");
        assertThat(ids).containsAll(REWRITTEN);
        assertThat(ids(read(TUTORIALS))).containsAll(ids).containsAll(removed);
        assertThat(ids).doesNotContainAnyElementsOf(removed);
        Matcher hashed = PREVIOUS_HASH.matcher(replaced);
        List<String> withHash = new ArrayList<>();
        while (hashed.find()) withHash.add(hashed.group(1));
        assertThat(withHash).as("every replaced tutorial names the hash of the version it replaces").isEqualTo(ids);
        // Like V24 and V30, only a tutorial that is still the seed is replaced or deleted; a deletion is the console's.
        assertThat(replaced).contains("AND p.deleted = FALSE AND p.published IS NOT NULL", "AND p.draft = p.published",
            "AND md5(p.draft::text) = tutorials.previous_hash");
        assertThat(removedPart).contains("SET published = NULL,", "published_at = NULL,", "deleted = TRUE,",
            "version = p.version + 1,", "AND p.deleted = FALSE AND p.published IS NOT NULL", "AND p.draft = p.published",
            "AND md5(p.draft::text) = removed.previous_hash;");
        assertThat(BODY.matcher(sql).replaceAll("")).doesNotContainPattern("(?im)^\\s*(INSERT|DELETE|ALTER|CREATE|DROP)\\b");

        Map<String, List<String>> earlier = pagesAfter(List.of(TUTORIALS, USER_FOCUS, SOURCE_TIERS));
        Pattern removedLink = Pattern.compile("#/documentation/(" + String.join("|", removed) + ")\\b");
        List<String> bodies = bodies(replaced);
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            String zh = bodies.get(i * 2);
            String en = bodies.get(i * 2 + 1);
            assertThat(removedLink.matcher(zh + en).find()).as("%s links to a deleted page", id).isFalse();
            if (REWRITTEN.contains(id)) {
                continue;
            }
            String zhBefore = earlier.get(id).get(0);
            String enBefore = earlier.get(id).get(1);
            assertThat(protectedParts(zh)).as("%s: inline code and headings", id)
                .containsExactlyInAnyOrderElementsOf(protectedParts(zhBefore));
            for (String language : List.of("zh", "en")) {
                List<String> before = links("zh".equals(language) ? zhBefore : enBefore);
                List<String> after = links("zh".equals(language) ? zh : en);
                before.removeIf(target -> removedLink.matcher(target).find());
                assertThat(after).as("%s (%s): links to the deleted pages are gone, the others stay", id, language)
                    .noneMatch(target -> removedLink.matcher(target).find()).containsAll(before);
            }
            // The English text is not polished: a line changes only where it had a link to another tutorial.
            String[] enLines = en.split("\n", -1);
            String[] enLinesBefore = enBefore.split("\n", -1);
            assertThat(enLines).as("%s in English", id).hasSameSizeAs(enLinesBefore);
            for (int line = 0; line < enLines.length; line++) {
                if (!enLines[line].equals(enLinesBefore[line])) {
                    assertThat(enLinesBefore[line]).as("%s in English, line %d changed without a link to a tutorial", id, line + 1)
                        .contains("#/documentation/");
                }
            }
        }
        // What a reader is left with links to no deleted page.
        Map<String, List<String>> after = pagesAfter(List.of(TUTORIALS, USER_FOCUS, SOURCE_TIERS, POLISH));
        removed.forEach(after::remove);
        assertThat(after).hasSize(10);
        after.forEach((id, texts) -> assertThat(texts).as(id)
            .allSatisfy(text -> assertThat(removedLink.matcher(text).find()).isFalse()));

        // The two short pages still carry what the deleted ones taught: billing and plans, keys, models, prices and tiers.
        String billingZh = after.get("billing").get(0);
        String billingEn = after.get("billing").get(1);
        assertThat(billingZh).contains("余额计费", "Coding Plan", "`402`", "**不会**改扣余额", "兑换码", "已入账", "预留", "额度明细");
        assertThat(billingEn).contains("Balance billing", "Coding Plan", "`402`", "**not** charged", "Redeem a code", "Credited",
            "reserved", "Quota history");
        String keysZh = after.get("api-keys").get(0);
        String keysEn = after.get("api-keys").get(1);
        assertThat(keysZh).contains("`API_KEY_MODEL_PROVIDER_FORBIDDEN`", "[模型 ID](#/models)", "`MODEL_PRICE_UNAVAILABLE`",
            "号源偏好", "及以下", "X-Starry-Tier-Status", "ignored", "`模型@auto`", "× 实际服务这个请求的号池的系数",
            "`SOURCE_TIER_UNKNOWN`", "`SOURCE_TIER_NOT_OFFERED`", "`SOURCE_TIER_NOT_SELECTABLE`", "`SOURCE_TIER_UNAVAILABLE`",
            "`API_KEY_TIER_PREFERENCE_INVALID`");
        assertThat(keysEn).contains("`API_KEY_MODEL_PROVIDER_FORBIDDEN`", "[Model IDs](#/models)", "`MODEL_PRICE_UNAVAILABLE`",
            "Tier preference", "and below", "X-Starry-Tier-Status", "`model@auto`", "the factor of the tier that served the request",
            "`SOURCE_TIER_UNKNOWN`", "`SOURCE_TIER_NOT_OFFERED`", "`SOURCE_TIER_NOT_SELECTABLE`", "`SOURCE_TIER_UNAVAILABLE`",
            "`API_KEY_TIER_PREFERENCE_INVALID`");
    }

    /** Each page as these migrations leave it, in order: [Chinese body, English body]. Deletions are not applied here. */
    private static Map<String, List<String>> pagesAfter(List<String> resources) throws IOException {
        Map<String, List<String>> pages = new HashMap<>();
        for (String resource : resources) {
            String sql = read(resource);
            int removedAt = sql.indexOf("WITH removed(");
            String replaced = removedAt < 0 ? sql : sql.substring(0, removedAt);
            List<String> ids = ids(replaced);
            List<String> bodies = bodies(replaced);
            for (int i = 0; i < ids.size(); i++) {
                pages.put(ids.get(i), bodies.subList(i * 2, i * 2 + 2));
            }
        }
        return pages;
    }

    private static List<String> protectedParts(String body) {
        List<String> parts = new ArrayList<>();
        Matcher part = PROTECTED.matcher(body);
        while (part.find()) parts.add(part.group());
        return parts;
    }

    private static List<String> links(String body) {
        List<String> targets = new ArrayList<>();
        Matcher link = LINK_TARGET.matcher(body);
        while (link.find()) targets.add(link.group(1));
        return targets;
    }

    @Test
    void everyInternalDocumentationLinkPointsToAnExistingTutorial() throws IOException {
        List<String> known = ids(read(TUTORIALS));
        int links = 0;
        for (String resource : List.of(TUTORIALS, USER_FOCUS, SOURCE_TIERS, POLISH)) {
            Matcher link = LINK.matcher(read(resource));
            while (link.find()) {
                links++;
                assertThat(known).as("link to #/documentation/%s in %s", link.group(1), resource).contains(link.group(1));
            }
        }
        assertThat(links).isGreaterThan(20);
    }

    @Test
    void revisedTutorialsContainNoOperationsOrHostingWording() throws IOException {
        List<String> revised = new ArrayList<>(bodies(read(USER_FOCUS)));
        revised.addAll(bodies(read(SOURCE_TIERS)));
        revised.addAll(bodies(read(POLISH)));
        assertThat(revised).isNotEmpty()
            .allSatisfy(text -> assertThat(OPERATIONS_WORDING.matcher(text).find()).as("operations wording").isFalse());
    }
}
