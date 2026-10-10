package dev.tip.tools;

import dev.tip.ai.EvalClassifier;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.ResourcePropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs labelled tip messages through the real Claude model with the production classifier and prints
 * how often it agrees with the labels, so prompt quality is measured instead of guessed. Manual only:
 * never part of {@code build}, {@code check} or CI, because it calls the paid API.
 *
 * <pre>./gradlew aiEval</pre>
 *
 * <p>Reads {@code ANTHROPIC_API_KEY}, {@code TIP_AI_MODEL} and {@code ANTHROPIC_BASE_URL} through the
 * app's own {@code application.properties}, from the environment or {@code .env}.
 */
public final class AiEval {

    static final String CASES = "ai-eval-cases.jsonl";
    static final int EXIT_NO_KEY = 2;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AiEval() {}

    public static void main(String[] args) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new ResourcePropertySource("classpath:application.properties"));
        Optional<EvalClassifier> classifier = EvalClassifier.fromEnvironment(environment);
        if (classifier.isEmpty()) {
            System.err.println("ANTHROPIC_API_KEY is not set. Put it in .env or the environment and run"
                    + " ./gradlew aiEval again. Nothing was sent.");
            System.exit(EXIT_NO_KEY);
            return;
        }
        List<Case> cases = loadCases();
        System.out.printf(
                "Running %d cases against %s, one at a time%n",
                cases.size(), classifier.get().model());
        run(cases, classifier.get()::classify, System.out);
    }

    /**
     * One labelled message: the kinds accepted as correct (more than one for a message that is genuinely
     * ambiguous), the expected flag and whether the owner should see a note.
     */
    record Case(String message, List<String> kinds, boolean flagged, Note note) {

        boolean accepts(String kind) {
            return kinds.contains(kind);
        }
    }

    /** {@code ownerNote} in the cases file: {@code true}, {@code false} or {@code "ifRequest"}. */
    enum Note {
        YES,
        NO,
        IF_REQUEST;

        boolean expectedFor(String kind) {
            return this == YES || (this == IF_REQUEST && "REQUEST".equals(kind));
        }

        static Note parse(JsonNode node) {
            if (node.isBoolean()) {
                return node.booleanValue() ? YES : NO;
            }
            if ("ifRequest".equals(node.asString())) {
                return IF_REQUEST;
            }
            throw new IllegalArgumentException("ownerNote must be true, false or \"ifRequest\"");
        }
    }

    record Score(int total, int kindRight, int flaggedRight, int noteRight, int allRight) {}

    static List<Case> loadCases() {
        try (InputStream in = AiEval.class.getClassLoader().getResourceAsStream(CASES)) {
            if (in == null) {
                throw new IllegalStateException(CASES + " is missing from the tools resources");
            }
            List<Case> cases = new ArrayList<>();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = JSON.readTree(line);
                JsonNode kind = node.required("kind");
                cases.add(new Case(
                        node.required("message").asString(),
                        kind.isArray()
                                ? kind.valueStream().map(JsonNode::asString).toList()
                                : List.of(kind.asString()),
                        node.required("flagged").asBoolean(),
                        Note.parse(node.required("ownerNote"))));
            }
            return cases;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs the cases in order and prints one line per disagreement, then the accuracy of each part. */
    static Score run(List<Case> cases, Function<String, EvalClassifier.Verdict> classify, PrintStream out) {
        int kindRight = 0;
        int flaggedRight = 0;
        int noteRight = 0;
        int allRight = 0;
        for (Case c : cases) {
            EvalClassifier.Verdict verdict = classify.apply(c.message());
            boolean kind = verdict.valid() && c.accepts(verdict.kind());
            boolean flagged = verdict.valid() && c.flagged() == verdict.flagged();
            boolean note = verdict.valid() && c.note().expectedFor(verdict.kind()) == (verdict.ownerNote() != null);
            kindRight += kind ? 1 : 0;
            flaggedRight += flagged ? 1 : 0;
            noteRight += note ? 1 : 0;
            if (kind && flagged && note) {
                allRight++;
                continue;
            }
            out.printf(
                    "MISS  %-50s  expected %s  got %s%n",
                    quoted(c.message()),
                    expected(c),
                    verdict.valid()
                            ? describe(verdict.kind(), verdict.flagged(), verdict.ownerNote() != null)
                                    + (verdict.ownerNote() == null ? "" : " " + quoted(verdict.ownerNote()))
                            : "INVALID (" + verdict.failure() + ")");
        }
        Score score = new Score(cases.size(), kindRight, flaggedRight, noteRight, allRight);
        out.println(line("kind", score.kindRight(), score.total()));
        out.println(line("flagged", score.flaggedRight(), score.total()));
        out.println(line("owner note", score.noteRight(), score.total()));
        out.println(line("all three", score.allRight(), score.total()));
        return score;
    }

    private static String expected(Case c) {
        String note = switch (c.note()) {
            case YES -> " +note";
            case NO -> "";
            case IF_REQUEST -> " +note if REQUEST";
        };
        return String.join("|", c.kinds()) + (c.flagged() ? " flagged" : "") + note;
    }

    private static String describe(String kind, boolean flagged, boolean ownerNote) {
        return kind + (flagged ? " flagged" : "") + (ownerNote ? " +note" : "");
    }

    private static String line(String part, int right, int total) {
        return String.format(
                Locale.ROOT, "%-11s %2d/%d  %5.1f %%", part, right, total, total == 0 ? 0.0 : 100.0 * right / total);
    }

    private static String quoted(String text) {
        return "\"" + text + "\"";
    }
}
