CREATE TABLE IF NOT EXISTS ai_request_log (
    id uuid PRIMARY KEY,
    created_at timestamp with time zone NOT NULL,
    user_id uuid,
    related_entity_type varchar(255),
    related_entity_id uuid,
    prompt_id varchar(255) NOT NULL,
    prompt_version integer NOT NULL,
    provider varchar(255),
    model varchar(255),
    outcome varchar(32) CHECK (outcome IN (
        'SUCCESS', 'RETRY_SUCCESS', 'INVALID_OUTPUT', 'PROVIDER_ERROR',
        'CACHE_HIT', 'BLOCKED_PII', 'KILL_SWITCH'
    )),
    attempt_count integer NOT NULL CHECK (attempt_count >= 0),
    total_latency_ms bigint NOT NULL,
    input_sha256 varchar(64) NOT NULL,
    output_sha256 varchar(64)
);

ALTER TABLE ai_call_log ADD COLUMN IF NOT EXISTS request_id uuid;
ALTER TABLE ai_call_log DROP CONSTRAINT IF EXISTS ai_call_log_outcome_check;
ALTER TABLE ai_call_log ADD CONSTRAINT ai_call_log_outcome_check CHECK (outcome IN (
    'SUCCESS', 'RETRY_SUCCESS', 'INVALID_OUTPUT', 'PROVIDER_ERROR',
    'CACHE_HIT', 'BLOCKED_PII', 'KILL_SWITCH'
));

-- Legacy rows did not have a correlation ID. Each becomes its own historical request.
INSERT INTO ai_request_log (
    id, created_at, user_id, related_entity_type, related_entity_id, prompt_id, prompt_version,
    provider, model, outcome, attempt_count, total_latency_ms, input_sha256, output_sha256
)
SELECT id, created_at, user_id, related_entity_type, related_entity_id, prompt_id, prompt_version,
    provider, model, outcome, 1, latency_ms, input_sha256, output_sha256
FROM ai_call_log a
WHERE a.request_id IS NULL AND NOT EXISTS (SELECT 1 FROM ai_request_log r WHERE r.id = a.id);

UPDATE ai_call_log SET request_id = id WHERE request_id IS NULL;
ALTER TABLE ai_call_log ALTER COLUMN request_id SET NOT NULL;
ALTER TABLE ai_call_log DROP CONSTRAINT IF EXISTS ai_call_log_request_fk;
ALTER TABLE ai_call_log ADD CONSTRAINT ai_call_log_request_fk FOREIGN KEY (request_id) REFERENCES ai_request_log(id);
CREATE INDEX IF NOT EXISTS ai_call_log_request_idx ON ai_call_log(request_id);
