package dev.tip.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps every request body at {@link #MAX_BYTES}, far above any request TiP takes (a PayPal webhook is a few KB), so
 * no anonymous request can make the server hold a large body in memory. A declared length over the cap answers 413
 * problem details before anything reads the body; a body without a declared length (chunked) fails with {@link
 * TooLarge} once it passes the cap, which {@link ProblemDetailsAdvice} answers with the same 413. Form posts are
 * parsed by Tomcat, whose own limit ({@code server.tomcat.max-http-form-post-size}) is the same size.
 */
final class RequestSizeLimit extends OncePerRequestFilter {

    static final int MAX_BYTES = 64 * 1024;

    /** 413 problem details, the same for a declared and a counted body. */
    static final HttpStatus STATUS = HttpStatus.CONTENT_TOO_LARGE;

    private final ProblemWriter problems;

    RequestSizeLimit(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > MAX_BYTES) {
            problems.write(request, response, STATUS, GenericProblems.code(STATUS), GenericProblems.detail(STATUS));
            return;
        }
        chain.doFilter(declared == 0 ? request : new Capped(request), response);
    }

    /** Read past {@link #MAX_BYTES}. */
    static final class TooLarge extends IOException {
        TooLarge() {
            super("The request body is larger than " + MAX_BYTES + " bytes");
        }
    }

    private static final class Capped extends HttpServletRequestWrapper {

        private ServletInputStream stream;

        Capped(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new CappedStream(super.getInputStream());
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class CappedStream extends ServletInputStream {

        private final ServletInputStream body;
        private long read;

        CappedStream(ServletInputStream body) {
            this.body = body;
        }

        @Override
        public int read() throws IOException {
            int next = body.read();
            if (next >= 0) {
                count(1);
            }
            return next;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = body.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws TooLarge {
            read += n;
            if (read > MAX_BYTES) {
                throw new TooLarge();
            }
        }

        @Override
        public boolean isFinished() {
            return body.isFinished();
        }

        @Override
        public boolean isReady() {
            return body.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            body.setReadListener(listener);
        }
    }
}
