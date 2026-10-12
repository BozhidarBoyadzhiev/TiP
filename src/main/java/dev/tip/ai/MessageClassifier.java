package dev.tip.ai;

/** Reads a tip message and decides its kind, whether it needs moderation, and a note for the owner. */
interface MessageClassifier {

    /**
     * @throws ClassificationFailed on a timeout, an API error or a response outside the schema
     */
    Classification classify(String message);

    /**
     * {@code flagReason} is at most 200 characters and only set when flagged. {@code ownerNote} is at
     * most 100 characters, has no control characters and is only set for {@link MessageKind#REQUEST}.
     * {@code language} is a known ISO 639-1 code or null. {@code englishText} (at most 280 characters) is
     * only set for an unflagged message in another language than English, {@code song} (at most 80) only
     * for an unflagged request that names one. Every text is sanitised like a tipper's message.
     */
    record Classification(
            MessageKind kind,
            boolean flagged,
            String flagReason,
            String ownerNote,
            String language,
            String englishText,
            String song) {

        Classification(MessageKind kind, boolean flagged, String flagReason, String ownerNote) {
            this(kind, flagged, flagReason, ownerNote, null, null, null);
        }
    }

    /** {@code getMessage()} never contains the tip message or model output, so it is safe to log. */
    final class ClassificationFailed extends RuntimeException {
        ClassificationFailed(String reason) {
            super(reason);
        }
    }
}
