package za.gov.govaccess.policy.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import java.security.Principal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.servlet.support.RequestContextUtils;
import za.gov.govaccess.ai.provider.AiProviderException;
import za.gov.govaccess.ai.dto.AiCallContext;
import za.gov.govaccess.ai.service.AiOutputInvalidException;
import za.gov.govaccess.ai.service.AiReplayMissException;
import za.gov.govaccess.ai.service.PolicyInterpretationService;
import za.gov.govaccess.policy.service.NsfasDemoPolicyService;

@RestController
class PolicyInterpretationController {
    private final PolicyInterpretationService interpretation;
    private final NsfasDemoPolicyService policies;

    PolicyInterpretationController(PolicyInterpretationService interpretation, NsfasDemoPolicyService policies) {
        this.interpretation = interpretation;
        this.policies = policies;
    }

    @PostMapping("/api/policies/interpret")
    ResponseEntity<Map<String, String>> interpret(@RequestParam String versionLabel, HttpServletRequest request,
                                                  HttpServletResponse response, Principal principal) {
        String label = versionLabel.strip();
        if (label.isBlank() || label.length() > 100 || policies.findVersion(label).isPresent()) {
            return error(HttpStatus.BAD_REQUEST, "Choose a new policy version label of up to 100 characters.");
        }
        try {
            var draftId = UUID.randomUUID();
            var context = AiCallContext.forPrincipal(principal, "POLICY_VERSION", draftId);
            policies.saveDraft(draftId, label, interpretation.interpretBundledSample(context), true);
        } catch (AiOutputInvalidException e) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "The AI draft did not match the bundled policy.");
        } catch (AiReplayMissException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE,
                    "No recorded AI response matches this import. Use the normal Supabase profile for a live provider.");
        } catch (AiProviderException e) {
            return error(e.reason() == AiProviderException.Reason.RATE_LIMITED ? HttpStatus.TOO_MANY_REQUESTS
                    : HttpStatus.SERVICE_UNAVAILABLE, providerMessage(e));
        } catch (DataIntegrityViolationException | IllegalArgumentException e) {
            return error(HttpStatus.CONFLICT, "The draft could not be saved. Choose a different version label or try again.");
        }
        String location = UriComponentsBuilder.fromPath(request.getContextPath() + "/policies/nsfas-2025")
                .queryParam("version", "{version}").encode().buildAndExpand(label).toUriString();
        RequestContextUtils.getOutputFlashMap(request).put("importedDraftLabel", label);
        RequestContextUtils.saveOutputFlashMap(location, request, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("location", location));
    }

    private static String providerMessage(AiProviderException error) {
        return switch (error.reason()) {
            case NOT_CONFIGURED -> "No AI provider is configured. Set AI_BASE_URL, AI_MODEL and AI_API_KEY in the app's launch environment.";
            case DISABLED -> "AI calls are disabled by the application configuration.";
            case RATE_LIMITED -> error.getMessage();
            case BLOCKED -> "The AI request was blocked by the hosted provider's input safeguards.";
            case UNAVAILABLE -> "The AI provider could not be reached or returned an unreadable response. Check the endpoint and try again.";
            case HTTP_ERROR -> switch (error.status()) {
                case 401 -> "The AI provider rejected authentication (HTTP 401). Check AI_API_KEY in the app's launch environment.";
                case 403 -> "The AI provider denied access (HTTP 403). Check your key's permissions for AI_MODEL.";
                case 404 -> "The AI provider could not find the model or endpoint (HTTP 404). Check AI_MODEL and AI_BASE_URL.";
                case 429 -> "The AI provider is rate-limiting requests (HTTP 429). Check your quota or try again later.";
                default -> "The AI provider rejected the request (HTTP " + error.status() + ").";
            };
        };
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }
}
