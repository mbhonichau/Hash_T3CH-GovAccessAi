ALTER TABLE case_application ADD COLUMN declared_values text;

CREATE TABLE assessment_evidence_quality (
    assessment_id uuid PRIMARY KEY REFERENCES assessment(id),
    input_snapshot_id uuid NOT NULL UNIQUE REFERENCES audit_protected_snapshot(id),
    findings text NOT NULL,
    breakdown text NOT NULL,
    active_configuration text NOT NULL
);
