package za.gov.govaccess.security;

import java.util.List;
import java.util.Set;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public enum ManagedRole {
    APPLICANT("Applicant", "APPLICATION_SUBMIT", "APPLICATION_VIEW_OWN", "DOCUMENT_UPLOAD"),
    ASSESSMENT_OFFICER("Assessment Officer", "APPLICATION_SUBMIT", "CASE_REVIEW", "ASSESSMENT_RUN",
            "DECISION_APPROVE", "DECISION_OVERRIDE", "AUDIT_VIEW_LIMITED", "EVIDENCE_CORRECT",
            "EVIDENCE_REQUEST", "NOTICE_APPROVE"),
    POLICY_ADMINISTRATOR("Policy Administrator", "POLICY_VIEW", "POLICY_EDIT", "POLICY_TEST",
            "POLICY_PUBLISH", "AUDIT_VIEW_LIMITED", "AI_ADMIN"),
    SUPERVISOR("Supervisor", "CASE_REVIEW", "ASSESSMENT_RUN", "DECISION_APPROVE", "DECISION_OVERRIDE",
            "ANALYTICS_VIEW", "AUDIT_VIEW_ALL", "EVIDENCE_CORRECT", "EVIDENCE_REQUEST", "NOTICE_APPROVE"),
    AUDITOR("Auditor", "ANALYTICS_VIEW", "AUDIT_VIEW_ALL", "AUDIT_VERIFY");

    private final String label;
    private final Set<String> permissions;

    ManagedRole(String label, String... permissions) {
        this.label = label;
        this.permissions = Set.of(permissions);
    }

    public String getLabel() { return label; }
    public boolean grants(String permission) { return permissions.contains(permission); }

    List<SimpleGrantedAuthority> authorities() {
        var list = new java.util.ArrayList<SimpleGrantedAuthority>();
        list.add(new SimpleGrantedAuthority("ROLE_" + name()));
        permissions.stream().sorted().map(SimpleGrantedAuthority::new).forEach(list::add);
        return List.copyOf(list);
    }
}
