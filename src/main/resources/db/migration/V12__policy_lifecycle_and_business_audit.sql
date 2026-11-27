ALTER TABLE policy_version ADD COLUMN author_id uuid;
ALTER TABLE policy_version ADD COLUMN publisher_id uuid;
ALTER TABLE policy_version ADD COLUMN published_at timestamp with time zone;
ALTER TABLE policy_version ADD COLUMN effective_from date;
ALTER TABLE policy_version ADD COLUMN supersedes_id uuid REFERENCES policy_version(id);
ALTER TABLE policy_version ADD COLUMN content_hash varchar(64);

CREATE TABLE policy_test_run (
    id uuid PRIMARY KEY,
    policy_version_id uuid NOT NULL REFERENCES policy_version(id),
    tester_id uuid NOT NULL,
    content_hash varchar(64) NOT NULL,
    passed boolean NOT NULL,
    total_cases integer NOT NULL CHECK (total_cases > 0),
    passed_cases integer NOT NULL CHECK (passed_cases >= 0 AND passed_cases <= total_cases),
    executed_at timestamp with time zone NOT NULL
);
CREATE INDEX policy_test_run_policy_idx ON policy_test_run(policy_version_id);

CREATE TABLE policy_transition_window (
    id uuid PRIMARY KEY,
    programme_id uuid NOT NULL REFERENCES programme(id),
    policy_version_id uuid NOT NULL REFERENCES policy_version(id),
    starts_on date NOT NULL,
    ends_on date NOT NULL,
    priority integer NOT NULL,
    CHECK (ends_on >= starts_on)
);
CREATE INDEX policy_transition_programme_idx ON policy_transition_window(programme_id, starts_on, ends_on);

CREATE TABLE managed_user (
    id uuid PRIMARY KEY,
    username varchar(100) NOT NULL UNIQUE,
    password_hash varchar(255) NOT NULL,
    role varchar(40) NOT NULL CHECK (role IN ('APPLICANT','ASSESSMENT_OFFICER','POLICY_ADMINISTRATOR','SUPERVISOR','AUDITOR')),
    enabled boolean NOT NULL
);

CREATE TABLE audit_protected_snapshot (
    id uuid PRIMARY KEY,
    payload text NOT NULL
);
REVOKE ALL ON TABLE audit_protected_snapshot FROM PUBLIC;
ALTER TABLE policy_test_run ADD COLUMN fixture_snapshot_id uuid REFERENCES audit_protected_snapshot(id);

CREATE TABLE audit_scope_assignment (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES managed_user(id),
    application_id uuid,
    programme_id uuid REFERENCES programme(id),
    CHECK ((application_id IS NULL) <> (programme_id IS NULL)),
    UNIQUE (actor_id, application_id),
    UNIQUE (actor_id, programme_id)
);

CREATE TABLE IF NOT EXISTS audit_event (
    id uuid PRIMARY KEY,
    application_id uuid,
    actor_username varchar(100) NOT NULL,
    action varchar(64) NOT NULL,
    detail text NOT NULL,
    occurred_at timestamp with time zone NOT NULL
);
ALTER TABLE audit_event ADD COLUMN event_sequence bigint;
ALTER TABLE audit_event ADD COLUMN actor_id uuid;
ALTER TABLE audit_event ADD COLUMN actor_role varchar(100);
ALTER TABLE audit_event ADD COLUMN policy_version_id uuid;
ALTER TABLE audit_event ADD COLUMN policy_hash varchar(64);
ALTER TABLE audit_event ADD COLUMN assessment_id uuid;
ALTER TABLE audit_event ADD COLUMN previous_snapshot_id uuid REFERENCES audit_protected_snapshot(id);
ALTER TABLE audit_event ADD COLUMN new_snapshot_id uuid REFERENCES audit_protected_snapshot(id);
ALTER TABLE audit_event ADD COLUMN ai_prompt_id varchar(255);
ALTER TABLE audit_event ADD COLUMN ai_prompt_version integer;
ALTER TABLE audit_event ADD COLUMN ai_model varchar(255);
ALTER TABLE audit_event ADD COLUMN previous_event_hash varchar(64);
ALTER TABLE audit_event ADD COLUMN event_hash varchar(64);
ALTER TABLE audit_event ADD CONSTRAINT audit_event_sequence_unique UNIQUE (event_sequence);

CREATE TABLE audit_chain_head (
    id integer PRIMARY KEY CHECK (id = 1),
    last_sequence bigint NOT NULL,
    last_hash varchar(64)
);
INSERT INTO audit_chain_head (id,last_sequence,last_hash) VALUES (1,0,NULL);

ALTER TABLE ai_request_log ADD COLUMN audit_event_id uuid UNIQUE REFERENCES audit_event(id);
