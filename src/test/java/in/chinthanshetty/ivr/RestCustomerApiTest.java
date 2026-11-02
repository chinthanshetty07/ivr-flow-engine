package in.chinthanshetty.ivr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Against a real local HTTP server: JSON is parsed, errors and slow responses become CustomerApiException. */
class RestCustomerApiTest {
    HttpServer server;
    RestCustomerApi api;

    @BeforeEach
    void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/customers/9876543210/balance", ex -> {
            byte[] b = "{\"balance\":\"12,450\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/customers/9876543210/card", ex -> { ex.sendResponseHeaders(500, -1); ex.close(); });
        server.createContext("/customers/1111111111/balance", ex -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        var props = new IvrProperties(null, ZoneId.of("Asia/Kolkata"),
                new IvrProperties.AgentHours(Set.of(DayOfWeek.MONDAY), LocalTime.NOON, LocalTime.MIDNIGHT),
                new IvrProperties.CustomerApi("http://127.0.0.1:" + server.getAddress().getPort(), 500));
        api = new RestCustomerApi(RestClient.builder(), props);
    }

    @AfterEach
    void down() { server.stop(0); }

    @Test
    void parsesJson() {
        assertThat(api.call("balance", "9876543210")).containsEntry("balance", "12,450");
    }

    @Test
    void serverErrorBecomesCustomerApiException() {
        assertThatThrownBy(() -> api.call("card", "9876543210")).isInstanceOf(CustomerApi.CustomerApiException.class);
    }

    @Test
    void slowResponseTimesOutInsteadOfLeavingTheCallerWaiting() {
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> api.call("balance", "1111111111")).isInstanceOf(CustomerApi.CustomerApiException.class);
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(2500);
    }
}
