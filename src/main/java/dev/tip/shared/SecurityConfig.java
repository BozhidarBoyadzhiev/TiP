package dev.tip.shared;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * The fallback chain for every path no feature chain claims. Public endpoints are listed; everything
 * else is denied with 403 problem details, signed in or not, except that a browser {@code GET} for an
 * unknown page gets the 404 page: a 404 tells a visitor nothing more than a 403 does.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, ProblemWriter problems, TipProperties properties, NotFoundPage notFoundPage)
            throws Exception {
        SecurityDefaults.apply(http, problems, properties)
                .authorizeHttpRequests(auth -> auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.GET,
                                "/t/**",
                                "/p/**",
                                "/css/**",
                                "/js/**",
                                "/fonts/**",
                                "/icons/**",
                                "/images/**",
                                "/favicon.ico")
                        .permitAll()
                        // Link checkers and preview crawlers may ask for a static file with HEAD first.
                        .requestMatchers(
                                HttpMethod.HEAD,
                                "/css/**",
                                "/js/**",
                                "/fonts/**",
                                "/icons/**",
                                "/images/**",
                                "/favicon.ico")
                        .permitAll()
                        .requestMatchers("/api/public/**")
                        .permitAll()
                        // WebSocket handshakes authenticate in their own handshake interceptors.
                        .requestMatchers(HttpMethod.GET, "/ws/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/webhooks/paypal")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health")
                        .permitAll()
                        .anyRequest()
                        .denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(
                                notFoundForBrowsers(notFoundPage, SecurityDefaults.forbidden(problems)))
                        .accessDeniedHandler(
                                notFoundForBrowsers(notFoundPage, SecurityDefaults.accessDeniedHandler(problems))))
                // Stateless JSON endpoints without a session cookie: there is nothing for CSRF to ride on.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/public/**", "/webhooks/**"));
        return http.build();
    }

    private static AuthenticationEntryPoint notFoundForBrowsers(
            NotFoundPage notFoundPage, AuthenticationEntryPoint otherwise) {
        return (request, response, e) -> {
            if (NotFoundPage.wantedBy(request)) {
                notFoundPage.write(request, response);
            } else {
                otherwise.commence(request, response, e);
            }
        };
    }

    // A GET never fails the CSRF check, so a browser GET denied here is always an unknown path.
    private static AccessDeniedHandler notFoundForBrowsers(NotFoundPage notFoundPage, AccessDeniedHandler otherwise) {
        return (request, response, e) -> {
            if (NotFoundPage.wantedBy(request)) {
                notFoundPage.write(request, response);
            } else {
                otherwise.handle(request, response, e);
            }
        };
    }
}
