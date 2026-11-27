package za.gov.govaccess.document.service;

import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import za.gov.govaccess.ai.dto.AiAttachment;
import za.gov.govaccess.ai.dto.AiCallContext;
import za.gov.govaccess.ai.service.ApplicationDocumentInterpreter;
import za.gov.govaccess.ai.provider.AiProviderException;
import za.gov.govaccess.audit.service.BusinessAuditService;
import za.gov.govaccess.audit.service.BusinessAuditService.Change;
import za.gov.govaccess.document.dto.ExtractedField;
import za.gov.govaccess.document.dto.ExtractedField.Validation;
import za.gov.govaccess.document.service.ApplicationDocuments.Document;

@Component
@ConditionalOnProperty(prefix = "govaccess.documents", name = "worker-enabled", matchIfMissing = true)
public class DocumentExtractionWorker {
    private final JdbcTemplate jdbc;
    private final ApplicationDocuments documents;
    private final PrivateDocumentStorage storage;
    private final DocumentOcr ocr;
    private final DocumentFieldPlan plan;
    private final ApplicationDocumentInterpreter ai;
    private final BusinessAuditService audit;
    private final TransactionTemplate transactions;
    private final boolean images;
    private final int textThreshold;
    private final double lowConfidence;
    private final za.gov.govaccess.ai.service.AiInputSafety safety;

    public DocumentExtractionWorker(JdbcTemplate jdbc, ApplicationDocuments documents, PrivateDocumentStorage storage,
                                    DocumentOcr ocr, DocumentFieldPlan plan, ApplicationDocumentInterpreter ai,
                                    BusinessAuditService audit, org.springframework.transaction.PlatformTransactionManager manager,
                                    @Value("${govaccess.documents.image-extraction-enabled:false}") boolean images,
                                    @Value("${govaccess.documents.min-text-characters:40}") int textThreshold,
                                    @Value("${govaccess.documents.low-ocr-confidence:65}") double lowConfidence,
                                    za.gov.govaccess.ai.service.AiInputSafety safety) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.storage = storage;
        this.ocr = ocr;
        this.plan = plan;
        this.ai = ai;
        this.audit = audit;
        this.transactions = new TransactionTemplate(manager);
        this.images = images;
        this.textThreshold = textThreshold;
        this.lowConfidence = lowConfidence;
        this.safety = safety;
    }

    private record Scan(int page, BufferedImage image, DocumentOcr.Result result) {}
    private record Result(String type, Double confidence, Map<String, ExtractedField> fields) {}

    @Scheduled(fixedDelayString = "${govaccess.documents.poll-ms:2000}")
    public void processNext() {
        jdbc.update("""
                UPDATE govaccess.document_processing SET state='FAILED',error_message='Processing timed out. Ask an officer to retry.'
                WHERE state='RUNNING' AND started_at<?
                """, OffsetDateTime.now().minusMinutes(30));
        var id = jdbc.query("""
                SELECT j.document_id FROM govaccess.document_processing j JOIN govaccess.application_document d
                ON d.id=j.document_id WHERE j.state='QUEUED' ORDER BY d.created_at,j.document_id LIMIT 1
                """, (row, index) -> row.getObject(1, UUID.class)).stream().findFirst();
        if (id.isEmpty() || jdbc.update("""
                UPDATE govaccess.document_processing SET state='RUNNING',started_at=CURRENT_TIMESTAMP
                WHERE document_id=? AND state='QUEUED'
                """, id.get()) != 1) return;
        var document = documents.find(id.get()).orElseThrow();
        try {
            var result = interpret(document);
            if (document.fields().values().stream().anyMatch(ExtractedField::verified)
                    && !java.util.Objects.equals(document.type(), result.type())) {
                throw new IllegalStateException("Reclassification conflicts with verified fields");
            }
            var fields = new LinkedHashMap<>(result.fields());
            document.fields().forEach((key, value) -> {
                if (value.humanCorrected() || value.verified()) fields.put(key, value);
            });
            transactions.executeWithoutResult(transaction -> {
                if (jdbc.update("""
                        UPDATE govaccess.document_processing
                        SET state='SUCCEEDED',document_type=?,ocr_confidence=?,extracted_fields=?
                        WHERE document_id=? AND state='RUNNING' AND version=?
                        """, result.type(), result.confidence(), documents.json(fields), document.id(), document.version()) == 1) {
                    String status = !fields.isEmpty() && fields.values().stream().allMatch(ExtractedField::verified)
                            ? "VERIFIED" : "PROCESSED";
                    jdbc.update("UPDATE govaccess.application_document SET processing_status=? WHERE id=?", status, document.id());
                    audit.append(new Change(document.applicationId(), document.uploadedBy(), "SYSTEM",
                            "DOCUMENT_EXTRACTED", document.policyId(), null, null, null,
                            Map.of("documentId", document.id(), "version", document.version()), null, null, null));
                }
            });
        } catch (IOException | RuntimeException | LinkageError e) {
            String message = e instanceof AiProviderException provider && provider.reason() == AiProviderException.Reason.RATE_LIMITED
                    ? provider.getMessage() : e instanceof IllegalArgumentException argument && argument.getMessage() != null
                        && (argument.getMessage().startsWith("A hosted AI provider is active") || argument.getMessage().startsWith("Document text exceeds"))
                    ? argument.getMessage() : e instanceof ApplicationDocumentInterpreter.DocumentTypeMismatch
                    ? "The document does not match the expected type. Review it manually."
                    : e instanceof AiProviderException || e instanceof za.gov.govaccess.ai.service.AiOutputInvalidException
                    ? "Local AI is unavailable or returned invalid output."
                    : "The document could not be processed. Check PDF quality and local OCR setup.";
            int failed = jdbc.update("""
                    UPDATE govaccess.document_processing SET state='FAILED',error_message=?
                    WHERE document_id=? AND state='RUNNING' AND version=?
                    """, message, document.id(), document.version());
            if (failed == 1 && message.startsWith("A hosted AI provider is active.")) {
                try { storage.delete(document.id()); }
                catch (IOException cleanup) {
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Could not remove blocked document {}", document.id());
                }
            }
        }
    }

    private Result interpret(Document document) throws IOException {
        var text = new ArrayList<String>();
        var scans = new ArrayList<Scan>();
        byte[] pdfBytes = storage.read(document.id());
        try (var pdf = Loader.loadPDF(pdfBytes)) {
            var reader = new PDFTextStripper();
            var renderer = new PDFRenderer(pdf);
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                reader.setStartPage(page);
                reader.setEndPage(page);
                String value = reader.getText(pdf).strip();
                if (value.length() < textThreshold) {
                    var image = renderer.renderImageWithDPI(page - 1, 125);
                    var result = ocr.read(image);
                    value = result.text();
                    scans.add(new Scan(page, images ? image : null, result));
                }
                text.add(value);
            }
        }
        String extractedText = String.join("\n", text);
        if (za.gov.govaccess.ai.service.AiInputSafety.instructionLike(extractedText)) {
            safety.flagDocument(document.id(), document.uploadedBy(), document.applicationId(), document.policyId());
        }
        safety.checkText(extractedText);
        var context = new AiCallContext(document.uploadedBy(), "APPLICATION_DOCUMENT", document.id(), document.applicationId());
        var mean = scans.stream().mapToDouble(scan -> scan.result().confidence()).average();
        Double confidence = mean.isPresent() ? mean.getAsDouble() : null;
        String type = ai.classify(text, context);
        if (type == null) return new Result(null, confidence, Map.of());
        var fields = plan.fields(document.policyId(), type);
        if ("IDENTITY_DOCUMENT".equals(type)) {
            var identity = IdentityNumberValidator.find(String.join("\n", text), LocalDate.now());
            var codes = fields.values().stream().flatMap(field -> field.ruleCodes().stream()).distinct().toList();
            var reference = identity.masked() == null ? null : TextNode.valueOf(identity.masked());
            return new Result(type, confidence, Map.of(
                    "identity.maskedReference", new ExtractedField(reference, 0, null, null,
                            document.id(), document.version(), codes, identity.valid() ? Validation.PASSED : Validation.FAILED, false, false),
                    "identity.valid", new ExtractedField(BooleanNode.valueOf(identity.valid()), 0, null, null,
                            document.id(), document.version(), codes, identity.valid() ? Validation.PASSED : Validation.FAILED, false, false)));
        }
        if (fields.isEmpty()) return new Result(type, confidence, Map.of());
        var first = ai.extract(type, fields, text, context, document.version(), List.of());
        var scan = scans.stream().filter(page -> page.result().confidence() < lowConfidence && page.image() != null)
                .min(java.util.Comparator.comparingDouble(page -> page.result().confidence()));
        if (scan.isEmpty()) return new Result(type, confidence, first);
        var attachment = safeImage(scan.get(), fields, text);
        if (attachment == null) return new Result(type, confidence, first);
        try {
            var second = ai.extract(type, fields, text, context, document.version(), List.of(attachment));
            var best = new LinkedHashMap<>(first);
            second.forEach((key, value) -> best.merge(key, value, (a, b) ->
                    b.value() != null && (a.value() == null || b.confidence() > a.confidence()) ? b : a));
            return new Result(type, confidence, best);
        } catch (AiProviderException | za.gov.govaccess.ai.service.AiOutputInvalidException e) {
            return new Result(type, confidence, first);
        }
    }

    private static AiAttachment safeImage(Scan scan, Map<String, DocumentFieldPlan.Field> fields,
                                          List<String> pages) throws IOException {
        var allowed = new java.util.HashSet<String>();
        fields.forEach((key, field) -> allowed.addAll(ApplicationDocumentInterpreter.passages(pages, key, field)
                .getOrDefault(scan.page(), List.of())));
        var regions = scan.result().lines().stream().filter(line -> allowed.contains(line.text().strip())).toList();
        if (regions.isEmpty()) return null;
        var source = scan.image();
        var canvas = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        var painter = canvas.createGraphics();
        try {
            painter.setColor(Color.WHITE);
            painter.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
            for (var region : regions) {
                Rectangle box = region.bounds().intersection(new Rectangle(0, 0, canvas.getWidth(), canvas.getHeight()));
                if (!box.isEmpty()) painter.drawImage(source.getSubimage(box.x, box.y, box.width, box.height), box.x, box.y, null);
            }
        } finally {
            painter.dispose();
        }
        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(canvas, "png", output);
            return new AiAttachment(output.toByteArray(), "image/png");
        }
    }
}
