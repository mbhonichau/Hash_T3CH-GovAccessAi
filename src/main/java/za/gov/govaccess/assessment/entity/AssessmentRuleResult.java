package za.gov.govaccess.assessment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import za.gov.govaccess.evidence.entity.EvidenceItem;

@Entity
@Table(name = "assessment_rule_result")
public class AssessmentRuleResult {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assessment_id", nullable = false) private Assessment assessment;
    @Column(name = "rule_key", nullable = false, length = 100) private String ruleKey;
    @Column(nullable = false, length = 32) private String result;
    @Column(nullable = false, columnDefinition = "text") private String explanation;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evidence_item_id") private EvidenceItem evidenceItem;

    protected AssessmentRuleResult() {}
    public UUID getId() { return id; }
    public String getRuleKey() { return ruleKey; }
    public String getResult() { return result; }
    public String getExplanation() { return explanation; }
}
