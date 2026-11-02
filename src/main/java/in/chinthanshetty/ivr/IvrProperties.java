package in.chinthanshetty.ivr;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

@ConfigurationProperties("ivr")
public record IvrProperties(Resource flow, ZoneId timezone, AgentHours agentHours, CustomerApi customerApi) {
    public record AgentHours(Set<DayOfWeek> days, LocalTime open, LocalTime close) {}
    public record CustomerApi(String baseUrl, int timeoutMs) {}
}
