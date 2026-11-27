CREATE TABLE programme_notice_configuration (
    programme_id uuid PRIMARY KEY REFERENCES programme(id),
    procedure_text text NOT NULL,
    approved_by uuid NOT NULL,
    approved_at timestamp with time zone NOT NULL
);

CREATE TABLE case_communication (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES case_application(id),
    assessment_id uuid NOT NULL REFERENCES assessment(id),
    decision_id uuid REFERENCES official_decision(id),
    kind varchar(24) NOT NULL CHECK (kind IN ('EVIDENCE_REQUEST','DECISION_NOTICE')),
    state varchar(16) NOT NULL CHECK (state IN ('DRAFT','APPROVED','SENT')),
    english_text text NOT NULL,
    request_items text,
    officer_summary text,
    generated_by varchar(16) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    invalidated boolean NOT NULL DEFAULT false,
    approved_at timestamp with time zone,
    sent_at timestamp with time zone,
    translation_text text,
    translation_language varchar(40),
    translation_approved boolean NOT NULL DEFAULT false,
    CHECK ((decision_id IS NOT NULL) = (kind = 'DECISION_NOTICE'))
);
CREATE INDEX case_communication_case_idx ON case_communication(application_id, created_at);
