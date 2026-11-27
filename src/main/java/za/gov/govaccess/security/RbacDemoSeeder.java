package za.gov.govaccess.security;

import com.fasterxml.jackson.databind.node.TextNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import za.gov.govaccess.applicant.entity.Applicant;
import za.gov.govaccess.applicant.repository.ApplicantRepository;
import za.gov.govaccess.application.entity.ApplicationRecord;
import za.gov.govaccess.application.repository.ApplicationRecordRepository;
import za.gov.govaccess.audit.service.AuditHistoryService;
import za.gov.govaccess.audit.service.BusinessAuditService;
import za.gov.govaccess.audit.service.BusinessAuditService.Change;
import za.gov.govaccess.policy.entity.PolicyVersion;
import za.gov.govaccess.policy.repository.PolicyVersionRepository;
import za.gov.govaccess.policy.service.PolicyVersionSelectionService;
import za.gov.govaccess.policy.service.RulePolicyService;
import za.gov.govaccess.programme.repository.ProgrammeRepository;
import za.gov.govaccess.rules.enums.RuleOperator;
import za.gov.govaccess.rules.model.RuleDocument;

@Component
@Profile("demo")
public class RbacDemoSeeder implements ApplicationRunner {
    private static final String LABEL = "DEMO DATA ONLY";
    private static final String BURSARY = "NSFAS_BURSARY";
    private static final String GRANT = "SOCIAL_SUPPORT_GRANT";
    private static final String DRAFT = "DEMO DATA ONLY - Named RBAC publishing exercise";
    private static final Authentication SEED_ACTOR = UsernamePasswordAuthenticationToken.authenticated(
            "rbac-demo-seed", null, List.of(new SimpleGrantedAuthority("USER_MANAGE")));

    private enum FixtureUser {
        APPLICANT_A("thandi.maseko", "Thandi Maseko", ManagedRole.APPLICANT),
        APPLICANT_B("kabelo.dlamini", "Kabelo Dlamini", ManagedRole.APPLICANT),
        OFFICER_A("lerato.nkosi", "Lerato Nkosi", ManagedRole.ASSESSMENT_OFFICER),
        OFFICER_B("sibusiso.khumalo", "Sibusiso Khumalo", ManagedRole.ASSESSMENT_OFFICER),
        POLICY_AUTHOR("naledi.molefe", "Naledi Molefe", ManagedRole.POLICY_ADMINISTRATOR),
        POLICY_REVIEWER("ayanda.zulu", "Ayanda Zulu", ManagedRole.POLICY_ADMINISTRATOR),
        SUPERVISOR_A("nomsa.mthembu", "Nomsa Mthembu", ManagedRole.SUPERVISOR),
        SUPERVISOR_B("pieter.jacobs", "Pieter Jacobs", ManagedRole.SUPERVISOR),
        AUDITOR("amina.patel", "Amina Patel", ManagedRole.AUDITOR);

        private final String username;
        private final String displayName;
        private final ManagedRole role;

        FixtureUser(String suffix, String displayName, ManagedRole role) {
            this.username = "rbac-" + suffix;
            this.displayName = displayName;
            this.role = role;
        }
    }

    private record FixtureCase(String suffix, String programme, FixtureUser owner, FixtureUser officer,
                               FixtureUser team, FixtureUser escalation) {}

    private final ManagedUserService users;
    private final ApplicantRepository applicants;
    private final ApplicationRecordRepository cases;
    private final ProgrammeRepository programmes;
    private final PolicyVersionRepository policies;
    private final PolicyVersionSelectionService selection;
    private final RulePolicyService rules;
    private final BusinessAuditService audit;
    private final AuditHistoryService history;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;

    public RbacDemoSeeder(ManagedUserService users, ApplicantRepository applicants,
                          ApplicationRecordRepository cases, ProgrammeRepository programmes,
                          PolicyVersionRepository policies, PolicyVersionSelectionService selection,
                          RulePolicyService rules, BusinessAuditService audit, AuditHistoryService history,
                          PasswordEncoder encoder, JdbcTemplate jdbc) {
        this.users = users;
        this.applicants = applicants;
        this.cases = cases;
        this.programmes = programmes;
        this.policies = policies;
        this.selection = selection;
        this.rules = rules;
        this.audit = audit;
        this.history = history;
        this.encoder = encoder;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String demoPassword = "Gov!access12345";
        var ids = new EnumMap<FixtureUser, UUID>(FixtureUser.class);
        for (var fixture : FixtureUser.values()) {
            var existing = users.principal(fixture.username);
            if (existing.isPresent() && existing.get().role() != fixture.role) {
                throw new IllegalStateException("RBAC fixture username has a different managed role: " + fixture.username);
            }
            UUID id = existing.map(ManagedPrincipal::id).orElseGet(() -> users.create(SEED_ACTOR, fixture.username, demoPassword, fixture.role));
            jdbc.update("UPDATE govaccess.managed_user SET password_hash=?,enabled=true WHERE id=?", encoder.encode(demoPassword), id);
            ids.put(fixture, id);
        }
        for (var fixture : List.of(
                new FixtureCase("THANDI-BURSARY", BURSARY, FixtureUser.APPLICANT_A, FixtureUser.OFFICER_A, FixtureUser.SUPERVISOR_A, null),
                new FixtureCase("KABELO-GRANT", GRANT, FixtureUser.APPLICANT_B, FixtureUser.OFFICER_B, FixtureUser.SUPERVISOR_B, null),
                new FixtureCase("KABELO-ESCALATED", GRANT, FixtureUser.APPLICANT_B, FixtureUser.OFFICER_B, FixtureUser.SUPERVISOR_B, FixtureUser.SUPERVISOR_A),
                new FixtureCase("THANDI-UNASSIGNED", BURSARY, FixtureUser.APPLICANT_A, null, null, null))) {
            seedCase(fixture, ids);
        }

        seedDraft(BURSARY, ids.get(FixtureUser.POLICY_AUTHOR));
        seedDraft(GRANT, ids.get(FixtureUser.POLICY_REVIEWER));
    }

    private void seedCase(FixtureCase fixture, EnumMap<FixtureUser, UUID> ids) {
        String reference = "DEMO-RBAC-" + fixture.suffix();
        if (cases.existsByReference(reference)) return;
        var programme = programmes.findByCode(fixture.programme()).orElseThrow();
        var applicant = applicants.save(new Applicant(fixture.owner().displayName));
        var record = new ApplicationRecord(reference, applicant, programme,
                LABEL + " - RBAC record access fixture.", SEED_ACTOR.getName());
        var date = LocalDate.now(ZoneId.of("Africa/Johannesburg"));
        var published = selection.select(fixture.programme(), date);
        published.ifPresent(version -> record.recordSubmission(ids.get(fixture.owner()), date, version.getId(), ids.get(fixture.officer())));
        record.assign(ids.get(fixture.owner()), ids.get(fixture.officer()), ids.get(fixture.team()), ids.get(fixture.escalation()));
        cases.saveAndFlush(record);
        var actor = ActorIdentity.of(SEED_ACTOR);
        audit.append(new Change(record.getId(), actor.id(), actor.role(), "CASE_CREATED",
                published.map(PolicyVersion::getId).orElse(null), published.map(PolicyVersion::getContentHash).orElse(null),
                null, null, Map.of("fixture", reference), null, null, null));
        if (fixture.officer() != null) history.grantScope(SEED_ACTOR, ids.get(fixture.officer()), record.getId(), null);
    }

    private void seedDraft(String programmeCode, UUID author) {
        if (policies.findByProgrammeCodeAndVersionLabel(programmeCode, DRAFT).isPresent()) return;
        var programme = programmes.findByCode(programmeCode).orElseThrow();
        var document = new RuleDocument(1, List.of(new RuleDocument.Rule("rbac-evidence", "Synthetic evidence",
                null, RuleOperator.DOCUMENT_REQUIRED, TextNode.valueOf("INCOME_DECLARATION"), null, true,
                List.of(), RuleDocument.OnMissingEvidence.UNKNOWN,
                new RuleDocument.Source(LABEL, "RBAC fixture", 1, "Synthetic evidence is required."), null, null)), LABEL);
        rules.validate(document);
        var draft = PolicyVersion.manualDraft(UUID.randomUUID(), programme, DRAFT, null);
        draft.recordAuthor(author);
        draft.setContent(rules.write(document));
        policies.saveAndFlush(draft);
        var actor = ActorIdentity.of(SEED_ACTOR);
        audit.append(new Change(null, actor.id(), actor.role(), "POLICY_DRAFT_CREATED",
                draft.getId(), null, null, null, Map.of("fixture", DRAFT, "authorId", author), null, null, null));
        history.grantScope(SEED_ACTOR, author, null, programme.getId());
    }
}
