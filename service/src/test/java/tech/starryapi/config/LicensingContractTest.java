package tech.starryapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The license files and the setting that tells network users where the source is (AGPL-3.0, sections 7 and 13) have to be
 * the same in every place that ships or documents them.
 */
class LicensingContractTest {

    private static final String SOURCE_URL_FORWARDING =
        "STARRY_SOURCE_URL: ${STARRY_SOURCE_URL:-" + SourceCodeProperties.DEFAULT_URL + "}";

    @Test
    void everyDeploymentTemplateForwardsTheSourceAddressWithTheSameDefault() throws IOException {
        for (String template : List.of("docker-compose.yml", "scripts/publish-artifact.sh", "scripts/install-server.sh")) {
            assertThat(read(template)).as(template).contains(SOURCE_URL_FORWARDING);
        }
        for (String environmentFile : List.of(".env.example", "scripts/install-server.sh")) {
            assertThat(read(environmentFile)).as(environmentFile).contains("\nSTARRY_SOURCE_URL=" + SourceCodeProperties.DEFAULT_URL + "\n");
        }
        assertThat(read("service/src/main/resources/application.yml"))
            .contains("url: ${STARRY_SOURCE_URL:" + SourceCodeProperties.DEFAULT_URL + "}");
        assertThat(read("docs/CONFIGURATION.md")).contains("`STARRY_SOURCE_URL`").contains("AGPL-3.0");
    }

    @Test
    void theLicenseFilesAndTheirPackagingAreInPlace() throws IOException {
        assertThat(read("LICENSE")).contains("GNU AFFERO GENERAL PUBLIC LICENSE").contains("Version 3, 19 November 2007");
        assertThat(read("plugin-api/LICENSE")).contains("Apache License").contains("Version 2.0, January 2004");

        String notice = read("NOTICE");
        assertThat(notice)
            .contains("Copyright (C) 2026 StaR4y and contributors")
            .contains("SPDX-License-Identifier: AGPL-3.0-only")
            .contains("Additional permission under section 7")
            .contains("logback-classic and logback-core 1.5.38")
            .contains("jakarta.annotation-api 3.0.0")
            .contains("jakarta.mail-api 2.1.5 and angus-mail 2.0.5")
            .contains("DOMPurify 3.4.16")
            .contains("Mihomo")
            .contains("GPL-3.0");

        assertThat(read("service/build.gradle")).contains("file('LICENSE')").contains("file('NOTICE')").contains("into 'META-INF'")
            .contains("apply from: 'third-party-notices.gradle'");
        assertThat(read("service/third-party-notices.gradle"))
            .contains("tasks.register('generateJavaNotices')")
            .contains("tasks.register('copyleftSources')")
            .contains("into 'META-INF/licenses'");
        assertThat(read("plugin-api/build.gradle")).contains("file('LICENSE')").contains("into 'META-INF'");
        assertThat(read("service-api/build.gradle")).contains("file('LICENSE')").contains("file('NOTICE')").contains("into 'META-INF'");
        assertThat(read("licenses/LGPL-2.1.txt"))
            .contains("GNU LESSER GENERAL PUBLIC LICENSE")
            .contains("Version 2.1, February 1999")
            .contains("That's all there is to it!");
        assertThat(read("licenses/logback-NOTICE.txt")).contains("Copyright (C) 1999-2026, QOS.ch").contains("version 2.1");
        assertThat(read("scripts/publish-artifact.sh"))
            .contains("cp \"$ROOT_DIR/LICENSE\" \"$stage_dir/LICENSE\"")
            .contains("cp \"$ROOT_DIR/NOTICE\" \"$stage_dir/NOTICE\"");
    }

    /**
     * The libraries used under a copyleft license are named in four places: the table of the build that collects their source
     * jars, the two scripts that must find those jars in a release, and section 3 of NOTICE. A fifth library has to be added
     * to all of them or the release would ship binaries without the source code their license asks for.
     */
    @Test
    void theCopyleftLibrariesAreTheSameInTheBuildTheScriptsAndTheNotice() throws IOException {
        String build = read("service/third-party-notices.gradle");
        int start = build.indexOf("def electedCopyleft = [");
        assertThat(start).isPositive();
        String table = build.substring(start, build.indexOf("]", start));
        List<String> artifacts = new ArrayList<>();
        Matcher entries = Pattern.compile("'[^':]+:([^':]+)'\\s*:\\s*'").matcher(table);
        while (entries.find()) {
            artifacts.add(entries.group(1));
        }
        assertThat(artifacts).containsExactlyInAnyOrder(
            "logback-classic", "logback-core", "jakarta.annotation-api", "jakarta.mail-api", "angus-mail");

        for (String script : List.of("scripts/publish-artifact.sh", "scripts/check-bundle-contents.sh")) {
            Matcher libraries = Pattern.compile("COPYLEFT_SOURCE_LIBRARIES=\\(([^)]*)\\)").matcher(read(script));
            assertThat(libraries.find()).as(script).isTrue();
            assertThat(libraries.group(1).trim().split("\\s+")).as(script).containsExactlyInAnyOrderElementsOf(artifacts);
        }

        String notice = read("NOTICE");
        assertThat(notice)
            .contains("logback-classic and logback-core 1.5.38")
            .contains("jakarta.annotation-api 3.0.0")
            .contains("jakarta.mail-api 2.1.5 and angus-mail 2.0.5")
            .contains("licenses/LGPL-2.1.txt")
            .contains("third-party-sources/")
            .contains("JAVA-THIRD-PARTY-NOTICES.txt");
        assertThat(read("scripts/publish-artifact.sh"))
            .contains("META-INF/JAVA-THIRD-PARTY-NOTICES.txt")
            .contains("META-INF/licenses/LGPL-2.1.txt")
            .contains("check-bundle-contents.sh");
    }

    /** README quotes the additional permission for plugins; it has to be the one in NOTICE, word for word. */
    @Test
    void theReadmeQuotesTheAdditionalPermissionOfTheNoticeWordForWord() throws IOException {
        String notice = read("NOTICE");
        int start = notice.indexOf("Additional permission under section 7");
        assertThat(start).isPositive();
        String permission = notice.substring(start, notice.indexOf("\n\n", start)).replaceAll("\\s+", " ");

        assertThat(read("README.md").replaceAll("\\s+", " ")).contains(permission);
        assertThat(permission).contains("web widget plugin").contains("/plugin-assets");
    }

    /**
     * AGPL-3.0 section 7 says a permission that covers only part of a program leaves the whole program under the license without
     * it, so the promise to plugin authors in section 2 of NOTICE holds only if every contribution comes in together with that
     * permission. Everything that tells a contributor under which license their work goes has to say so.
     */
    @Test
    void everyStatementOfTheContributionTermsIncludesTheAdditionalPermission() throws IOException {
        assertThat(read("NOTICE")).contains("2. Plugins and the plugin-api module").contains("Additional permission under section 7");

        String contributing = read("CONTRIBUTING.md");
        int terms = contributing.indexOf("## Licensing of contributions");
        assertThat(terms).isPositive();
        String english = contributing.substring(terms, contributing.indexOf("## 中文说明"));
        assertThat(english)
            .contains("`AGPL-3.0-only` together with the additional permission")
            .contains("section 7 of that license")
            .contains("section 2 of [NOTICE](NOTICE)")
            .contains("a contribution outside `plugin-api/` under `AGPL-3.0-only` together with the additional permission in section 2 of NOTICE")
            .contains("a change to `plugin-api/` under the Apache License 2.0");
        String chinese = contributing.substring(contributing.indexOf("## 中文说明"));
        assertThat(chinese).contains("按 AGPL-3.0-only 授权，并附带 NOTICE 第 2 节的插件附加许可").contains("`plugin-api/` 的改动按 Apache-2.0");

        assertThat(read(".github/PULL_REQUEST_TEMPLATE.md"))
            .contains("I license this contribution under AGPL-3.0-only together with the additional permission for plugins in section 2 of NOTICE"
                + " (Apache-2.0 for changes to `plugin-api/`)");

        String readme = read("README.md");
        String contributingSection = readme.substring(readme.indexOf("## 参与贡献"), readme.indexOf("## 许可证"));
        assertThat(contributingSection)
            .contains("你提交的代码按 AGPL-3.0-only 授权，并附带 [NOTICE](NOTICE) 第 2 节的插件附加许可")
            .contains("`plugin-api/` 的改动按 Apache-2.0");
    }

    /**
     * Section 3 of NOTICE promises that every dependency outside the elections is permissive and that the texts it needs are
     * printed. The Gradle task that writes the Java notices enforces that (it stops the build for a library it cannot account
     * for, which CI runs through bootJar), and the list it enforces is the one NOTICE names.
     */
    @Test
    void theJavaNoticesGeneratorStopsOnALicenseItDoesNotKnowAndNoticeNamesTheSameList() throws IOException {
        String build = read("service/third-party-notices.gradle");
        assertThat(build)
            .contains("def printableKinds = ['Apache-2.0', 'MIT', 'MIT-0', 'CC0']")
            .contains("def permissiveKinds = printableKinds + ['BSD-2-Clause', 'BSD-3-Clause', 'EDL-1.0', 'ISC']")
            .contains("if (!info.licenses) {")
            .contains("!kinds.any { permissiveKinds.contains(it) }")
            .contains("!kinds.any { printableKinds.contains(it) }")
            .contains("if (unaccountedFor) {")
            .contains("throw new GradleException(")
            .contains("cannot account for");

        String notice = read("NOTICE").replaceAll("\\s+", " ");
        assertThat(notice)
            .contains("(Apache-2.0, MIT, MIT-0, BSD-2-Clause, BSD-3-Clause, ISC, CC0-1.0 and the Eclipse Distribution License 1.0,"
                + " which has the terms of BSD-3-Clause)")
            .contains("it stops when a library declares only licenses outside that list (or none) and is not one of the elections above")
            .contains("declares a license other than Apache-2.0, MIT, MIT-0 or CC0-1.0");
    }

    /** The files NOTICE points at have to be where it says, and its citation of section 7 has to name the clauses that carry the terms. */
    @Test
    void noticeNamesTheRealPathsAndTheSubsectionsOfSection7() throws IOException {
        String notice = read("NOTICE").replaceAll("\\s+", " ");
        assertThat(notice)
            .contains("service/build/generated/java-notices/JAVA-THIRD-PARTY-NOTICES.txt")
            .contains("BOOT-INF/classes/static/THIRD-PARTY-NOTICES.txt")
            .contains("(section 7(c), (d) and (e) of the GNU Affero General Public License, version 3)");
        assertThat(read("service/third-party-notices.gradle")).contains("generated/java-notices/JAVA-THIRD-PARTY-NOTICES.txt");
        assertThat(read("scripts/check-bundle-contents.sh")).contains("BOOT-INF/classes/static/THIRD-PARTY-NOTICES.txt");
    }

    /** NOTICE names exact versions; when the build moves them, the elections have to be looked at again. */
    @Test
    void theVersionsNamedInTheNoticeAreTheOnesTheBuildPins() throws IOException {
        assertThat(read("build.gradle"))
            .as("NOTICE section 3 names logback 1.5.38, jakarta.annotation-api 3.0.0, jakarta.mail-api 2.1.5 and angus-mail 2.0.5,"
                + " which the Spring Boot 4.1.2 BOM manages: when the BOM moves, check those versions and licenses and update NOTICE")
            .contains("org.springframework.boot:spring-boot-dependencies:4.1.2");
        assertThat(read("webui/package-lock.json"))
            .as("NOTICE section 3 names DOMPurify 3.4.16: when it moves, update NOTICE")
            .contains("\"node_modules/dompurify\": {\n      \"version\": \"3.4.16\"");
        assertThat(read("webui/package.json")).contains("\"license\": \"AGPL-3.0-only\"");
    }

    @Test
    void theOpenApiDocumentAndTheConsoleNameTheSameLicense() throws IOException {
        assertThat(new OpenApiConfig().openAPI().getInfo().getLicense().getName()).isEqualTo("AGPL-3.0-only");
        assertThat(read("webui/src/utils/sourceCode.ts"))
            .contains("LICENSE_NAME = 'AGPL-3.0-only'")
            .contains("DEFAULT_SOURCE_URL = '" + SourceCodeProperties.DEFAULT_URL + "'");
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(repositoryRoot().resolve(relativePath));
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle")) && Files.isDirectory(candidate.resolve("service"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Unable to locate repository root from " + Path.of("").toAbsolutePath());
    }
}
