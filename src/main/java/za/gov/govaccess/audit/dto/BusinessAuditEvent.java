package za.gov.govaccess.audit.dto;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import za.gov.govaccess.audit.service.CanonicalJson;

public record BusinessAuditEvent(UUID id, long sequence, UUID applicationId, UUID actorId, String actorRole,
                                 String action, Instant occurredAt, UUID policyVersionId, String policyHash,
                                 UUID assessmentId, UUID previousSnapshotId, UUID newSnapshotId,
                                 String aiPromptId, Integer aiPromptVersion, String aiModel,
                                 String previousEventHash, String eventHash) {
    public String calculateHash() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("id", id);
        fields.put("sequence", sequence);
        fields.put("applicationId", applicationId);
        fields.put("actorId", actorId);
        fields.put("actorRole", actorRole);
        fields.put("action", action);
        fields.put("occurredAt", occurredAt.toString());
        fields.put("policyVersionId", policyVersionId);
        fields.put("policyHash", policyHash);
        fields.put("assessmentId", assessmentId);
        fields.put("previousSnapshotId", previousSnapshotId);
        fields.put("newSnapshotId", newSnapshotId);
        fields.put("aiPromptId", aiPromptId);
        fields.put("aiPromptVersion", aiPromptVersion);
        fields.put("aiModel", aiModel);
        fields.put("previousEventHash", previousEventHash);
        return CanonicalJson.sha256(CanonicalJson.write(fields));
    }
}
