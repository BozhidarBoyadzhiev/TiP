package dev.tip.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Whether the owner sees a message is decided in one place, {@link HiddenMessages}. Every owner facing query
 * lives in the dashboard feature, so no other dashboard source may read the AI's flag or the owner's verdict
 * in its SQL: a query that did would show or hide a message by the raw flag and ignore the owner's verdict.
 */
class HiddenMessagesRuleTest {

    private static final Path DASHBOARD = Path.of("src/main/java/dev/tip/dashboard");

    /**
     * The flag column read in SQL: qualified by a table alias, or after an uppercase keyword that tests it,
     * the way this codebase writes SQL, so prose such as "when flagged" in a comment does not count.
     */
    private static final Pattern RAW_FLAG =
            Pattern.compile("\\b[a-z_]+\\.flagged\\b(?!\\s*\\()|\\b(?:WHERE|AND|OR|NOT|WHEN|ON)\\s+flagged\\b");

    private static final Pattern VERDICT = Pattern.compile("\\bowner_verdict\\b");

    /** The definition itself, and the one writer of the verdict, which acts only through the definition. */
    private static final Map<Pattern, List<String>> ALLOWED = Map.of(
            RAW_FLAG, List.of("HiddenMessages.java"),
            VERDICT, List.of("HiddenMessages.java", "ModerationRepository.java"));

    @Test
    void theRuleCatchesHowOwnerQueriesReadTheFlagBefore() {
        assertThat(List.of(
                        "CASE WHEN t.flagged THEN NULL ELSE t.message END",
                        " AND NOT t.flagged AND t.message_kind = 'REQUEST'",
                        " AND t.flagged = :flagged",
                        "SELECT message FROM tip WHERE flagged"))
                .allMatch(sql -> RAW_FLAG.matcher(sql).find());
        assertThat(List.of(
                        "query.flagged()",
                        "INSERT INTO tip (message_kind, reaction, flagged, flag_reason)",
                        "rs.getBoolean(\"hidden\")"))
                .noneMatch(code -> RAW_FLAG.matcher(code).find());
    }

    @Test
    void onlyHiddenMessagesReadsTheFlagAndTheVerdict() throws IOException {
        assertThat(DASHBOARD).isDirectory();
        try (Stream<Path> files = Files.list(DASHBOARD)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String name = file.getFileName().toString();
                ALLOWED.forEach((pattern, allowed) -> {
                    if (!allowed.contains(name)) {
                        assertThat(pattern.matcher(source).find())
                                .as("%s reads %s outside HiddenMessages", name, pattern)
                                .isFalse();
                    }
                });
            }
        }
    }
}
