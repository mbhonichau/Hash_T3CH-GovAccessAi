package com.hashtech.govaccessai;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import za.gov.govaccess.policy.repository.PolicyVersionRepository;
import za.gov.govaccess.application.repository.ApplicationRecordRepository;
import za.gov.govaccess.policy.service.FinancialPolicyPreviewService;
import za.gov.govaccess.security.AccountBootstrap;
import za.gov.govaccess.security.UserAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import za.gov.govaccess.rules.dto.FinancialScreenFacts;
import za.gov.govaccess.rules.enums.IncomePathway;
import za.gov.govaccess.rules.enums.VerifiedSassaGrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
        "govaccess.bootstrap.username=user",
        "govaccess.bootstrap.password=login-test-password",
        "spring.datasource.url=jdbc:h2:mem:govaccess;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
class GovaccessaiApplicationTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserAccountRepository userAccounts;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AccountBootstrap accountBootstrap;

    @Test
    void bootstrapPersistsBcryptCredentialsAndDoesNotRotateThemOnRestart() {
        var account = userAccounts.findByUsernameIgnoreCase("user").orElseThrow();
        var storedHash = account.getPasswordHash();
        assertThat(storedHash).startsWith("$2");
        assertThat(passwordEncoder.matches("login-test-password", storedHash)).isTrue();

        accountBootstrap.run(new DefaultApplicationArguments(new String[0]));

        assertThat(userAccounts.findByUsernameIgnoreCase("user").orElseThrow().getPasswordHash())
                .isEqualTo(storedHash);
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
    void signInOpensHomeEvenWhenAnErrorRequestWasSaved() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(get("/error").session(session))
                .andExpect(status().is3xxRedirection());
        var originalSessionId = session.getId();

        mvc.perform(post("/login").session(session).with(csrf())
                        .param("username", "user")
                        .param("password", "login-test-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        assertThat(session.getId()).isNotEqualTo(originalSessionId);

        mvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Eligibility overview")))
                .andExpect(content().string(containsString("2025 financial screening")));
    }

    @Test
    @WithMockUser(authorities = "PERM_DASHBOARD_READ")
    void logoutRequiresCsrfAndUsesPost() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/logout").session(session).with(csrf().asHeader()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
    }

    @Autowired
    private PolicyVersionRepository policies;

    @Autowired
    private FinancialPolicyPreviewService financialPreview;

    @Autowired
    private ApplicationRecordRepository applicationRecords;

    @Test
    @WithMockUser(username = "intake-officer", authorities = {
            "PERM_DASHBOARD_READ", "PERM_APPLICATION_READ", "PERM_APPLICATION_CREATE"})
    void staffCanCreateARecordAndReopenItFromTheQueue() throws Exception {
        assertThat(applicationRecords.count()).isEqualTo(3);
        mvc.perform(get("/applications"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("DEMO-NSFAS-ELIGIBLE-001")))
                .andExpect(content().string(containsString("DEMO-NSFAS-REVIEW-001")))
                .andExpect(content().string(containsString("DEMO-NSFAS-INELIGIBLE-001")));

        var createResult = mvc.perform(post("/applications").with(csrf())
                        .param("applicantName", "Synthetic New Applicant")
                        .param("programmeCode", "NSFAS_BURSARY")
                        .param("summary", "Synthetic case created in the intake test."))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        var detailUrl = createResult.getResponse().getRedirectedUrl();
        assertThat(detailUrl).startsWith("/applications/");
        mvc.perform(get(detailUrl))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Synthetic New Applicant")))
                .andExpect(content().string(containsString("Synthetic case created in the intake test.")));
        assertThat(applicationRecords.count()).isEqualTo(4);
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
    @WithMockUser(authorities = "PERM_DASHBOARD_READ")
    void dashboardOnlyAccountDoesNotReceivePolicyRecordsOrNavigation() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Officer workspace")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("2025 financial screening"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Policy versions</span>"))));
    }

    @Test
    @WithMockUser(authorities = "PERM_POLICY_READ")
    void accountWithoutDashboardPermissionCannotOpenOverview() throws Exception {
        mvc.perform(get("/" )).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "PERM_APPLICATION_CREATE")
    void createOnlyStaffCannotReadTheApplicationQueue() throws Exception {
        mvc.perform(get("/applications")).andExpect(status().isForbidden());
        mvc.perform(get("/applications/new")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = "PERM_USER_ADMIN")
    void onlyAdministratorsCanManageStaffPermissions() throws Exception {
        mvc.perform(get("/admin/staff"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Add staff account")))
                .andExpect(content().string(containsString("APPLICATION_REVIEW")));
    }

    @Test
    @WithMockUser(authorities = "PERM_DASHBOARD_READ")
    void dashboardStaffCannotManageAccounts() throws Exception {
        mvc.perform(get("/admin/staff")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = {"PERM_DASHBOARD_READ", "PERM_POLICY_READ"})
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
