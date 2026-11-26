CREATE TABLE applicant (
    id uuid PRIMARY KEY,
    full_name varchar(200) NOT NULL,
    created_at timestamp with time zone NOT NULL
);

CREATE TABLE case_application (
    id uuid PRIMARY KEY,
    reference varchar(32) NOT NULL UNIQUE,
    applicant_id uuid NOT NULL REFERENCES applicant(id),
    programme_id uuid NOT NULL REFERENCES programme(id),
    status varchar(32) NOT NULL CHECK (status IN (
        'RECEIVED', 'IN_REVIEW', 'NEEDS_EVIDENCE', 'ASSESSED', 'DECISION_RECORDED', 'CLOSED'
    )),
    recommendation varchar(32) CHECK (recommendation IN (
        'LIKELY_ELIGIBLE', 'NEEDS_REVIEW', 'NOT_ELIGIBLE'
    )),
    summary text NOT NULL,
    created_by varchar(100) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);

CREATE INDEX case_application_queue_idx ON case_application (created_at DESC, status);
CREATE INDEX case_application_applicant_idx ON case_application (applicant_id);

CREATE TABLE application_document (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES case_application(id) ON DELETE CASCADE,
    original_filename varchar(255) NOT NULL,
    content_type varchar(150),
    storage_key varchar(500) NOT NULL,
    sha256 varchar(64),
    processing_status varchar(32) NOT NULL CHECK (processing_status IN (
        'RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED', 'VERIFIED'
    )),
    uploaded_by varchar(100) NOT NULL,
    created_at timestamp with time zone NOT NULL
);
CREATE INDEX application_document_case_idx ON application_document (application_id, created_at DESC);

CREATE TABLE evidence_item (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES case_application(id) ON DELETE CASCADE,
    document_id uuid REFERENCES application_document(id) ON DELETE SET NULL,
    evidence_type varchar(100) NOT NULL,
    value_summary text NOT NULL,
    verification_status varchar(32) NOT NULL CHECK (verification_status IN (
        'UNVERIFIED', 'VERIFIED', 'REJECTED', 'CONFLICTING'
    )),
    verified_by varchar(100),
    created_at timestamp with time zone NOT NULL
);
CREATE INDEX evidence_item_case_idx ON evidence_item (application_id, verification_status);

CREATE TABLE assessment (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES case_application(id) ON DELETE CASCADE,
    policy_version_id uuid REFERENCES policy_version(id),
    recommendation varchar(32) NOT NULL CHECK (recommendation IN (
        'LIKELY_ELIGIBLE', 'NEEDS_REVIEW', 'NOT_ELIGIBLE'
    )),
    confidence numeric(5,4),
    summary text NOT NULL,
    assessed_by varchar(100) NOT NULL,
    assessed_at timestamp with time zone NOT NULL
);
CREATE INDEX assessment_case_idx ON assessment (application_id, assessed_at DESC);

CREATE TABLE assessment_rule_result (
    id uuid PRIMARY KEY,
    assessment_id uuid NOT NULL REFERENCES assessment(id) ON DELETE CASCADE,
    rule_key varchar(100) NOT NULL,
    result varchar(32) NOT NULL CHECK (result IN ('PASS', 'FAIL', 'REVIEW', 'NOT_EVALUATED')),
    explanation text NOT NULL,
    evidence_item_id uuid REFERENCES evidence_item(id) ON DELETE SET NULL
);

CREATE TABLE official_decision (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES case_application(id) ON DELETE CASCADE,
    decision varchar(32) NOT NULL CHECK (decision IN ('APPROVED', 'DECLINED', 'RETURNED_FOR_EVIDENCE')),
    rationale text NOT NULL,
    decided_by varchar(100) NOT NULL,
    decided_at timestamp with time zone NOT NULL
);
CREATE INDEX official_decision_case_idx ON official_decision (application_id, decided_at DESC);

CREATE TABLE audit_event (
    id uuid PRIMARY KEY,
    application_id uuid REFERENCES case_application(id) ON DELETE SET NULL,
    actor_username varchar(100) NOT NULL,
    action varchar(64) NOT NULL,
    detail text NOT NULL,
    occurred_at timestamp with time zone NOT NULL
);
CREATE INDEX audit_event_case_idx ON audit_event (application_id, occurred_at DESC);
