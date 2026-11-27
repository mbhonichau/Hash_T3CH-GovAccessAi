ALTER TABLE assessment ADD COLUMN policy_hash varchar(64);
ALTER TABLE assessment_evidence_quality ADD COLUMN explanation_input text;
ALTER TABLE official_decision ADD COLUMN assessment_id uuid REFERENCES assessment(id);
ALTER TABLE official_decision ADD CONSTRAINT official_decision_per_assessment UNIQUE (assessment_id);

CREATE TABLE assessment_explanation_cache (
    assessment_id uuid PRIMARY KEY REFERENCES assessment(id),
    input_hash varchar(64) NOT NULL,
    generated_by varchar(16) NOT NULL CHECK (generated_by IN ('AI', 'TEMPLATE')),
    explanation text NOT NULL
);

CREATE TABLE assessment_supersession (
    previous_assessment_id uuid PRIMARY KEY REFERENCES assessment(id),
    replacement_assessment_id uuid NOT NULL UNIQUE REFERENCES assessment(id)
);

CREATE TABLE decision_handoff (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES case_application(id),
    assessment_id uuid NOT NULL REFERENCES assessment(id),
    type varchar(32) NOT NULL CHECK (type IN ('OVERRIDE_ESCALATION','ADDITIONAL_EVIDENCE')),
    proposed_decision varchar(32),
    reason text NOT NULL,
    owner_id uuid,
    created_at timestamp with time zone NOT NULL,
    invalidated boolean NOT NULL DEFAULT false
);
CREATE INDEX decision_handoff_case_idx ON decision_handoff(application_id, invalidated);
