INSERT INTO applicant (id, full_name, created_at)
SELECT '71000000-0000-4000-8000-000000000001', 'Naledi Mokoena', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM applicant WHERE id = '71000000-0000-4000-8000-000000000001');
INSERT INTO applicant (id, full_name, created_at)
SELECT '71000000-0000-4000-8000-000000000002', 'Thabo Ndlovu', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM applicant WHERE id = '71000000-0000-4000-8000-000000000002');
INSERT INTO applicant (id, full_name, created_at)
SELECT '71000000-0000-4000-8000-000000000003', 'Lerato Khumalo', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM applicant WHERE id = '71000000-0000-4000-8000-000000000003');

INSERT INTO case_application (id, reference, applicant_id, programme_id, status, recommendation, summary, created_by, created_at, updated_at)
SELECT '72000000-0000-4000-8000-000000000001', 'DEMO-NSFAS-ELIGIBLE-001',
       '71000000-0000-4000-8000-000000000001', p.id, 'ASSESSED', 'LIKELY_ELIGIBLE',
       'Synthetic case: verified grant and household income meet the draft screening rules.',
       'demo-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM programme p WHERE p.code = 'NSFAS_BURSARY'
  AND NOT EXISTS (SELECT 1 FROM case_application WHERE reference = 'DEMO-NSFAS-ELIGIBLE-001');
INSERT INTO case_application (id, reference, applicant_id, programme_id, status, recommendation, summary, created_by, created_at, updated_at)
SELECT '72000000-0000-4000-8000-000000000002', 'DEMO-NSFAS-REVIEW-001',
       '71000000-0000-4000-8000-000000000002', p.id, 'NEEDS_EVIDENCE', 'NEEDS_REVIEW',
       'Synthetic case: proof of household income is missing; an officer must request evidence.',
       'demo-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM programme p WHERE p.code = 'NSFAS_BURSARY'
  AND NOT EXISTS (SELECT 1 FROM case_application WHERE reference = 'DEMO-NSFAS-REVIEW-001');
INSERT INTO case_application (id, reference, applicant_id, programme_id, status, recommendation, summary, created_by, created_at, updated_at)
SELECT '72000000-0000-4000-8000-000000000003', 'DEMO-NSFAS-INELIGIBLE-001',
       '71000000-0000-4000-8000-000000000003', p.id, 'ASSESSED', 'NOT_ELIGIBLE',
       'Synthetic case: verified household income is above the applicable screening limit.',
       'demo-seed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM programme p WHERE p.code = 'NSFAS_BURSARY'
  AND NOT EXISTS (SELECT 1 FROM case_application WHERE reference = 'DEMO-NSFAS-INELIGIBLE-001');

INSERT INTO evidence_item (id, application_id, evidence_type, value_summary, verification_status, verified_by, created_at)
SELECT '73000000-0000-4000-8000-000000000001', id, 'GRANT_STATUS', 'Qualifying grant confirmed (synthetic)', 'VERIFIED', 'demo-seed', CURRENT_TIMESTAMP
FROM case_application WHERE reference = 'DEMO-NSFAS-ELIGIBLE-001'
  AND NOT EXISTS (SELECT 1 FROM evidence_item WHERE id = '73000000-0000-4000-8000-000000000001');
INSERT INTO evidence_item (id, application_id, evidence_type, value_summary, verification_status, verified_by, created_at)
SELECT '73000000-0000-4000-8000-000000000002', id, 'HOUSEHOLD_INCOME', 'R 280,000 annual household income (synthetic)', 'VERIFIED', 'demo-seed', CURRENT_TIMESTAMP
FROM case_application WHERE reference = 'DEMO-NSFAS-ELIGIBLE-001'
  AND NOT EXISTS (SELECT 1 FROM evidence_item WHERE id = '73000000-0000-4000-8000-000000000002');
INSERT INTO evidence_item (id, application_id, evidence_type, value_summary, verification_status, verified_by, created_at)
SELECT '73000000-0000-4000-8000-000000000003', id, 'HOUSEHOLD_INCOME', 'Verified income above screening limit (synthetic)', 'VERIFIED', 'demo-seed', CURRENT_TIMESTAMP
FROM case_application WHERE reference = 'DEMO-NSFAS-INELIGIBLE-001'
  AND NOT EXISTS (SELECT 1 FROM evidence_item WHERE id = '73000000-0000-4000-8000-000000000003');

INSERT INTO assessment (id, application_id, policy_version_id, recommendation, confidence, summary, assessed_by, assessed_at)
SELECT '74000000-0000-4000-8000-000000000001', a.id, p.id, 'LIKELY_ELIGIBLE', 0.9200,
       'Verified synthetic evidence meets the financial screening criteria.', 'demo-seed', CURRENT_TIMESTAMP
FROM case_application a JOIN policy_version p ON p.programme_id = a.programme_id
 AND p.version_label = '2025 financial screening'
WHERE a.reference = 'DEMO-NSFAS-ELIGIBLE-001'
  AND NOT EXISTS (SELECT 1 FROM assessment WHERE id = '74000000-0000-4000-8000-000000000001');
INSERT INTO assessment (id, application_id, policy_version_id, recommendation, confidence, summary, assessed_by, assessed_at)
SELECT '74000000-0000-4000-8000-000000000002', a.id, p.id, 'NEEDS_REVIEW', 0.4500,
       'Household income evidence is missing; no eligibility conclusion can be made.', 'demo-seed', CURRENT_TIMESTAMP
FROM case_application a JOIN policy_version p ON p.programme_id = a.programme_id
 AND p.version_label = '2025 financial screening'
WHERE a.reference = 'DEMO-NSFAS-REVIEW-001'
  AND NOT EXISTS (SELECT 1 FROM assessment WHERE id = '74000000-0000-4000-8000-000000000002');
INSERT INTO assessment (id, application_id, policy_version_id, recommendation, confidence, summary, assessed_by, assessed_at)
SELECT '74000000-0000-4000-8000-000000000003', a.id, p.id, 'NOT_ELIGIBLE', 0.9600,
       'Verified synthetic household income exceeds the applicable screening limit.', 'demo-seed', CURRENT_TIMESTAMP
FROM case_application a JOIN policy_version p ON p.programme_id = a.programme_id
 AND p.version_label = '2025 financial screening'
WHERE a.reference = 'DEMO-NSFAS-INELIGIBLE-001'
  AND NOT EXISTS (SELECT 1 FROM assessment WHERE id = '74000000-0000-4000-8000-000000000003');

INSERT INTO audit_event (id, application_id, actor_username, action, detail, occurred_at)
SELECT '75000000-0000-4000-8000-000000000001', id, 'demo-seed', 'CASE_CREATED', 'Synthetic likely-eligible example seeded.', CURRENT_TIMESTAMP
FROM case_application WHERE reference = 'DEMO-NSFAS-ELIGIBLE-001'
  AND NOT EXISTS (SELECT 1 FROM audit_event WHERE id = '75000000-0000-4000-8000-000000000001');
INSERT INTO audit_event (id, application_id, actor_username, action, detail, occurred_at)
SELECT '75000000-0000-4000-8000-000000000002', id, 'demo-seed', 'CASE_CREATED', 'Synthetic missing-evidence example seeded.', CURRENT_TIMESTAMP
FROM case_application WHERE reference = 'DEMO-NSFAS-REVIEW-001'
  AND NOT EXISTS (SELECT 1 FROM audit_event WHERE id = '75000000-0000-4000-8000-000000000002');
INSERT INTO audit_event (id, application_id, actor_username, action, detail, occurred_at)
SELECT '75000000-0000-4000-8000-000000000003', id, 'demo-seed', 'CASE_CREATED', 'Synthetic not-eligible example seeded.', CURRENT_TIMESTAMP
FROM case_application WHERE reference = 'DEMO-NSFAS-INELIGIBLE-001'
  AND NOT EXISTS (SELECT 1 FROM audit_event WHERE id = '75000000-0000-4000-8000-000000000003');
