package za.gov.govaccess.document.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import za.gov.govaccess.ai.service.ApplicationDocumentInterpreter;
import za.gov.govaccess.application.service.ApplicationIntakeService;
import za.gov.govaccess.audit.service.BusinessAuditService;
import za.gov.govaccess.audit.service.BusinessAuditService.Change;
import za.gov.govaccess.document.dto.ExtractedField;
import za.gov.govaccess.document.dto.ExtractedField.Validation;
import za.gov.govaccess.security.ActorIdentity;

@Service
public class ApplicationDocuments {
    private final JdbcTemplate jdbc;
    private final ApplicationIntakeService cases;
    private final PrivateDocumentStorage storage;
    private final BusinessAuditService audit;
    private final DocumentFieldPlan plan;
    private final ObjectMapper mapper;
    private final za.gov.govaccess.ai.service.AiInputSafety safety;

    public ApplicationDocuments(JdbcTemplate jdbc, ApplicationIntakeService cases, PrivateDocumentStorage storage,
                                BusinessAuditService audit, DocumentFieldPlan plan, ObjectMapper mapper,
                                za.gov.govaccess.ai.service.AiInputSafety safety) {
        this.jdbc = jdbc;
        this.cases = cases;
        this.storage = storage;
        this.audit = audit;
        this.plan = plan;
        this.mapper = mapper;
        this.safety = safety;
    }

    public record Document(UUID id, UUID applicationId, UUID uploadedBy, UUID policyId, String filename,
                           String state, int version, UUID duplicateOf, String type, Double ocrConfidence,
                           String error, Map<String, ExtractedField> fields, boolean instructionLikeContent) {}

    private static final String SELECT = """
            SELECT d.id,d.application_id,d.original_filename,j.*,a.policy_version_id
            FROM govaccess.document_processing j JOIN govaccess.application_document d ON d.id=j.document_id
            JOIN govaccess.case_application a ON a.id=d.application_id
            """;

    @Transactional
    public UUID upload(Authentication actor, UUID applicationId, MultipartFile file) throws IOException {
        var application = cases.requireUpload(actor, applicationId);
        if (application.getPolicyVersionId() == null) throw new IllegalArgumentException("This application has no published policy.");
        if (file.isEmpty() || file.getSize() > PrivateDocumentStorage.MAX_BYTES) {
            throw new IllegalArgumentException("Upload a PDF of up to 8 MB.");
        }
        byte[] bytes = file.getBytes();
        storage.validate(bytes);
        safety.checkPdfText(bytes);
        String hash = hash(bytes);
        UUID duplicate = jdbc.query("""
                SELECT d.id FROM govaccess.application_document d JOIN govaccess.document_processing j ON j.document_id=d.id
                WHERE d.application_id=? AND d.sha256=? AND j.duplicate_of IS NULL
                ORDER BY d.created_at LIMIT 1
                """, (row, index) -> row.getObject(1, UUID.class), applicationId, hash).stream().findFirst().orElse(null);
        var id = UUID.randomUUID();
        var identity = ActorIdentity.of(actor);
        var name = safeName(file.getOriginalFilename());
        if (duplicate == null) {
            storage.store(id, bytes);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        try { storage.delete(id); } catch (IOException e) {
                            org.slf4j.LoggerFactory.getLogger(ApplicationDocuments.class)
                                    .warn("Could not remove rolled-back document {}", id);
                        }
                    }
                }
            });
        }
        jdbc.update("""
                INSERT INTO govaccess.application_document
                    (id,application_id,original_filename,content_type,storage_key,sha256,processing_status,uploaded_by,created_at)
                VALUES (?,?,?,'application/pdf',?,?,'RECEIVED',?,CURRENT_TIMESTAMP)
                """, id, applicationId, name, (duplicate == null ? id : duplicate) + ".pdf", hash, actor.getName());
        jdbc.update("""
                INSERT INTO govaccess.document_processing (document_id,uploaded_actor_id,size_bytes,state,duplicate_of)
                VALUES (?,?,?,?,?)
                """, id, identity.id(), bytes.length, duplicate == null ? "QUEUED" : "DUPLICATE_DOCUMENT", duplicate);
        audit.append(new Change(applicationId, identity.id(), identity.role(),
                duplicate == null ? "DOCUMENT_UPLOADED" : "DUPLICATE_DOCUMENT", application.getPolicyVersionId(),
                null, null, null, Map.of("documentId", id, "sha256", hash), null, null, null));
        return id;
    }

    @Transactional(readOnly = true)
    public List<Document> list(Authentication actor, UUID applicationId) {
        cases.requireReadable(actor, applicationId, false);
        return jdbc.query(SELECT + " WHERE d.application_id=? ORDER BY d.created_at DESC", this::read, applicationId);
    }

    @Transactional(readOnly = true)
    public Document get(Authentication actor, UUID applicationId, UUID id) {
        cases.requireReadable(actor, applicationId, false);
        return find(id).filter(item -> item.applicationId().equals(applicationId)).orElseThrow(ApplicationDocuments::missing);
    }

    public byte[] content(Authentication actor, UUID applicationId, UUID id) throws IOException {
        var document = get(actor, applicationId, id);
        if (document.error() != null && document.error().startsWith("A hosted AI provider is active.")) throw missing();
        return storage.read(document.duplicateOf() == null ? id : document.duplicateOf());
    }

    java.util.Optional<Document> find(UUID id) {
        return jdbc.query(SELECT + " WHERE d.id=?", this::read, id).stream().findFirst();
    }

    @Transactional
    public void verify(Authentication actor, UUID applicationId, UUID id, String fieldKey, String rawValue, int version) {
        cases.requireVerification(actor, applicationId);
        var document = jdbc.query(SELECT + " WHERE d.id=? AND d.application_id=? FOR UPDATE", this::read, id, applicationId)
                .stream().findFirst().orElseThrow(ApplicationDocuments::missing);
        if (!"SUCCEEDED".equals(document.state()) || document.version() != version) {
            throw new IllegalArgumentException("Refresh the document before verifying fields.");
        }
        var existing = document.fields().get(fieldKey);
        boolean identityDocument = "IDENTITY_DOCUMENT".equals(document.type());
        var definition = identityDocument ? null : plan.fields(document.policyId(), document.type()).get(fieldKey);
        if (existing == null || !identityDocument && definition == null) {
            throw new IllegalArgumentException("This field cannot be edited.");
        }
        JsonNode value = existing.value();
        if (rawValue != null && !rawValue.isBlank()) {
            if (identityDocument) throw new IllegalArgumentException("Upload a corrected identity document for validation.");
            if (rawValue.length() > 400) throw new IllegalArgumentException("The correction is too long.");
            try {
                value = mapper.readerFor(JsonNode.class)
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                              com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        .readValue(rawValue);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Enter a valid JSON field value.", e);
            }
        }
        var validated = identityDocument ? existing.validation()
                : ApplicationDocumentInterpreter.validate(value, definition, existing.confidence());
        if (value == null || value.isNull() || validated == Validation.FAILED) {
            throw new IllegalArgumentException("Supply a valid field value before verifying.");
        }
        boolean changed = !value.equals(existing.value());
        var revised = new ExtractedField(value, changed ? 0 : existing.confidence(), changed ? null : existing.sourceSnippet(),
                changed ? null : existing.pageNumber(), id, document.version(), existing.affectsRuleCodes(), validated,
                existing.humanCorrected() || changed, true);
        var fields = new LinkedHashMap<>(document.fields());
        fields.put(fieldKey, revised);
        jdbc.update("UPDATE govaccess.document_processing SET extracted_fields=? WHERE document_id=?", json(fields), id);
        if (fields.values().stream().allMatch(ExtractedField::verified)) {
            jdbc.update("UPDATE govaccess.application_document SET processing_status='VERIFIED' WHERE id=?", id);
        }
        var identity = ActorIdentity.of(actor);
        audit.append(new Change(applicationId, identity.id(), identity.role(), changed ? "DOCUMENT_FIELD_CORRECTED" : "DOCUMENT_FIELD_VERIFIED",
                document.policyId(), null, null, Map.of("field", fieldKey, "fieldState", existing),
                Map.of("field", fieldKey, "fieldState", revised), null, null, null));
    }

    @Transactional
    public void requeue(Authentication actor, UUID applicationId, UUID id) {
        cases.requireVerification(actor, applicationId);
        if (jdbc.update("""
                UPDATE govaccess.document_processing SET state='QUEUED',version=version+1,error_message=NULL,started_at=NULL
                WHERE document_id=? AND state IN ('SUCCEEDED','FAILED')
                  AND EXISTS (SELECT 1 FROM govaccess.application_document WHERE id=? AND application_id=?)
                """, id, id, applicationId) != 1) throw missing();
        var identity = ActorIdentity.of(actor);
        audit.append(new Change(applicationId, identity.id(), identity.role(), "DOCUMENT_REQUEUED",
                null, null, null, null, Map.of("documentId", id), null, null, null));
    }

    String json(Map<String, ExtractedField> fields) {
        try { return mapper.writeValueAsString(fields); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot save extracted fields", e); }
    }

    private Document read(ResultSet row, int index) throws SQLException {
        String json = row.getString("extracted_fields");
        Map<String, ExtractedField> fields;
        try {
            fields = json == null ? Map.of() : mapper.readerFor(new TypeReference<Map<String, ExtractedField>>() {})
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(json);
        } catch (JsonProcessingException e) {
            throw new SQLException("Cannot read extracted fields", e);
        }
        return new Document(row.getObject("id", UUID.class), row.getObject("application_id", UUID.class),
                row.getObject("uploaded_actor_id", UUID.class), row.getObject("policy_version_id", UUID.class),
                row.getString("original_filename"), row.getString("state"), row.getInt("version"),
                row.getObject("duplicate_of", UUID.class), row.getString("document_type"),
                row.getObject("ocr_confidence", Double.class), row.getString("error_message"), fields,
                row.getBoolean("instruction_like_content"));
    }

    private static String safeName(String name) {
        if (name == null) return "document.pdf";
        String base = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        base = base.replaceAll("[\\p{Cntrl}]", "").strip();
        base = base.replaceAll("(?<![0-9])[0-9]{13}(?![0-9])", "masked");
        return base.isBlank() ? "document.pdf" : base.substring(0, Math.min(base.length(), 255));
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
    }
}
