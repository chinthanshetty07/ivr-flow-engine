CREATE TABLE call_records (
    call_id      VARCHAR(36)  PRIMARY KEY,
    caller       VARCHAR(20)  NOT NULL,      -- masked: only the last 4 digits are kept
    started_at   TIMESTAMP    NOT NULL,
    ended_at     TIMESTAMP    NULL,
    path         VARCHAR(500) NOT NULL,      -- nodes visited, e.g. welcome>main_menu>balance
    outcome      VARCHAR(40)  NULL,          -- SELF_SERVED, TRANSFERRED, ABANDONED, AFTER_HOURS
    queue        VARCHAR(40)  NULL
);
CREATE INDEX idx_call_records_started ON call_records (started_at);
