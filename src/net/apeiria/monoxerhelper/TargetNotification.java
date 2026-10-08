package net.apeiria.monoxerhelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import de.robv.android.xposed.XposedBridge;

/**
 * Persistent status-bar notification with the module's controls: toggle the answer overlay, toggle
 * auto answering, and cycle the answer delay. It is the only settings surface — the module ships no
 * app UI.
 *
 * <p>Runs entirely inside the target app's process: notifications must be posted by the app whose
 * uid owns them, and the module app cannot be reached from here on Android 11+ (package
 * visibility). The buttons are package-scoped broadcasts picked up by a runtime-registered
 * receiver, so the module exposes no manifest components.
 */
final class TargetNotification {

  private static final String ACTION_TOGGLE_SHOW = "net.apeiria.monoxerhelper.TOGGLE_SHOW";
  private static final String ACTION_TOGGLE_AUTO = "net.apeiria.monoxerhelper.TOGGLE_AUTO";
  private static final String ACTION_CYCLE_DELAY = "net.apeiria.monoxerhelper.CYCLE_DELAY";

  private static final String CHANNEL_ID = "monoxerhelper_controls";
  private static final int NOTIF_ID = 0x4D58; // "MX"
  private static final String TAG = "[MonoxerHelper] ";

  private static Context app;
  private static boolean started;

  /** The "notifications disabled" warning is logged at most once per unsuccessful streak. */
  private static boolean warnedNoNotifications;

  private TargetNotification() {}

  /**
   * Shows the notification; called as soon as the target app has a context, and again for every
   * question. The receiver is registered only once; the post itself is retried, so a notification
   * permission granted mid-session takes effect without an app restart.
   */
  static void start(Context context) {
    Context application = context.getApplicationContext();
    app = application == null ? context : application;
    try {
      if (!started) {
        started = true;
        registerReceiver();
      }
      ensureChannel();
      NotificationManager nm = app.getSystemService(NotificationManager.class);
      if (nm == null) {
        return;
      }
      if (!nm.areNotificationsEnabled()) {
        if (!warnedNoNotifications) {
          warnedNoNotifications = true;
          XposedBridge.log(
              TAG
                  + "notifications disabled for the target app; enable them via: "
                  + "adb shell pm grant com.monoxer android.permission.POST_NOTIFICATIONS");
        }
        return;
      }
      nm.notify(NOTIF_ID, build());
    } catch (Throwable t) {
      XposedBridge.log(TAG + "notif: " + t);
    }
  }

  // ------------------------------------------------------------------

  private static final BroadcastReceiver RECEIVER =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          try {
            String action = intent.getAction();
            if (ACTION_TOGGLE_SHOW.equals(action)) {
              boolean value = !Prefs.showAnswer();
              Prefs.setShow(value);
              if (value) {
                AnswerOverlay.reshow();
              } else {
                AnswerOverlay.hideIfShown();
              }
            } else if (ACTION_TOGGLE_AUTO.equals(action)) {
              boolean value = !Prefs.autoAnswer();
              Prefs.setAuto(value);
              if (!value) {
                AutoAnswer.cancelPending();
              }
            } else if (ACTION_CYCLE_DELAY.equals(action)) {
              Prefs.cycleDelay();
            } else {
              return;
            }
            repost();
          } catch (Throwable t) {
            XposedBridge.log(TAG + "button: " + t);
          }
        }
      };

  private static void registerReceiver() {
    IntentFilter filter = new IntentFilter(ACTION_TOGGLE_SHOW);
    filter.addAction(ACTION_TOGGLE_AUTO);
    filter.addAction(ACTION_CYCLE_DELAY);
    if (Build.VERSION.SDK_INT >= 33) {
      app.registerReceiver(RECEIVER, filter, Context.RECEIVER_NOT_EXPORTED);
    } else {
      app.registerReceiver(RECEIVER, filter);
    }
  }

  /** Updates the notification to the current settings (after overlay gestures / double-tap). */
  static void refresh() {
    if (started) {
      repost();
    }
  }

  private static void repost() {
    NotificationManager nm = app.getSystemService(NotificationManager.class);
    if (nm != null && nm.areNotificationsEnabled()) {
      nm.notify(NOTIF_ID, build());
    }
  }

  private static Notification build() {
    boolean show = Prefs.showAnswer();
    boolean auto = Prefs.autoAnswer();
    return new Notification.Builder(app, CHANNEL_ID)
        .setSmallIcon(smallIcon())
        .setContentTitle("MonoxerHelper")
        .setContentText(
            "Answers: "
                + (show ? "ON" : "OFF")
                + "  Auto: "
                + (auto ? "ON" : "OFF")
                + "  Delay: "
                + delayLabel())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .addAction(
            new Notification.Action.Builder(
                    0, "Answers: " + (show ? "ON" : "OFF"), pending(ACTION_TOGGLE_SHOW))
                .build())
        .addAction(
            new Notification.Action.Builder(
                    0, "Auto: " + (auto ? "ON" : "OFF"), pending(ACTION_TOGGLE_AUTO))
                .build())
        .addAction(
            new Notification.Action.Builder(
                    0, "Delay: " + delayLabel(), pending(ACTION_CYCLE_DELAY))
                .build())
        .build();
  }

  private static String delayLabel() {
    int ms = Prefs.delayMs();
    return ms < 1000 ? ms + "ms" : String.format(java.util.Locale.US, "%.1fs", ms / 1000f);
  }

  /**
   * The notification is posted under the target app's uid, so resource ids must resolve against the
   * target's resources — use its app icon instead of any module drawable.
   */
  private static int smallIcon() {
    int icon = app.getApplicationInfo().icon;
    return icon != 0 ? icon : android.R.drawable.stat_sys_download;
  }

  /** Package-scoped implicit intent: delivered to the runtime receiver registered above. */
  private static PendingIntent pending(String action) {
    Intent intent = new Intent(action).setPackage(app.getPackageName());
    return PendingIntent.getBroadcast(
        app,
        action.hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private static void ensureChannel() {
    NotificationManager nm = app.getSystemService(NotificationManager.class);
    if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
      NotificationChannel ch =
          new NotificationChannel(CHANNEL_ID, "MonoxerHelper", NotificationManager.IMPORTANCE_LOW);
      ch.setDescription("Answer overlay / auto answering controls");
      nm.createNotificationChannel(ch);
    }
  }
}
