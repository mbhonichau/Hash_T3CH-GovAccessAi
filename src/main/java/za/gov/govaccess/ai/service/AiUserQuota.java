package za.gov.govaccess.ai.service;

import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiUserQuota {
    private final JdbcTemplate jdbc;
    private final int perMinute;
    private final int perDay;

    public AiUserQuota(JdbcTemplate jdbc, @Value("${govaccess.ai.user-requests-per-minute:60}") int perMinute,
                       @Value("${govaccess.ai.user-requests-per-day:500}") int perDay) {
        if (perMinute < 1 || perDay < 1) throw new IllegalArgumentException("AI request limits must be positive.");
        this.jdbc = jdbc;
        this.perMinute = perMinute;
        this.perDay = perDay;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserve(UUID user) {
        if (user == null) return true;
        long second = Instant.now().getEpochSecond();
        long minute = Math.floorDiv(second, 60);
        long day = Math.floorDiv(second, 86_400);
        jdbc.update("""
                INSERT INTO govaccess.ai_user_quota VALUES (?,?,?,0,0) ON CONFLICT DO NOTHING
                """, user, minute, day);
        var usage = jdbc.queryForObject("""
                SELECT minute_window,day_window,minute_count,day_count FROM govaccess.ai_user_quota WHERE user_id=? FOR UPDATE
                """, (row, index) -> new Usage(row.getLong(1) == minute ? row.getInt(3) : 0,
                row.getLong(2) == day ? row.getInt(4) : 0), user);
        if (usage.minute() >= perMinute || usage.day() >= perDay) return false;
        jdbc.update("UPDATE govaccess.ai_user_quota SET minute_window=?,day_window=?,minute_count=?,day_count=? WHERE user_id=?",
                minute, day, usage.minute() + 1, usage.day() + 1, user);
        return true;
    }

    private record Usage(int minute, int day) {}
}
