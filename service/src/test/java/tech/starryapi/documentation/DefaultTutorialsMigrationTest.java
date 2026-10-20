package tech.starryapi.documentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DefaultTutorialsMigrationTest {
    private static final String TUTORIALS = "/db/migration/V23__documentation_tutorials.sql";
    private static final String USER_FOCUS = "/db/migration/V24__documentation_user_focus.sql";
    private static final Pattern TUTORIAL = Pattern.compile("(?m)^    \\('([a-z0-9-]+)', (?:'[0-9a-f]{32}', )?jsonb_build_object\\(");
    private static final Pattern BODY = Pattern.compile("\\$doc\\$(.*?)\\$doc\\$", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("#/documentation/([a-z0-9-]+)");
    private static final Pattern OPERATIONS_WORDING = Pattern.compile(
        "127\\.0\\.0\\.1|上游|部署在远程|管理员专属|倍率|upstream|hosted remotely|administrator-only|multiplier");

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
        for (String resource : List.of(TUTORIALS, USER_FOCUS)) {
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
    void everyInternalDocumentationLinkPointsToAnExistingTutorial() throws IOException {
        List<String> known = ids(read(TUTORIALS));
        int links = 0;
        for (String resource : List.of(TUTORIALS, USER_FOCUS)) {
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
        assertThat(bodies(read(USER_FOCUS))).isNotEmpty()
            .allSatisfy(text -> assertThat(OPERATIONS_WORDING.matcher(text).find()).as("operations wording").isFalse());
    }
}
