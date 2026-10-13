package dev.tip.ai;

import dev.tip.ai.api.AiUsage;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code ai_usage} table: model calls and failed calls per UTC hour, one upsert per call. */
@Repository
class AiUsageRepository implements AiUsage {

    private final JdbcClient jdbc;

    AiUsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void record(Instant at, boolean failed) {
        jdbc.sql("""
                        INSERT INTO ai_usage (hour, calls, failures) VALUES (:hour, 1, :failure)
                        ON CONFLICT (hour) DO UPDATE SET calls = ai_usage.calls + 1,
                                                         failures = ai_usage.failures + :failure""")
                .param("hour", at.truncatedTo(ChronoUnit.HOURS).atOffset(ZoneOffset.UTC))
                .param("failure", failed ? 1 : 0)
                .update();
    }

    @Override
    public Totals since(Instant from) {
        return jdbc.sql("""
                        SELECT coalesce(sum(calls), 0) AS calls, coalesce(sum(failures), 0) AS failures
                        FROM ai_usage WHERE hour >= :from""")
                .param("from", from.truncatedTo(ChronoUnit.HOURS).atOffset(ZoneOffset.UTC))
                .query((rs, row) -> new Totals(rs.getLong("calls"), rs.getLong("failures")))
                .single();
    }
}
