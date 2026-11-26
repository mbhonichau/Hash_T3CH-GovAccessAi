package za.gov.govaccess.policy;

import com.hashtech.govaccessai.GovaccessaiApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import za.gov.govaccess.audit.service.BusinessAuditService;
import za.gov.govaccess.audit.service.AuditHistoryService;
import za.gov.govaccess.audit.service.BusinessAuditService.Change;
import za.gov.govaccess.audit.service.CanonicalJson;
import za.gov.govaccess.policy.repository.PolicyVersionRepository;
import za.gov.govaccess.policy.service.PolicyLifecycleService;
import za.gov.govaccess.policy.service.PolicyLifecycleService.SyntheticCase;
import za.gov.govaccess.policy.service.PolicyVersionSelectionService;
import za.gov.govaccess.policy.service.RulePolicyService;
import za.gov.govaccess.rules.dto.VerifiedRuleEvidence;
import za.gov.govaccess.rules.model.RuleDocument;
import za.gov.govaccess.rules.model.RuleDocument.OnMissingEvidence;
import za.gov.govaccess.rules.model.RuleDocument.Rule;
import za.gov.govaccess.rules.model.RuleDocument.Source;
import za.gov.govaccess.rules.enums.RuleOperator;
import za.gov.govaccess.security.ManagedRole;
import za.gov.govaccess.security.ManagedUserService;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static za.gov.govaccess.rules.service.RecommendationService.Recommendation.*;

@SpringBootTest(classes = GovaccessaiApplication.class, properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:lifecycle;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "govaccess.policy-import.worker-enabled=false"
})
@AutoConfigureMockMvc
class PolicyLifecycleTests {
    private static final String PROGRAMME = "NSFAS_BURSARY";
    private static final LocalDate FIRST = LocalDate.of(2025, 1, 1);
    private static final SyntheticCase PASS = new SyntheticCase("Manual referral", true, NEEDS_REVIEW, true, List.of());
    private static final SyntheticCase FAIL = new SyntheticCase("Manual referral", true, LIKELY_ELIGIBLE, true, List.of());
    private static final String LIFECYCLE = "/policies/lifecycle";

    @Autowired PolicyLifecycleService lifecycle;
    @Autowired PolicyVersionSelectionService selection;
    @Autowired PolicyVersionRepository versions;
    @Autowired RulePolicyService rules;
    @Autowired BusinessAuditService audit;
    @Autowired AuditHistoryService history;
    @Autowired ManagedUserService users;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    @Transactional
    void namedPublicationIsTheFixedAssessmentInputAfterSupersession() throws Exception {
        var bootstrap = actorWithPermissions("bootstrap", "USER_MANAGE");
        UUID reviewerId = users.create(bootstrap, "demo-policy-reviewer", "Testing-password-3344",
                ManagedRole.POLICY_ADMINISTRATOR);
        users.create(bootstrap, "demo-assessment-officer", "Testing-password-3344", ManagedRole.ASSESSMENT_OFFICER);
        var reviewer = managed("demo-policy-reviewer");
        var author = editor("demo-policy-author");
        var officer = authentication(managed("demo-assessment-officer"));
        var evidence = new VerifiedRuleEvidence("synthetic-income", "NSFAS_DEMO_FACT",
                "nsfas.annualGrossHouseholdIncome", IntNode.valueOf(300000));
        var request = mapper.writeValueAsString(Map.of("programmeCode", PROGRAMME, "applicationDate", FIRST.plusMonths(3),
                "verifiedEvidence", List.of(evidence)));
        mvc.perform(post("/api/assessments").with(officer).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict());

        UUID original = incomeDraft(350000, author, evidence, LIKELY_ELIGIBLE);
        mvc.perform(post(LIFECYCLE + "/" + original + "/publish").with(authentication(reviewer)).with(csrf())
                        .param("effectiveFrom", FIRST.toString())).andExpect(status().is3xxRedirection());
        versions.flush();
        var published = versions.findById(original).orElseThrow();
        assertThat(published.getPublisherId()).isEqualTo(reviewerId);
        assertThat(published.getPublisherName()).isEqualTo("demo-policy-reviewer");
        mvc.perform(get(LIFECYCLE + "/" + original).with(authentication(reviewer)))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("demo-policy-reviewer")));
        mvc.perform(post("/api/assessments").with(officer)
                        .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isForbidden());
        mvc.perform(post("/api/assessments").with(authentication(reviewer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isForbidden());
        var firstRun = mvc.perform(post("/api/assessments").with(officer).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.policyVersionId").value(original.toString()))
                .andExpect(jsonPath("$.policyHash").value(published.getContentHash()))
                .andExpect(jsonPath("$.recommendation").value("LIKELY_ELIGIBLE")).andReturn();
        JsonNode assessment = mapper.readTree(firstRun.getResponse().getContentAsString());
        String runId = assessment.path("id").asText();
        String runPath = firstRun.getResponse().getHeader("Location");

        UUID newer = incomeDraft(250000, author, evidence, NOT_ELIGIBLE);
        lifecycle.publish(newer, reviewer, FIRST.plusMonths(1), original);
        versions.flush();
        mvc.perform(post("/api/assessments").with(officer).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.policyVersionId").value(newer.toString()))
                .andExpect(jsonPath("$.recommendation").value("NOT_ELIGIBLE"));
        mvc.perform(get(runPath).with(officer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.policyVersionId").value(original.toString()))
                .andExpect(jsonPath("$.policyHash").value(published.getContentHash()))
                .andExpect(jsonPath("$.recommendation").value("LIKELY_ELIGIBLE"));
        mvc.perform(get(runPath).with(user("another-officer").authorities(AuthorityUtils.createAuthorityList("ASSESSMENT_RUN"))))
                .andExpect(status().isNotFound());
        assertThatThrownBy(() -> jdbc.update("UPDATE govaccess.assessment_policy_run SET policy_version_id=? WHERE id=?",
                newer, UUID.fromString(runId))).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("""
                SELECT policy_hash FROM govaccess.audit_event WHERE action='ASSESSMENT_RUN' AND assessment_id=?
                """, String.class, UUID.fromString(runId))).isEqualTo(published.getContentHash());
        assertThat(audit.verifyChain().intact()).isTrue();
    }

    private UUID incomeDraft(int limit, UsernamePasswordAuthenticationToken author, VerifiedRuleEvidence evidence,
                             za.gov.govaccess.rules.service.RecommendationService.Recommendation expected) {
        var rule = new Rule("income", "Synthetic income cap", evidence.fieldKey(), RuleOperator.LESS_THAN_OR_EQUAL,
                IntNode.valueOf(limit), "ZAR/year", true, List.of(evidence.documentType()), OnMissingEvidence.UNKNOWN,
                new Source("Synthetic policy", "Income", 1, "Income must not exceed R" + limit + "."), null, null);
        UUID id = rules.saveDraft(PROGRAMME, "Stage 4 income " + limit, new RuleDocument(1, List.of(rule), null)).getId();
        lifecycle.submitForReview(id, author);
        assertThat(lifecycle.runTests(id, author,
                List.of(new SyntheticCase("Income boundary", true, expected, false, List.of(evidence)))).passed()).isTrue();
        return id;
    }

    @Test
    @Transactional
    void passedTestIsRequiredAndPublishedSnapshotIsImmutable() {
        var actor = editor("sole-editor");
        UUID id = draft();
        assertThatIllegalStateException().isThrownBy(() -> lifecycle.runTests(id, actor, List.of(PASS)));
        assertThatIllegalStateException().isThrownBy(() -> lifecycle.publish(id, actor, FIRST, null));
        lifecycle.submitForReview(id, actor);
        assertThatIllegalStateException().isThrownBy(() -> lifecycle.submitForReview(id, actor));
        assertThatIllegalStateException().isThrownBy(() -> lifecycle.publish(id, actor, FIRST, null));
        assertThat(lifecycle.runTests(id, actor, List.of(FAIL)).passed()).isFalse();
        assertThat(versions.findById(id).orElseThrow().getStatus()).isEqualTo("REVIEW");
        assertThat(lifecycle.runTests(id, actor, List.of(PASS)).passed()).isTrue();
        assertThatIllegalStateException().isThrownBy(() -> lifecycle.submitForReview(id, actor));
        assertThatIllegalStateException().isThrownBy(() -> lifecycle.runTests(id, actor, List.of(PASS)));
        lifecycle.publish(id, actor, FIRST, null);
        versions.flush();
        var published = versions.findById(id).orElseThrow();
        assertThat(published.getContentHash()).isEqualTo(CanonicalJson.sha256(published.getContent()));
        assertThat(published.getEffectiveFrom()).isEqualTo(FIRST);
        assertThat(published.getPublishedAt()).isNotNull();
        assertThat(published.getPublisherId()).isEqualTo(published.getAuthorId());
        assertThatIllegalStateException().isThrownBy(() -> lifecycle.submitForReview(id, actor));
        assertThatIllegalStateException().isThrownBy(() -> lifecycle.runTests(id, actor, List.of(PASS)));
        assertThatIllegalStateException().isThrownBy(() -> lifecycle.publish(id, actor, FIRST, null));
        assertThatThrownBy(() -> jdbc.update("UPDATE govaccess.policy_version SET version_label='changed' WHERE id=?", id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM govaccess.policy_version WHERE id=?", id))
                .isInstanceOf(DataAccessException.class);
        UUID fork = lifecycle.forkPublished(id, actor, "Fork " + UUID.randomUUID());
        lifecycle.editDraft(fork, actor, published.getContent());
        assertThat(versions.findById(fork).orElseThrow().getStatus()).isEqualTo("DRAFT");
        assertThat(audit.verifyChain().intact()).isTrue();
    }

    @Test
    @Transactional
    void alternatePublisherAndTransitionalDatesAreEnforced() {
        var bootstrap = actorWithPermissions("bootstrap", "USER_MANAGE");
        users.create(bootstrap, "second-reviewer", "Testing-password-3344", ManagedRole.POLICY_ADMINISTRATOR);
        var author = editor("author");
        var publisher = managed("second-reviewer");
        UUID older = tested(author);
        assertThatThrownBy(() -> lifecycle.publish(older, author, FIRST, null))
                .isInstanceOf(AccessDeniedException.class);
        lifecycle.publish(older, publisher, FIRST, null);
        UUID newer = tested(author);
        lifecycle.publish(newer, publisher, FIRST.plusMonths(2), older);
        versions.flush();
        assertThat(selection.select(PROGRAMME, FIRST.minusDays(1))).isEmpty();
        assertThat(selection.select(PROGRAMME, FIRST.plusMonths(1))).get().extracting(p -> p.getId()).isEqualTo(older);
        assertThat(selection.select(PROGRAMME, FIRST.plusMonths(3))).get().extracting(p -> p.getId()).isEqualTo(newer);
        selection.configureTransition(publisher, older, FIRST.plusMonths(3), FIRST.plusMonths(4), 10);
        assertThatIllegalArgumentException().isThrownBy(() ->
                selection.configureTransition(publisher, newer, FIRST.plusMonths(3), FIRST.plusMonths(4), 10));
        assertThat(selection.select(PROGRAMME, FIRST.plusMonths(4))).get().extracting(p -> p.getId()).isEqualTo(older);
        assertThat(selection.select(PROGRAMME, FIRST.plusMonths(4).plusDays(1))).get().extracting(p -> p.getId()).isEqualTo(newer);
    }

    @Test
    @Transactional
    void backendEnforcesManagedRolePermissionsAndCsrf() throws Exception {
        var bootstrap = actorWithPermissions("bootstrap", "USER_MANAGE");
        String id = draft().toString();
        for (ManagedRole role : ManagedRole.values()) {
            String username = "test-" + role.name().toLowerCase(java.util.Locale.ROOT);
            users.create(bootstrap, username, "Testing-password-3344", role);
            var principal = user(users.principal(username).orElseThrow());
            boolean policy = role == ManagedRole.POLICY_ADMINISTRATOR;
            var session = new MockHttpSession();
            mvc.perform(post("/workspace/access").session(session).with(principal).with(csrf())
                            .param("mode", "POLICY_EDIT"))
                    .andExpect(status().is(policy ? 302 : 403));
            mvc.perform(get(LIFECYCLE).with(principal)).andExpect(status().is(policy ? 200 : 403));
            mvc.perform(get(LIFECYCLE + "/" + id).with(principal)).andExpect(status().is(policy ? 200 : 403));
            for (String action : List.of("review", "test", "publish", "fork", "edit", "transition")) {
                mvc.perform(post(LIFECYCLE + "/" + id + "/" + action).session(session).with(principal).with(csrf())
                                .param("cases", "[]").param("effectiveFrom", FIRST.toString())
                                .param("label", "Test fork").param("content", "{}").param("startsOn", FIRST.toString())
                                .param("endsOn", FIRST.toString()).param("priority", "1"))
                        .andExpect(status().is(policy ? 302 : 403));
            }
            mvc.perform(post(LIFECYCLE + "/" + id + "/review").session(session).with(principal))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/audit").with(principal)).andExpect(status().is(role == ManagedRole.AUDITOR ? 200 : 403));
            mvc.perform(get("/audit/history").with(principal)).andExpect(status().is(role == ManagedRole.APPLICANT ? 403 : 200));
            mvc.perform(get("/admin/users").with(principal)).andExpect(status().isForbidden());
            mvc.perform(post("/admin/users").with(principal).with(csrf())).andExpect(status().isForbidden());
            mvc.perform(post("/admin/users/" + id + "/enabled").with(principal).with(csrf()))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/admin/users/" + id + "/audit-scope").with(principal).with(csrf()))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/ai/status").with(principal)).andExpect(status().is(policy ? 200 : 403));
        }
    }

    @Test
    void appendOnlyAuditReportsFirstTamperedEvent() {
        UUID actor = UUID.randomUUID();
        UUID event = audit.append(new Change(null, actor, "AUDITOR", "TEST_EVENT", null, null, null,
                null, Map.of("private", "protected value"), null, null, null));
        assertThat(audit.verifyChain().intact()).isTrue();
        assertThat(jdbc.queryForObject("SELECT detail FROM govaccess.audit_event WHERE id=?", String.class, event))
                .doesNotContain("protected value");
        assertThatThrownBy(() -> jdbc.update("UPDATE govaccess.audit_event SET action='ALTERED' WHERE id=?", event))
                .isInstanceOf(DataAccessException.class);
        jdbc.execute("DROP TRIGGER govaccess.audit_event_append_only");
        try {
            jdbc.update("UPDATE govaccess.audit_event SET action='ALTERED' WHERE id=?", event);
            var verification = audit.verifyChain();
            assertThat(verification.intact()).isFalse();
            assertThat(verification.firstBrokenEvent()).isEqualTo(event);
        } finally {
            jdbc.update("UPDATE govaccess.audit_event SET action='TEST_EVENT' WHERE id=?", event);
            jdbc.execute("""
                    CREATE TRIGGER govaccess.audit_event_append_only BEFORE UPDATE, DELETE ON govaccess.audit_event
                    FOR EACH ROW AS '
                    org.h2.api.Trigger create() {
                        return (connection, oldRow, newRow) -> {
                            throw new java.sql.SQLException("Business audit records are append-only", "23514");
                        };
                    }'
                    """);
        }
    }

    @Test
    @Transactional
    void limitedAuditHistoryOnlyIncludesAssignedCasesAndProgrammes() {
        var bootstrap = actorWithPermissions("bootstrap", "USER_MANAGE");
        UUID officerId = users.create(bootstrap, "scope-officer", "Testing-password-3344", ManagedRole.ASSESSMENT_OFFICER);
        UUID editorId = users.create(bootstrap, "scope-editor", "Testing-password-3344", ManagedRole.POLICY_ADMINISTRATOR);
        UUID application = UUID.randomUUID();
        UUID policy = draft();
        UUID programme = jdbc.queryForObject("SELECT programme_id FROM govaccess.policy_version WHERE id=?", UUID.class, policy);
        UUID assignedCase = audit.append(new Change(application, officerId, "ASSESSMENT_OFFICER", "CASE_EVENT",
                null, null, null, null, null, null, null, null));
        UUID unassignedCase = audit.append(new Change(UUID.randomUUID(), officerId, "ASSESSMENT_OFFICER", "CASE_EVENT",
                null, null, null, null, null, null, null, null));
        UUID assignedProgramme = audit.append(new Change(null, editorId, "POLICY_ADMINISTRATOR", "POLICY_EVENT",
                policy, null, null, null, null, null, null, null));
        history.grantScope(bootstrap, officerId, application, null);
        history.grantScope(bootstrap, editorId, null, programme);

        assertThat(history.visible(managed("scope-officer")))
                .extracting(AuditHistoryService.Summary::id).contains(assignedCase)
                .doesNotContain(unassignedCase, assignedProgramme);
        assertThat(history.visible(managed("scope-editor")))
                .extracting(AuditHistoryService.Summary::id).contains(assignedProgramme)
                .doesNotContain(assignedCase, unassignedCase);
        assertThat(history.visible(actorWithPermissions("supervisor", "AUDIT_VIEW_ALL")))
                .extracting(AuditHistoryService.Summary::id).contains(assignedCase, unassignedCase, assignedProgramme);
    }

    @Test
    @Transactional
    void bootstrapCanManageUsersAndDisabledAccountsLoseTheirSession() throws Exception {
        var bootstrap = user("bootstrap").authorities(AuthorityUtils.createAuthorityList("USER_MANAGE"));
        mvc.perform(get("/admin/users").with(bootstrap)).andExpect(status().isOk());
        mvc.perform(post("/admin/users").with(bootstrap).with(csrf())
                        .param("username", "managed-applicant").param("password", "Testing-password-3344")
                        .param("role", "APPLICANT"))
                .andExpect(status().is3xxRedirection());
        var principal = users.principal("managed-applicant").orElseThrow();
        var session = (MockHttpSession) mvc.perform(formLogin().user("managed-applicant").password("Testing-password-3344"))
                .andExpect(status().is3xxRedirection()).andReturn().getRequest().getSession(false);
        assertThat(session).isNotNull();
        mvc.perform(get("/").session(session)).andExpect(status().isOk());
        mvc.perform(post("/admin/users/" + principal.id() + "/enabled").with(bootstrap).with(csrf())
                        .param("enabled", "false"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/").session(session)).andExpect(status().is3xxRedirection());
    }

    private UUID draft() {
        var seed = versions.findByProgrammeCodeAndStatusOrderByVersionLabel(PROGRAMME, "DRAFT").getFirst();
        return rules.saveDraft(PROGRAMME, "Lifecycle " + UUID.randomUUID(), rules.read(seed.getContent())).getId();
    }

    private UUID tested(UsernamePasswordAuthenticationToken actor) {
        UUID id = draft();
        lifecycle.submitForReview(id, actor);
        lifecycle.runTests(id, actor, List.of(PASS));
        return id;
    }

    private UsernamePasswordAuthenticationToken managed(String username) {
        var account = users.principal(username).orElseThrow();
        return UsernamePasswordAuthenticationToken.authenticated(account, null, account.getAuthorities());
    }

    private static UsernamePasswordAuthenticationToken editor(String name) {
        return actorWithPermissions(name, "POLICY_VIEW", "POLICY_EDIT", "POLICY_TEST", "POLICY_PUBLISH");
    }

    private static UsernamePasswordAuthenticationToken actorWithPermissions(String name, String... permissions) {
        return UsernamePasswordAuthenticationToken.authenticated(name, null, AuthorityUtils.createAuthorityList(permissions));
    }
}
