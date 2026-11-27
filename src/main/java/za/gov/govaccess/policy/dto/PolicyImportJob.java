package za.gov.govaccess.policy.dto;

import java.time.Instant;
import java.util.UUID;

public record PolicyImportJob(UUID id, UUID userId, UUID draftId, String programmeCode, String versionLabel,
                              String sourceLabel, Status status, int completedChunks, int totalChunks,
                              String failure, Instant updatedAt) {
    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED }
}
