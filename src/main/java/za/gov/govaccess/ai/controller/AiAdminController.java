package za.gov.govaccess.ai.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.gov.govaccess.ai.dto.AiCallContext;
import za.gov.govaccess.ai.dto.AiRequest;
import za.gov.govaccess.ai.provider.AiProviderException;
import za.gov.govaccess.ai.repository.PromptRepository;
import za.gov.govaccess.ai.service.AiAvailability;
import za.gov.govaccess.ai.service.AiOutputInvalidException;
import za.gov.govaccess.ai.service.AiReplayMissException;
import za.gov.govaccess.ai.service.StructuredOutputService;

@RestController
class AiAdminController {
    private static final UUID PROVIDER_PROBE_ID = UUID.fromString("20d9e460-aa7e-40bf-930a-6408bfcbb834");

    private final AiAvailability availability;
    private final PromptRepository prompts;
    private final StructuredOutputService outputs;
    private final JsonNode probeSchema;

    AiAdminController(AiAvailability availability, PromptRepository prompts,
                      StructuredOutputService outputs, ObjectMapper mapper) throws JsonProcessingException {
        this.availability = availability;
        this.prompts = prompts;
        this.outputs = outputs;
        probeSchema = mapper.readTree("""
                {"type":"object","properties":{"ok":{"type":"boolean"}},
                 "required":["ok"],"additionalProperties":false}
                """);
    }

    @GetMapping("/api/admin/ai/status")
    Map<String, Boolean> status() {
        return Map.of("enabled", availability.enabled());
    }

    @PostMapping("/api/admin/ai/enabled")
    Map<String, Boolean> toggle(@RequestParam boolean enabled) {
        return Map.of("enabled", availability.setEnabled(enabled));
    }

    @PostMapping("/api/admin/ai/probe")
    ResponseEntity<Map<String, Object>> probe(Principal principal) {
        var prompt = prompts.active("provider-probe");
        var context = AiCallContext.forPrincipal(principal, "AI_PROVIDER", PROVIDER_PROBE_ID);
        var request = new AiRequest(prompt.id(), prompt.version(), prompt.text(), "Return the JSON readiness response.",
                List.of(), probeSchema, 0, true, context);
        try {
            outputs.probe(request, ProbeResult.class);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (AiProviderException e) {
            return failure(e.reason().name(), e.status());
        } catch (AiOutputInvalidException e) {
            return failure("INVALID_OUTPUT", 0);
        } catch (AiReplayMissException e) {
            return failure("REPLAY_ONLY", 0);
        }
    }

    private static ResponseEntity<Map<String, Object>> failure(String reason, int status) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("ok", false, "reason", reason, "providerStatus", status));
    }

    public record ProbeResult(@NotNull @AssertTrue Boolean ok) {}
}
