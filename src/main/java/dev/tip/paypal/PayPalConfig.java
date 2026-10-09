package dev.tip.paypal;

import com.paypal.sdk.Environment;
import com.paypal.sdk.PaypalServerSdkClient;
import com.paypal.sdk.authentication.ClientCredentialsAuthModel;
import dev.tip.paypal.api.PayPalPublicConfig;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class PayPalConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    @Bean
    PayPalPublicConfig payPalPublicConfig(PayPalProperties properties) {
        return new PayPalPublicConfig(properties.clientId());
    }

    @Bean
    PayPalAccessTokens payPalAccessTokens(Clock clock) {
        return new PayPalAccessTokens(clock);
    }

    /**
     * The SDK picks its host from an environment enum and has no base URL setting, so an interceptor
     * points every SDK call, including the OAuth token call, at {@code PAYPAL_BASE_URL}. SDK retries stay
     * off: a rewriting interceptor breaks them, and create and capture are made idempotent with {@code
     * PayPal-Request-Id} instead.
     */
    @Bean(destroyMethod = "")
    PaypalServerSdkClient paypalServerSdkClient(PayPalProperties properties, PayPalAccessTokens tokens) {
        HttpUrl target = HttpUrl.get(properties.baseUrl());
        Interceptor rewriteHost = chain -> {
            HttpUrl url = chain.request()
                    .url()
                    .newBuilder()
                    .scheme(target.scheme())
                    .host(target.host())
                    .port(target.port())
                    .build();
            return chain.proceed(chain.request().newBuilder().url(url).build());
        };
        OkHttpClient okHttp = new OkHttpClient.Builder()
                .addInterceptor(rewriteHost)
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(READ_TIMEOUT)
                .writeTimeout(READ_TIMEOUT)
                .callTimeout(READ_TIMEOUT.plus(CONNECT_TIMEOUT))
                .retryOnConnectionFailure(false)
                .build();
        return new PaypalServerSdkClient.Builder()
                .environment(Environment.SANDBOX)
                .clientCredentialsAuth(
                        new ClientCredentialsAuthModel.Builder(properties.clientId(), properties.clientSecret())
                                .oAuthOnTokenUpdate(tokens::update)
                                .build())
                .httpClientConfig(config -> config.httpClientInstance(okHttp, false)
                        .numberOfRetries(0)
                        .timeout(READ_TIMEOUT.toSeconds()))
                .build();
    }

    @Bean
    RestClient payPalRestClient(PayPalProperties properties) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
