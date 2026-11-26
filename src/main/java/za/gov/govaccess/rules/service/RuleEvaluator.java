package za.gov.govaccess.rules.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import za.gov.govaccess.rules.dto.RuleEvaluation;
import za.gov.govaccess.rules.dto.RuleEvaluation.Status;
import za.gov.govaccess.rules.dto.VerifiedRuleEvidence;
import za.gov.govaccess.rules.entity.DocumentType;
import za.gov.govaccess.rules.entity.FieldDefinition;
import za.gov.govaccess.rules.enums.RuleOperator;
import za.gov.govaccess.rules.model.RuleDocument;
import za.gov.govaccess.rules.model.RuleDocument.OnMissingEvidence;
import za.gov.govaccess.rules.model.RuleDocument.Rule;

@Component
public class RuleEvaluator {
    private final RuleDocumentValidator validator;

    public RuleEvaluator(RuleDocumentValidator validator) {
        this.validator = validator;
    }

    public record Result(List<RuleEvaluation> evaluations, boolean manualReview) {
        public Result { evaluations = List.copyOf(evaluations); }
    }

    private record Finding(Status status, JsonNode value, List<String> refs, boolean review) {}

    public Result evaluate(RuleDocument document, Map<String, FieldDefinition> fields, Set<String> documentTypes,
                           List<VerifiedRuleEvidence> evidence) {
        validator.validate(document, fields, documentTypes);
        var results = new ArrayList<RuleEvaluation>();
        var context = new EvaluationContext(Map.copyOf(fields), evidence.stream()
                .filter(item -> documentTypes.contains(item.documentType())).toList());
        boolean review = false;
        for (var rule : document.rules()) {
            review |= evaluate(rule, context, results).review();
        }
        return new Result(results, review);
    }

    private record EvaluationContext(Map<String, FieldDefinition> fields, List<VerifiedRuleEvidence> evidence) {}

    private Finding evaluate(Rule rule, EvaluationContext context, List<RuleEvaluation> results) {
        Finding gate = gate(rule, context);
        if (gate.status() != Status.PASS) {
            markBranch(rule, results, gate);
            return gate;
        }
        Finding finding = rule.operator().isGroup() ? group(rule, context, results) : leaf(rule, context);
        finding = new Finding(finding.status(), finding.value(),
                java.util.stream.Stream.concat(gate.refs().stream(), finding.refs().stream()).distinct().sorted().toList(),
                finding.review());
        results.add(toEvaluation(rule, finding));
        return finding;
    }

    private Finding gate(Rule rule, EvaluationContext context) {
        var references = new ArrayList<String>();
        if (rule.condition() != null) {
            var condition = rule.condition();
            var field = context.fields().get(condition.fieldKey());
            var value = fact(field, context.evidence());
            if (value.status() == Status.CONFLICT) {
                return value;
            }
            if (value.status() == Status.UNKNOWN) {
                return missing(rule, value.refs());
            }
            if (!value.value().equals(RuleValues.normalize(condition.expectedValue(), field.getValueType()))) {
                return new Finding(Status.NOT_APPLICABLE, null, value.refs(), false);
            }
            references.addAll(value.refs());
        }
        var documents = context.evidence().stream()
                .filter(item -> rule.requiredEvidence().contains(item.documentType())).toList();
        references.addAll(refs(documents));
        var present = documents.stream().map(VerifiedRuleEvidence::documentType).collect(Collectors.toSet());
        var uniqueRefs = references.stream().distinct().sorted().toList();
        return present.containsAll(rule.requiredEvidence()) ? new Finding(Status.PASS, null, uniqueRefs, false)
                : missing(rule, uniqueRefs);
    }

    private Finding leaf(Rule rule, EvaluationContext context) {
        if (rule.operator() == RuleOperator.DOCUMENT_REQUIRED) {
            var refs = refs(context.evidence().stream()
                    .filter(item -> item.documentType().equals(rule.expectedValue().textValue())).toList());
            return refs.isEmpty() ? missing(rule, refs) : new Finding(Status.PASS, BooleanNode.TRUE, refs, false);
        }
        var field = context.fields().get(rule.fieldKey());
        var evidence = fact(field, context.evidence());
        if (evidence.status() == Status.UNKNOWN) {
            return missing(rule, evidence.refs());
        }
        if (evidence.status() == Status.CONFLICT) {
            return evidence;
        }
        var actual = evidence.value();
        var expected = rule.expectedValue();
        var type = field.getValueType();
        boolean passed = switch (rule.operator()) {
            case EQUALS -> actual.equals(RuleValues.normalize(expected, type));
            case NOT_EQUALS -> !actual.equals(RuleValues.normalize(expected, type));
            case IS_TRUE -> actual.booleanValue();
            case GREATER_THAN_OR_EQUAL -> RuleValues.compare(actual, RuleValues.normalize(expected, type)) >= 0;
            case LESS_THAN_OR_EQUAL -> RuleValues.compare(actual, RuleValues.normalize(expected, type)) <= 0;
            case BETWEEN, DATE_WITHIN -> RuleValues.compare(actual, RuleValues.normalize(expected.get(0), type)) >= 0
                    && RuleValues.compare(actual, RuleValues.normalize(expected.get(1), type)) <= 0;
            case IN_SET -> {
                boolean found = false;
                for (var item : expected) {
                    found |= actual.equals(RuleValues.normalize(item, type));
                }
                yield found;
            }
            default -> throw new IllegalStateException("Unexpected leaf operator");
        };
        return new Finding(passed ? Status.PASS : Status.FAIL, actual, evidence.refs(), false);
    }

    private Finding group(Rule rule, EvaluationContext context, List<RuleEvaluation> results) {
        var children = rule.children().stream().map(child -> evaluate(child, context, results)).toList();
        var statuses = children.stream().map(Finding::status).collect(Collectors.toSet());
        boolean review = children.stream().anyMatch(Finding::review);
        var refs = children.stream().flatMap(child -> child.refs().stream()).distinct().sorted().toList();
        boolean or = rule.operator() == RuleOperator.OR;
        Status decisive = or ? Status.PASS : Status.FAIL;
        Status other = or ? Status.FAIL : Status.PASS;
        Status status = statuses.contains(decisive) ? decisive
                : statuses.contains(Status.CONFLICT) ? Status.CONFLICT
                : statuses.contains(Status.UNKNOWN) ? Status.UNKNOWN
                : statuses.contains(other) ? other : Status.NOT_APPLICABLE;
        return new Finding(status, null, refs, review);
    }

    private static Finding fact(FieldDefinition field, List<VerifiedRuleEvidence> evidence) {
        var sources = field.getSourceDocumentTypes().stream().map(DocumentType::getKey).collect(Collectors.toSet());
        var matching = evidence.stream().filter(item -> field.getKey().equals(item.fieldKey())
                && sources.contains(item.documentType())).toList();
        var values = new LinkedHashMap<JsonNode, VerifiedRuleEvidence>();
        for (var item : matching) {
            try {
                values.putIfAbsent(RuleValues.normalize(item.value(), field.getValueType()), item);
            } catch (IllegalArgumentException e) {
                return new Finding(Status.CONFLICT, null, refs(matching), false);
            }
        }
        var references = refs(matching);
        if (values.isEmpty()) {
            return new Finding(Status.UNKNOWN, null, references, false);
        }
        if (values.size() > 1) {
            return new Finding(Status.CONFLICT, null, references, false);
        }
        return new Finding(Status.PASS, values.keySet().iterator().next(), references, false);
    }

    private static Finding missing(Rule rule, List<String> refs) {
        return new Finding(Status.UNKNOWN, null, refs, rule.onMissingEvidence() == OnMissingEvidence.NEEDS_REVIEW);
    }

    private static void markBranch(Rule rule, List<RuleEvaluation> results, Finding finding) {
        rule.children().forEach(child -> markBranch(child, results, finding));
        results.add(toEvaluation(rule, finding));
    }

    private static RuleEvaluation toEvaluation(Rule rule, Finding finding) {
        return new RuleEvaluation(rule.ruleCode(), finding.status(), rule.mandatory(),
                rule.expectedValue(), finding.value(), finding.refs());
    }

    private static List<String> refs(List<VerifiedRuleEvidence> evidence) {
        return evidence.stream().map(VerifiedRuleEvidence::reference).distinct().sorted().toList();
    }
}
