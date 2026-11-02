package in.chinthanshetty.ivr;

import java.util.Map;

/** The bank's customer system, identified by the caller's number. Any failure surfaces as CustomerApiException. */
public interface CustomerApi {
    Map<String, String> call(String api, String caller);

    class CustomerApiException extends RuntimeException {
        public CustomerApiException(String message, Throwable cause) { super(message, cause); }
    }
}
