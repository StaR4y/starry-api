package tech.starryapi.admin;

import java.time.Instant;
import java.util.List;

public final class DataTransferDtos {

    private DataTransferDtos() {
    }

    public record DataTransferStatusResponse(
        String database,
        String backupDir,
        long maxImportBytes,
        int backupCount,
        long backupBytes
    ) {
    }

    public record BackupFileResponse(
        String fileName,
        long sizeBytes,
        Instant createdAt
    ) {
    }

    public record BackupListResponse(
        String backupDir,
        List<BackupFileResponse> files
    ) {
    }

    public record BackupCreateResponse(
        BackupFileResponse file
    ) {
    }

    public record DeleteBackupResponse(
        String fileName,
        boolean deleted
    ) {
    }

    public record ImportResultResponse(
        String fileName,
        String sourceType,
        String preRestoreBackupName,
        Instant importedAt
    ) {
    }
}
