package za.gov.govaccess.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import za.gov.govaccess.rules.dto.RuleEvaluation;
import za.gov.govaccess.rules.dto.VerifiedRuleEvidence;
import za.gov.govaccess.rules.entity.DocumentType;
import za.gov.govaccess.rules.entity.FieldDefinition;
import za.gov.govaccess.rules.enums.FieldValueType;
import za.gov.govaccess.rules.enums.RuleOperator;
import za.gov.govaccess.rules.model.RuleDocument;
import za.gov.govaccess.rules.model.RuleDocument.Condition;
import za.gov.govaccess.rules.model.RuleDocument.Group;
import za.gov.govaccess.rules.model.RuleDocument.OnMissingEvidence;
import za.gov.govaccess.rules.model.RuleDocument.Rule;
import za.gov.govaccess.rules.model.RuleDocument.Source;
import za.gov.govaccess.rules.service.RecommendationService;
import za.gov.govaccess.rules.service.RuleDocumentValidator;
import za.gov.govaccess.rules.service.RuleEvaluator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static za.gov.govaccess.rules.dto.RuleEvaluation.Status.*;
import static za.gov.govaccess.rules.enums.RuleOperator.*;
import static za.gov.govaccess.rules.service.RecommendationService.Recommendation.*;

class RuleEvaluatorTests {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Source SOURCE = new Source("Synthetic test policy", "1", 1, "Test rule");
    private final DocumentType certificate = new DocumentType("certificate", "Certificate");
    private final Map<String, FieldDefinition> fields = Map.of(
            "income", new FieldDefinition("income", "Income", FieldValueType.MONEY, "ZAR/year", Set.of(certificate)),
            "active", new FieldDefinition("active", "Active", FieldValueType.BOOLEAN, null, Set.of(certificate)),
            "category", new FieldDefinition("category", "Category", FieldValueType.TEXT, null, Set.of(certificate)),
            "date", new FieldDefinition("date", "Date", FieldValueType.DATE, null, Set.of(certificate)));
    private final RuleEvaluator evaluator = new RuleEvaluator(new RuleDocumentValidator(4));
    private final RecommendationService recommendations = new RecommendationService();

    static Stream<Arguments> operators() {
        return Stream.of(
                Arguments.of(GREATER_THAN_OR_EQUAL, "income", "100.00", "100", "99.99"),
                Arguments.of(LESS_THAN_OR_EQUAL, "income", "100", "100.00", "100.01"),
                Arguments.of(EQUALS, "income", "100.00000000000001", "100.00000000000001", "100.00000000000002"),
                Arguments.of(NOT_EQUALS, "category", "\"A\"", "\"B\"", "\"A\""),
                Arguments.of(BETWEEN, "income", "[10,20]", "20", "20.01"),
                Arguments.of(IN_SET, "category", "[\"A\",\"B\"]", "\"B\"", "\"C\""),
                Arguments.of(IS_TRUE, "active", "true", "true", "false"),
                Arguments.of(DATE_WITHIN, "date", "[\"2025-01-01\",\"2025-12-31\"]", "\"2025-01-01\"", "\"2024-12-31\""));
    }

    @ParameterizedTest
    @MethodSource("operators")
    void comparesTypedValuesWithInclusiveBounds(RuleOperator operator, String key, String expected,
                                                 String passing, String failing) {
        var rule = rule("candidate", key, operator, expected, true, OnMissingEvidence.UNKNOWN, null);
        var pass = evaluate(document(rule), fact("fact", key, passing));
        var fail = evaluate(document(rule), fact("fact", key, failing));
        assertThat(pass.evaluations()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(PASS);
            assertThat(result.evidenceRefs()).containsExactly("fact");
        });
        assertThat(fail.evaluations()).singleElement().extracting(RuleEvaluation::status).isEqualTo(FAIL);
    }

    @Test
    void checksDocumentAndMissingEvidencePolicies() {
        var required = rule("document", null, DOCUMENT_REQUIRED, "\"certificate\"", true,
                OnMissingEvidence.NEEDS_REVIEW, null);
        var document = document(required);
        var missing = evaluate(document);
        assertThat(missing.evaluations()).singleElement().extracting(RuleEvaluation::status).isEqualTo(UNKNOWN);
        assertThat(missing.manualReview()).isTrue();
        assertThat(evaluate(document, new VerifiedRuleEvidence("certificate-1", "certificate", null, null))
                .evaluations()).singleElement().satisfies(result -> {
                    assertThat(result.status()).isEqualTo(PASS);
                    assertThat(result.evidenceRefs()).containsExactly("certificate-1");
                });
        var gated = new Rule("gated", "Gated", "income", LESS_THAN_OR_EQUAL, json("100"), "ZAR/year",
                true, List.of("extra"), OnMissingEvidence.UNKNOWN, SOURCE, null, null);
        var result = evaluate(document(gated), fact("pay", "income", "80"));
        assertThat(result.manualReview()).isFalse();
        assertThat(result.evaluations()).singleElement().extracting(RuleEvaluation::status).isEqualTo(UNKNOWN);
    }

    @Test
    void respectsConditionsGroupsConflictsAndMaximumDepth() {
        var conditional = rule("conditional", "income", LESS_THAN_OR_EQUAL, "100", true,
                OnMissingEvidence.UNKNOWN, new Condition("category", json("\"A\"")));
        var alternative = rule("alternative", "active", IS_TRUE, "true", true, OnMissingEvidence.UNKNOWN, null);
        var nested = group("outer", AND, group("choice", OR, conditional, alternative));
        var policy = document(nested);

        var result = evaluate(policy, fact("category", "category", "\"B\""), fact("active", "active", "true"));
        assertThat(result.evaluations()).extracting(RuleEvaluation::status).containsExactly(NOT_APPLICABLE, PASS, PASS, PASS);
        assertThat(recommendations.recommend(policy, result, false)).isEqualTo(LIKELY_ELIGIBLE);

        var conflict = evaluate(policy, fact("category", "category", "\"A\""),
                fact("pay-1", "income", "50"), fact("pay-2", "income", "150"), fact("active", "active", "false"));
        assertThat(conflict.evaluations()).extracting(RuleEvaluation::status).containsExactly(CONFLICT, FAIL, CONFLICT, CONFLICT);
        assertThat(conflict.evaluations().getFirst().evidenceRefs()).containsExactly("category", "pay-1", "pay-2");
        assertThat(recommendations.recommend(policy, conflict, false)).isEqualTo(NEEDS_REVIEW);

        var strictDepth = new RuleEvaluator(new RuleDocumentValidator(1));
        assertThatThrownBy(() -> strictDepth.evaluate(policy, fields, Set.of("certificate"), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("depth");
    }

    @Test
    void rejectsUnknownFieldsAndIgnoresUnapprovedDocumentSources() {
        var rule = rule("income-rule", "income", LESS_THAN_OR_EQUAL, "100", true, OnMissingEvidence.UNKNOWN, null);
        var unapproved = new VerifiedRuleEvidence("extra-document", "extra", "income", json("70"));
        assertThat(evaluate(document(rule), unapproved).evaluations()).singleElement()
                .extracting(RuleEvaluation::status).isEqualTo(UNKNOWN);
        var invalid = rule("unknown", "undefined", EQUALS, "1", true, OnMissingEvidence.UNKNOWN, null);
        assertThatThrownBy(() -> evaluate(document(invalid))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown rule field");
    }

    @Test
    void recommendsAccordingToPrecedenceAndRequiresAllMandatoryEvaluations() {
        var first = rule("first", "active", IS_TRUE, "true", true, OnMissingEvidence.UNKNOWN, null);
        var second = rule("second", "active", IS_TRUE, "true", true, OnMissingEvidence.UNKNOWN, null);
        var policy = document(first, second);
        assertThat(recommendations.recommend(policy, result(), false)).isEqualTo(NEEDS_REVIEW);
        assertThat(recommendations.recommend(policy, result(answer("first", PASS)), false)).isEqualTo(NEEDS_REVIEW);
        assertThat(recommendations.recommend(policy, result(answer("first", FAIL), answer("second", UNKNOWN)), false))
                .isEqualTo(NEEDS_REVIEW);
        assertThat(recommendations.recommend(policy, result(answer("first", FAIL), answer("second", CONFLICT)), false))
                .isEqualTo(NEEDS_REVIEW);
        assertThat(recommendations.recommend(policy, result(answer("first", FAIL), answer("second", PASS)), false))
                .isEqualTo(NOT_ELIGIBLE);
        assertThat(recommendations.recommend(policy, result(answer("first", PASS), answer("second", NOT_APPLICABLE)), false))
                .isEqualTo(LIKELY_ELIGIBLE);
        assertThat(recommendations.recommend(policy, result(answer("first", PASS), answer("second", PASS)), true))
                .isEqualTo(NEEDS_REVIEW);
        var group = document(group("choice", OR, first, second));
        assertThat(recommendations.recommend(group, result(answer("choice", PASS), answer("first", PASS)), false))
                .isEqualTo(NEEDS_REVIEW);
    }

    private RuleEvaluator.Result evaluate(RuleDocument document, VerifiedRuleEvidence... evidence) {
        return evaluator.evaluate(document, fields, Set.of("certificate", "extra"), List.of(evidence));
    }

    private static RuleEvaluator.Result result(RuleEvaluation... answers) {
        return new RuleEvaluator.Result(List.of(answers), false);
    }

    private static RuleEvaluation answer(String code, RuleEvaluation.Status status) {
        return new RuleEvaluation(code, status, true, null, null, List.of());
    }

    private static VerifiedRuleEvidence fact(String ref, String field, String value) {
        return new VerifiedRuleEvidence(ref, "certificate", field, json(value));
    }

    private static Rule rule(String code, String field, RuleOperator operator, String expected, boolean mandatory,
                             OnMissingEvidence onMissing, Condition condition) {
        return new Rule(code, code, field, operator, json(expected), "income".equals(field) ? "ZAR/year" : null,
                mandatory, List.of(), onMissing, SOURCE, null, condition);
    }

    private static Rule group(String code, RuleOperator type, Rule... children) {
        return new Rule(code, code, null, type, null, null, true, List.of(), OnMissingEvidence.UNKNOWN, SOURCE,
                new Group(type, List.of(children)), null);
    }

    private static RuleDocument document(Rule... rules) {
        return new RuleDocument(1, List.of(rules), null);
    }

    private static JsonNode json(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
