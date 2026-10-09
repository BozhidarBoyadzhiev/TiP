package dev.tip.paypal;

import com.paypal.sdk.PaypalServerSdkClient;
import com.paypal.sdk.exceptions.ApiException;
import com.paypal.sdk.models.OAuthToken;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shares the SDK's OAuth token with the calls the SDK does not cover. The SDK reports every token it
 * fetches through {@link #update}; when none is cached yet, one is fetched through the SDK.
 */
class PayPalAccessTokens {

    private static final long EXPIRY_MARGIN_SECONDS = 60;

    private final Clock clock;
    private final AtomicReference<OAuthToken> current = new AtomicReference<>();

    PayPalAccessTokens(Clock clock) {
        this.clock = clock;
    }

    void update(OAuthToken token) {
        current.set(token);
    }

    void invalidate() {
        current.set(null);
    }

    String accessToken(PaypalServerSdkClient client) throws ApiException, IOException {
        OAuthToken token = current.get();
        if (!usable(token)) {
            token = client.getClientCredentialsAuth().fetchToken();
            current.set(token);
        }
        return token.getAccessToken();
    }

    private boolean usable(OAuthToken token) {
        if (token == null || token.getAccessToken() == null) {
            return false;
        }
        Long expiry = token.getExpiry();
        return expiry == null
                || expiry - EXPIRY_MARGIN_SECONDS > clock.instant().getEpochSecond();
    }
}
