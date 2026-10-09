package dev.tip.tools;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tip.ai.EvalClassifier;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AiEvalTest {

    @Test
    void casesCoverEveryKindTheBoundaryRowsAndFridaysTips() {
        List<AiEval.Case> cases = AiEval.loadCases();

        assertThat(cases).hasSizeBetween(28, 40);
        Map<String, Long> byKind =
                cases.stream().collect(Collectors.groupingBy(AiEval.Case::kind, Collectors.counting()));
        assertThat(byKind).containsOnlyKeys("WARM", "PLAYFUL", "REQUEST", "PLAIN");
        assertThat(byKind.values()).allMatch(count -> count >= 3);
        assertThat(cases)
                .contains(
                        new AiEval.Case("You are disgusting just awful experience", "PLAIN", true, false),
                        new AiEval.Case("This was so shit I cant stand this", "PLAIN", false, false),
                        new AiEval.Case("Awful experience honestly", "PLAIN", false, false),
                        new AiEval.Case("A bit too loud by the cafe", "REQUEST", false, true),
                        new AiEval.Case("Play Oasis or I will find you", "REQUEST", true, false),
                        new AiEval.Case("You made my day", "WARM", false, false),
                        new AiEval.Case("Bet you can't play Wonderfall", "PLAYFUL", false, false),
                        new AiEval.Case("Play some Oasis", "REQUEST", false, true),
                        new AiEval.Case("Thanks", "PLAIN", false, false));
        assertThat(cases)
                .filteredOn(AiEval.Case::ownerNote)
                .allMatch(c -> c.kind().equals("REQUEST") && !c.flagged());
    }

    @Test
    void scoresKindFlagAndNoteSeparatelyAndCountsInvalidOutputAsAMiss() {
        List<AiEval.Case> cases = List.of(
                new AiEval.Case("right", "WARM", false, false),
                new AiEval.Case("wrong kind", "PLAYFUL", false, false),
                new AiEval.Case("missed flag", "PLAIN", true, false),
                new AiEval.Case("missing note", "REQUEST", false, true),
                new AiEval.Case("broken", "PLAIN", false, false));
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
}
