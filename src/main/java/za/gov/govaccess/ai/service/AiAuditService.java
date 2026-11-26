package za.gov.govaccess.ai.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import za.gov.govaccess.ai.dto.AiRequest;
import za.gov.govaccess.ai.dto.AiResponse;
import za.gov.govaccess.ai.entity.AiCallLog;
import za.gov.govaccess.ai.enums.AiOutcome;
import za.gov.govaccess.ai.repository.AiCallLogRepository;

@Service
public class AiAuditService {
    private final AiCallLogRepository logs;

    public AiAuditService(AiCallLogRepository logs) {
        this.logs = logs;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(AiRequest request, AiResponse response, AiOutcome outcome) {
        logs.save(new AiCallLog(request, response.provider(), response.model(), AiRequestHashes.input(request),
                AiRequestHashes.output(response.rawText()), response.latencyMs(), response.tokensIn(),
                response.tokensOut(), outcome));
    }
}
