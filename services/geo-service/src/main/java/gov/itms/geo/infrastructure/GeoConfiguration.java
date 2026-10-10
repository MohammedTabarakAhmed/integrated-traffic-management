package gov.itms.geo.infrastructure;

import gov.itms.geo.application.GeoService;
import gov.itms.geo.application.Ports;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods=false)
public class GeoConfiguration {
    @Bean Ports.GeoRepository geoRepository(JdbcClient jdbc){return new JdbcGeoRepository(jdbc);}
    @Bean GeoService geoService(Ports.GeoRepository repo,AuditRecorder audit,DomainEventRecorder events,Clock clock){return new GeoService(repo,audit,events,clock);}
}
