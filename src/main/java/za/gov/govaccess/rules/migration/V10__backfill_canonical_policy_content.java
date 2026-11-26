package za.gov.govaccess.rules.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import za.gov.govaccess.rules.model.FinancialScreenPolicy;
import za.gov.govaccess.rules.service.FinancialRuleConverter;

public class V10__backfill_canonical_policy_content extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        var mapper = new ObjectMapper();
        try (var seed = connection.prepareStatement("""
                INSERT INTO policy_version (id, programme_id, version_label, status, origin, rule_json)
                SELECT ?, p.id, '2025 financial screening', 'DRAFT', 'MANUAL', ?
                FROM programme p WHERE p.code = 'NSFAS_BURSARY'
                AND NOT EXISTS (SELECT 1 FROM policy_version v
                    WHERE v.programme_id = p.id AND v.version_label = '2025 financial screening')
                """)) {
            seed.setObject(1, UUID.fromString("3c72c8f9-080f-49c4-9a96-36092d0d9187"));
            try (var input = getClass().getResourceAsStream("/demo/nsfas-2025-financial-screen.json")) {
                if (input == null) {
                    throw new IOException("The bundled NSFAS policy draft is unavailable");
                }
                seed.setString(2, new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            seed.executeUpdate();
        }
        var updated = new ArrayList<Backfill>();
        try (var statement = connection.prepareStatement("SELECT id, rule_json FROM policy_version WHERE content IS NULL");
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                var id = rows.getObject("id", UUID.class);
                try {
                    var legacy = mapper.readValue(rows.getString("rule_json"), FinancialScreenPolicy.class);
                    updated.add(new Backfill(id, mapper.writeValueAsString(FinancialRuleConverter.convert(legacy))));
                } catch (IOException | IllegalArgumentException | NullPointerException e) {
                    throw new IllegalStateException("Cannot convert legacy policy version " + id, e);
                }
            }
        }
        try (var statement = connection.prepareStatement("UPDATE policy_version SET content = ? WHERE id = ?")) {
            for (var row : updated) {
                statement.setString(1, row.content());
                statement.setObject(2, row.id());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE policy_version ALTER COLUMN content SET NOT NULL");
        }
    }

    private record Backfill(UUID id, String content) {}
}
