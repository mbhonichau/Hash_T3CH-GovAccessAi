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

    public AiResponseCache(AiCacheRepository repository) {
        this.repository = repository;
    }

    public Optional<AiResponse> get(String key) {
        return repository.findById(key).map(AiCacheEntry::response);
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
