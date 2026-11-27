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
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.gov.govaccess.ai.dto.AiCallContext;
import za.gov.govaccess.ai.dto.AiRequest;
import za.gov.govaccess.ai.provider.AiProviderException;
import za.gov.govaccess.ai.repository.PromptRepository;
import za.gov.govaccess.ai.service.AiAvailability;
import za.gov.govaccess.ai.service.AiOutputInvalidException;
import za.gov.govaccess.ai.service.AiReplayMissException;
import za.gov.govaccess.ai.service.StructuredOutputService;

@Controller
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
    @ResponseBody
    Map<String, Boolean> status() {
        return Map.of("enabled", availability.enabled());
    }

    @PostMapping("/api/admin/ai/enabled")
    @ResponseBody
    Map<String, Boolean> toggle(@RequestParam boolean enabled) {
        return Map.of("enabled", availability.setEnabled(enabled));
    }

    @PostMapping("/api/admin/ai/probe")
    ResponseEntity<Map<String, Object>> probe(Principal principal) {
        var prompt = prompts.active("provider-probe");
        var context = AiCallContext.forPrincipal(principal, "AI_PROVIDER", PROVIDER_PROBE_ID);
        var request = za.gov.govaccess.ai.service.AiPromptBuilder.build(prompt, null, "", "Return the JSON readiness response.",
                List.of(), probeSchema, true, context);
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

    @GetMapping("/admin/ai")
    String settings(Model model) {
        model.addAttribute("aiEnabled", availability.enabled());
        return "admin/ai";
    }

    @PostMapping("/admin/ai/enabled")
    String changeAvailability(@RequestParam boolean enabled, RedirectAttributes flash) {
        boolean active = availability.setEnabled(enabled);
        if (enabled && !active) {
            flash.addFlashAttribute("errorMessage", "AI is disabled in the application configuration.");
        } else {
            flash.addFlashAttribute("successMessage", active ? "AI calls enabled." : "AI calls disabled.");
        }
        return "redirect:/admin/ai";
    }

    @PostMapping("/admin/ai/probe")
    String checkProvider(Principal principal, RedirectAttributes flash) {
        var result = probe(principal);
        if (result.getStatusCode().is2xxSuccessful()) {
            flash.addFlashAttribute("successMessage", "The provider returned a valid test response.");
        } else {
            var response = result.getBody();
            String reason = response == null ? "UNKNOWN" : String.valueOf(response.get("reason"));
            flash.addFlashAttribute("errorMessage", switch (reason) {
                case "DISABLED" -> "Enable AI before testing the provider.";
                case "REPLAY_ONLY" -> "Provider tests require live calls. The application is in replay mode.";
                case "NOT_CONFIGURED" -> "The application has no configured AI provider.";
                case "INVALID_OUTPUT" -> "The provider returned an invalid test response.";
                case "RATE_LIMITED" -> "AI request limit reached. Try again when your request budget resets.";
                default -> "The provider test failed. Check the server configuration and provider access.";
            });
        }
        return "redirect:/admin/ai";
    }

    private static ResponseEntity<Map<String, Object>> failure(String reason, int status) {
        return ResponseEntity.status("RATE_LIMITED".equals(reason) ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("ok", false, "reason", reason, "providerStatus", status));
    }

    public record ProbeResult(@NotNull @AssertTrue Boolean ok) {}
}
