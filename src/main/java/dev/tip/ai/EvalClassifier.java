package dev.tip.ai;

import dev.tip.ai.MessageClassifier.Classification;
import dev.tip.ai.MessageClassifier.ClassificationFailed;
import java.util.Optional;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/**
 * The public seam for the {@code aiEval} tool in {@code src/tools}. It runs the production classifier, so
 * the same prompt, schema, parser and validation, with the same client settings and the same {@code
 * tip.ai.*} configuration as the app. An eval that ran a copy of the prompt would measure nothing.
 *
 * <p>Only the tool uses it. Other features reach this one through {@code ai.api}, which ArchUnit enforces.
 */
public final class EvalClassifier {

    private final MessageClassifier classifier;
    private final String model;

    private EvalClassifier(MessageClassifier classifier, String model) {
        this.classifier = classifier;
        this.model = model;
    }

    /** Binds {@code tip.ai.*} from the environment. Empty when no API key is configured. */
    public static Optional<EvalClassifier> fromEnvironment(Environment environment) {
        AiProperties properties = Binder.get(environment)
                .bind("tip.ai", AiProperties.class)
                .orElseThrow(() -> new IllegalStateException("tip.ai is not configured"));
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new EvalClassifier(
                new ClaudeMessageClassifier(
                        AiConfig.buildClient(properties),
                        properties.model(),
                        JsonMapper.builder().build()),
                properties.model()));
    }

    public String model() {
        return model;
    }

    /** One Claude call. Output the parser rejects, or a failed call, comes back as an invalid verdict. */
    public Verdict classify(String message) {
        try {
            Classification classification = classifier.classify(message);
            // The stored outcome decides what is kept; the amount only sets repeats, unused here. A flagged
            // message stays hidden until the owner overrules it, so by default they see none of its read.
            TipReactions.Outcome outcome = TipReactions.Outcome.classified(classification, 0, "GBP");
            boolean hidden = classification.flagged();
            return new Verdict(
                    classification.kind().name(),
                    hidden,
                    hidden ? null : outcome.ownerNote(),
                    hidden ? null : outcome.language(),
                    hidden ? null : outcome.englishText(),
                    hidden ? null : outcome.song(),
                    null);
        } catch (ClassificationFailed e) {
            return new Verdict(null, false, null, null, null, null, e.getMessage());
        }
    }

    /**
     * What the owner sees by default. {@code ownerNote}, {@code language}, {@code englishText} and {@code
     * song} are null for a flagged message, which stays hidden until the owner overrules the flag. {@code failure} is set, and the rest empty,
     * when the model's output was invalid or the call failed.
     */
    public record Verdict(
            String kind,
            boolean flagged,
            String ownerNote,
            String language,
            String englishText,
            String song,
            String failure) {

        /** A verdict without language, translation or song. */
        public Verdict(String kind, boolean flagged, String ownerNote, String failure) {
            this(kind, flagged, ownerNote, null, null, null, failure);
        }

        public boolean valid() {
            return failure == null;
        }
    }
}
