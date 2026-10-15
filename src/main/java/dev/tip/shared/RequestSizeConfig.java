package dev.tip.shared;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Puts {@link RequestSizeLimit} in front of every other filter, the security chains included. */
@Configuration(proxyBeanMethods = false)
class RequestSizeConfig {

    @Bean
    FilterRegistrationBean<RequestSizeLimit> requestSizeLimit(ProblemWriter problems) {
        FilterRegistrationBean<RequestSizeLimit> registration =
                new FilterRegistrationBean<>(new RequestSizeLimit(problems));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
