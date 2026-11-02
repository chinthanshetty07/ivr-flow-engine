package in.chinthanshetty.ivr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.chinthanshetty.ivr.Action.Collect;
import in.chinthanshetty.ivr.Action.Hangup;
import in.chinthanshetty.ivr.Action.Say;
import in.chinthanshetty.ivr.Action.Transfer;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestSupport.class)
class CallFlowTest {
    @Autowired CallEngine engine;
    @Autowired CallStore store;
    @Autowired TestSupport.MutableClock clock;
    @Autowired TestSupport.FakeCustomerApi api;

    static final String CALLER = "+919876543210";

    @BeforeEach
    void reset() {
        clock.set("2026-11-03T06:30:00Z");        // Tuesday noon IST: agents open
        api.failing.clear();
        api.blocked.clear();
    }

    static List<String> said(CallEngine.Result r) {
        return r.actions().stream().filter(a -> a instanceof Say).map(a -> ((Say) a).text()).toList();
    }

    @Test
    void callStartsWithWelcomeThenWaitsForAMenuChoice() {
        var r = engine.start(CALLER);
        assertThat(said(r).get(0)).isEqualTo("Welcome to Example Bank.");
        assertThat(said(r).get(1)).startsWith("For your account balance, press 1");
        assertThat(r.actions().get(r.actions().size() - 1)).isEqualTo(new Collect(1, 5));
        assertThat(r.ended()).isFalse();
    }

    @Test
    void balanceIsFetchedFromTheCustomerApiAndRead() {
        var call = engine.start(CALLER);
        var r = engine.digits(call.callId(), "1");
        assertThat(said(r)).contains("Your available balance is 12,450 rupees.");
        var end = engine.digits(call.callId(), "2");
        assertThat(said(end)).contains("Thank you for calling Example Bank. Goodbye.");
        assertThat(end.actions()).endsWith(new Hangup());
        var cdr = store.find(call.callId()).orElseThrow();
        assertThat(cdr.get("outcome")).isEqualTo("SELF_SERVED");
        assertThat(cdr.get("path")).isEqualTo("welcome>main_menu>balance>after_service>goodbye>hangup");
    }

    @Test
    void cardBlockConfirmsWithTheLast4DigitsBeforeActing() {
        var call = engine.start(CALLER);
        var confirm = engine.digits(call.callId(), "2");
        assertThat(said(confirm)).contains("To block the card ending 4821, press 1. To go back, press 2.");
        assertThat(api.blocked).isEmpty();                         // nothing happens until the caller confirms
        var done = engine.digits(call.callId(), "1");
        assertThat(said(done)).contains("Your card is blocked. A replacement will reach you in 7 working days.");
        assertThat(api.blocked).containsExactly(CALLER);
    }

    @Test
    void goingBackFromConfirmationDoesNotBlockTheCard() {
        var call = engine.start(CALLER);
        engine.digits(call.callId(), "2");
        var back = engine.digits(call.callId(), "2");
        assertThat(said(back).get(0)).startsWith("For your account balance");
        assertThat(api.blocked).isEmpty();
    }

    @Test
    void invalidKeyApologisesAndRepeatsTheMenu() {
        var call = engine.start(CALLER);
        var r = engine.digits(call.callId(), "7");
        assertThat(said(r)).containsExactly("Sorry, that's not a valid option.",
                "For your account balance, press 1. To block a lost card, press 2. To speak to an agent, press 0.");
    }

    @Test
    void tooManyBadAttemptsSendsTheCallerToAnAgent() {
        var call = engine.start(CALLER);
        engine.noInput(call.callId());
        engine.digits(call.callId(), "9");
        var r = engine.noInput(call.callId());                     // 3rd failure, max-retries is 2
        assertThat(r.actions()).endsWith(new Transfer("support"));
        assertThat(r.ended()).isTrue();
        assertThat(store.find(call.callId()).orElseThrow().get("outcome")).isEqualTo("TRANSFERRED");
    }

    @Test
    void customerApiFailureApologisesAndTransfersInsteadOfLeavingSilence() {
        api.failing.add("balance");
        var call = engine.start(CALLER);
        var r = engine.digits(call.callId(), "1");
        assertThat(said(r)).contains("Sorry, we can't fetch your details right now. Let me connect you to an agent.");
        assertThat(r.actions()).endsWith(new Transfer("support"));
    }

    @Test
    void cardDetailsUnavailableSkipsTheConfirmationMenu() {
        api.failing.add("card");
        var call = engine.start(CALLER);
        var r = engine.digits(call.callId(), "2");
        assertThat(said(r)).noneMatch(s -> s.contains("card ending"));
        assertThat(r.actions()).endsWith(new Transfer("support"));
    }

    @Test
    void afterHoursTheAgentOptionPlaysOpeningHoursAndHangsUp() {
        clock.set("2026-11-03T14:00:00Z");                         // Tuesday 19:30 IST
        var call = engine.start(CALLER);
        var r = engine.digits(call.callId(), "0");
        assertThat(said(r)).contains("Our agents are available Monday to Saturday, 9 am to 6 pm. Please call back then. Goodbye.");
        assertThat(r.actions()).endsWith(new Hangup()).doesNotContain(new Transfer("support"));
        assertThat(store.find(call.callId()).orElseThrow().get("outcome")).isEqualTo("AFTER_HOURS");
    }

    @Test
    void sundayIsClosedEvenAtNoon() {
        clock.set("2026-11-08T06:30:00Z");                         // Sunday 12:00 IST
        assertThat(engine.agentsOpen()).isFalse();
        clock.set("2026-11-07T06:30:00Z");                         // Saturday 12:00 IST
        assertThat(engine.agentsOpen()).isTrue();
    }

    @Test
    void callerHangingUpMidMenuIsRecordedAsAbandoned() {
        var call = engine.start(CALLER);
        engine.hangup(call.callId());
        assertThat(store.find(call.callId()).orElseThrow().get("outcome")).isEqualTo("ABANDONED");
        assertThatThrownBy(() -> engine.digits(call.callId(), "1")).isInstanceOf(CallEngine.UnknownCallException.class);
    }

    @Test
    void callerNumberIsMaskedInTheCallRecord() {
        var call = engine.start(CALLER);
        assertThat(store.find(call.callId()).orElseThrow().get("caller")).isEqualTo("********3210");
        assertThat(CallStore.mask("12")).isEqualTo("****");
    }
}
