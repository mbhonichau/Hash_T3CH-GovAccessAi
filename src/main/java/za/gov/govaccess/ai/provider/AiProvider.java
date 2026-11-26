package za.gov.govaccess.ai.provider;

import za.gov.govaccess.ai.dto.AiRequest;
import za.gov.govaccess.ai.dto.AiResponse;

public interface AiProvider {
    AiResponse complete(AiRequest request);
}
