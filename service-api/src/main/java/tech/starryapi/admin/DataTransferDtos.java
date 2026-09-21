package tech.starryapi.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

    public record DataManagementSecurityStatusResponse(
        boolean initialized,
        boolean locked,
        int failedAttempts,
        int maxFailedAttempts,
        long unlockTtlSeconds,
        Instant initializedAt,
        Instant lastFailureAt,
        Instant lockedAt,
        Instant updatedAt
    ) {
    }

    public record InitializeDataManagementSecurityResponse(
        DataManagementSecurityStatusResponse status,
        String securityKey
    ) {
    }

    public record UnlockDataManagementRequest(
        @NotBlank @Size(max = 512) String securityKey,
        @NotBlank @Size(max = 64) String action,
        @Size(max = 255) String target
    ) {
    }

    public record UnlockDataManagementResponse(
        String tokenType,
        String accessToken,
        Instant expiresAt,
        String action,
        String target
    ) {
    }
}
