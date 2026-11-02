package in.chinthanshetty.ivr;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Call detail records. Only the last 4 digits of the caller's number are stored. */
@Repository
public class CallStore {
    private final JdbcClient db;

    public CallStore(JdbcClient db) { this.db = db; }

    public static String mask(String number) {
        String digits = number == null ? "" : number.replaceAll("\\D", "");
        return digits.length() <= 4 ? "****" : "*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
    }

    public void start(String callId, String caller, Instant at) {
        db.sql("INSERT INTO call_records (call_id, caller, started_at, path) VALUES (?, ?, ?, '')")
                .params(callId, mask(caller), java.sql.Timestamp.from(at)).update();
    }

    public void update(String callId, List<String> path, String outcome, String queue, Instant endedAt) {
        db.sql("UPDATE call_records SET path = ?, outcome = ?, queue = ?, ended_at = ? WHERE call_id = ?")
                .params(String.join(">", path), outcome, queue, endedAt == null ? null : java.sql.Timestamp.from(endedAt), callId)
                .update();
    }

    public Optional<Map<String, Object>> find(String callId) {
        return db.sql("SELECT call_id, caller, path, outcome, queue, started_at, ended_at FROM call_records WHERE call_id = ?")
                .param(callId).query().listOfRows().stream().findFirst();
    }

    public Map<String, Integer> outcomeCounts() {
        Map<String, Integer> out = new java.util.TreeMap<>();
        db.sql("SELECT COALESCE(outcome, 'IN_PROGRESS') o, COUNT(*) n FROM call_records GROUP BY COALESCE(outcome, 'IN_PROGRESS')")
                .query((rs, i) -> Map.entry(rs.getString("o"), rs.getInt("n"))).list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }
}
