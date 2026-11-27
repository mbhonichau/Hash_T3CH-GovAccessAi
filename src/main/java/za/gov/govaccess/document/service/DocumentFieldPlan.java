package za.gov.govaccess.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import za.gov.govaccess.policy.repository.PolicyVersionRepository;
import za.gov.govaccess.policy.service.RulePolicyService;
import za.gov.govaccess.rules.entity.FieldDefinition;
import za.gov.govaccess.rules.model.RuleDocument.Rule;
import za.gov.govaccess.rules.service.RuleCatalogueService;
import java.util.UUID;

@Component
public class DocumentFieldPlan {
    private final PolicyVersionRepository versions;
    private final RulePolicyService policies;
    private final RuleCatalogueService catalogue;
    private final ObjectMapper mapper;

    public DocumentFieldPlan(PolicyVersionRepository versions, RulePolicyService policies,
                             RuleCatalogueService catalogue, ObjectMapper mapper) {
        this.versions = versions;
        this.policies = policies;
        this.catalogue = catalogue;
        this.mapper = mapper;
    }

    public record Field(String label, za.gov.govaccess.rules.enums.FieldValueType type, String unit,
                        List<String> ruleCodes) {}

    @Transactional(readOnly = true)
    public Map<String, Field> fields(UUID policyId, String documentType) {
        var policy = versions.findById(policyId).filter(v -> "PUBLISHED".equals(v.getStatus())).orElseThrow();
        var definitions = catalogue.catalogue().fields();
        var result = new LinkedHashMap<String, Field>();
        visit(policies.read(policy.getContent()).rules(), definitions, documentType, result);
        return Map.copyOf(result);
    }

    private void visit(List<Rule> rules, Map<String, FieldDefinition> definitions, String type,
                       Map<String, Field> result) {
        for (var rule : rules) {
            var definition = rule.fieldKey() == null ? null : definitions.get(rule.fieldKey());
            if (definition != null && rule.requiredEvidence().contains(type)
                    && definition.getSourceDocumentTypes().stream().anyMatch(document -> type.equals(document.getKey()))) {
                var previous = result.get(rule.fieldKey());
                var codes = new ArrayList<>(previous == null ? List.<String>of() : previous.ruleCodes());
                codes.add(rule.ruleCode());
                result.put(rule.fieldKey(), new Field(definition.getLabel(), definition.getValueType(),
                        definition.getUnit(), List.copyOf(codes)));
            }
            visit(rule.children(), definitions, type, result);
        }
    }

    public ObjectNode schema(Map<String, Field> fields) {
        var root = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        root.putArray("required").add("documentTypeMismatch").add("fields");
        var outputProperties = root.putObject("properties");
        outputProperties.putObject("documentTypeMismatch").put("type", "boolean");
        var wrapper = outputProperties.putObject("fields").put("type", "object")
                .put("additionalProperties", false);
        var required = wrapper.putArray("required");
        var properties = wrapper.putObject("properties");
        fields.forEach((key, field) -> {
            required.add(key);
            var entry = properties.putObject(key).put("type", "object").put("additionalProperties", false);
            entry.putArray("required").add("value").add("confidence").add("sourceSnippet").add("pageNumber");
            var members = entry.putObject("properties");
            var value = members.putObject("value").put("description", field.label());
            value.putArray("type").add(switch (field.type()) {
                case BOOLEAN -> "boolean";
                case INTEGER -> "integer";
                case DECIMAL, MONEY -> "number";
                case TEXT, DATE -> "string";
            }).add("null");
            members.putObject("confidence").put("type", "number").put("minimum", 0).put("maximum", 1);
            members.putObject("sourceSnippet").put("maxLength", 200).putArray("type").add("string").add("null");
            members.putObject("pageNumber").put("minimum", 1).putArray("type").add("integer").add("null");
        });
        return root;
    }
}
