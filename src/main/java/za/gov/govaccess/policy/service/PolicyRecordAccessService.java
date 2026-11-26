package za.gov.govaccess.policy.service;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.gov.govaccess.policy.entity.PolicyVersion;
import za.gov.govaccess.policy.repository.PolicyVersionRepository;

/** Policy records are shared workspace records; permission checks live at this service boundary. */
@Service
@Transactional(readOnly = true)
public class PolicyRecordAccessService {
    private final PolicyVersionRepository policies;

    public PolicyRecordAccessService(PolicyVersionRepository policies) { this.policies = policies; }

    @PreAuthorize("hasAuthority('PERM_POLICY_READ')")
    public List<PolicyVersion> recent() { return policies.findTop5ByOrderByVersionLabelDescIdAsc(); }

    @PreAuthorize("hasAuthority('PERM_POLICY_READ')")
    public PolicySummary summary() {
        return new PolicySummary(policies.count(), policies.countByStatus("DRAFT"),
                policies.countByStatus("PUBLISHED"));
    }

    @PreAuthorize("hasAuthority('PERM_POLICY_READ')")
    public PolicyVersion findReadable(UUID id) {
        return policies.findById(id).orElseThrow(() -> new AccessDeniedException("Policy record is unavailable"));
    }

    public record PolicySummary(long total, long drafts, long published) {}
}
