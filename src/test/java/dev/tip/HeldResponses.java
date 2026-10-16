package dev.tip;

import com.github.tomakehurst.wiremock.extension.ResponseTransformerV2;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A WireMock transformer that holds the response of a stub naming {@link #NAME} until the test releases it, so a test
 * can act while the app waits on PayPal, in an order the test sets rather than by timing. Registered on the shared
 * PayPal server; only one hold at a time.
 */
public final class HeldResponses implements ResponseTransformerV2 {

    public static final String NAME = "held";

    static final HeldResponses INSTANCE = new HeldResponses();

    private static final Duration LONGEST_HOLD = Duration.ofSeconds(30);

    private volatile Hold current = new Hold();

    private HeldResponses() {}

    /** Holds the next responses of stubs naming {@link #NAME} until {@link Hold#release()}. */
    public static Hold hold() {
        Hold hold = new Hold();
        INSTANCE.current = hold;
        return hold;
    }

    /** Lets any hold a test left behind go, so it never blocks a later test. */
    static void releaseAll() {
        INSTANCE.current.release();
    }

    @Override
    public Response transform(Response response, ServeEvent serveEvent) {
        Hold hold = current;
        hold.arrived.countDown();
        try {
            if (!hold.released.await(LONGEST_HOLD.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("A held response was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return response;
    }

    @Override
    public boolean applyGlobally() {
        return false;
    }

    @Override
    public String getName() {
        return NAME;
    }

    public static final class Hold {

        private final CountDownLatch arrived = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        /** Waits until the app's request reached the stub and is being held. */
        public void awaitArrived() throws InterruptedException {
            if (!arrived.await(LONGEST_HOLD.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("No request reached the held stub");
            }
        }

        public void release() {
            released.countDown();
        }
    }
}
