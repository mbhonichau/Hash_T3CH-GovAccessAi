package za.gov.govaccess.policy.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import za.gov.govaccess.rules.model.RuleDocument;

public record PolicyImportReview(RuleDocument document, LocalDate effectiveFrom, List<String> exceptions,
                                 List<String> definitions, List<String> requiredDocuments,
                                 List<String> unmappedRequirements, List<String> ambiguities,
                                 List<RejectedRule> rejectedRules, Map<String, BigDecimal> confidence,
                                 List<String> exemptions, List<String> calculationMethods,
                                 Map<String, RuleReviewNote> ruleNotes) {
    public PolicyImportReview {
        exemptions = exemptions == null ? List.of() : List.copyOf(exemptions);
        calculationMethods = calculationMethods == null ? List.of() : List.copyOf(calculationMethods);
        ruleNotes = ruleNotes == null ? Map.of() : Map.copyOf(ruleNotes);
    }

    public record RejectedRule(String ruleCode, String reason, JsonNode suggestion) {}
    public record RuleReviewNote(String ambiguityNote, boolean requiresHumanReview) {}
}
