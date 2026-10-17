import com.portalframe.SleepRules;

public class SleepRulesTest {
  private static int checks;

  static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    checks++;
  }

  static void rejects(String value) {
    try {
      SleepRules.minutes(value);
      throw new AssertionError("Accepted invalid time");
    } catch (IllegalArgumentException expected) {
      checks++;
    }
  }

  public static void main(String[] args) {
    check(SleepRules.minutes("00:00") == 0, "Midnight parsing");
    check(SleepRules.minutes("07:00") == 420, "Morning parsing");
    check(SleepRules.minutes("23:59") == 1439, "Last minute of day");
    rejects("24:00");
    rejects("7:00");
    rejects("12:60");
    rejects("");
    rejects(null);
    check(SleepRules.inWindow(0, 0, 420), "Midnight included");
    check(SleepRules.inWindow(419, 0, 420), "Last sleeping minute included");
    check(!SleepRules.inWindow(420, 0, 420), "7 AM excluded");
    check(!SleepRules.inWindow(1439, 0, 420), "Evening excluded");
    check(SleepRules.inWindow(1320, 1320, 420), "Cross-midnight start included");
    check(SleepRules.inWindow(1439, 1320, 420), "Before midnight included");
    check(SleepRules.inWindow(0, 1320, 420), "After midnight included");
    check(SleepRules.inWindow(419, 1320, 420), "Cross-midnight final minute");
    check(!SleepRules.inWindow(420, 1320, 420), "Cross-midnight end excluded");
    check(!SleepRules.inWindow(1319, 1320, 420), "Before start excluded");
    check(SleepRules.inWindow(600, 540, 1020), "Daytime schedule works");
    check(!SleepRules.inWindow(1020, 540, 1020), "Daytime end excluded");
    check(!SleepRules.inWindow(0, 0, 0), "Equal times are not a 24-hour schedule");
    check(!SleepRules.inWindow(-1, 0, 420), "Invalid minute rejected");
    check(!SleepRules.inWindow(0, 0, 1440), "Invalid end rejected");

    SleepRules.Darkness d = new SleepRules.Darkness();
    check(!d.update(0, false, 0, 10), "Darkness outside schedule must not activate");
    check(!d.update(0, true, Float.NaN, 10), "No sensor reading must not mean darkness");
    check(!d.update(0, true, 104, 10), "Bright room within schedule must not activate");
    check(!d.update(0, true, 10, 10), "Threshold entry starts a delay");
    check(!d.update(4999, true, 9, 10), "No early activation");
    check(d.update(5000, true, 9, 10), "Five seconds of darkness activates");
    check(d.update(6000, true, 14, 10), "Hysteresis avoids flicker near threshold");
    check(d.update(7000, true, 15, 10), "Bright room starts exit delay");
    check(d.update(9999, true, 16, 10), "No early exit");
    check(!d.update(10000, true, 16, 10), "Three seconds of brightness exits");

    d.reset();
    check(!d.update(0, true, 0, 10), "Restart entry");
    check(!d.update(4000, true, 20, 10), "Brief darkness is cancelled by light");
    check(!d.update(7000, true, 0, 10), "New dark interval starts afresh");
    check(!d.update(11999, true, 0, 10), "Interrupted interval cannot enter early");
    check(d.update(12000, true, 0, 10), "New stable darkness activates");
    check(
        d.update(13000, true, Float.NaN, 10), "Retain current mode while awaiting a fresh sample");
    check(!d.update(14000, false, 0, 10), "End of schedule exits immediately even in darkness");
    check(!d.pending(), "Leaving schedule clears pending transitions");
    check(!d.update(15000, true, 0, Double.NaN), "Invalid threshold cannot activate");
    check(!d.update(16000, true, -1, 10), "Negative lux cannot activate");
    System.out.println(
        checks + " sleep-schedule, light-gating, hysteresis and debounce assertions passed");
  }
}
