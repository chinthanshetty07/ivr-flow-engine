package in.chinthanshetty.ivr;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** HTTP stand-in for the telephony platform's webhooks (call start, DTMF, no-input, hangup). */
@RestController
@RequestMapping("/calls")
public class CallController {
    private final CallEngine engine;
    private final CallStore store;

    public CallController(CallEngine engine, CallStore store) {
        this.engine = engine;
        this.store = store;
    }

    public record StartCall(@NotBlank @Pattern(regexp = "\\+?[0-9]{10,13}") String caller) {}
    public record Digits(@NotBlank @Pattern(regexp = "[0-9*#]{1,4}") String digits) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CallEngine.Result start(@Valid @RequestBody StartCall body) { return engine.start(body.caller()); }

    @PostMapping("/{id}/digits")
    public CallEngine.Result digits(@PathVariable String id, @Valid @RequestBody Digits body) { return engine.digits(id, body.digits()); }

    @PostMapping("/{id}/no-input")
    public CallEngine.Result noInput(@PathVariable String id) { return engine.noInput(id); }

    @PostMapping("/{id}/hangup")
    public CallEngine.Result hangup(@PathVariable String id) { return engine.hangup(id); }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> record(@PathVariable String id) {
        return store.find(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/stats")
    public Map<String, Integer> stats() { return store.outcomeCounts(); }

    @ExceptionHandler(CallEngine.UnknownCallException.class)
    ResponseEntity<Map<String, String>> unknown(CallEngine.UnknownCallException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
