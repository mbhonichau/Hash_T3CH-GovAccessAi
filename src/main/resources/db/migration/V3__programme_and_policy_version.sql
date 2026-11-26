CREATE TABLE programme (
    id uuid PRIMARY KEY,
    code varchar(100) NOT NULL UNIQUE,
    name varchar(255) NOT NULL
);

CREATE TABLE policy_version (
    id uuid PRIMARY KEY,
    programme_id uuid NOT NULL REFERENCES programme(id),
    version_label varchar(100) NOT NULL,
    status varchar(32) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED')),
    origin varchar(32) NOT NULL CHECK (origin IN ('MANUAL', 'AI_GENERATED')),
    rule_json text NOT NULL,
    UNIQUE (programme_id, version_label)
);
