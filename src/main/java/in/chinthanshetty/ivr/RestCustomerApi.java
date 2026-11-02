package in.chinthanshetty.ivr;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the customer system over HTTP with a short timeout: a caller is waiting in silence, so after 2 seconds it is
 * better to apologise and transfer than to keep them hanging.
 */
@Component
public class RestCustomerApi implements CustomerApi {
    private static final Map<String, String> PATHS = Map.of(
            "balance", "/customers/{caller}/balance",
            "card", "/customers/{caller}/card",
            "block-card", "/customers/{caller}/card/block");
    private final RestClient http;

    public RestCustomerApi(RestClient.Builder builder, IvrProperties props) {
        var jdk = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(props.customerApi().timeoutMs())).build();
        var factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofMillis(props.customerApi().timeoutMs()));
        this.http = builder.baseUrl(props.customerApi().baseUrl()).requestFactory(factory).build();
    }

    @Override
    public Map<String, String> call(String api, String caller) {
        String path = PATHS.get(api);
        if (path == null) throw new CustomerApiException("Unknown api " + api, null);
        try {
            var spec = api.equals("block-card") ? http.post().uri(path, caller) : http.get().uri(path, caller);
            Map<String, String> body = spec.retrieve().body(new ParameterizedTypeReference<Map<String, String>>() {});
            return body == null ? Map.of() : body;
        } catch (RestClientException e) {
            throw new CustomerApiException(api + " failed: " + e.getClass().getSimpleName(), e);
        }
    }
}
