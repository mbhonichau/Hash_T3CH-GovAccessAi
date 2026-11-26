CREATE TABLE policy_import_job (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    draft_id uuid NOT NULL UNIQUE,
    programme_code varchar(100) NOT NULL REFERENCES programme(code),
    version_label varchar(100) NOT NULL,
    source_label varchar(255) NOT NULL,
    source_text text,
    source_pdf bytea,
    status varchar(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    completed_chunks integer NOT NULL DEFAULT 0,
    total_chunks integer NOT NULL DEFAULT 0,
    failure varchar(255),
    review_json text,
    updated_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((source_text IS NULL) <> (source_pdf IS NULL))
);
CREATE INDEX policy_import_job_queue_idx ON policy_import_job(status, updated_at);
CREATE INDEX policy_import_job_user_idx ON policy_import_job(user_id, updated_at);

CREATE TABLE policy_import_rule_edit (
    id uuid PRIMARY KEY,
    job_id uuid NOT NULL REFERENCES policy_import_job(id),
    rule_code varchar(100) NOT NULL,
    editor_id uuid NOT NULL,
    editor_label varchar(255) NOT NULL,
    original_value text NOT NULL,
    previous_value text NOT NULL,
    new_value text NOT NULL,
    edited_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX policy_import_rule_edit_job_idx ON policy_import_rule_edit(job_id, edited_at);
