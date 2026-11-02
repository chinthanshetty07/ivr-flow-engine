# IVR Flow Engine

[![CI](https://github.com/chinthanshetty07/ivr-flow-engine/actions/workflows/ci.yml/badge.svg)](https://github.com/chinthanshetty07/ivr-flow-engine/actions/workflows/ci.yml)

A Spring Boot service that runs IVR (interactive voice response) call flows: menus driven by key presses (DTMF),
retries on wrong or no input, look-ups in a customer system over REST, transfer to an agent queue during business
hours, and a call detail record for every call in SQL.

Built as an independent learning project after applying for a Java trainee role that works on IVR applications.
It is not affiliated with any company, it doesn't talk to a real telephony platform (Avaya or otherwise), and the
"bank" in the sample flow is made up.

## How it works

```
telephony platform  --POST /calls, /digits, /no-input, /hangup-->  CallEngine  --REST-->  customer system
                    <------- actions: Say / Collect / Transfer / Hangup --------
                                                   |
                                                   +--> call_records (MySQL / H2 in MySQL mode)
```

The flow lives in YAML (`src/main/resources/flows/banking.yml`), not in code, so a new menu option is a config
change. Node types:

| Type | Behaviour |
|---|---|
| `prompt` | Speak and continue |
| `menu` | Speak, wait for one key; wrong key or silence apologises and repeats; after `max-retries` goes to `on-fail` |
| `lookup` / `action` | Call the customer API, fill `{variables}` into the prompt; on failure go to `on-error` |
| `transfer` | Hand the call to an agent queue, or to `after-hours` outside Mon–Sat 9:00–18:00 IST |
| `hangup` | End the call |

The flow is **validated when the app starts**: dangling references, a menu without a fallback, an API node
without an error path, multi-digit menu keys, or no way to hang up all fail the deploy instead of a live call.

## The sample flow (a small banking IVR)

- **1, Balance:** looks up the balance and reads it out.
- **2, Block card:** reads back the last 4 digits and asks the caller to confirm. **Nothing is blocked until they
  press 1**; pressing 2 goes back.
- **0, Agent:** transfers to the `support` queue, or plays the opening hours after hours.
- **Wrong key or silence:** "Sorry, that's not a valid option" or "I didn't hear anything", then the menu again.
  After three failures the caller goes to an agent rather than being stuck.

## Things a caller would notice if they went wrong

- **The customer system is slow or down.** The REST client times out after 2 seconds. The caller hears "Sorry,
  we can't fetch your details right now" and is transferred, instead of sitting in silence. A test runs this against a
  real local HTTP server that takes 3 seconds to reply.
- **Card details can't be fetched.** The confirmation menu is skipped entirely, because asking "block the card
  ending ____?" without the number would be worse than transferring.
- **The caller hangs up mid-menu.** The call is recorded as `ABANDONED`.
- **Privacy.** Only the last 4 digits of the caller's number are stored or logged.

Every call ends with one outcome in `call_records`: `SELF_SERVED`, `TRANSFERRED`, `AFTER_HOURS` or `ABANDONED`,
plus the path taken (e.g. `welcome>main_menu>balance>after_service>goodbye>hangup`). `GET /calls/stats` counts
calls by outcome.

## Run

```bash
mvn verify                 # 22 tests
mvn spring-boot:run        # H2 in MySQL mode; set DB_URL/DB_USER/DB_PASSWORD for MySQL
```

```bash
curl -X POST localhost:8080/calls -H 'content-type: application/json' -d '{"caller":"9876543210"}'
curl -X POST localhost:8080/calls/<id>/digits -H 'content-type: application/json' -d '{"digits":"1"}'
curl localhost:8080/calls/<id>
```

Set `CUSTOMER_API_URL` to point the look-ups at a customer system; without one, look-ups fail fast and calls take
the apologise-and-transfer path, which is the behaviour you want anyway.

## Tests (22)

Call flows end to end (welcome, balance, card block with confirmation and going back, invalid keys, no input,
retry limit to agent, API failure fallback, missing card details, after hours, Saturday open and Sunday closed,
abandoned calls, masking); flow validation (5 broken flows rejected at load); the REST client against a real HTTP
server (JSON, 500 errors, timeout under 2.5 s); and the HTTP API with MockMvc (validation, 404 for unknown calls).

## What a real IVR adds

Speech recognition and text-to-speech, VXML or a vendor SDK (e.g. Avaya Experience Portal) instead of these webhooks,
caller authentication (PIN or OTP) before account actions, queue wait-time announcements, and call recording consent.

Built with Claude Code; the tests are how I checked what it wrote.
