package net.apeiria.monoxerhelper;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Module settings, persisted inside the target app's own storage. There is no settings UI: the
 * toggles are changed through the control notification and the overlay gestures, so this state is
 * the single source of truth.
 */
public final class Prefs {

  private static final String FILE = "monoxerhelper_state";
  private static final String KEY_SHOW = "show";
  private static final String KEY_AUTO = "auto";
  private static final String KEY_DELAY = "delay_ms";

  /** Selectable auto-answer delays (ms), cycled by a notification button. */
  static final int[] DELAY_STEPS_MS = {400, 1200, 3000};

  private static final boolean DEFAULT_SHOW = true;
  private static final boolean DEFAULT_AUTO = false;
  private static final int DEFAULT_DELAY_MS = 1200;

  /** Application context of the target app. */
  private static Context app;

  private Prefs() {}

  /** Captures the target app context; called as soon as one is available. */
  public static void attach(Context context) {
    if (context != null) {
      Context application = context.getApplicationContext();
      app = application == null ? context : application;
    }
  }

  public static boolean showAnswer() {
    return get(KEY_SHOW, DEFAULT_SHOW);
  }

  public static boolean autoAnswer() {
    return get(KEY_AUTO, DEFAULT_AUTO);
  }

  public static int delayMs() {
    try {
      return prefs().getInt(KEY_DELAY, DEFAULT_DELAY_MS);
    } catch (Throwable ignored) {
      return DEFAULT_DELAY_MS;
    }
  }

  public static void setShow(boolean value) {
    put(KEY_SHOW, value);
  }

  public static void setAuto(boolean value) {
    put(KEY_AUTO, value);
  }

  /** Advances the delay to the next step (wraps around). */
  public static void cycleDelay() {
    int current = delayMs();
    for (int i = 0; i < DELAY_STEPS_MS.length; i++) {
      if (DELAY_STEPS_MS[i] == current) {
        putInt(KEY_DELAY, DELAY_STEPS_MS[(i + 1) % DELAY_STEPS_MS.length]);
        return;
      }
    }
    putInt(KEY_DELAY, DEFAULT_DELAY_MS);
  }

  // ------------------------------------------------------------------

  private static boolean get(String key, boolean def) {
    try {
      return prefs().getBoolean(key, def);
    } catch (Throwable ignored) {
      return def;
    }
  }

  private static void put(String key, boolean value) {
    try {
      prefs().edit().putBoolean(key, value).apply();
    } catch (Throwable ignored) {
    }
  }

  private static void putInt(String key, int value) {
    try {
      prefs().edit().putInt(key, value).apply();
    } catch (Throwable ignored) {
    }
  }

  private static SharedPreferences prefs() {
    return app.getSharedPreferences(FILE, Context.MODE_PRIVATE);
  }
}
