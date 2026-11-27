package za.gov.govaccess.ai.service;

import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import za.gov.govaccess.ai.dto.AiRequest;
import za.gov.govaccess.ai.dto.AiResponse;
import za.gov.govaccess.ai.entity.AiCallLog;
import za.gov.govaccess.ai.entity.AiRequestLog;
import za.gov.govaccess.ai.enums.AiOutcome;
import za.gov.govaccess.ai.repository.AiCallLogRepository;
import za.gov.govaccess.ai.repository.AiRequestLogRepository;
import za.gov.govaccess.audit.service.BusinessAuditService;
import za.gov.govaccess.audit.service.BusinessAuditService.Change;
import za.gov.govaccess.audit.service.CanonicalJson;
import za.gov.govaccess.security.ManagedUserService;

@Service
public class AiAuditService {
    private final AiCallLogRepository logs;
    private final AiRequestLogRepository requests;
    private final TransactionTemplate independentTransaction;
    private final ThreadLocal<RequestScope> active = new ThreadLocal<>();
    private final BusinessAuditService businessAudit;
    private final ManagedUserService users;
    private final JdbcTemplate jdbc;
    private final AiUserQuota quota;

    public AiAuditService(AiCallLogRepository logs, AiRequestLogRepository requests,
                          PlatformTransactionManager transactions, BusinessAuditService businessAudit,
                          ManagedUserService users, JdbcTemplate jdbc, AiUserQuota quota) {
        this.logs = logs;
        this.requests = requests;
        this.businessAudit = businessAudit;
        this.users = users;
        this.jdbc = jdbc;
        this.quota = quota;
        independentTransaction = new TransactionTemplate(transactions);
        independentTransaction.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T around(AiRequest request, Supplier<T> operation) {
        if (active.get() != null) {
            return operation.get();
        }
        var entry = new AiRequestLog(request, AiRequestHashes.input(request));
        independentTransaction.executeWithoutResult(status -> requests.saveAndFlush(entry));
        var scope = new RequestScope(entry);
        active.set(scope);
        long start = System.nanoTime();
        try {
            if (!quota.reserve(request.userId())) {
                scope.outcome = AiOutcome.RATE_LIMITED;
                throw new za.gov.govaccess.ai.provider.AiRateLimitException();
            }
            return operation.get();
        } finally {
            active.remove();
            long latency = Duration.ofNanos(System.nanoTime() - start).toMillis();
            entry.finish(scope.provider, scope.model, scope.outcome, scope.attempts, latency, scope.outputHash);
            independentTransaction.executeWithoutResult(status -> {
                var policyId = "POLICY_VERSION".equals(request.relatedEntityType()) ? request.relatedEntityId() : null;
                String hash = policyId == null ? null : jdbc.query(
                        "SELECT content FROM govaccess.policy_version WHERE id=?",
                        (rs, index) -> CanonicalJson.sha256(CanonicalJson.document(rs.getString(1))), policyId)
                        .stream().findFirst().orElse(null);
                entry.linkAuditEvent(businessAudit.append(new Change(request.context().applicationId(),
                        request.userId(), users.roleOf(request.userId()),
                        "AI_REQUEST_COMPLETED", policyId, hash, null, null, null,
                        request.promptId(), request.promptVersion(), scope.model)));
                requests.saveAndFlush(entry);
            });
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(AiRequest request, AiResponse response, AiOutcome outcome) {
        var scope = recordOutcome(response, outcome);
        logs.save(new AiCallLog(scope.entry.getId(), request, response.provider(), response.model(), AiRequestHashes.input(request),
                scope.outputHash, response.latencyMs(), response.tokensIn(),
                response.tokensOut(), outcome));
        scope.attempts++;
    }

    public void logicalOutcome(AiResponse response, AiOutcome outcome) {
        recordOutcome(response, outcome);
    }

    public void providerFallback() { active.get().entry.markProviderFallback(); }

    private RequestScope recordOutcome(AiResponse response, AiOutcome outcome) {
        var scope = active.get();
        scope.provider = response.provider();
        scope.model = response.model();
        scope.outcome = outcome;
        scope.outputHash = AiRequestHashes.output(response.rawText());
        return scope;
    }

    private static final class RequestScope {
        private final AiRequestLog entry;
        private String provider;
        private String model;
        private AiOutcome outcome = AiOutcome.PROVIDER_ERROR;
        private String outputHash;
        private int attempts;

        private RequestScope(AiRequestLog entry) {
            this.entry = entry;
        }
    }
}
