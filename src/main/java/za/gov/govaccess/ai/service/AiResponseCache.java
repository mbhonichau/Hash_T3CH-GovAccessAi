package za.gov.govaccess.ai.service;

import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import za.gov.govaccess.ai.dto.AiResponse;
import za.gov.govaccess.ai.entity.AiCacheEntry;
import za.gov.govaccess.ai.repository.AiCacheRepository;

@Service
public class AiResponseCache {
    private final AiCacheRepository repository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public AiResponseCache(AiCacheRepository repository, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    public Optional<AiResponse> get(String key) {
        return repository.findById(key).map(AiCacheEntry::response);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSynthetic(za.gov.govaccess.ai.dto.AiRequest request, AiResponse response) {
        if (!request.synthetic() || response.rawText() == null || response.rawText().length() > 200_000) return;
        jdbc.update("""
                INSERT INTO govaccess.ai_demo_response (id,request_hash,prompt_id,provider,model,raw_text)
                VALUES (?,?,?,?,?,?)
                """, java.util.UUID.randomUUID(), AiRequestHashes.input(request), request.promptId(), response.provider(),
                response.model(), response.rawText());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void put(String key, AiResponse response) {
        try {
            repository.saveAndFlush(new AiCacheEntry(key, response));
        } catch (DataIntegrityViolationException e) {
            if (!repository.existsById(key)) {
                throw e;
            }
        }
    }
}
