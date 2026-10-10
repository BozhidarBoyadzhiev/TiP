package dev.tip.shared;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The fallback chain for every path no feature chain claims. Public endpoints are listed; everything
 * else is denied with 403 problem details, signed in or not.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemWriter problems) throws Exception {
        SecurityDefaults.apply(http, problems)
                .authorizeHttpRequests(auth -> auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.GET, "/t/**", "/p/**", "/css/**", "/js/**", "/fonts/**", "/favicon.ico")
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
                .exceptionHandling(
                        exceptions -> exceptions.authenticationEntryPoint(SecurityDefaults.forbidden(problems)))
                // Stateless JSON endpoints without a session cookie: there is nothing for CSRF to ride on.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/public/**", "/webhooks/**"));
        return http.build();
    }
}
