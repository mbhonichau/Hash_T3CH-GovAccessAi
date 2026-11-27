ALTER TABLE govaccess.user_account_permission
    DROP CONSTRAINT IF EXISTS user_account_permission_permissions_check;

ALTER TABLE govaccess.user_account_permission
    ADD CONSTRAINT user_account_permission_permissions_check
    CHECK (permissions IN (
        'DASHBOARD_READ',
        'POLICY_READ',
        'POLICY_WRITE',
        'APPLICATION_READ',
        'APPLICATION_CREATE',
        'APPLICATION_REVIEW',
        'EVIDENCE_READ',
        'USER_ADMIN'
    ));
