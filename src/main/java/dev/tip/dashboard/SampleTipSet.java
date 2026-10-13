package dev.tip.dashboard;

import dev.tip.ai.api.ReactionRepeats;
import dev.tip.dashboard.api.SampleTips;
import dev.tip.shared.TipProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sample tips of dev, every sandbox and the public demo, the one implementation of {@link SampleTips}. Songs and
 * bands are invented; nothing names a real song, artist or brand. The samples cover the four kinds and the four
 * visible statuses, tips without a message, requests and feedback with their owner notes, messages in French,
 * Spanish and German with translations, one song asked for in three spellings that share a key, and two hidden
 * messages, one in Bulgarian with its translation and one in English.
 *
 * <p>They are spread over three local days in the owner's zone: today's between local midnight and now, so there
 * are always several today, the others at fixed local times. Each is stored as the AI would have left it, {@code
 * demo} and without PayPal ids, with an acked delivery as old as the tip (except {@link #WITHOUT_DELIVERY}, so the
 * dashboard shows {@code repeats} null too), so nothing is sent to a device and the recovery job never takes one.
 * Today's notes and request groups are stored as the digester would have written them, so no model call is needed.
 *
 * <p>It writes {@code tip} and {@code reaction_delivery} with its own SQL, so a migration that changes their
 * columns, or the resend and replay rules, must update it.
 */
@Component
class SampleTipSet implements SampleTips {

    /** Samples without a stored delivery, as if the server restarted before the dispatcher ran. */
    static final Set<Integer> WITHOUT_DELIVERY = Set.of(3, 16);

    /** The send count of a seeded delivery: the resend job only takes deliveries sent fewer times than this. */
    static final int SEEDED_SEND_COUNT = 4;

    static final List<Sample> SAMPLES = List.of(
            // Today: the share of the time since local midnight at which each was made.
            Sample.warm("CAPTURED", 500, "Lovely music, made my morning").today(0.95),
            Sample.request("CAPTURED", 200, "Could you play Marmalade Moonwalk next?", "Request: Marmalade Moonwalk")
                    .inLanguage("en", null)
                    .withSong("Marmalade Moonwalk", "marmalade moonwalk")
                    .today(0.85),
            Sample.playful("CAPTURED", 300, "¡El panda baila mejor que yo!")
                    .inLanguage("es", "The panda dances better than me!")
                    .today(0.75),
            Sample.silent("CAPTURED", 100).today(0.65),
            Sample.request("CAPTURED", 100, "marmalade moonwalk again pls!!", "Request: Marmalade Moonwalk again")
                    .inLanguage("en", null)
                    .withSong("marmalade moonwalk!", "marmalade moonwalk")
                    .today(0.55),
            Sample.flagged(
                            "CAPTURED",
                            100,
                            "Ужасна музика, ти си пълен смешник",
                            "PLAIN",
                            "insult aimed at the performer")
                    .inLanguage("bg", "Terrible music, you are a complete clown")
                    .today(0.45),
            Sample.warm("CAPTURED", 400, "Merci, c'était magnifique, bonne journée !")
                    .inLanguage("fr", "Thank you, that was beautiful, have a good day!")
                    .today(0.35),
            Sample.pending(500, "Thanks for the tunes").today(0.25),
            Sample.request("CAPTURED", 300, "A bit too loud near the cafe tables", "Feedback: too loud near the café")
                    .inLanguage("en", null)
                    .today(0.15),
            Sample.warm("CAPTURED", 1000, "You brighten up this street every week")
                    .today(0.05),
            // Yesterday, at local times.
            Sample.request(
                            "CAPTURED",
                            500,
                            "Play The Marmalade Moonwalk by the Velvet Kettles",
                            "Request: Marmalade Moonwalk by the Velvet Kettles")
                    .inLanguage("en", null)
                    .withSong("The Marmalade Moonwalk", "marmalade moonwalk")
                    .daysAgo(1, 21, 40),
            Sample.flagged("CAPTURED", 200, "What a clown, total loser", "PLAYFUL", "name calling")
                    .inLanguage("en", null)
                    .daysAgo(1, 20, 15),
            Sample.playful("CAPTURED", 200, "My dog danced, so you earned this").daysAgo(1, 19, 5),
            Sample.request("CAPTURED", 300, "Tu peux jouer Tram Stop Lullaby ?", "Request: Tram Stop Lullaby")
                    .inLanguage("fr", "Can you play Tram Stop Lullaby?")
                    .withSong("Tram Stop Lullaby", "tram stop lullaby")
                    .daysAgo(1, 18, 20),
            Sample.warm("REFUNDED", 200, "So relaxing, thank you").daysAgo(1, 17, 45),
            Sample.plain("CAPTURED", 200, "thanks").daysAgo(1, 16, 30),
            Sample.silent("CAPTURED", 300).daysAgo(1, 15, 10),
            Sample.warm("CAPTURED", 500, "Danke, du hast meinen Tag gerettet!")
                    .inLanguage("de", "Thank you, you saved my day!")
                    .daysAgo(1, 13, 50),
            Sample.request("CAPTURED", 200, "More jazz on Sundays would be great", "Feedback: more jazz on Sundays")
                    .daysAgo(1, 12, 25),
            Sample.playful("REVERSED", 300, "Tipping the panda, not you").daysAgo(1, 11, 0),
            // The day before.
            Sample.warm("CAPTURED", 10000, "Best busker in town, buy yourself dinner")
                    .daysAgo(2, 20, 30),
            Sample.request("CAPTURED", 200, "Anything by the Copper Foxes?", "Request: something by the Copper Foxes")
                    .daysAgo(2, 19, 15),
            Sample.flagged(
                            "REFUNDED",
                            5000,
                            "Nobody wants you here, go away",
                            "REQUEST",
                            "hostile, tells the performer to leave")
                    .daysAgo(2, 18, 0),
            Sample.playful("CAPTURED", 500, "Encore! Or I keep clapping forever")
                    .daysAgo(2, 16, 40),
            Sample.request("CAPTURED", 400, "¿Puedes tocar Pebble Street Shuffle?", "Request: Pebble Street Shuffle")
                    .inLanguage("es", "Can you play Pebble Street Shuffle?")
                    .withSong("Pebble Street Shuffle", "pebble street shuffle")
                    .daysAgo(2, 15, 20),
            Sample.silent("CAPTURED", 1000).daysAgo(2, 14, 5),
            Sample.plain("CAPTURED", 100, "Keep it up").daysAgo(2, 12, 45),
            Sample.warm("CAPTURED", 700, "Made my commute so much better").daysAgo(2, 10, 30));

    /** Today's panda's notes, as the digest job would have stored them. */
    static final List<String> DIGEST_BULLETS = List.of(
            "Marmalade Moonwalk was the most asked for song, with three requests.",
            "Tippers wrote in French, Spanish and German as well as English.",
            "Lots of warm thanks, and the panda's dancing got a laugh.");

    /**
     * Today's request groups as the digester labels them, covering every shown request of the seven day window, by
     * index into {@link #SAMPLES}. The hidden request is in none, as the digester never sees it.
     */
    static final Map<String, List<Integer>> DIGEST_GROUPS = new LinkedHashMap<>();

    static {
        DIGEST_GROUPS.put("Play Marmalade Moonwalk", List.of(1, 4, 10));
        DIGEST_GROUPS.put("Play Tram Stop Lullaby", List.of(13));
        DIGEST_GROUPS.put("Play Pebble Street Shuffle", List.of(24));
        DIGEST_GROUPS.put("Play something by the Copper Foxes", List.of(21));
        DIGEST_GROUPS.put("Too loud near the café", List.of(8));
        DIGEST_GROUPS.put("More jazz on Sundays", List.of(18));
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TipProperties tipProperties;
    private final Clock clock;

    SampleTipSet(JdbcClient jdbc, JsonMapper json, TipProperties tipProperties, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.tipProperties = tipProperties;
        this.clock = clock;
    }

    @Override
    public void seed(UUID ownerId, UUID deviceId, ZoneId zone) {
        OwnerDay day = OwnerDay.of(clock, zone);
        Instant now = day.instant().truncatedTo(ChronoUnit.MICROS);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < SAMPLES.size(); i++) {
            Sample sample = SAMPLES.get(i);
            UUID id = UUID.randomUUID();
            Instant createdAt = sample.createdAt(day);
            insertTip(id, deviceId, sample, createdAt, now);
            if (!WITHOUT_DELIVERY.contains(i)) {
                insertDelivery(id, sample);
            }
            ids.add(id);
        }
        insertDigest(ownerId, day, ids, now);
    }

    @Override
    public boolean refresh(UUID ownerId, UUID deviceId, ZoneId zone) {
        OwnerDay day = OwnerDay.of(clock, zone);
        boolean seededToday = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM tip WHERE device_id = :deviceId AND demo AND created_at >= :start)""")
                .param("deviceId", deviceId)
                .param("start", DashboardRepository.utc(day.startOfToday()))
                .query(Boolean.class)
                .single();
        if (seededToday) {
            return false;
        }
        jdbc.sql("""
                        DELETE FROM reaction_delivery
                        WHERE tip_id IN (SELECT id FROM tip WHERE device_id = :deviceId AND demo)""").param("deviceId", deviceId).update();
        jdbc.sql("DELETE FROM tip WHERE device_id = :deviceId AND demo")
                .param("deviceId", deviceId)
                .update();
        jdbc.sql("DELETE FROM owner_digest WHERE owner_id = :ownerId")
                .param("ownerId", ownerId)
                .update();
        seed(ownerId, deviceId, zone);
        return true;
    }

    private void insertTip(UUID id, UUID deviceId, Sample sample, Instant createdAt, Instant now) {
        boolean captured = !sample.status().equals("PENDING");
        Instant capturedAt = createdAt.plusSeconds(20).isAfter(now) ? now : createdAt.plusSeconds(20);
        jdbc.sql("""
                        INSERT INTO tip (id, device_id, amount_minor, currency, message, status, message_kind,
                                         reaction, flagged, flag_reason, owner_note, message_language,
                                         message_english, requested_song, requested_song_key, ai_status,
                                         created_at, captured_at, reaction_at, demo)
                        VALUES (:id, :deviceId, :amountMinor, :currency, :message, :status, :kind,
                                :reaction, :flagged, :flagReason, :ownerNote, :language,
                                :english, :song, :songKey, :aiStatus,
                                :createdAt, :capturedAt, :capturedAt, true)""")
                .param("id", id)
                .param("deviceId", deviceId)
                .param("amountMinor", sample.amountMinor())
                .param("currency", tipProperties.currency())
                .param("message", sample.message())
                .param("status", sample.status())
                .param("kind", sample.kind())
                .param("reaction", captured ? sample.reaction() : null)
                .param("flagged", sample.flagged())
                .param("flagReason", sample.flagReason())
                .param("ownerNote", sample.ownerNote())
                .param("language", sample.language())
                .param("english", sample.english())
                .param("song", sample.song())
                .param("songKey", sample.songKey())
                .param("aiStatus", sample.aiStatus())
                .param("createdAt", DashboardRepository.utc(createdAt))
                .param("capturedAt", captured ? DashboardRepository.utc(capturedAt) : null)
                .update();
    }

    /**
     * The reaction the panda would have been sent, with repeats from the amount ({@link ReactionRepeats}, 1 when
     * flagged), already acked, at the resend limit and as old as the tip, hours past the 60 second replay window,
     * so nothing is ever resent or replayed. A pending sample has no reaction and gets no row.
     */
    private void insertDelivery(UUID tipId, Sample sample) {
        if (sample.status().equals("PENDING")) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO reaction_delivery (tip_id, device_id, reaction, repeats, reacted_at, sent_count,
                                                       last_sent_at, acked_at)
                        SELECT id, device_id, reaction, :repeats, reaction_at, :sentCount, reaction_at,
                               reaction_at + interval '1 second'
                        FROM tip WHERE id = :tipId""")
                .param("repeats", ReactionRepeats.of(sample.amountMinor(), tipProperties.currency(), sample.flagged()))
                .param("sentCount", SEEDED_SEND_COUNT)
                .param("tipId", tipId)
                .update();
    }

    private void insertDigest(UUID ownerId, OwnerDay day, List<UUID> ids, Instant now) {
        List<Map<String, Object>> groups = new ArrayList<>();
        DIGEST_GROUPS.forEach((label, indexes) -> groups.add(
                Map.of("label", label, "tipIds", indexes.stream().map(ids::get).toList())));
        jdbc.sql("""
                        INSERT INTO owner_digest (owner_id, local_day, bullets, request_groups, message_count,
                                                  source_watermark, generated_at)
                        VALUES (:ownerId, :day, CAST(:bullets AS jsonb), CAST(:groups AS jsonb), :messageCount,
                                :now, :now)
                        ON CONFLICT (owner_id, local_day) DO UPDATE
                        SET bullets = EXCLUDED.bullets, request_groups = EXCLUDED.request_groups,
                            message_count = EXCLUDED.message_count, source_watermark = EXCLUDED.source_watermark,
                            generated_at = EXCLUDED.generated_at, stale = false, claimed_at = NULL""")
                .param("ownerId", ownerId)
                .param("day", day.today())
                .param("bullets", json.writeValueAsString(DIGEST_BULLETS))
                .param("groups", json.writeValueAsString(groups))
                .param("messageCount", todaysShownMessages())
                .param("now", DashboardRepository.utc(now))
                .update();
    }

    /** What the digest read: today's classified messages that are not hidden. */
    static int todaysShownMessages() {
        return (int) SAMPLES.stream()
                .filter(s ->
                        s.daysAgo() == 0 && s.message() != null && s.aiStatus().equals("DONE") && !s.flagged())
                .count();
    }

    /**
     * One invented tip, stored as the AI would have left it. A captured tip without a message was skipped
     * (reaction {@code PLAIN}, no kind); a pending payment has not been read yet. {@code songKey} is written out as
     * the AI feature computes it from {@code song}. Made {@code daysAgo} local days ago: today at {@code
     * shareOfToday} of the time since local midnight, earlier days at {@code localTime}.
     */
    record Sample(
            String status,
            long amountMinor,
            String message,
            String kind,
            boolean flagged,
            String flagReason,
            String ownerNote,
            String aiStatus,
            String language,
            String english,
            String song,
            String songKey,
            int daysAgo,
            double shareOfToday,
            LocalTime localTime) {

        Sample(
                String status,
                long amountMinor,
                String message,
                String kind,
                boolean flagged,
                String flagReason,
                String ownerNote,
                String aiStatus) {
            this(
                    status,
                    amountMinor,
                    message,
                    kind,
                    flagged,
                    flagReason,
                    ownerNote,
                    aiStatus,
                    null,
                    null,
                    null,
                    null,
                    0,
                    0,
                    null);
        }

        /** The language the AI read and, for one other than English, its translation. */
        Sample inLanguage(String code, String englishText) {
            return new Sample(
                    status,
                    amountMinor,
                    message,
                    kind,
                    flagged,
                    flagReason,
                    ownerNote,
                    aiStatus,
                    code,
                    englishText,
                    song,
                    songKey,
                    daysAgo,
                    shareOfToday,
                    localTime);
        }

        Sample withSong(String title, String key) {
            return new Sample(
                    status,
                    amountMinor,
                    message,
                    kind,
                    flagged,
                    flagReason,
                    ownerNote,
                    aiStatus,
                    language,
                    english,
                    title,
                    key,
                    daysAgo,
                    shareOfToday,
                    localTime);
        }

        Sample today(double share) {
            return new Sample(
                    status,
                    amountMinor,
                    message,
                    kind,
                    flagged,
                    flagReason,
                    ownerNote,
                    aiStatus,
                    language,
                    english,
                    song,
                    songKey,
                    0,
                    share,
                    null);
        }

        Sample daysAgo(int days, int hour, int minute) {
            return new Sample(
                    status,
                    amountMinor,
                    message,
                    kind,
                    flagged,
                    flagReason,
                    ownerNote,
                    aiStatus,
                    language,
                    english,
                    song,
                    songKey,
                    days,
                    0,
                    LocalTime.of(hour, minute));
        }

        Instant createdAt(OwnerDay day) {
            if (daysAgo == 0) {
                Duration sinceMidnight = Duration.between(day.startOfToday(), day.instant());
                long micros = (long) (sinceMidnight.toNanos() / 1_000 * shareOfToday);
                return day.startOfToday().plus(micros, ChronoUnit.MICROS);
            }
            return day.today()
                    .minusDays(daysAgo)
                    .atTime(localTime)
                    .atZone(day.zone())
                    .toInstant()
                    .truncatedTo(ChronoUnit.MICROS);
        }

        static Sample warm(String status, long amountMinor, String message) {
            return new Sample(status, amountMinor, message, "WARM", false, null, null, "DONE");
        }

        static Sample playful(String status, long amountMinor, String message) {
            return new Sample(status, amountMinor, message, "PLAYFUL", false, null, null, "DONE");
        }

        static Sample plain(String status, long amountMinor, String message) {
            return new Sample(status, amountMinor, message, "PLAIN", false, null, null, "DONE");
        }

        /** A request or feedback, with the short note the AI writes for the owner. */
        static Sample request(String status, long amountMinor, String message, String ownerNote) {
            return new Sample(status, amountMinor, message, "REQUEST", false, null, ownerNote, "DONE");
        }

        static Sample flagged(String status, long amountMinor, String message, String kind, String flagReason) {
            return new Sample(status, amountMinor, message, kind, true, flagReason, null, "DONE");
        }

        static Sample silent(String status, long amountMinor) {
            return new Sample(status, amountMinor, null, null, false, null, null, "SKIPPED");
        }

        static Sample pending(long amountMinor, String message) {
            return new Sample("PENDING", amountMinor, message, null, false, null, null, "PENDING");
        }

        /** What the panda played: {@code PLAIN} when flagged or when there was nothing to read. */
        String reaction() {
            return flagged || kind == null ? "PLAIN" : kind;
        }
    }
}
