package za.gov.govaccess.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hashtech.govaccessai.GovaccessaiApplication;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import za.gov.govaccess.ai.dto.AiCallContext;
import za.gov.govaccess.ai.repository.AiRequestLogRepository;
import za.gov.govaccess.policy.dto.PolicyImportJob.Status;
import za.gov.govaccess.policy.repository.PolicyImportRepository;
import za.gov.govaccess.policy.repository.PolicyVersionRepository;
import za.gov.govaccess.policy.service.PolicyImportJobs;
import za.gov.govaccess.policy.service.PolicyImportWorker;
import za.gov.govaccess.rules.enums.FieldValueType;
import za.gov.govaccess.rules.service.RuleCatalogueService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = GovaccessaiApplication.class, properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:document-import;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "govaccess.policy-import.worker-enabled=false", "govaccess.rules.max-group-depth=2"
})
@AutoConfigureMockMvc
class PolicyDocumentImportTests {
    private static final MockWebServer SERVER = localServer();
    private static final String PROGRAMME = "NSFAS_BURSARY";
    private static final String SENTENCE = "Applicants must be at least 18 years old, have an annual household income "
            + "not exceeding R350,000, have proof of acceptance at an approved institution and have an academic average of at least 60%.";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired RuleCatalogueService catalogue;
    @Autowired PolicyImportJobs jobs;
    @Autowired PolicyImportWorker worker;
    @Autowired PolicyImportRepository jobRows;
    @Autowired PolicyVersionRepository versions;
    @Autowired AiRequestLogRepository audit;
    private final java.security.Principal owner = () -> "editor";

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        registry.add("govaccess.ai.providers[0].name", () -> "local-test");
        registry.add("govaccess.ai.providers[0].base-url", () -> SERVER.url("/v1/").toString());
        registry.add("govaccess.ai.providers[0].model", () -> "stub");
        registry.add("govaccess.ai.providers[0].hosted", () -> "false");
    }

    @BeforeEach
    void prepareCatalogue() throws InterruptedException {
        while (SERVER.takeRequest(0, TimeUnit.MILLISECONDS) != null) {
            // Discard captured requests from the previous method.
        }
        if (catalogue.catalogue().documentTypes().contains("acceptance")) {
            return;
        }
        catalogue.addDocument("acceptance", "Acceptance proof");
        catalogue.addField("age", "Age", FieldValueType.INTEGER, "years", Set.of("acceptance"));
        catalogue.addField("average", "Academic average", FieldValueType.DECIMAL, "%", Set.of("acceptance"));
    }

    @AfterAll
    static void close() throws IOException {
        SERVER.close();
    }

    @Test
    @WithMockUser(username = "editor", authorities = "POLICY_EDIT")
    void importsFourBursaryRulesAndKeepsTheOriginalValueWhenEdited() throws Exception {
        var response = response(
                rule("age", "age", "GREATER_THAN_OR_EQUAL", mapper.valueToTree(18), "years",
                        "Applicants must be at least 18 years old", 1, "Policy text"),
                rule("income", "nsfas.annualGrossHouseholdIncome", "LESS_THAN_OR_EQUAL", mapper.valueToTree(350000),
                        "ZAR/year", "have an annual household income not exceeding R350,000", 1, "Policy text"),
                rule("acceptance", null, "DOCUMENT_REQUIRED", mapper.valueToTree("acceptance"), null,
                        "have proof of acceptance at an approved institution", 1, "Policy text"),
                rule("average", "average", "GREATER_THAN_OR_EQUAL", mapper.valueToTree(60), "%",
                        "have an academic average of at least 60%.", 1, "Policy text"));
        response.put("effectiveFrom", "2025-01-01");
        response.putArray("unmappedRequirements").add("A reviewer must confirm the approved list.");
        response.putArray("exemptions").add("No exemptions stated.");
        response.putArray("calculationMethods").add("Use the academic average.");
        ((ObjectNode) response.path("rules").get(3)).put("ambiguityNote", "The averaging period is unspecified.")
                .put("confidence", 0.9);
        enqueue(response, 900);
        var session = editorSession();
        UUID id = queue(session, "Four-rule bursary", SENTENCE, null);
        assertThat(jobs.owned(id, owner).status()).isEqualTo(Status.QUEUED);
        mvc.perform(get("/policies/imports/{id}", id).session(session))
                .andExpect(status().isOk()).andExpect(content().string(containsString("data-poll-url")));
        mvc.perform(get("/api/policies/imports/{id}", id).with(user("other")))
                .andExpect(status().isNotFound());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var task = executor.submit(worker::processNext);
            var request = SERVER.takeRequest(4, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getBody().readUtf8()).contains("LESS_THAN_OR_EQUAL", "nsfas.annualGrossHouseholdIncome",
                    "<policy>", "</policy>", "character for character");
            assertThat(jobs.owned(id, owner).status()).isEqualTo(Status.RUNNING);
            task.get(6, TimeUnit.SECONDS);
        }

        mvc.perform(get("/api/policies/imports/{id}", id).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.completedChunks").value(1));
        mvc.perform(get("/policies/imports/{id}/review", id).session(session))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Low confidence")))
                .andExpect(content().string(containsString("2025-01-01")))
                .andExpect(content().string(containsString("Human review required")))
                .andExpect(content().string(containsString("The averaging period is unspecified.")))
                .andExpect(content().string(containsString("Use the academic average.")))
                .andExpect(content().string(containsString("No exemptions stated.")))
                .andExpect(content().string(containsString("A reviewer must confirm the approved list.")));
        var job = jobs.owned(id, owner);
        var version = versions.findById(job.draftId()).orElseThrow();
        assertThat(version.getStatus()).isEqualTo("DRAFT");
        assertThat(version.getOrigin()).isEqualTo("AI_GENERATED");
        assertThat(jobs.review(id, owner).rows()).hasSize(4);
        assertThat(jobs.review(id, owner).imported().ruleNotes().get("age").requiresHumanReview()).isTrue();
        assertThat(jobs.review(id, owner).imported().confidence().get("average")).isEqualByComparingTo("0.59");
        assertThat(audit.findAll()).anyMatch(row -> job.draftId().equals(row.getRelatedEntityId())
                && AiCallContext.forPrincipal(owner, null, null).userId().equals(row.getUserId()));

        mvc.perform(post("/policies/imports/{id}/edit", id).session(session).with(csrf())
                        .param("ruleCode", "income").param("expectedValue", "349999"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/policies/imports/{id}/review", id).session(session))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Modified from source")))
                .andExpect(content().string(containsString("349999")));
        var edit = jobRows.edits(id).getFirst();
        assertThat(edit.editor()).isEqualTo("editor");
        assertThat(edit.originalValue()).isEqualTo("350000");
        assertThat(edit.newValue()).isEqualTo("349999");
    }

    @Test
    @WithMockUser(username = "editor", authorities = "POLICY_EDIT")
    void mergesChunksAndKeepsRejectedSuggestionsWithReasons() throws Exception {
        String first = "First threshold is “18 years”.";
        String second = "Second threshold is 18 years.";
        var a = rule("accepted", "age", "GREATER_THAN_OR_EQUAL", mapper.valueToTree(18), "years",
                "First threshold is \"18 years\".", 1, "Basics");
        var duplicateCode = rule("accepted", "age", "GREATER_THAN_OR_EQUAL", mapper.valueToTree(19), "years",
                second, 1, "Review");
        var duplicateSemantic = rule("same-value", "age", "GREATER_THAN_OR_EQUAL", mapper.valueToTree(18), "years",
                second, 1, "Review");
        var invented = rule("invented", "age", "GREATER_THAN_OR_EQUAL", mapper.valueToTree(19), "years",
                "Words that do not occur in the source", 1, "Review");
        var unknown = rule("unknown", "missing-field", "EQUALS", mapper.valueToTree(19), null, second, 1, "Review");
        var deepLeaf = rule("deep-leaf", "age", "EQUALS", mapper.valueToTree(31), "years", second, 1, "Review");
        var deep = group("deep-root", second, "Review", group("deep-mid", second, "Review",
                group("deep-third", second, "Review", deepLeaf)));
        var nested = group("accepted-group", second, "Review", rule("child-rule", "age", "EQUALS",
                mapper.valueToTree(19), "years", second, 1, "Review"));
        var badOperator = rule("bad-operator", "age", "MAYBE", mapper.valueToTree(19), "years", second, 1, "Review");
        var wrongType = rule("wrong-type", "age", "EQUALS", mapper.valueToTree("nineteen"), "years", second, 1, "Review");
        enqueue(response(a), 0);
        enqueue(response(duplicateCode, duplicateSemantic, invented, unknown, nested, deep, badOperator, wrongType), 0);
        UUID id = queue(editorSession(), "Merged rules", "# Basics\n" + first + "\n# Review\n" + second, null);
        worker.processNext();

        var review = jobs.review(id, owner);
        assertThat(review.rows()).extracting(row -> row.rule().ruleCode())
                .containsExactly("accepted", "accepted-group", "child-rule");
        assertThat(review.imported().rejectedRules()).extracting(item -> item.reason())
                .anyMatch(reason -> reason.contains("Duplicate"))
                .anyMatch(reason -> reason.contains("quote"))
                .anyMatch(reason -> reason.contains("Unknown rule field"))
                .anyMatch(reason -> reason.contains("depth"))
                .anyMatch(reason -> reason.contains("Unknown operator"))
                .anyMatch(reason -> reason.contains("match field type"));
        assertThat(review.imported().rejectedRules()).hasSize(7);
        assertThat(jobs.owned(id, owner).completedChunks()).isEqualTo(2);
    }

    @Test
    @WithMockUser(username = "editor", authorities = "POLICY_EDIT")
    void storesPdfPrivatelyAndHandlesUnreadableInput() throws Exception {
        String sentence = "The applicant must be at least 18 years old.";
        enqueue(response(rule("pdf-age", "age", "GREATER_THAN_OR_EQUAL", mapper.valueToTree(18), "years",
                sentence, 1, "Policy text")), 0);
        byte[] pdf = pdf(sentence);
        var session = editorSession();
        var id = queue(session, "PDF draft", null, pdf);
        assertThat(jobRows.source(id).pdf()).isEqualTo(pdf);
        worker.processNext();
        assertThat(jobs.review(id, owner).rows()).singleElement()
                .satisfies(row -> assertThat(row.rule().source().page()).isEqualTo(1));

        var corrupt = queue(session, "Invalid PDF draft", null, new byte[]{1, 2, 3});
        worker.processNext();
        assertThat(jobs.owned(corrupt, owner).status()).isEqualTo(Status.FAILED);
        assertThat(jobs.owned(corrupt, owner).failure()).contains("could not be read");
        assertThat(versions.findById(jobs.owned(corrupt, owner).draftId())).isEmpty();

        var interrupted = queue(session, "Interrupted job", SENTENCE, null);
        assertThat(jobRows.claim(interrupted)).isTrue();
        jobRows.expireRunningBefore(java.time.Instant.now().plusSeconds(1));
        assertThat(jobs.owned(interrupted, owner).status()).isEqualTo(Status.FAILED);
    }

    private UUID queue(MockHttpSession session, String label, String text, byte[] pdf) throws Exception {
        var request = multipart("/policies/imports");
        if (pdf != null) {
            request.file(new MockMultipartFile("pdf", "policy.pdf", "application/pdf", pdf));
        }
        if (text != null) {
            request.param("text", text);
        }
        var result = mvc.perform(request.session(session).with(csrf()).param("programmeCode", PROGRAMME)
                        .param("versionLabel", label).param("documentTitle", "Synthetic bursary source"))
                .andExpect(status().is3xxRedirection()).andReturn();
        var redirect = result.getResponse().getRedirectedUrl();
        return UUID.fromString(redirect.substring(redirect.lastIndexOf('/') + 1));
    }

    private MockHttpSession editorSession() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/workspace/access").session(session).with(csrf()).param("mode", "POLICY_EDIT"))
                .andExpect(redirectedUrl("/workspace/access"));
        return session;
    }

    private ObjectNode response(ObjectNode... rules) {
        var result = mapper.createObjectNode().put("schemaVersion", 1).putNull("effectiveFrom");
        var array = result.putArray("rules");
        for (var rule : rules) {
            array.add(rule);
        }
        for (var key : java.util.List.of("exceptions", "definitions", "requiredDocuments",
                "unmappedRequirements", "ambiguities", "exemptions", "calculationMethods")) {
            result.putArray(key);
        }
        return result;
    }

    private ObjectNode rule(String code, String field, String operator, JsonNode value, String unit, String quote,
                            int page, String section) {
        var rule = mapper.createObjectNode().put("ruleCode", code).put("name", code).put("fieldKey", field)
                .put("operator", operator).put("unit", unit).put("mandatory", true)
                .put("onMissingEvidence", "UNKNOWN").put("confidence", 0.5)
                .putNull("ambiguityNote").put("requiresHumanReview", false);
        rule.putArray("requiredEvidence");
        rule.putObject("source").put("page", page).put("section", section).put("quote", quote);
        rule.set("expectedValue", value);
        return rule;
    }

    private ObjectNode group(String code, String quote, String section, ObjectNode child) {
        var group = rule(code, null, "AND", null, null, quote, 1, section);
        group.putObject("group").put("type", "AND").putArray("rules").add(child);
        return group;
    }

    private void enqueue(ObjectNode response, long delay) throws IOException {
        var body = mapper.createObjectNode().put("model", "stub");
        body.putArray("choices").addObject().putObject("message").put("content", mapper.writeValueAsString(response));
        SERVER.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
                .setBodyDelay(delay, TimeUnit.MILLISECONDS));
    }

    private static byte[] pdf(String line) throws IOException {
        try (var pdf = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(40, 700);
                stream.showText(line);
                stream.endText();
            }
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private static MockWebServer localServer() {
        try {
            var server = new MockWebServer();
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
