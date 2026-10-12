package dev.tip.devices;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A WebSocket client on a plain socket. It shows the handshake response byte for byte, and once
 * connected it never reads unless asked, so it never answers a ping: a device that froze.
 */
public final class RawWebSocket implements AutoCloseable {

    private static final int TIMEOUT_MILLIS = 10_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Socket socket;
    private final InputStream in;
    public final int status;
    public final Map<String, String> headers;
    public final String body;

    private RawWebSocket(Socket socket, int status, Map<String, String> headers, String body) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.status = status;
        this.headers = headers;
        this.body = body;
    }

    /** Sends an upgrade request for {@code path} with the extra headers and reads the response head and body. */
    public static RawWebSocket handshake(int port, String path, Map<String, String> extraHeaders) throws IOException {
        Socket socket = new Socket("localhost", port);
        socket.setSoTimeout(TIMEOUT_MILLIS);
        StringBuilder request = new StringBuilder()
                .append("GET ")
                .append(path)
                .append(" HTTP/1.1\r\n")
                .append("Host: localhost:")
                .append(port)
                .append("\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Connection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: ")
                .append(handshakeKey())
                .append("\r\n")
                .append("Sec-WebSocket-Version: 13\r\n");
        extraHeaders.forEach(
                (name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
        request.append("\r\n");
        OutputStream out = socket.getOutputStream();
        out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        InputStream in = socket.getInputStream();
        String[] head = readHead(in).split("\r\n");
        int status = Integer.parseInt(head[0].split(" ")[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < head.length; i++) {
            int colon = head[i].indexOf(':');
            headers.put(
                    head[i].substring(0, colon).toLowerCase(Locale.ROOT),
                    head[i].substring(colon + 1).strip());
        }
        String body = status == 101 ? "" : readBody(in, headers);
        return new RawWebSocket(socket, status, headers, body);
    }

    /** The RFC 6455 handshake nonce: 16 random bytes, Base64 encoded, fresh for every handshake. */
    private static String handshakeKey() {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        return Base64.getEncoder().encodeToString(nonce);
    }

    /** The next frame from the server: opcode and payload. Server frames are never masked. */
    public Frame readFrame() throws IOException {
        int first = readByte();
        int second = readByte();
        long length = second & 0x7f;
        if (length == 126) {
            length = (readByte() << 8) | readByte();
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | readByte();
            }
        }
        byte[] payload = in.readNBytes((int) length);
        return new Frame(first & 0x0f, payload);
    }

    /** The next text frame's payload, skipping pings and other frames. */
    public String readUntilText() throws IOException {
        while (true) {
            Frame frame = readFrame();
            if (frame.opcode() == Frame.TEXT) {
                return new String(frame.payload(), StandardCharsets.UTF_8);
            }
        }
    }

    /** Reads frames until a close frame and returns its close code. */
    public int readUntilCloseCode() throws IOException {
        while (true) {
            Frame frame = readFrame();
            if (frame.opcode() == Frame.CLOSE) {
                return ((frame.payload()[0] & 0xff) << 8) | (frame.payload()[1] & 0xff);
            }
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new IOException("Connection closed");
        }
        return b;
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        while (matched < end.length) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("Connection closed before the response head ended");
            }
            head.write(b);
            matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        String text = head.toString(StandardCharsets.ISO_8859_1);
        return text.substring(0, text.length() - end.length);
    }

    private static String readBody(InputStream in, Map<String, String> headers) throws IOException {
        if (headers.containsKey("content-length")) {
            int length = Integer.parseInt(headers.get("content-length"));
            return new String(in.readNBytes(length), StandardCharsets.UTF_8);
        }
        if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            while (true) {
                int size = Integer.parseInt(readLine(in).strip(), 16);
                if (size == 0) {
                    readLine(in);
                    return body.toString(StandardCharsets.UTF_8);
                }
                body.write(in.readNBytes(size));
                readLine(in);
            }
        }
        return "";
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0 && b != '\n') {
            if (b != '\r') {
                line.write(b);
            }
        }
        return line.toString(StandardCharsets.ISO_8859_1);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    public record Frame(int opcode, byte[] payload) {
        public static final int TEXT = 0x1;
        public static final int CLOSE = 0x8;
        public static final int PING = 0x9;
    }
}
