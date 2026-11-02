package in.chinthanshetty.ivr;

import in.chinthanshetty.ivr.Action.Collect;
import in.chinthanshetty.ivr.Action.Hangup;
import in.chinthanshetty.ivr.Action.Say;
import in.chinthanshetty.ivr.Action.Transfer;
import in.chinthanshetty.ivr.CustomerApi.CustomerApiException;
import in.chinthanshetty.ivr.Flow.Node;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs calls through the flow. The telephony side sends events (call started, digits pressed, no input) and gets
 * back the actions to perform. Automatic nodes (prompts, API calls, transfers) run straight through until the
 * call needs input from the caller or ends.
 */
@Service
public class CallEngine {
    private static final Logger log = LoggerFactory.getLogger(CallEngine.class);
    private static final Pattern VAR = Pattern.compile("\\{(\\w+)}");
    static final int DIGIT_TIMEOUT_SECONDS = 5;

    public static final class Call {
        final String id, caller;
        String node;
        int retries;
        final Map<String, String> vars = new HashMap<>();
        final List<String> path = new ArrayList<>();
        String outcome, queue;
        boolean ended;

        Call(String id, String caller) { this.id = id; this.caller = caller; }
        public String id() { return id; }
        public boolean ended() { return ended; }
    }

    public record Result(String callId, List<Action> actions, boolean ended) {}

    private final Flow flow;
    private final CustomerApi api;
    private final CallStore store;
    private final IvrProperties props;
    private final Clock clock;
    private final Map<String, Call> live = new ConcurrentHashMap<>();

    public CallEngine(IvrProperties props, CustomerApi api, CallStore store, Clock clock) {
        this.flow = Flow.load(props.flow());
        this.api = api;
        this.store = store;
        this.props = props;
        this.clock = clock;
    }

    public Result start(String caller) {
        Call c = new Call(UUID.randomUUID().toString(), caller);
        live.put(c.id, c);
        store.start(c.id, caller, clock.instant());
        log.info("call {} from {}", c.id, CallStore.mask(caller));
        return run(c, flow.start());
    }

    public Result digits(String callId, String digits) {
        Call c = active(callId);
        Node menu = flow.node(c.node);
        String next = menu.options().get(digits);
        if (next != null) {
            c.retries = 0;
            return run(c, next);
        }
        return retry(c, menu, "Sorry, that's not a valid option.");
    }

    public Result noInput(String callId) {
        Call c = active(callId);
        return retry(c, flow.node(c.node), "Sorry, I didn't hear anything.");
    }

    /** Caller hung up mid-flow. */
    public Result hangup(String callId) {
        Call c = active(callId);
        if (c.outcome == null) c.outcome = "ABANDONED";
        return finish(c, new ArrayList<>());
    }

    private Result retry(Call c, Node menu, String apology) {
        c.retries++;
        if (c.retries > menu.maxRetries()) {
            c.retries = 0;
            List<Action> out = new ArrayList<>(List.of(new Say(apology)));
            return run(c, menu.onFail(), out);
        }
        // Re-ask with the apology first, so the caller knows why they're hearing the menu again.
        List<Action> out = new ArrayList<>(List.of(new Say(apology)));
        return run(c, menu.id(), out);
    }

    private Result run(Call c, String nodeId) {
        return run(c, nodeId, new ArrayList<>());
    }

    private Result run(Call c, String nodeId, List<Action> out) {
        for (int guard = 0; guard < 50; guard++) {          // a flow with a prompt loop must not spin forever
            Node n = flow.node(nodeId);
            c.node = n.id();
            if (c.path.isEmpty() || !c.path.get(c.path.size() - 1).equals(n.id())) c.path.add(n.id());
            switch (n.type()) {
                case PROMPT -> {
                    out.add(new Say(render(n.say(), c)));
                    nodeId = n.next();
                }
                case MENU -> {
                    if (n.needs() != null && !fetch(c, n.needs())) {
                        nodeId = n.onFail();                   // can't describe the card without its details
                        continue;
                    }
                    out.add(new Say(render(n.say(), c)));
                    out.add(new Collect(1, DIGIT_TIMEOUT_SECONDS));
                    save(c);
                    return new Result(c.id, out, false);
                }
                case LOOKUP, ACTION -> {
                    if (!fetch(c, n.api())) {
                        nodeId = n.onError();
                        continue;
                    }
                    c.outcome = "SELF_SERVED";
                    out.add(new Say(render(n.say(), c)));
                    nodeId = n.next();
                }
                case TRANSFER -> {
                    if (!agentsOpen()) {
                        c.outcome = "AFTER_HOURS";
                        nodeId = n.afterHours();
                        continue;
                    }
                    c.outcome = "TRANSFERRED";
                    c.queue = n.queue();
                    out.add(new Transfer(n.queue()));
                    return finish(c, out);
                }
                case HANGUP -> {
                    if (c.outcome == null) c.outcome = "SELF_SERVED";
                    out.add(new Hangup());
                    return finish(c, out);
                }
            }
        }
        throw new IllegalStateException("Flow loops without waiting for input at " + nodeId);
    }

    private boolean fetch(Call c, String apiName) {
        try {
            c.vars.putAll(api.call(apiName, c.caller));
            return true;
        } catch (CustomerApiException e) {
            log.warn("call {}: {}", c.id, e.getMessage());
            return false;
        }
    }

    boolean agentsOpen() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(props.timezone()));
        var h = props.agentHours();
        return h.days().contains(now.getDayOfWeek()) && !now.toLocalTime().isBefore(h.open()) && now.toLocalTime().isBefore(h.close());
    }

    private String render(String template, Call c) {
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(c.vars.getOrDefault(m.group(1), "")));
        m.appendTail(sb);
        return sb.toString();
    }

    private Result finish(Call c, List<Action> out) {
        c.ended = true;
        live.remove(c.id);
        store.update(c.id, c.path, c.outcome, c.queue, clock.instant());
        return new Result(c.id, out, true);
    }

    private void save(Call c) {
        store.update(c.id, c.path, c.outcome, c.queue, null);
    }

    private Call active(String callId) {
        Call c = live.get(callId);
        if (c == null) throw new UnknownCallException(callId);
        return c;
    }

    public static class UnknownCallException extends RuntimeException {
        public UnknownCallException(String id) { super("No active call " + id); }
    }
}
