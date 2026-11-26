package za.gov.govaccess.rules.service;

import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import za.gov.govaccess.rules.dto.RuleEvaluation;
import za.gov.govaccess.rules.dto.RuleEvaluation.Status;
import za.gov.govaccess.rules.model.RuleDocument;
import za.gov.govaccess.rules.model.RuleDocument.Rule;

@Service
public class RecommendationService {
    public enum Recommendation { NEEDS_REVIEW, NOT_ELIGIBLE, LIKELY_ELIGIBLE }

    public Recommendation recommend(RuleDocument policy, RuleEvaluator.Result result, boolean manualReview) {
        if (manualReview || result.manualReview() || result.evaluations().isEmpty() || policy.rules().isEmpty()) {
            return Recommendation.NEEDS_REVIEW;
        }
        var evaluations = new HashMap<String, RuleEvaluation>();
        for (var evaluation : result.evaluations()) {
            if (evaluation.status() == null || evaluations.putIfAbsent(evaluation.ruleCode(), evaluation) != null) {
                return Recommendation.NEEDS_REVIEW;
            }
        }
        if (policy.rules().stream().anyMatch(rule -> incomplete(rule, evaluations))) {
            return Recommendation.NEEDS_REVIEW;
        }
        var rootResults = policy.rules().stream().map(rule -> evaluations.get(rule.ruleCode())).toList();
        if (rootResults.stream().anyMatch(value -> value == null
                || value.status() == Status.UNKNOWN || value.status() == Status.CONFLICT)) {
            return Recommendation.NEEDS_REVIEW;
        }
        return rootResults.stream().anyMatch(value -> value.mandatory() && value.status() == Status.FAIL)
                ? Recommendation.NOT_ELIGIBLE : Recommendation.LIKELY_ELIGIBLE;
    }

    private static boolean incomplete(Rule rule, Map<String, RuleEvaluation> evaluations) {
        var evaluation = evaluations.get(rule.ruleCode());
        return (rule.mandatory() && evaluation == null)
                || evaluation != null && evaluation.mandatory() != rule.mandatory()
                || rule.children().stream().anyMatch(child -> incomplete(child, evaluations));
    }
}
