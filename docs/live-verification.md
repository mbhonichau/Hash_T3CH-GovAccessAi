# Live Groq verification on Supabase

1. Use a disposable or approved Supabase workspace. Set `DB_PASSWORD` and the JDBC connection settings. Run `.\scripts\Start-GovAccessWithGroq.ps1` from PowerShell and enter a current Groq key. Confirm startup reaches Flyway V14 and Hibernate validation succeeds.
2. To test admin controls, launch the built-in account with `SPRING_SECURITY_USER_ROLES=POLICY_EDIT,AI_ADMIN`. Sign in, then use a session-authenticated **POST** to `/api/admin/ai/probe` with a CSRF token. Expect `{"ok":true}` from one small completion. The probe bypasses replay caching and does not retry. The Actuator indicator reports configuration state.
3. In **Workspace access**, switch to **Policy Edit**. Open **Policy Studio**, enter a unique draft label, and click **Import with AI** once. Expect an **AI import complete** notice and a draft marked **AI-generated**. Verify the policy citations and that the published count stays unchanged.
4. Check the request and attempt metadata in the Supabase SQL Editor. Filter by your unique draft label and confirm one request row, a non-null user ID, a related policy version ID, prompt `policy-interpretation`, the expected model, a successful outcome, and an attempt count matching the associated child rows.

```sql
SELECT r.id, r.user_id, r.related_entity_id, r.prompt_id, r.prompt_version,
       r.provider, r.model, r.outcome, r.attempt_count, COUNT(a.id) AS recorded_attempts,
       r.total_latency_ms
FROM govaccess.ai_request_log r
JOIN govaccess.policy_version p ON p.id = r.related_entity_id
LEFT JOIN govaccess.ai_call_log a ON a.request_id = r.id
WHERE p.version_label = 'replace with your unique draft label'
GROUP BY r.id;
```

5. As an AI administrator, POST to `/api/admin/ai/enabled?enabled=false` with a CSRF token. Retry the import with a different label and confirm it fails immediately, audits a `KILL_SWITCH` request with zero attempts, and creates no draft. POST `enabled=true` to restore the runtime setting.

For browser calls, the signed-in page includes a hidden CSRF token in the sign-out form. For example, run this in its developer console to probe the configured provider:

```javascript
const csrf = document.querySelector('form[action$="/logout"] input[name="_csrf"]').value;
const response = await fetch('/api/admin/ai/probe', {
    method: 'POST',
    headers: {'X-CSRF-TOKEN': csrf}
});
console.log(response.status, await response.json());
```
