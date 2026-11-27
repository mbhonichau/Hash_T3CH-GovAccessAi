package com.hashtech.govaccessai;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.Set;
import za.gov.govaccess.ai.dto.AiRequest;
import za.gov.govaccess.ai.dto.AiCallContext;
import za.gov.govaccess.ai.provider.AiProviderException;
import za.gov.govaccess.ai.service.PolicyInterpretationService.Extraction;
import za.gov.govaccess.ai.service.StructuredOutputService;
import za.gov.govaccess.policy.repository.PolicyVersionRepository;
import za.gov.govaccess.policy.service.FinancialPolicyPreviewService;
import za.gov.govaccess.policy.service.RulePolicyService;
import za.gov.govaccess.programme.repository.ProgrammeRepository;
import za.gov.govaccess.rules.dto.VerifiedRuleEvidence;
import za.gov.govaccess.rules.enums.FieldValueType;
import za.gov.govaccess.rules.model.RuleDocument;
import za.gov.govaccess.rules.service.LegacyFinancialScreenReference;
import za.gov.govaccess.rules.service.RuleCatalogueService;
import za.gov.govaccess.rules.service.RecommendationService;
import za.gov.govaccess.rules.dto.FinancialScreenFacts;
import za.gov.govaccess.rules.enums.IncomePathway;
import za.gov.govaccess.rules.enums.VerifiedSassaGrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static za.gov.govaccess.rules.dto.FinancialScreenResult.Outcome.FAILS_FINANCIAL_SCREEN;
import static za.gov.govaccess.rules.dto.FinancialScreenResult.Outcome.NEEDS_REVIEW;
import static za.gov.govaccess.rules.dto.FinancialScreenResult.Outcome.PASSES_FINANCIAL_SCREEN;

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.security.user.password=login-test-password",
        "spring.security.user.roles=POLICY_EDIT",
        "govaccess.ai.providers[0].base-url=https://unused-provider.invalid/v1",
        "govaccess.ai.providers[0].model=test-hosted",
        "govaccess.ai.providers[0].hosted=true",
        "spring.datasource.url=jdbc:h2:mem:govaccess;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
class GovaccessaiApplicationTests {
    @Autowired org.springframework.jdbc.core.JdbcTemplate intakeJdbc;
    @Autowired za.gov.govaccess.ai.service.AiInputSafety aiSafety;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private StructuredOutputService structuredOutput;

    @Test
    @Transactional
    @WithMockUser(authorities = "POLICY_EDIT")
    void importsAndEditsDraftsUsingTheSameFinancialRuleContract() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/workspace/access").session(session).with(csrf()).param("mode", "POLICY_EDIT"))
                .andExpect(redirectedUrl("/workspace/access"));
        var grants = Set.of(VerifiedSassaGrant.FOSTER_CARE, VerifiedSassaGrant.CARE_DEPENDENCY,
                VerifiedSassaGrant.CHILD_SUPPORT);
        var requestContext = new java.util.concurrent.atomic.AtomicReference<AiCallContext>();
        when(structuredOutput.generate(any(AiRequest.class), eq(Extraction.class), any())).thenAnswer(invocation -> {
            AiRequest request = invocation.getArgument(0);
            assertThat(request.synthetic()).isTrue();
            assertThat(request.systemPrompt() + request.userContent()).doesNotContainIgnoringCase("disability");
            assertThat(request.userContent()).contains("IN_SET", "LESS_THAN_OR_EQUAL");
            assertThat(request.jsonSchema().at("/properties/incomeOperator/enum/0").asText())
                    .isEqualTo("LESS_THAN_OR_EQUAL");
            assertThat(request.context().userId()).isEqualTo(AiCallContext.forPrincipal(() -> "user", null, null).userId());
            assertThat(request.context().relatedEntityType()).isEqualTo("POLICY_VERSION");
            assertThat(request.context().relatedEntityId()).isNotNull();
            requestContext.set(request.context());
            return new Extraction(grants, new BigDecimal("350000"));
        });
        mvc.perform(post("/api/policies/interpret").param("versionLabel", "Test AI draft"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/policies/interpret").session(session).with(csrf()).param("versionLabel", "Test AI draft"))
                .andExpect(status().isCreated());
        var imported = policies.findByProgrammeCodeAndVersionLabel("NSFAS_BURSARY", "Test AI draft").orElseThrow();
        assertThat(imported.getOrigin()).isEqualTo("AI_GENERATED");
        assertThat(imported.getStatus()).isEqualTo("DRAFT");
        assertThat(imported.getId()).isEqualTo(requestContext.get().relatedEntityId());

        mvc.perform(get(java.net.URI.create("/policies/nsfas-2025?version=Test%20AI%20draft")).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("AI import complete.")))
                .andExpect(content().string(containsString("Rule builder")));
        mvc.perform(post("/policies/nsfas-2025/edit").session(session).with(csrf()).param("version", "Test AI draft")
                        .param("versionLabel", "Test edited draft").param("standardIncome", "349999")
                        .param("disabilityIncome", "600000").param("grants", "CHILD_SUPPORT"))
                .andExpect(status().is3xxRedirection());
        var result = financialPreview.preview("NSFAS_BURSARY", "Test edited draft",
                new FinancialScreenFacts(new BigDecimal("350000"), VerifiedSassaGrant.NONE_OR_NON_QUALIFYING,
                        IncomePathway.STANDARD));
        assertThat(result.outcome()).isEqualTo(FAILS_FINANCIAL_SCREEN);
        assertThat(policies.countByStatus("PUBLISHED")).isZero();

        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<Extraction> verify = invocation.getArgument(2);
            var invalid = new Extraction(grants, new BigDecimal("999999"));
            verify.accept(invalid);
            return invalid;
        }).when(structuredOutput).generate(any(AiRequest.class), eq(Extraction.class), any());
        mvc.perform(post("/api/policies/interpret").session(session).with(csrf()).param("versionLabel", "Invalid AI draft"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(policies.findByProgrammeCodeAndVersionLabel("NSFAS_BURSARY", "Invalid AI draft")).isEmpty();

        org.mockito.Mockito.doThrow(new AiProviderException(401)).when(structuredOutput)
                .generate(any(AiRequest.class), eq(Extraction.class), any());
        mvc.perform(post("/api/policies/interpret").session(session).with(csrf()).param("versionLabel", "Unauthorized AI"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("HTTP 401")))
                .andExpect(content().string(containsString("AI_API_KEY")));
    }

    @Test
    @WithMockUser
    void requiresPolicyEditAuthorityToImportOrEdit() throws Exception {
        mvc.perform(post("/api/admin/ai/probe").with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/ai/enabled").with(csrf()).param("enabled", "false"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/workspace/access").with(csrf()).param("mode", "POLICY_EDIT"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/policies/nsfas-2025"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("require the Policy Edit permission")));
        mvc.perform(post("/api/policies/interpret").with(csrf()).param("versionLabel", "Denied"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/policies/nsfas-2025/edit").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "AI_ADMIN")
    void authorizesTheProbeAndRuntimeToggleWithCsrf() throws Exception {
        mvc.perform(get("/admin/ai")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Test provider")));
        mvc.perform(post("/api/admin/ai/probe")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/ai/enabled").param("enabled", "false")).andExpect(status().isForbidden());
        when(structuredOutput.probe(any(AiRequest.class), any())).thenAnswer(invocation -> {
            AiRequest request = invocation.getArgument(0);
            assertThat(request.synthetic()).isTrue();
            assertThat(request.context().userId()).isNotNull();
            assertThat(request.context().relatedEntityType()).isEqualTo("AI_PROVIDER");
            assertThat(request.context().relatedEntityId()).isNotNull();
            return null;
        });
        mvc.perform(post("/api/admin/ai/probe").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"ok\":true")));
        mvc.perform(post("/admin/ai/probe").with(csrf())).andExpect(redirectedUrl("/admin/ai"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                        .attribute("successMessage", "The provider returned a valid test response."));
        try {
            mvc.perform(post("/admin/ai/enabled").with(csrf()).param("enabled", "false"))
                    .andExpect(redirectedUrl("/admin/ai"));
            mvc.perform(get("/admin/ai")).andExpect(content().string(containsString("Enable AI calls")));
            mvc.perform(get("/api/admin/ai/status"))
                    .andExpect(content().string(containsString("\"enabled\":false")));
        } finally {
            mvc.perform(post("/api/admin/ai/enabled").with(csrf()).param("enabled", "true"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void rendersTheSignInPageAndItsStylesBeforeAuthentication() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sign in · GovAccess AI")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        mvc.perform(get("/vendor/mazer/css/app.css")).andExpect(status().isOk());
        mvc.perform(get("/css/app.css")).andExpect(status().isOk());
    }

    @Test
    void loginWithAnExpiredFormReturnsToSignInWithoutAuthenticating() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/login").session(session).with(csrf().useInvalidToken())
                        .param("username", "user").param("password", "login-test-password"))
                .andExpect(redirectedUrl("/login?expired"));

        mvc.perform(get("/login?expired").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Your sign-in form expired")));
        mvc.perform(get("/").session(session)).andExpect(redirectedUrl("http://localhost/login"));

        mvc.perform(post("/login").session(session).with(csrf())
                        .param("username", "user").param("password", "login-test-password"))
                .andExpect(redirectedUrl("/"));
        mvc.perform(get("/").session(session)).andExpect(status().isOk());
    }

    @Test
    void signInOpensHomeEvenWhenAnErrorRequestWasSaved() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(get("/error").session(session))
                .andExpect(status().is3xxRedirection());

        mvc.perform(post("/login").session(session).with(csrf())
                        .param("username", "user")
                        .param("password", "login-test-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        mvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Eligibility overview")))
                .andExpect(content().string(containsString("Hosted AI configured. Synthetic data only.")))
                .andExpect(content().string(containsString("2025 financial screening")));
        mvc.perform(get("/system/status").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Application health")));

        mvc.perform(get("/workspace/access").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Switch to Policy Edit")));
        mvc.perform(post("/api/policies/interpret").session(session).with(csrf()).param("versionLabel", "Denied"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/workspace/access").session(session).param("mode", "POLICY_EDIT"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/workspace/access").session(session).with(csrf()).param("mode", "POLICY_EDIT"))
                .andExpect(redirectedUrl("/workspace/access"));
        mvc.perform(get("/policies/nsfas-2025").session(session))
                .andExpect(content().string(containsString("Save as new draft")));
        mvc.perform(post("/workspace/access").session(session).with(csrf()).param("mode", "REVIEW"))
                .andExpect(redirectedUrl("/workspace/access"));
        mvc.perform(post("/policies/nsfas-2025/edit").session(session).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "AUDIT_VERIFY")
    void showsAiGovernanceAndBlocksRecognisedIdsWithHostedAi() throws Exception {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> aiSafety.checkText("Example ID 0000000000018"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity number");
        mvc.perform(get("/audit/ai").param("outcome", "RATE_LIMITED")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Rate-limited requests")));
        mvc.perform(get("/audit/ai").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("officer").authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("CASE_REVIEW"))))
                .andExpect(status().isForbidden());
    }

    @Autowired
    private PolicyVersionRepository policies;

    @Autowired
    private ProgrammeRepository programmes;

    @Test
    @Transactional
    @WithMockUser(authorities = "POLICY_EDIT")
    void loadsAndPreviewsTheBundledDraftWhenThePolicyIsMissing() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/workspace/access").session(session).with(csrf()).param("mode", "POLICY_EDIT"))
                .andExpect(redirectedUrl("/workspace/access"));
        long programmeCount = programmes.count();
        intakeJdbc.update("UPDATE govaccess.assessment SET policy_version_id=NULL");
        policies.deleteAllInBatch();
        mvc.perform(get("/policies/nsfas-2025").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Load NSFAS draft")));
        mvc.perform(post("/policies/nsfas-2025/load")).andExpect(status().isForbidden());
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/policies/nsfas-2025/load").session(session).with(csrf()))
                    .andExpect(redirectedUrl("/policies/nsfas-2025"));
        }
        assertThat(programmes.count()).isEqualTo(programmeCount);
        assertThat(policies.countByStatus("DRAFT")).isEqualTo(1);
        assertThat(policies.countByStatus("PUBLISHED")).isZero();
        mvc.perform(get("/policies/nsfas-2025"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Section 4.4.3.1")));
        mvc.perform(post("/policies/nsfas-2025/preview").with(csrf())
                        .param("income", "350000").param("grant", "NONE_OR_NON_QUALIFYING")
                        .param("pathway", "STANDARD"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Passes financial screen")));
        mvc.perform(post("/policies/nsfas-2025/preview").with(csrf()).param("income", "-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Enter a nonnegative income")));
    }

    @Autowired
    private FinancialPolicyPreviewService financialPreview;

    @Autowired
    private RulePolicyService rulePolicies;

    @Autowired
    private RuleCatalogueService catalogue;

    @Autowired
    private RecommendationService recommendations;

    @Test
    void preservesLegacyNsfasResultsAcrossTheFullSyntheticBoundaryMatrix() {
        var version = policies.findByProgrammeCodeAndVersionLabel("NSFAS_BURSARY", "2025 financial screening")
                .orElseThrow();
        assertThat(rulePolicies.read(version.getContent()).notice()).contains("DEMO DATA ONLY");
        var legacy = new LegacyFinancialScreenReference();
        var rules = financialPreview.readRules(version);
        var grants = new java.util.ArrayList<>(java.util.List.of(VerifiedSassaGrant.values()));
        grants.add(null);
        for (var grant : grants) {
            for (var pathway : java.util.Arrays.asList(null, IncomePathway.STANDARD, IncomePathway.DISABILITY)) {
                for (var amount : java.util.Arrays.asList(null, "0", "349999.99", "350000", "350000.01",
                        "599999.99", "600000", "600000.01")) {
                    var facts = new FinancialScreenFacts(amount == null ? null : new BigDecimal(amount), grant, pathway);
                    assertThat(financialPreview.preview("NSFAS_BURSARY", "2025 financial screening", facts))
                            .as("parity for %s", facts).isEqualTo(legacy.evaluate(rules, facts));
                }
            }
        }
    }

    @Test
    @Transactional
    @WithMockUser(authorities = "POLICY_EDIT")
    void addsCatalogueEntriesInTheUiAndEvaluatesAFourthProgrammeThroughServices() throws Exception {
        assertThat(programmes.findAll()).hasSize(3).allMatch(programme -> programme.isDemoData());
        var session = new MockHttpSession();
        mvc.perform(get("/rules/catalogue").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Housing Support Subsidy")));
        mvc.perform(post("/rules/catalogue/documents").session(session).with(csrf())
                        .param("key", "syntheticLetter").param("label", "Synthetic letter"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/workspace/access").session(session).with(csrf()).param("mode", "POLICY_EDIT"))
                .andExpect(redirectedUrl("/workspace/access"));
        mvc.perform(post("/rules/catalogue/documents").session(session).with(csrf())
                        .param("key", "syntheticLetter").param("label", "Synthetic letter"))
                .andExpect(redirectedUrl("/rules/catalogue"));
        mvc.perform(post("/rules/catalogue/fields").session(session).with(csrf())
                        .param("key", "fourth.verifiedAmount").param("label", "Verified amount")
                        .param("valueType", "MONEY").param("unit", "ZAR/year").param("sources", "syntheticLetter"))
                .andExpect(redirectedUrl("/rules/catalogue"));
        assertThat(catalogue.catalogue().fields().get("fourth.verifiedAmount").getValueType())
                .isEqualTo(FieldValueType.MONEY);

        var programme = rulePolicies.createProgramme("FOURTH_DEMO", "Fourth programme fixture");
        var expected = com.fasterxml.jackson.databind.node.DecimalNode.valueOf(new BigDecimal("1000.25"));
        var rule = new RuleDocument.Rule("cap", "Annual cap", "fourth.verifiedAmount",
                za.gov.govaccess.rules.enums.RuleOperator.LESS_THAN_OR_EQUAL, expected, "ZAR/year", true,
                java.util.List.of("syntheticLetter"), RuleDocument.OnMissingEvidence.UNKNOWN,
                new RuleDocument.Source("Synthetic test policy", "1", 1, "Fixture cap"), null, null);
        var saved = rulePolicies.saveDraft(programme.getCode(), "First", new RuleDocument(1, java.util.List.of(rule), null));
        assertThat(programmes.count()).isEqualTo(4);
        assertThat(saved.getRuleJson()).isNull();
        var stored = rulePolicies.read(saved.getContent());
        var evaluations = rulePolicies.evaluate(stored,
                java.util.List.of(new VerifiedRuleEvidence("letter-1", "syntheticLetter", "fourth.verifiedAmount", expected)));
        assertThat(recommendations.recommend(stored, evaluations, false))
                .isEqualTo(RecommendationService.Recommendation.LIKELY_ELIGIBLE);
    }

    @Test
    void previewsTheSeededNsfasFinancialDraft() {
        var draft = policies.findByProgrammeCodeAndVersionLabel("NSFAS_BURSARY", "2025 financial screening")
                .orElseThrow();
        assertThat(draft.getStatus()).isEqualTo("DRAFT");
        assertThat(draft.getOrigin()).isEqualTo("MANUAL");

        assertThat(preview("350000", VerifiedSassaGrant.NONE_OR_NON_QUALIFYING, IncomePathway.STANDARD))
                .satisfies(result -> {
                    assertThat(result.outcome()).isEqualTo(PASSES_FINANCIAL_SCREEN);
                    assertThat(result.sourceSection()).isEqualTo("4.4.3.1");
                });
        assertThat(preview("350000.01", VerifiedSassaGrant.NONE_OR_NON_QUALIFYING, IncomePathway.STANDARD).outcome())
                .isEqualTo(FAILS_FINANCIAL_SCREEN);
        assertThat(preview("600000", VerifiedSassaGrant.NONE_OR_NON_QUALIFYING, IncomePathway.DISABILITY).outcome())
                .isEqualTo(PASSES_FINANCIAL_SCREEN);
        assertThat(preview("600000.01", VerifiedSassaGrant.NONE_OR_NON_QUALIFYING, IncomePathway.DISABILITY).outcome())
                .isEqualTo(FAILS_FINANCIAL_SCREEN);
        assertThat(preview(null, VerifiedSassaGrant.CHILD_SUPPORT, null).outcome())
                .isEqualTo(PASSES_FINANCIAL_SCREEN);
        assertThat(preview(null, null, null).outcome()).isEqualTo(NEEDS_REVIEW);
        assertThat(preview("350000.01", null, IncomePathway.STANDARD).outcome()).isEqualTo(NEEDS_REVIEW);
    }

    private za.gov.govaccess.rules.dto.FinancialScreenResult preview(String income, VerifiedSassaGrant grant,
                                                                   IncomePathway incomePathway) {
        var facts = new FinancialScreenFacts(income == null ? null : new BigDecimal(income), grant, incomePathway);
        return financialPreview.preview("NSFAS_BURSARY", "2025 financial screening", facts);
    }

    @Test
    @WithMockUser
    void rendersThymeleafAndServesLocalBootstrap() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<h1")))
                .andExpect(content().string(containsString("/webjars/bootstrap/5.3.8/css/bootstrap.min.css")));

        mvc.perform(get("/webjars/bootstrap/5.3.8/css/bootstrap.min.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"));
    }

    @Test
    void extractsTextFromASyntheticPolicyPdf() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(40, 700);
                stream.showText("Synthetic programme income threshold");
                stream.endText();
            }
            pdf.save(bytes);
        }

        var pages = new PagePdfDocumentReader(new ByteArrayResource(bytes.toByteArray())).get();
        assertThat(pages).singleElement().satisfies(page ->
                assertThat(page.getText().strip().replaceAll("\\s+", " ")).isEqualTo("Synthetic programme income threshold"));
    }
}
