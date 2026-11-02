package in.chinthanshetty.ivr;

/** What the telephony platform should do next on the call. */
public sealed interface Action {
    record Say(String text) implements Action {}
    record Collect(int maxDigits, int timeoutSeconds) implements Action {}
    record Transfer(String queue) implements Action {}
    record Hangup() implements Action {}
}
