package dev.tip.owners;

import dev.tip.owners.api.OwnerPrincipal;
import dev.tip.owners.api.OwnerSignedOut;
import dev.tip.shared.ProblemWriter;
import dev.tip.shared.SecurityDefaults;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Session based form login for owners, for {@code /owner/**} pages and the {@code /api/owner/**} JSON
 * API, plus the sign ins without a password under {@code /judges/**} and {@code /demo/**}, and {@link
 * OwnerAccessFilter} for sandbox expiry and the read only demo. CSRF protection stays on for both. Session fixation protection is Spring's default (the session
 * id changes on login) and nothing here turns it off. Cookie flags are in application.properties.
 */
@Configuration(proxyBeanMethods = false)
class OwnerSecurityConfig {

    static final String LOGIN_PATH = "/owner/login";
    static final String HOME_PATH = "/owner";
    static final String LOGOUT_PATH = "/owner/logout";
    static final String EMAIL = "email";
    static final String PASSWORD = "password";

    /** The owner's live channel, see {@code docs/OWNER_CHANNEL.md}. It needs the owner's session too. */
    static final String CHANNEL_PATH = "/ws/owner";

    @Bean
    @Order(1)
    SecurityFilterChain ownerSecurityFilterChain(
            HttpSecurity http,
            ProblemWriter problems,
            LoginRateLimiter limiter,
            ApplicationEventPublisher events,
            Clock clock)
            throws Exception {
        SimpleUrlAuthenticationSuccessHandler toHome = new SimpleUrlAuthenticationSuccessHandler(HOME_PATH);
        toHome.setAlwaysUseDefaultTargetUrl(true);

        SecurityDefaults.apply(http, problems)
                .securityMatcher("/owner", "/owner/**", "/api/owner/**", CHANNEL_PATH, "/judges/**", "/demo/**")
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET, LOGIN_PATH)
                        .permitAll()
                        // Sign ins without a password, see SandboxController. Same CSRF and session as the login.
                        .requestMatchers(HttpMethod.POST, SandboxController.SESSION_PATH, SandboxController.DEMO_PATH)
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, SandboxController.RESUME_PATH)
                        .permitAll()
                        .anyRequest()
                        .hasRole(OwnerPrincipal.ROLE))
                .formLogin(form -> form.loginPage(LOGIN_PATH)
                        .loginProcessingUrl(LOGIN_PATH)
                        .usernameParameter(EMAIL)
                        .passwordParameter(PASSWORD)
                        .successHandler(toHome)
                        // One answer for an unknown email and a wrong password, so emails cannot be probed.
                        .failureHandler((request, response, e) -> problems.write(
                                request,
                                response,
                                HttpStatus.UNAUTHORIZED,
                                "LOGIN_FAILED",
                                "Email or password is incorrect.")))
                .logout(logout -> logout.logoutUrl(LOGOUT_PATH)
                        // Lets other features close what the session opened, such as the live channel. The
                        // public demo is one owner shared by every visitor and has no sockets, so its sign out
                        // tells nobody.
                        .addLogoutHandler((request, response, authentication) -> {
                            if (authentication != null
                                    && authentication.getPrincipal() instanceof OwnerPrincipal o
                                    && !o.readOnly()) {
                                events.publishEvent(new OwnerSignedOut(o.id()));
                            }
                        })
                        .logoutSuccessUrl(LOGIN_PATH + "?signedOut")
                        .deleteCookies("JSESSIONID"))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint(problems)))
                .addFilterBefore(
                        new LoginRateLimitFilter(LOGIN_PATH, limiter, problems),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new OwnerAccessFilter(problems, clock), AuthorizationFilter.class);
        return http.build();
    }

    /**
     * The JSON API and the live channel answer 401 problem details; pages send the browser to the sign in
     * page.
     */
    private static AuthenticationEntryPoint entryPoint(ProblemWriter problems) {
        RequestMatcher apiOnly = PathPatternRequestMatcher.withDefaults().matcher("/api/owner/**");
        RequestMatcher channel = PathPatternRequestMatcher.withDefaults().matcher(CHANNEL_PATH);
        RequestMatcher api = request -> apiOnly.matches(request) || channel.matches(request);
        AuthenticationEntryPoint unauthorized = SecurityDefaults.unauthorized(problems);
        AuthenticationEntryPoint loginPage = new LoginUrlAuthenticationEntryPoint(LOGIN_PATH);
        return (request, response, e) ->
                (api.matches(request) ? unauthorized : loginPage).commence(request, response, e);
    }
}
