ALTER TABLE case_application ADD COLUMN owner_id uuid;
ALTER TABLE case_application ADD COLUMN application_date date;
ALTER TABLE case_application ADD COLUMN policy_version_id uuid REFERENCES policy_version(id);
ALTER TABLE case_application ADD COLUMN assigned_officer_id uuid REFERENCES managed_user(id);
ALTER TABLE case_application ADD COLUMN team_supervisor_id uuid REFERENCES managed_user(id);
ALTER TABLE case_application ADD COLUMN escalated_to_id uuid REFERENCES managed_user(id);
CREATE INDEX case_application_owner_idx ON case_application(owner_id);

CREATE TABLE document_processing (
    document_id uuid PRIMARY KEY REFERENCES application_document(id) ON DELETE CASCADE,
    uploaded_actor_id uuid NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes > 0),
    state varchar(32) NOT NULL CHECK (state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','DUPLICATE_DOCUMENT')),
    version integer NOT NULL DEFAULT 1,
    duplicate_of uuid REFERENCES application_document(id),
    document_type varchar(100) REFERENCES document_type(type_key),
    ocr_confidence decimal(5,2),
    error_message varchar(255),
    extracted_fields text,
    started_at timestamp with time zone
);
CREATE INDEX document_processing_state_idx ON document_processing(state);
ALTER TABLE ai_request_log ADD COLUMN application_id uuid;

INSERT INTO document_type (type_key,label) VALUES
    ('IDENTITY_DOCUMENT','Identity document'), ('PAYSLIP','Payslip'),
    ('BANK_STATEMENT','Bank statement'), ('PROOF_OF_RESIDENCE','Proof of residence'),
    ('ACADEMIC_TRANSCRIPT','Academic transcript'), ('ACCEPTANCE_LETTER','Acceptance letter'),
    ('INCOME_DECLARATION','Income declaration'), ('UNEMPLOYMENT_DECLARATION','Unemployment declaration');
