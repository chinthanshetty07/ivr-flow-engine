package in.chinthanshetty.ivr;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** A settable clock and an in-memory customer system, so tests control time and API failures. */
@TestConfiguration
public class TestSupport {

    public static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-03T06:30:00Z");   // Tuesday 12:00 IST
        public void set(String isoUtc) { now = Instant.parse(isoUtc); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    public static class FakeCustomerApi implements CustomerApi {
        public final Set<String> failing = new HashSet<>();
        public final Set<String> blocked = new HashSet<>();
        public int calls;

        @Override
        public Map<String, String> call(String api, String caller) {
            calls++;
            if (failing.contains(api)) throw new CustomerApiException(api + " timed out", null);
            return switch (api) {
                case "balance" -> Map.of("balance", "12,450");
                case "card" -> Map.of("cardLast4", "4821");
                case "block-card" -> { blocked.add(caller); yield Map.of("status", "BLOCKED"); }
                default -> throw new CustomerApiException("unknown " + api, null);
            };
        }
    }

    @Bean @Primary MutableClock testClock() { return new MutableClock(); }

    @Bean @Primary FakeCustomerApi fakeCustomerApi() { return new FakeCustomerApi(); }
}
