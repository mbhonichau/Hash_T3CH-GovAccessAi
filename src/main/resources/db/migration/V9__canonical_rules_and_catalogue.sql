ALTER TABLE policy_version ADD COLUMN content text;
ALTER TABLE policy_version ALTER COLUMN rule_json DROP NOT NULL;
ALTER TABLE programme ADD COLUMN demo_data boolean NOT NULL DEFAULT false;

CREATE TABLE document_type (
    type_key varchar(100) PRIMARY KEY,
    label varchar(255) NOT NULL
);

CREATE TABLE field_definition (
    field_key varchar(100) PRIMARY KEY,
    label varchar(255) NOT NULL,
    value_type varchar(32) NOT NULL CHECK (value_type IN ('TEXT', 'BOOLEAN', 'INTEGER', 'DECIMAL', 'MONEY', 'DATE')),
    unit varchar(64)
);

CREATE TABLE field_definition_document_type (
    field_key varchar(100) NOT NULL REFERENCES field_definition(field_key),
    type_key varchar(100) NOT NULL REFERENCES document_type(type_key),
    PRIMARY KEY (field_key, type_key)
);

INSERT INTO document_type (type_key, label) VALUES ('NSFAS_DEMO_FACT', 'Synthetic verified NSFAS fact');

INSERT INTO field_definition (field_key, label, value_type, unit) VALUES
    ('nsfas.verifiedSassaGrant', 'Verified SASSA grant', 'TEXT', NULL),
    ('nsfas.annualGrossHouseholdIncome', 'Annual gross household income', 'MONEY', 'ZAR/year'),
    ('nsfas.verifiedIncomePathway', 'Verified income pathway', 'TEXT', NULL);

INSERT INTO field_definition_document_type (field_key, type_key)
SELECT field_key, 'NSFAS_DEMO_FACT' FROM field_definition;

INSERT INTO programme (id, code, name, demo_data)
SELECT 'e58c7032-b27d-4117-9885-63d830fd6453', 'NSFAS_BURSARY', 'NSFAS Bursary', true
WHERE NOT EXISTS (SELECT 1 FROM programme WHERE code = 'NSFAS_BURSARY');

INSERT INTO programme (id, code, name, demo_data)
SELECT 'f95a9242-7503-466c-b7b6-e9199031dbb0', 'SOCIAL_SUPPORT_GRANT', 'Social Support Grant', true
WHERE NOT EXISTS (SELECT 1 FROM programme WHERE code = 'SOCIAL_SUPPORT_GRANT');

INSERT INTO programme (id, code, name, demo_data)
SELECT 'd165d5cb-de1b-4591-98ad-b2c26aad3c73', 'HOUSING_SUPPORT_SUBSIDY', 'Housing Support Subsidy', true
WHERE NOT EXISTS (SELECT 1 FROM programme WHERE code = 'HOUSING_SUPPORT_SUBSIDY');

UPDATE programme SET demo_data = true
WHERE code IN ('NSFAS_BURSARY', 'SOCIAL_SUPPORT_GRANT', 'HOUSING_SUPPORT_SUBSIDY');
