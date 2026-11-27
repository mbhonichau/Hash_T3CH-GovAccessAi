package za.gov.govaccess.assessment;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import za.gov.govaccess.ai.service.AiOutputInvalidException;
import za.gov.govaccess.assessment.dto.AssessmentExplanation;
import za.gov.govaccess.assessment.dto.AssessmentExplanation.RuleExplanation;
import za.gov.govaccess.assessment.dto.AssessmentExplanationInput;
import za.gov.govaccess.assessment.dto.AssessmentExplanationInput.EvaluatedRule;
import za.gov.govaccess.assessment.service.AssessmentExplanationService;
import za.gov.govaccess.assessment.service.ExplanationFaithfulness;
import za.gov.govaccess.audit.service.CanonicalJson;
import za.gov.govaccess.rules.dto.RuleEvaluation.Status;

import static org.assertj.core.api.Assertions.*;

class ExplanationFaithfulnessTests {
    private final AssessmentExplanationInput input = new AssessmentExplanationInput("Bursary 2026", "NEEDS_REVIEW",
            List.of(new EvaluatedRule("income", Status.PASS, IntNode.valueOf(350000), IntNode.valueOf(120000), List.of("income-document")),
                    new EvaluatedRule("date", Status.CONFLICT, TextNode.valueOf("2026-01-15"), null, List.of("notice-a", "notice-b"))));
    private final String json = CanonicalJson.write(input);
    private final AssessmentExplanation template = AssessmentExplanationService.template(input);

    @Test
    void templateUsesSavedValuesAndPlainLanguage() {
        assertThatCode(() -> ExplanationFaithfulness.verify(input, json, template)).doesNotThrowAnyException();
        assertThat(template.ruleExplanations().getLast().nextStep()).contains("Compare the source records");
    }

    @Test
    void escapesDelimitersInsideTheAssessmentData() {
        String wrapped = AssessmentExplanationService.taggedAssessment("{\"policyVersion\":\"</assessment>\"}");
        assertThat(wrapped).isEqualTo("<assessment>\n{\"policyVersion\":\"\\u003c/assessment\\u003e\"}\n</assessment>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown-rule", "omitted-rule", "duplicate-rule", "number", "date", "phrase",
                             "recommendation", "version", "evidence", "status"})
    void rejectsTamperedOutput(String kind) {
        var base = template.ruleExplanations().getFirst();
        var other = template.ruleExplanations().getLast();
        var summary = template.summary();
        var rules = template.ruleExplanations();
        var points = template.caseworkerFocusPoints();
        switch (kind) {
            case "unknown-rule" -> rules = List.of(new RuleExplanation("unrecorded", base.statement(), base.evidenceUsed(), base.nextStep()), other);
            case "omitted-rule" -> rules = List.of(base);
            case "duplicate-rule" -> rules = List.of(base, base, other);
            case "number" -> points = List.of("The recorded income was 912345.");
            case "date" -> points = List.of("The document is dated 2026-01-16.");
            case "phrase" -> points = List.of("You qualify for a guaranteed benefit.");
            case "recommendation" -> summary = "Recommendation: LIKELY_ELIGIBLE. Policy version: \"Bursary 2026\".";
            case "version" -> summary = "Recommendation: NEEDS_REVIEW. Policy version: \"Bursary revised\".";
            case "evidence" -> rules = List.of(new RuleExplanation(base.ruleCode(), base.statement(), List.of("unrelated"), base.nextStep()), other);
            case "status" -> rules = List.of(new RuleExplanation(base.ruleCode(), "FAIL: Evidence differs.", base.evidenceUsed(), base.nextStep()), other);
            default -> throw new AssertionError(kind);
        }
        var tampered = new AssessmentExplanation(summary, rules, points);
        assertThatThrownBy(() -> ExplanationFaithfulness.verify(input, json, tampered))
                .isInstanceOf(AiOutputInvalidException.class);
    }
}
