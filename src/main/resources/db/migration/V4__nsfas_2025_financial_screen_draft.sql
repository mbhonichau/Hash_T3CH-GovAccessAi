INSERT INTO programme (id, code, name)
VALUES ('e58c7032-b27d-4117-9885-63d830fd6453', 'NSFAS_BURSARY', 'NSFAS Bursary');

INSERT INTO policy_version (id, programme_id, version_label, status, origin, rule_json)
VALUES (
    '3c72c8f9-080f-49c4-9a96-36092d0d9187',
    'e58c7032-b27d-4117-9885-63d830fd6453',
    '2025 financial screening', 'DRAFT', 'MANUAL',
    '{
      "qualifyingSassaGrant": {
        "ruleKey": "qualifying-sassa-grant",
        "value": ["FOSTER_CARE", "CARE_DEPENDENCY", "CHILD_SUPPORT"],
        "sourceSection": "4.4.2.1–4.4.2.2",
        "sourceQuote": "SASSA grant recipients that are eligible for funding include the Foster Care, Care Dependency and Child Support grants."
      },
      "standardIncome": {
        "ruleKey": "standard-income-cap",
        "value": 350000,
        "sourceSection": "4.4.3.1",
        "sourceQuote": "the applicant''s combined annual household income is R350,000 or less."
      },
      "disabilityIncome": {
        "ruleKey": "disability-income-cap",
        "value": 600000,
        "sourceSection": "4.4.3.2",
        "sourceQuote": "the applicant''s combined annual household income is R600,000 or less."
      }
    }'
);
