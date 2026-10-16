package dev.tip.shared;

import java.util.ArrayList;
import java.util.List;
import org.apache.catalina.Valve;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.TomcatWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Client address resolution around Tomcat's {@code RemoteIpValve}, which reads the header in
 * {@code server.tomcat.remoteip.remote-ip-header} ({@code TIP_CLIENT_IP_HEADER}).
 */
@Configuration(proxyBeanMethods = false)
class ClientIpConfig {

    @Bean
    WebServerFactoryCustomizer<TomcatWebServerFactory> clientIpValveInstaller(
            @Value("${server.tomcat.remoteip.remote-ip-header}") String header) {
        return new ValveInstaller(new ClientIpValve(header));
    }

    /** Runs last, so it can put the valve in front of the {@code RemoteIpValve} the other customizers added. */
    static final class ValveInstaller implements WebServerFactoryCustomizer<TomcatWebServerFactory>, Ordered {

        private final ClientIpValve valve;

        ValveInstaller(ClientIpValve valve) {
            this.valve = valve;
        }

        @Override
        public void customize(TomcatWebServerFactory factory) {
            List<Valve> valves = new ArrayList<>();
            valves.add(valve);
            valves.addAll(factory.getEngineValves());
            factory.setEngineValves(valves);
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
