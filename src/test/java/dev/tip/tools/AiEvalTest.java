package dev.tip.tools;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tip.ai.EvalClassifier;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AiEvalTest {

    @Test
    void casesCoverEveryKindTheBoundaryRowsAndFridaysTips() {
        List<AiEval.Case> cases = AiEval.loadCases();

        assertThat(cases).hasSizeBetween(35, 50);
        Map<String, Long> byKind = cases.stream()
                .flatMap(c -> c.kinds().stream())
                .collect(Collectors.groupingBy(kind -> kind, Collectors.counting()));
        assertThat(byKind).containsOnlyKeys("WARM", "PLAYFUL", "REQUEST", "PLAIN");
        assertThat(byKind.values()).allMatch(count -> count >= 3);
        assertThat(cases)
                .contains(
                        only("You are disgusting just awful experience", "PLAIN", true, AiEval.Note.NO),
                        only("This was so shit I cant stand this", "PLAIN", false, AiEval.Note.NO),
                        only("Awful experience honestly", "PLAIN", false, AiEval.Note.NO),
                        only("A bit too loud by the cafe", "REQUEST", false, AiEval.Note.YES),
                        only("Play Oasis or I will find you", "REQUEST", true, AiEval.Note.NO),
                        only("You made my day", "WARM", false, AiEval.Note.NO),
                        only("Bet you can't play Wonderfall", "PLAYFUL", false, AiEval.Note.NO),
                        only("Play some Oasis", "REQUEST", false, AiEval.Note.YES),
                        only("Thanks", "PLAIN", false, AiEval.Note.NO));
    }

    @Test
    void ambiguousMessagesAcceptSeveralKinds() {
        assertThat(AiEval.loadCases())
                .contains(
                        new AiEval.Case(
                                "My dog danced harder than I did", List.of("PLAYFUL", "WARM"), false, AiEval.Note.NO),
                        new AiEval.Case(
                                "Honestly the worst cover of Wonderwall I have ever heard",
                                List.of("PLAIN", "PLAYFUL"),
                                false,
                                AiEval.Note.NO),
                        new AiEval.Case(
                                "Oh great, another busker. Just what this street needed.",
                                List.of("PLAIN", "PLAYFUL"),
                                false,
                                AiEval.Note.NO),
                        new AiEval.Case(
                                "The card reader was really slow today",
                                List.of("PLAIN", "REQUEST"),
                                false,
                                AiEval.Note.IF_REQUEST));
    }

    @Test
    void notesAreExpectedOnlyOnUnflaggedRequests() {
        List<AiEval.Case> cases = AiEval.loadCases();

        assertThat(cases)
                .filteredOn(c -> c.note() == AiEval.Note.YES)
                .hasSizeGreaterThanOrEqualTo(13)
                .allMatch(c -> c.kinds().equals(List.of("REQUEST")) && !c.flagged());
        assertThat(cases)
                .filteredOn(c -> c.note() == AiEval.Note.IF_REQUEST)
                .allMatch(c -> c.kinds().contains("REQUEST") && c.kinds().size() > 1);
    }

    @Test
    void anyAcceptedKindCountsAndTheNoteIsOnlyExpectedWhenTheResultIsARequest() {
        AiEval.Case ambiguous =
                new AiEval.Case("slow reader", List.of("PLAIN", "REQUEST"), false, AiEval.Note.IF_REQUEST);
        List<EvalClassifier.Verdict> answers = new ArrayList<>(List.of(
                new EvalClassifier.Verdict("PLAIN", false, null, null),
                new EvalClassifier.Verdict("REQUEST", false, "Feedback: slow card reader", null),
                new EvalClassifier.Verdict("REQUEST", false, null, null),
                new EvalClassifier.Verdict("WARM", false, null, null)));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        AiEval.Score score = AiEval.run(
                List.of(ambiguous, ambiguous, ambiguous, ambiguous),
                message -> answers.removeFirst(),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));

        assertThat(score).isEqualTo(new AiEval.Score(4, 3, 4, 3, 2));
        assertThat(bytes.toString(StandardCharsets.UTF_8))
                .contains("expected PLAIN|REQUEST +note if REQUEST  got REQUEST")
                .contains("expected PLAIN|REQUEST +note if REQUEST  got WARM");
    }

    @Test
    void scoresKindFlagAndNoteSeparatelyAndCountsInvalidOutputAsAMiss() {
        List<AiEval.Case> cases = List.of(
                only("right", "WARM", false, AiEval.Note.NO),
                only("wrong kind", "PLAYFUL", false, AiEval.Note.NO),
                only("missed flag", "PLAIN", true, AiEval.Note.NO),
                only("missing note", "REQUEST", false, AiEval.Note.YES),
                only("broken", "PLAIN", false, AiEval.Note.NO));
        Map<String, EvalClassifier.Verdict> answers = Map.of(
                "right", new EvalClassifier.Verdict("WARM", false, null, null),
                "wrong kind", new EvalClassifier.Verdict("PLAIN", false, null, null),
                "missed flag", new EvalClassifier.Verdict("PLAIN", false, null, null),
                "missing note", new EvalClassifier.Verdict("REQUEST", false, null, null),
                "broken", new EvalClassifier.Verdict(null, false, null, "invalid response: not JSON"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        AiEval.Score score = AiEval.run(cases, answers::get, new PrintStream(bytes, true, StandardCharsets.UTF_8));

        assertThat(score).isEqualTo(new AiEval.Score(5, 3, 3, 3, 1));
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertThat(output.lines().filter(line -> line.startsWith("MISS"))).hasSize(4);
        assertThat(output)
                .contains("\"wrong kind\"", "expected PLAYFUL  got PLAIN")
                .contains("expected PLAIN flagged  got PLAIN")
                .contains("expected REQUEST +note  got REQUEST")
                .contains("INVALID (invalid response: not JSON)")
                .contains("kind         3/5   60.0 %", "all three    1/5   20.0 %")
                .doesNotContain("\"right\"");
    }

    private static AiEval.Case only(String message, String kind, boolean flagged, AiEval.Note note) {
        return new AiEval.Case(message, List.of(kind), flagged, note);
    }
}
