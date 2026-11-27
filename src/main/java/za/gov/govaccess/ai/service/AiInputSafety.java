package za.gov.govaccess.ai.service;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.gov.govaccess.ai.configuration.AiProperties;
import za.gov.govaccess.ai.enums.AiCacheMode;
import za.gov.govaccess.audit.service.BusinessAuditService;
import za.gov.govaccess.audit.service.BusinessAuditService.Change;

@Service
public class AiInputSafety {
    private static final Pattern INSTRUCTIONS = Pattern.compile(
            "(?iu)\\b(?:ignore\\s+(?:all\\s+)?(?:previous|prior|earlier)\\s+instructions|system\\s+prompt"
                    + "|you\\s+are\\s+now|disregard\\s+(?:the\\s+)?(?:rules|instructions)|reveal|override\\s+(?:the\\s+)?instructions)\\b");
    private static final int MAX_TEXT = 200_000;
    private final AiProperties properties;
    private final AiAvailability availability;
    private final JdbcTemplate jdbc;
    private final BusinessAuditService audit;

    public AiInputSafety(AiProperties properties, AiAvailability availability, JdbcTemplate jdbc, BusinessAuditService audit) {
        this.properties = properties;
        this.availability = availability;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public boolean hostedActive() {
        return availability.enabled() && properties.cacheMode() != AiCacheMode.REPLAY_ONLY
                && properties.providers().stream().anyMatch(provider -> provider.hosted() && provider.configured());
    }

    public static boolean instructionLike(String source) {
        return source != null && INSTRUCTIONS.matcher(source).find();
    }

    public void checkText(String text) {
        if (text == null) return;
        if (text.length() > MAX_TEXT) throw new IllegalArgumentException("Document text exceeds the maximum supported length.");
        if (hostedActive() && RegexPiiRedactor.containsId(text)) {
            throw new IllegalArgumentException("A hosted AI provider is active. Uploads containing a valid identity number are blocked.");
        }
    }

    public void checkPdfText(byte[] bytes) {
        if (!hostedActive() || bytes == null) return;
        try (var document = Loader.loadPDF(bytes)) {
            if (document.getNumberOfPages() > 100) throw new IllegalArgumentException("The PDF exceeds the supported page limit.");
            checkText(new PDFTextStripper().getText(document));
        } catch (IOException e) {
            throw new IllegalArgumentException("The PDF could not be checked. Upload a readable PDF.", e);
        }
    }

    @Transactional
    public void flagPolicy(UUID jobId, UUID userId, UUID draftId) {
        if (jdbc.update("UPDATE govaccess.policy_import_job SET instruction_like_content=true WHERE id=? AND NOT instruction_like_content",
                jobId) == 1) {
            flag(userId, null, null, draftId);
        }
    }

    @Transactional
    public void flagDocument(UUID documentId, UUID userId, UUID applicationId, UUID policyId) {
        if (jdbc.update("UPDATE govaccess.document_processing SET instruction_like_content=true WHERE document_id=? AND NOT instruction_like_content",
                documentId) == 1) {
            flag(userId, applicationId, policyId, documentId);
        }
    }

    private void flag(UUID user, UUID application, UUID policy, UUID entity) {
        audit.append(new Change(application, user, "SYSTEM", "INSTRUCTION_LIKE_CONTENT", policy, null, null,
                null, Map.of("entityId", entity), null, null, null));
    }
}
