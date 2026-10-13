package dev.tip.owners;

import dev.tip.ai.api.AiUsage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * At 00:10 London time, one INFO {@code daily_summary} line from the database for the last 24 hours: sandboxes made
 * per code label, paid tips by owner kind (sample tips aside) and model calls with their failures. Counts only, no
 * personal data. It reads {@code tip} and {@code device} with its own SQL, never writes them.
 */
@Component
class DailySummaryJob {

    static final Duration WINDOW = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(DailySummaryJob.class);

    private final JdbcClient jdbc;
    private final AiUsage aiUsage;
    private final Clock clock;

    DailySummaryJob(JdbcClient jdbc, AiUsage aiUsage, Clock clock) {
        this.jdbc = jdbc;
        this.aiUsage = aiUsage;
        this.clock = clock;
    }

    @Scheduled(cron = "${tip.owners.daily-summary-cron}", zone = "Europe/London")
    void logSummary() {
        Instant since = clock.instant().minus(WINDOW);
        Map<String, Long> sandboxes = counts(
                "SELECT code_label AS name, count(*) AS n FROM owner"
                        + " WHERE kind = 'SANDBOX' AND created_at > :since GROUP BY code_label",
                since);
        Map<String, Long> tipsPaid = counts("""
                        SELECT o.kind AS name, count(*) AS n
                        FROM tip t JOIN device d ON d.id = t.device_id JOIN owner o ON o.id = d.owner_id
                        WHERE t.captured_at > :since AND NOT t.demo
                        GROUP BY o.kind""", since);
        AiUsage.Totals ai = aiUsage.since(since);
        log.info(
                "daily_summary sandboxes={} tipsPaid={} aiCalls={} aiFailures={}",
                format(sandboxes),
                format(tipsPaid),
                ai.calls(),
                ai.failures());
    }

    private Map<String, Long> counts(String sql, Instant since) {
        Map<String, Long> counts = new TreeMap<>();
        jdbc.sql(sql)
                .param("since", since.atOffset(ZoneOffset.UTC))
                .query((rs, row) -> counts.put(String.valueOf(rs.getString("name")), rs.getLong("n")))
                .list();
        return counts;
    }

    private static String format(Map<String, Long> counts) {
        return counts.isEmpty()
                ? "none"
                : counts.entrySet().stream()
                        .map(e -> e.getKey() + ":" + e.getValue())
                        .collect(Collectors.joining(","));
    }
}
