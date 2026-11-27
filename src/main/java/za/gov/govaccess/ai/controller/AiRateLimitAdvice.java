package za.gov.govaccess.ai.controller;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import za.gov.govaccess.ai.provider.AiRateLimitException;

@ControllerAdvice
class AiRateLimitAdvice {
    @ExceptionHandler(AiRateLimitException.class)
    ResponseEntity<Map<String, String>> limited(AiRateLimitException error) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", error.getMessage()));
    }
}
