package in.chinthanshetty.ivr;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

class FlowValidationTest {
    static Flow load(String yaml) {
        return Flow.load(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void danglingReferenceFailsAtLoadTime() {
        assertThatThrownBy(() -> load("""
                start: a
                nodes:
                  a: { type: prompt, say: hi, next: b }
                  h: { type: hangup }
                """)).hasMessageContaining("a points to missing node b");
    }

    @Test
    void menuWithoutFallbackIsRejected() {
        assertThatThrownBy(() -> load("""
                start: m
                nodes:
                  m: { type: menu, say: pick, options: { "1": h } }
                  h: { type: hangup }
                """)).hasMessageContaining("a menu needs options and on-fail");
    }

    @Test
    void apiNodeWithoutErrorPathIsRejected() {
        assertThatThrownBy(() -> load("""
                start: l
                nodes:
                  l: { type: lookup, api: balance, say: x, next: h }
                  h: { type: hangup }
                """)).hasMessageContaining("needs api and on-error");
    }

    @Test
    void flowWithoutHangupIsRejected() {
        assertThatThrownBy(() -> load("""
                start: a
                nodes:
                  a: { type: prompt, say: hi, next: a }
                """)).hasMessageContaining("no hangup");
    }

    @Test
    void multiDigitMenuKeysAreRejected() {
        assertThatThrownBy(() -> load("""
                start: m
                nodes:
                  m: { type: menu, say: pick, options: { "12": h }, on-fail: h }
                  h: { type: hangup }
                """)).hasMessageContaining("single keys");
    }
}
