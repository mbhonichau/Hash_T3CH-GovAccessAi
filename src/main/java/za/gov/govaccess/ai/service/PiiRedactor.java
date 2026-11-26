package za.gov.govaccess.ai.service;

import za.gov.govaccess.ai.dto.AiRequest;
import za.gov.govaccess.ai.enums.AiRedactionMode;

public interface PiiRedactor {
    /** Returns null when the request must be blocked. */
    AiRequest redact(AiRequest request, AiRedactionMode mode);
}
