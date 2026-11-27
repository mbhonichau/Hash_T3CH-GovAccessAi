package za.gov.govaccess.ai.controller;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import za.gov.govaccess.ai.enums.AiOutcome;
import za.gov.govaccess.security.ActorIdentity;

@Controller
class AiGovernanceController {
    private final JdbcTemplate jdbc;

    AiGovernanceController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Request(UUID user, String entityType, UUID entity, String prompt, int version, String provider,
                   String model, String outcome, int attempts, long latency, OffsetDateTime time) {}
    record Counts(long invalid, long providerFallbacks, long templateFallbacks, long rateLimited) {}

    @GetMapping("/audit/ai")
    String governance(Authentication actor, @RequestParam(defaultValue = "") String prompt,
                      @RequestParam(defaultValue = "") String provider,
                      @RequestParam(required = false) AiOutcome outcome, @RequestParam(required = false) UUID user, Model model) {
        ActorIdentity.require(actor, "AUDIT_VERIFY");
        String promptFilter = prompt.strip();
        String providerFilter = provider.strip();
        String outcomeFilter = outcome == null ? "" : outcome.name();
        model.addAttribute("requests", jdbc.query("""
                SELECT user_id,related_entity_type,related_entity_id,prompt_id,prompt_version,provider,model,outcome,
                       attempt_count,total_latency_ms,created_at
                FROM govaccess.ai_request_log
                WHERE (?='' OR LOWER(prompt_id)=LOWER(?)) AND (?='' OR LOWER(provider)=LOWER(?))
                  AND (?='' OR outcome=?) AND (? OR user_id=?)
                ORDER BY created_at DESC,id LIMIT 100
                """, (row, index) -> new Request(row.getObject(1, UUID.class), row.getString(2), row.getObject(3, UUID.class),
                row.getString(4), row.getInt(5), row.getString(6), row.getString(7), row.getString(8), row.getInt(9),
                row.getLong(10), row.getObject(11, OffsetDateTime.class)), promptFilter, promptFilter, providerFilter,
                providerFilter, outcomeFilter, outcomeFilter, user == null, user));
        model.addAttribute("counts", jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM govaccess.ai_call_log WHERE outcome='INVALID_OUTPUT'),
                       (SELECT COUNT(*) FROM govaccess.ai_request_log WHERE provider_fallback=true),
                       (SELECT COUNT(*) FROM govaccess.audit_event e JOIN govaccess.audit_protected_snapshot s
                           ON s.id=e.new_snapshot_id WHERE e.action IN ('ASSESSMENT_EXPLANATION_GENERATED',
                           'CASE_COMMUNICATION_DRAFTED','NOTICE_TRANSLATION_FALLBACK') AND s.payload LIKE '%"generatedBy":"TEMPLATE"%'),
                       (SELECT COUNT(*) FROM govaccess.ai_request_log WHERE outcome='RATE_LIMITED')
                """, (row, index) -> new Counts(row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4))));
        model.addAttribute("promptFilter", promptFilter);
        model.addAttribute("providerFilter", providerFilter);
        model.addAttribute("outcomeFilter", outcome);
        model.addAttribute("userFilter", user);
        model.addAttribute("outcomes", AiOutcome.values());
        return "audit/ai";
    }
}
