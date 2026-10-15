package dev.tip.shared;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.dao.DataAccessException;

/**
 * Values that may go into a log line without being personal data. A client IP is logged only as an HMAC under a
 * random key made when the process starts and never stored, so lines of one process can be matched to each other
 * but not to an address, and a restart unlinks them.
 */
public final class LogSafe {

    private static final String ALGORITHM = "HmacSHA256";
    private static final SecretKeySpec KEY = new SecretKeySpec(randomKey(), ALGORITHM);

    private LogSafe() {}

    /** 16 hex characters of the HMAC of {@code clientIp}, or {@code -} without one. */
    public static String ip(String clientIp) {
        if (clientIp == null) {
            return "-";
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(KEY);
            byte[] digest = mac.doFinal(clientIp.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /**
     * True when {@code error} or one of its causes comes from the database. Its message may carry the SQL and
     * PostgreSQL's detail, such as {@code Key (...)=(...)} with the row's values, so it is logged with {@link
     * #describe} instead.
     */
    public static boolean isDataError(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof DataAccessException || t instanceof SQLException) {
                return true;
            }
        }
        return false;
    }

    /**
     * The classes of {@code error} and its causes and the first frame in TiP's code, without any message: {@code
     * DataIntegrityViolationException <- PSQLException at dev.tip.x.Y.m(Y.java:42)}.
     */
    public static String describe(Throwable error) {
        List<String> classes = new ArrayList<>();
        for (Throwable t = error; t != null && classes.size() < 8; t = t.getCause()) {
            classes.add(t.getClass().getSimpleName());
        }
        String where = "";
        for (StackTraceElement frame : error.getStackTrace()) {
            String name = frame.getClassName();
            if (name.startsWith("dev.tip.") && !name.contains("$Proxy") && !name.contains("$$")) {
                where = " at " + frame;
                break;
            }
        }
        return String.join(" <- ", classes) + where;
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
