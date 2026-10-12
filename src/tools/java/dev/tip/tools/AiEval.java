package dev.tip.tools;

import dev.tip.ai.EvalClassifier;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
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
     * ambiguous), the expected flag and whether the owner should see a note. The extras are scored only on
     * cases that label them: {@code languages} exactly, any one of them (a list for a message that genuinely
     * mixes two), {@code translation} only on whether an English text
     * is there, and {@code song} as a case insensitive match, where an expected null means no song.
     */
    record Case(
            String message,
            List<String> kinds,
            boolean flagged,
            Note note,
            List<String> languages,
            Boolean translation,
            boolean songLabelled,
            String song) {

        Case(String message, List<String> kinds, boolean flagged, Note note) {
            this(message, kinds, flagged, note, null, null, false, null);
        }

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

    record Score(
            int total,
            int kindRight,
            int flaggedRight,
            int noteRight,
            int allRight,
            Part language,
            Part translation,
            Part song) {

        Score(int total, int kindRight, int flaggedRight, int noteRight, int allRight) {
            this(total, kindRight, flaggedRight, noteRight, allRight, Part.NONE, Part.NONE, Part.NONE);
        }
    }

    /** The score of an extra over the cases that label it. */
    record Part(int right, int total) {

        static final Part NONE = new Part(0, 0);

        Part add(boolean correct) {
            return new Part(right + (correct ? 1 : 0), total + 1);
        }
    }

    static List<Case> loadCases() {
        try (InputStream in = AiEval.class.getClassLoader().getResourceAsStream(CASES)) {
            if (in == null) {
                throw new IllegalStateException(CASES + " is missing from the tools resources");
            }
            return readCases(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Decodes each line as strict UTF-8, so a byte that is not UTF-8 fails with its line number instead of
     * silently becoming U+FFFD and evaluating a corrupted message.
     */
    static List<Case> readCases(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        List<Case> cases = new ArrayList<>();
        int lineNumber = 0;
        for (int start = 0; start < bytes.length; ) {
            int end = start;
            while (end < bytes.length && bytes[end] != '\n') {
                end++;
            }
            lineNumber++;
            String line;
            try {
                line = decoder.decode(ByteBuffer.wrap(bytes, start, end - start))
                        .toString();
            } catch (CharacterCodingException e) {
                throw new IllegalArgumentException(CASES + " line " + lineNumber + " is not valid UTF-8", e);
            }
            start = end + 1;
            if (!line.isBlank()) {
                cases.add(parseCase(line, lineNumber));
            }
        }
        return cases;
    }

    private static Case parseCase(String line, int lineNumber) {
        try {
            JsonNode node = JSON.readTree(line);
            JsonNode kind = node.required("kind");
            JsonNode song = node.get("song");
            return new Case(
                    node.required("message").asString(),
                    texts(kind),
                    node.required("flagged").asBoolean(),
                    Note.parse(node.required("ownerNote")),
                    node.has("language") ? texts(node.get("language")) : null,
                    node.has("translation") ? node.get("translation").asBoolean() : null,
                    song != null,
                    song == null || song.isNull() ? null : song.asString());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(CASES + " line " + lineNumber + ": " + e.getMessage(), e);
        }
    }

    /** A string, or an array of strings, as a list. */
    private static List<String> texts(JsonNode node) {
        return node.isArray() ? node.valueStream().map(JsonNode::asString).toList() : List.of(node.asString());
    }

    /** Runs the cases in order and prints one line per disagreement, then the accuracy of each part. */
    static Score run(List<Case> cases, Function<String, EvalClassifier.Verdict> classify, PrintStream out) {
        int kindRight = 0;
        int flaggedRight = 0;
        int noteRight = 0;
        int allRight = 0;
        Part language = Part.NONE;
        Part translation = Part.NONE;
        Part song = Part.NONE;
        for (Case c : cases) {
            EvalClassifier.Verdict verdict = classify.apply(c.message());
            boolean kind = verdict.valid() && c.accepts(verdict.kind());
            boolean flagged = verdict.valid() && c.flagged() == verdict.flagged();
            boolean note = verdict.valid() && c.note().expectedFor(verdict.kind()) == (verdict.ownerNote() != null);
            kindRight += kind ? 1 : 0;
            flaggedRight += flagged ? 1 : 0;
            noteRight += note ? 1 : 0;
            if (c.languages() != null) {
                boolean right = verdict.valid() && c.languages().contains(verdict.language());
                language = language.add(right);
                missExtra(out, right, c, "language", String.join("|", c.languages()), verdict.language());
            }
            if (c.translation() != null) {
                boolean right = verdict.valid() && c.translation() == (verdict.englishText() != null);
                translation = translation.add(right);
                missExtra(
                        out,
                        right,
                        c,
                        "translation",
                        c.translation() ? "present" : "absent",
                        verdict.englishText() == null ? "absent" : quoted(verdict.englishText()));
            }
            if (c.songLabelled()) {
                boolean right = verdict.valid()
                        && (c.song() == null
                                ? verdict.song() == null
                                : verdict.song() != null && c.song().equalsIgnoreCase(verdict.song()));
                song = song.add(right);
                missExtra(out, right, c, "song", c.song(), verdict.song());
            }
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
        Score score =
                new Score(cases.size(), kindRight, flaggedRight, noteRight, allRight, language, translation, song);
        out.println(line("kind", score.kindRight(), score.total()));
        out.println(line("flagged", score.flaggedRight(), score.total()));
        out.println(line("owner note", score.noteRight(), score.total()));
        out.println(line("all three", score.allRight(), score.total()));
        out.println(line("language", language.right(), language.total()));
        out.println(line("translation", translation.right(), translation.total()));
        out.println(line("song", song.right(), song.total()));
        return score;
    }

    private static void missExtra(PrintStream out, boolean right, Case c, String part, String expected, String got) {
        if (!right) {
            out.printf("MISS  %-50s  %s expected %s  got %s%n", quoted(c.message()), part, expected, got);
        }
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
