package net.apeiria.monoxerhelper;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;
import de.robv.android.xposed.XposedHelpers;
import java.lang.ref.WeakReference;

/**
 * Shows the correct answer in a TextView overlaid on the Activity's DecorView.
 *
 * <p>Gestures: tap turns the overlay off (until re-enabled via the notification); double-tap
 * toggles auto answering (same persisted setting as the notification button); holding past the
 * long-press timeout and moving drags the overlay — the offset sticks for the rest of the app
 * session.
 */
public final class AnswerOverlay {

  private static final String TAG = "monoxerhelper_answer_overlay";
  private static final int BG_COLOR = 0xCC1B1B1B;
  private static final int TEXT_COLOR = 0xFFFFE066;

  /** Last shown overlay; weak so a dead activity is never pinned. */
  private static WeakReference<TextView> last;

  /** The question the overlay was last shown for, so it can be brought back on re-enable. */
  private static WeakReference<Object> lastFrag;

  private static String lastText;

  /** Dragged offset applied to every overlay so the position sticks across questions. */
  private static float offsetX;

  private static float offsetY;

  private AnswerOverlay() {}

  public static void show(Object fragment, String answerText) {
    try {
      if (answerText == null || answerText.trim().isEmpty()) {
        return;
      }
      Activity activity = getActivity(fragment);
      if (activity == null) {
        return;
      }
      View decor = activity.getWindow().getDecorView();
      if (!(decor instanceof ViewGroup)) {
        return;
      }
      ViewGroup root = (ViewGroup) decor;

      TextView tv = findOverlay(root);
      if (tv == null) {
        tv = createOverlay(activity);
        // addView must receive the finished LayoutParams, otherwise the margins
        // set on the view get replaced by fresh (zero-margin) ones.
        root.addView(tv, buildLayoutParams(activity));
      }
      tv.setText(answerText.trim());
      tv.setVisibility(View.VISIBLE);
      tv.setTranslationX(offsetX);
      tv.setTranslationY(offsetY);
      tv.bringToFront();
      last = new WeakReference<>(tv);
      lastFrag = new WeakReference<>(fragment);
      lastText = answerText.trim();
    } catch (Throwable ignored) {
    }
  }

  /** Hides the currently visible overlay, if any (notification toggle). */
  static void hideIfShown() {
    TextView tv = last == null ? null : last.get();
    if (tv != null) {
      tv.setVisibility(View.GONE);
    }
  }

  /**
   * Retires the overlay at the start of a new question: hides it and forgets the last answer, so no
   * answer from a previous question is left on screen (or brought back by {@link #reshow()}) when
   * the new question has no answer of its own.
   */
  static void clear() {
    hideIfShown();
    lastFrag = null;
    lastText = null;
  }

  /** Brings the overlay back for the question it was last shown on (notification re-enable). */
  static void reshow() {
    Object frag = lastFrag == null ? null : lastFrag.get();
    if (frag != null && lastText != null) {
      show(frag, lastText);
    }
  }

  /**
   * Records the payload for {@code fragment} without showing it, so {@link #reshow()} can reveal it
   * later while the same page is still on screen. Needed for pages with no follow-up event (e.g.
   * the MiniTest passcode page), where a mid-page re-enable would otherwise do nothing.
   */
  static void remember(Object fragment, String text) {
    lastFrag = new WeakReference<>(fragment);
    lastText = text;
  }

  // ------------------------------------------------------------------

  private static FrameLayout.LayoutParams buildLayoutParams(Activity activity) {
    FrameLayout.LayoutParams lp =
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    // Sit clearly below the status bar / display cutout.
    lp.topMargin = insetsTop(activity) + dp(activity, 12);
    return lp;
  }

  private static int insetsTop(Activity activity) {
    try {
      WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
      if (insets == null) {
        return dp(activity, 24);
      }
      if (Build.VERSION.SDK_INT >= 30) {
        return insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout())
            .top;
      }
      return insets.getSystemWindowInsetTop();
    } catch (Throwable t) {
      return dp(activity, 24);
    }
  }

  private static TextView findOverlay(ViewGroup root) {
    View v = root.findViewWithTag(TAG);
    return v instanceof TextView ? (TextView) v : null;
  }

  private static TextView createOverlay(Activity activity) {
    TextView tv = new TextView(activity);
    tv.setTag(TAG);
    tv.setBackgroundColor(BG_COLOR);
    tv.setTextColor(TEXT_COLOR);
    tv.setTypeface(Typeface.DEFAULT_BOLD);
    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
    tv.setMaxLines(4);
    int padH = dp(activity, 12);
    int padV = dp(activity, 6);
    tv.setPadding(padH, padV, padH, padV);
    tv.setElevation(dp(activity, 8));
    tv.setOnTouchListener(new GestureListener(tv));
    return tv;
  }

  /**
   * Implemented on onTouch (not click/long-click/double-tap listeners) so tap, double-tap and drag
   * share one state machine and never race each other. A tap is only committed after the double-tap
   * timeout passes without a second tap. Drag math uses raw (screen) coordinates: view-local getX()
   * shifts as the view itself moves, which would make the overlay track the finger at half speed.
   */
  private static final class GestureListener implements View.OnTouchListener {

    private final TextView view;
    private final int slop;
    private final int doubleTapTimeout;

    private final Runnable close =
        new Runnable() {
          @Override
          public void run() {
            Prefs.setShow(false); // stays off until re-enabled via the notification
            view.setVisibility(View.GONE);
            TargetNotification.refresh();
          }
        };

    private long downAt;
    private float downRawX;
    private float downRawY;
    private float baseTx;
    private float baseTy;
    private boolean dragging;
    private boolean cancelledTap;
    private long lastTapAt;

    GestureListener(TextView view) {
      this.view = view;
      this.slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
      this.doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout();
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          downAt = SystemClock.uptimeMillis();
          downRawX = event.getRawX();
          downRawY = event.getRawY();
          baseTx = view.getTranslationX();
          baseTy = view.getTranslationY();
          dragging = false;
          cancelledTap = false;
          return true;
        case MotionEvent.ACTION_MOVE:
          float dx = event.getRawX() - downRawX;
          float dy = event.getRawY() - downRawY;
          if (dragging) {
            view.setTranslationX(baseTx + dx);
            view.setTranslationY(baseTy + dy);
          } else if (dx * dx + dy * dy > slop * slop) {
            if (SystemClock.uptimeMillis() - downAt >= ViewConfiguration.getLongPressTimeout()) {
              dragging = true;
              view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            } else {
              cancelledTap = true; // moved early: no longer a tap
            }
          }
          return true;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          if (dragging) {
            offsetX = view.getTranslationX();
            offsetY = view.getTranslationY();
          } else if (!cancelledTap && event.getActionMasked() == MotionEvent.ACTION_UP) {
            handleTap(SystemClock.uptimeMillis());
          }
          return true;
        default:
          return false;
      }
    }

    private void handleTap(long now) {
      view.removeCallbacks(close);
      if (lastTapAt > 0 && now - lastTapAt <= doubleTapTimeout) {
        lastTapAt = 0;
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        AutoAnswer.doubleTap();
      } else {
        lastTapAt = now;
        view.postDelayed(close, doubleTapTimeout);
      }
    }
  }

  // ------------------------------------------------------------------

  private static int dp(Activity activity, int v) {
    return (int)
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, activity.getResources().getDisplayMetrics());
  }

  private static Activity getActivity(Object fragment) {
    try {
      Object activity = XposedHelpers.callMethod(fragment, "getActivity");
      return activity instanceof Activity ? (Activity) activity : null;
    } catch (Throwable t) {
      return null;
    }
  }
}
