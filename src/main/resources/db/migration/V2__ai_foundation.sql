CREATE TABLE ai_call_log (
    id uuid PRIMARY KEY,
    created_at timestamp with time zone NOT NULL,
    user_id uuid,
    prompt_id varchar(255) NOT NULL,
    prompt_version integer NOT NULL,
    provider varchar(255),
    model varchar(255),
    input_sha256 varchar(64) NOT NULL,
    output_sha256 varchar(64),
    latency_ms bigint NOT NULL,
    tokens_in integer,
    tokens_out integer,
    outcome varchar(32) NOT NULL,
    related_entity_type varchar(255),
    related_entity_id uuid,
    CONSTRAINT ai_call_log_outcome_check CHECK (outcome IN (
        'SUCCESS', 'RETRY_SUCCESS', 'INVALID_OUTPUT', 'PROVIDER_ERROR',
        'CACHE_HIT', 'BLOCKED_PII', 'KILL_SWITCH'
    ))
);

CREATE TABLE ai_cache (
    cache_key varchar(64) PRIMARY KEY,
    raw_text text NOT NULL,
    model varchar(255) NOT NULL,
    provider varchar(255) NOT NULL,
    tokens_in integer,
    tokens_out integer
);
