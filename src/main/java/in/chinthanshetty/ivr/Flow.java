package in.chinthanshetty.ivr;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.Yaml;

/** A call flow loaded from YAML and checked once at startup, so a typo fails the deploy, not a live call. */
public record Flow(String start, Map<String, Node> nodes) {

    public enum Type { PROMPT, MENU, LOOKUP, ACTION, TRANSFER, HANGUP }

    public record Node(String id, Type type, String say, String next, Map<String, String> options, int maxRetries,
                       String onFail, String api, String onError, String queue, String afterHours, String needs) {}

    public Node node(String id) {
        Node n = nodes.get(id);
        if (n == null) throw new IllegalStateException("Unknown node " + id);
        return n;
    }

    @SuppressWarnings("unchecked")
    public static Flow load(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Map<String, Object>> raw = (Map<String, Map<String, Object>>) root.get("nodes");
            Map<String, Node> nodes = new LinkedHashMap<>();
            raw.forEach((id, m) -> {
                Map<String, String> options = new LinkedHashMap<>();
                if (m.get("options") instanceof Map<?, ?> o) o.forEach((k, v) -> options.put(String.valueOf(k), String.valueOf(v)));
                nodes.put(id, new Node(id, Type.valueOf(String.valueOf(m.get("type")).toUpperCase()), str(m, "say"),
                        str(m, "next"), options, m.get("max-retries") == null ? 0 : (Integer) m.get("max-retries"),
                        str(m, "on-fail"), str(m, "api"), str(m, "on-error"), str(m, "queue"), str(m, "after-hours"), str(m, "needs")));
            });
            Flow flow = new Flow((String) root.get("start"), nodes);
            flow.validate();
            return flow;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read flow " + resource, e);
        }
    }

    private static String str(Map<String, Object> m, String k) {
        return m.get(k) == null ? null : String.valueOf(m.get(k));
    }

    /** Every reference points at a real node, menus have options and a fallback, and lookups have an error path. */
    void validate() {
        if (!nodes.containsKey(start)) throw new IllegalStateException("start node '" + start + "' does not exist");
        for (Node n : nodes.values()) {
            for (String ref : new String[] {n.next(), n.onFail(), n.onError(), n.afterHours()}) {
                if (ref != null && !nodes.containsKey(ref)) throw new IllegalStateException(n.id() + " points to missing node " + ref);
            }
            n.options().values().forEach(ref -> {
                if (!nodes.containsKey(ref)) throw new IllegalStateException(n.id() + " option points to missing node " + ref);
            });
            switch (n.type()) {
                case MENU -> {
                    if (n.options().isEmpty() || n.onFail() == null) throw new IllegalStateException(n.id() + ": a menu needs options and on-fail");
                    if (!n.options().keySet().stream().allMatch(k -> k.matches("[0-9*#]"))) throw new IllegalStateException(n.id() + ": options must be single keys");
                }
                case LOOKUP, ACTION -> {
                    if (n.api() == null || n.onError() == null) throw new IllegalStateException(n.id() + ": needs api and on-error");
                }
                case TRANSFER -> {
                    if (n.queue() == null) throw new IllegalStateException(n.id() + ": transfer needs a queue");
                }
                case PROMPT -> {
                    if (n.next() == null) throw new IllegalStateException(n.id() + ": prompt needs next");
                }
                default -> { }
            }
        }
        if (nodes.values().stream().noneMatch(n -> n.type() == Type.HANGUP)) throw new IllegalStateException("flow has no hangup node");
    }
}
