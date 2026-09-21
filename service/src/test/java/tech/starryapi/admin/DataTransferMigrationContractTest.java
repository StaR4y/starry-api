package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class DataTransferMigrationContractTest {
    @Test
    void restoreContractCoversEveryBundledMigrationInVersionOrder() throws Exception {
        var history = new ArrayList<DataTransferService.FlywayMigration>();
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__*.sql")) {
            String filename = resource.getFilename();
            String version = filename.substring(1, filename.indexOf("__"));
            history.add(new DataTransferService.FlywayMigration(version, filename, "SQL", checksum(resource), true));
        }
        history.sort(Comparator.comparingInt(migration -> Integer.parseInt(migration.version())));
        assertThatCode(() -> DataTransferService.validateMigrationHistory(history)).doesNotThrowAnyException();
    }

    @Test
    void alreadyDeployedAnnouncementMigrationsKeepTheirOriginalChecksums() throws Exception {
        var resolver = new PathMatchingResourcePatternResolver();
        assertThat(checksum(resolver.getResource("classpath:db/migration/V17__announcements.sql"))).isEqualTo(1691840228);
        assertThat(checksum(resolver.getResource("classpath:db/migration/V18__announcement_runtime_grants.sql"))).isEqualTo(1273926045);
        assertThat(resolver.getResource("classpath:db/migration/V17__support_tickets.sql").exists()).isFalse();
        assertThat(resolver.getResource("classpath:db/migration/V19__support_tickets.sql").exists()).isTrue();
    }

    private static int checksum(Resource resource) throws Exception {
        CRC32 checksum = new CRC32();
        // Flyway checks UTF-8 lines without newline bytes, with a possible BOM removed.
        try (var reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("\uFEFF")) line = line.substring(1);
                checksum.update(line.getBytes(StandardCharsets.UTF_8));
            }
        }
        return (int) checksum.getValue();
    }
}
