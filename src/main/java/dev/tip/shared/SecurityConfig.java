package dev.tip.shared;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    private static final String PAYPAL_SOURCES = "https://*.paypal.com https://*.paypalobjects.com https://*.venmo.com";

    /**
     * Our origin plus the domains PayPal lists for its JS SDK. Inline scripts and styles run only with
     * the per request nonce, which the SDK applies to what it injects.
     */
    static String contentSecurityPolicy(String nonce) {
        String nonceSource = "'nonce-" + nonce + "'";
        return String.join(
                "; ",
                "default-src 'self'",
                "script-src 'self' " + nonceSource + " " + PAYPAL_SOURCES,
                "style-src 'self' " + nonceSource + " " + PAYPAL_SOURCES,
                "img-src 'self' data: " + PAYPAL_SOURCES,
                "connect-src 'self' " + PAYPAL_SOURCES,
                "frame-src " + PAYPAL_SOURCES,
                "child-src " + PAYPAL_SOURCES,
                "object-src 'none'",
                "base-uri 'self'",
                "form-action 'self'",
                "frame-ancestors 'none'");
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/t/**", "/css/**", "/js/**", "/favicon.ico")
                        .permitAll()
                        .requestMatchers("/api/public/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/webhooks/paypal")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health")
                        .permitAll()
                        .anyRequest()
                        .denyAll())
                // Stateless JSON endpoints without a session cookie: there is nothing for CSRF to ride on.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/public/**", "/webhooks/**"))
                .addFilterBefore(new CspNonce(), HeaderWriterFilter.class)
                .headers(headers -> headers.addHeaderWriter((request, response) -> response.setHeader(
                                "Content-Security-Policy", contentSecurityPolicy(CspNonce.of(request))))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .httpStrictTransportSecurity(
                                hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)));
        return http.build();
    }
}
