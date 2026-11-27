package za.gov.govaccess.security;

import com.hashtech.govaccessai.GovaccessaiApplication;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import za.gov.govaccess.audit.service.BusinessAuditService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = GovaccessaiApplication.class, properties = {
        "spring.profiles.active=demo,test",
        "spring.datasource.url=jdbc:h2:mem:rbac-seeder;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "GOVACCESS_RBAC_DEMO_PASSWORD=Gov!access12345"
})
@AutoConfigureMockMvc
class RbacDemoSeederTests {
    @Autowired private MockMvc mvc;
    @Autowired private ManagedUserService users;
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder encoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RbacDemoSeeder seeder;
    @Autowired private BusinessAuditService audit;

    @Test
    void appliesCommonDemoPasswordAndPreservesFixtureDataOnRestart() throws Exception {
        var roles = Map.of(
                "thandi.maseko", ManagedRole.APPLICANT, "kabelo.dlamini", ManagedRole.APPLICANT,
                "lerato.nkosi", ManagedRole.ASSESSMENT_OFFICER, "sibusiso.khumalo", ManagedRole.ASSESSMENT_OFFICER,
                "naledi.molefe", ManagedRole.POLICY_ADMINISTRATOR, "ayanda.zulu", ManagedRole.POLICY_ADMINISTRATOR,
                "nomsa.mthembu", ManagedRole.SUPERVISOR, "pieter.jacobs", ManagedRole.SUPERVISOR,
                "amina.patel", ManagedRole.AUDITOR);
        for (var entry : roles.entrySet()) {
            var actor = principal(entry.getKey());
            assertThat(actor.role()).isEqualTo(entry.getValue());
            mvc.perform(formLogin().user(actor.getUsername()).password("Gov!access12345"))
                    .andExpect(authenticated().withUsername(actor.getUsername()));
        }
        jdbc.update("UPDATE govaccess.managed_user SET password_hash=? WHERE username=?", encoder.encode("older-test-password"), "rbac-lerato.nkosi");
        seeder.run(new DefaultApplicationArguments());
        assertThat(encoder.matches("Gov!access12345", principal("lerato.nkosi").getPassword())).isTrue();
        assertThat(audit.verifyChain().intact()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM govaccess.case_application WHERE reference LIKE 'DEMO-RBAC-%'", Integer.class))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM govaccess.policy_version WHERE version_label LIKE '%Named RBAC publishing exercise' AND status='DRAFT'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void enforcesCaseVisibilityAndScopedAuditWithSeededAccounts() throws Exception {
        assertVisible("thandi.maseko", List.of("THANDI-BURSARY", "THANDI-UNASSIGNED"), List.of("KABELO-GRANT", "KABELO-ESCALATED"));
        assertVisible("kabelo.dlamini", List.of("KABELO-GRANT", "KABELO-ESCALATED"), List.of("THANDI-BURSARY", "THANDI-UNASSIGNED"));
        assertVisible("lerato.nkosi", List.of("THANDI-BURSARY"), List.of("KABELO-GRANT", "KABELO-ESCALATED", "THANDI-UNASSIGNED"));
        assertVisible("sibusiso.khumalo", List.of("KABELO-GRANT", "KABELO-ESCALATED"), List.of("THANDI-BURSARY", "THANDI-UNASSIGNED"));
        assertVisible("nomsa.mthembu", List.of("THANDI-BURSARY", "KABELO-ESCALATED"), List.of("KABELO-GRANT", "THANDI-UNASSIGNED"));
        assertVisible("pieter.jacobs", List.of("KABELO-GRANT", "KABELO-ESCALATED"), List.of("THANDI-BURSARY", "THANDI-UNASSIGNED"));
        assertVisible("amina.patel", List.of("THANDI-BURSARY", "KABELO-GRANT", "KABELO-ESCALATED", "THANDI-UNASSIGNED"), List.of());
        mvc.perform(get("/applications").with(user(principal("naledi.molefe")))).andExpect(status().isForbidden());
        mvc.perform(post("/applications/{id}/assessment", caseId("THANDI-BURSARY"))
                .with(user(principal("amina.patel"))).with(csrf())).andExpect(status().isForbidden());

        var ownEvent = eventForCase("THANDI-BURSARY");
        var otherEvent = eventForCase("KABELO-GRANT");
        var policyEvent = jdbc.queryForObject("""
                SELECT e.id FROM govaccess.audit_event e JOIN govaccess.policy_version p ON p.id=e.policy_version_id
                WHERE e.action='POLICY_DRAFT_CREATED' AND p.author_id=?
                """, UUID.class, principal("naledi.molefe").id());
        var otherPolicyEvent = jdbc.queryForObject("""
                SELECT e.id FROM govaccess.audit_event e JOIN govaccess.policy_version p ON p.id=e.policy_version_id
                WHERE e.action='POLICY_DRAFT_CREATED' AND p.author_id=?
                """, UUID.class, principal("ayanda.zulu").id());
        var history = org.springframework.test.web.servlet.result.MockMvcResultMatchers.model();
        mvc.perform(get("/audit/history").with(user(principal("lerato.nkosi"))))
                .andExpect(status().isOk()).andExpect(history.attributeExists("events"));

        var officerEvents = auditHistory.visible(authentication("lerato.nkosi")).stream().map(e -> e.id()).toList();
        assertThat(officerEvents).contains(ownEvent).doesNotContain(otherEvent, policyEvent);
        var policyEvents = auditHistory.visible(authentication("naledi.molefe")).stream().map(e -> e.id()).toList();
        assertThat(policyEvents).contains(policyEvent).doesNotContain(otherPolicyEvent, otherEvent);
        mvc.perform(get("/audit").with(user(principal("amina.patel")))).andExpect(status().isOk());
        mvc.perform(get("/audit").with(user(principal("nomsa.mthembu")))).andExpect(status().isForbidden());
        mvc.perform(get("/policies/lifecycle").with(user(principal("naledi.molefe")))).andExpect(status().isOk());
        mvc.perform(get("/policies/lifecycle").with(user(principal("lerato.nkosi")))).andExpect(status().isForbidden());
    }

    @Autowired private za.gov.govaccess.audit.service.AuditHistoryService auditHistory;
    @Autowired private za.gov.govaccess.policy.service.PolicyLifecycleService lifecycle;
    @Autowired private za.gov.govaccess.policy.repository.PolicyVersionRepository versions;

    @Test
    @org.springframework.transaction.annotation.Transactional
    void supportsAnIndependentPublisherWithTheSeededPolicyAccounts() {
        var id = jdbc.queryForObject("""
                SELECT id FROM govaccess.policy_version WHERE author_id=? AND version_label LIKE '%Named RBAC publishing exercise'
                """, UUID.class, principal("naledi.molefe").id());
        var author = authentication("naledi.molefe");
        var reviewer = authentication("ayanda.zulu");
        lifecycle.submitForReview(id, author);
        var evidence = new za.gov.govaccess.rules.dto.VerifiedRuleEvidence("DEMO-DOC-1", "INCOME_DECLARATION", null, null);
        assertThat(lifecycle.runTests(id, author, List.of(
                new za.gov.govaccess.policy.service.PolicyLifecycleService.SyntheticCase("DEMO DATA ONLY - present", true,
                        za.gov.govaccess.rules.service.RecommendationService.Recommendation.LIKELY_ELIGIBLE, false, List.of(evidence)),
                new za.gov.govaccess.policy.service.PolicyLifecycleService.SyntheticCase("DEMO DATA ONLY - missing", true,
                        za.gov.govaccess.rules.service.RecommendationService.Recommendation.NEEDS_REVIEW, false, List.of())
        )).passed()).isTrue();
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> lifecycle.publish(id, author, java.time.LocalDate.of(2025, 1, 1), null)))
                .hasMessageContaining("Another reviewer");
        lifecycle.publish(id, authentication("ayanda.zulu"), java.time.LocalDate.of(2025, 1, 1), null);
        assertThat(versions.findById(id).orElseThrow().getStatus()).isEqualTo("PUBLISHED");
    }
    private org.springframework.security.core.Authentication authentication(String name) {
        var actor = principal(name);
        return org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                actor, null, actor.getAuthorities());
    }

    private void assertVisible(String username, List<String> visible, List<String> hidden) throws Exception {
        var principal = principal(username);
        var queue = mvc.perform(get("/applications").with(user(principal))).andExpect(status().isOk());
        if (username.equals("thandi.maseko")) queue.andExpect(content().string(containsString("Thandi Maseko")));
        if (username.equals("kabelo.dlamini")) queue.andExpect(content().string(containsString("Kabelo Dlamini")));
        for (String suffix : visible) {
            queue.andExpect(content().string(containsString("DEMO-RBAC-" + suffix)));
            mvc.perform(get("/applications/{id}", caseId(suffix)).with(user(principal))).andExpect(status().isOk());
        }
        for (String suffix : hidden) {
            queue.andExpect(content().string(not(containsString("DEMO-RBAC-" + suffix))));
            mvc.perform(get("/applications/{id}", caseId(suffix)).with(user(principal))).andExpect(status().isNotFound());
        }
    }

    private ManagedPrincipal principal(String username) {
        return users.principal("rbac-" + username).orElseThrow();
    }

    private UUID caseId(String suffix) {
        return jdbc.queryForObject("SELECT id FROM govaccess.case_application WHERE reference=?",
                UUID.class, "DEMO-RBAC-" + suffix);
    }

    private UUID eventForCase(String suffix) {
        return jdbc.queryForObject("SELECT id FROM govaccess.audit_event WHERE application_id=? AND action='CASE_CREATED'",
                UUID.class, caseId(suffix));
    }
}
