package net.apeiria.monoxerhelper;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fully automatic answering for the question types that can be answered programmatically; all other
 * types rely on the verdict forcing in MainHook.
 *
 * <p>Answering is scheduled with a delay ({@link Prefs#delayMs()}) and retried until the answer is
 * observed to be in. A double-tap on the overlay toggles the persisted auto-answering setting (see
 * {@link #doubleTap}).
 *
 * <p>All target-app access is reflection based (see MainHook); dispatch on fragment types uses
 * Class.isInstance against classes resolved from the target app's own ClassLoader.
 */
public final class AutoAnswer {

  private static final long RETRY_STEP_MS = 700;
  private static final int MAX_ATTEMPTS = 5;

  private static final String TAG = "[MonoxerHelper] ";
  private static final Handler MAIN = new Handler(Looper.getMainLooper());

  /** Class loader of the target app; set once per process from MainHook. */
  private static ClassLoader appCl;

  /** Cache of target-app classes used for instanceof-style dispatch (negative results cached). */
  private static final Map<String, Optional<Class<?>>> CLASS_CACHE = new HashMap<>();

  /** The currently displayed question, for double-tap answering. */
  private static WeakReference<Object> studyFrag;

  private static WeakReference<Object> miniFrag;
  private static WeakReference<Object> miniQa;

  /**
   * Generation token for scheduled answers. Bumped when a new question is shown or the pending
   * answer is cancelled (auto answering turned off), which invalidates everything still pending.
   * Only touched on the main thread.
   */
  private static int generation;

  private interface Action {
    void run(Object frag) throws Throwable;
  }

  private interface DoneCheck {
    boolean isDone(Object frag);
  }

  private AutoAnswer() {}

  /** Must be called once per process before any dispatching happens. */
  static void init(ClassLoader cl) {
    appCl = cl;
    CLASS_CACHE.clear();
  }

  /** Study mode: called for every displayed question (also remembers it for double-tap). */
  public static void onStudyQuestionShown(Object frag) {
    studyFrag = new WeakReference<>(frag);
    miniFrag = null;
    miniQa = null;
    generation++;
    if (Prefs.autoAnswer()) {
      dispatchStudy(frag, Prefs.delayMs());
    }
  }

  /** MiniTest: called for every displayed question (also remembers it for double-tap). */
  public static void onMiniTestQuestionShown(Object frag, Object qa) {
    miniFrag = new WeakReference<>(frag);
    miniQa = new WeakReference<>(qa);
    studyFrag = null;
    generation++;
    if (Prefs.autoAnswer()) {
      dispatchMini(frag, qa, Prefs.delayMs());
    }
  }

  /**
   * Double-tap on the overlay: toggles the persisted auto-answering setting (same as the
   * notification button). Turning it on answers the question currently on screen right away;
   * turning it off cancels its pending answer.
   */
  static void doubleTap() {
    boolean enable = !Prefs.autoAnswer();
    Prefs.setAuto(enable);
    TargetNotification.refresh();
    if (!enable) {
      cancelPending();
      return;
    }
    answerCurrent();
  }

  /**
   * Answers the question currently on screen right away (auto answering just turned on, via the
   * overlay double-tap or the notification button). No-op when no question is around any more.
   */
  static void answerCurrent() {
    Object frag = studyFrag == null ? null : studyFrag.get();
    if (frag != null) {
      dispatchStudy(frag, Prefs.delayMs());
      return;
    }
    frag = miniFrag == null ? null : miniFrag.get();
    Object qa = miniQa == null ? null : miniQa.get();
    if (frag != null && qa != null) {
      dispatchMini(frag, qa, Prefs.delayMs());
    }
  }

  /**
   * Cancels the scheduled or retrying answer for the current question, if any. Called whenever auto
   * answering is turned off (notification button or overlay double-tap).
   */
  static void cancelPending() {
    generation++;
  }

  // ------------------------------------------------------------------

  private static void dispatchStudy(Object frag, long delay) {
    try {
      Class<?> baseChoice = cls("com.monoxer.view.study.choice.BaseStudyChoiceFragment");
      Class<?> qamChoice = cls("com.monoxer.view.study.StudyQuestionAndMeaningChoiceFragment");
      boolean isBaseChoice = baseChoice != null && baseChoice.isInstance(frag);
      boolean isQamChoice = !isBaseChoice && qamChoice != null && qamChoice.isInstance(frag);
      if (isBaseChoice || isQamChoice) {
        Object idx =
            XposedHelpers.callMethod(
                frag, isBaseChoice ? "answerIndex" : "answerIndex$app_mainlineRelease");
        scheduleChoiceClick(frag, idx == null ? -1 : ((Number) idx).intValue(), delay);
        return;
      }
      Class<?> shuffle = cls("com.monoxer.view.study.shuffle.BaseStudyShuffleFragment");
      if (shuffle != null && shuffle.isInstance(frag)) {
        scheduleTypeIn(frag, delay);
        return;
      }
      if (frag.getClass().getName().endsWith("StudyDictationFragment")) {
        scheduleDictation(frag, delay);
      }
      // Remaining types (text/decision/speaking/formula/handwriting) cannot be
      // answered programmatically; QuestionResult.recordResult forcing covers them.
    } catch (Throwable t) {
      XposedBridge.log(t);
    }
  }

  private static void dispatchMini(Object frag, Object qa, long delay) {
    try {
      if (!frag.getClass()
          .getName()
          .equals("com.monoxer.view.miniTest.tests.MiniTestChoiceFragment")) {
        return;
      }
      Object answer = qa == null ? null : XposedHelpers.callMethod(qa, "getAnswer");
      Object textObj = answer == null ? null : XposedHelpers.callMethod(answer, "getAnswerText");
      final String ans = textObj instanceof String ? ((String) textObj).trim() : null;
      if (ans == null || ans.isEmpty()) {
        return;
      }
      post(
          frag,
          new Action() {
            @Override
            public void run(Object frag) throws Throwable {
              Object status = XposedHelpers.callMethod(frag, "getMyAnswerStatus");
              if (status != null && "Answered".equals(status.toString())) {
                return; // same guard as the app's own choice buttons: never submit twice
              }
              XposedHelpers.callMethod(frag, "onAnswer", ans);
            }
          },
          null,
          delay);
    } catch (Throwable t) {
      XposedBridge.log(t);
    }
  }

  // ------------------------------------------------------------------

  private static void scheduleChoiceClick(final Object frag, int answerIdx, long delay) {
    final List<?> nodes;
    final long answerNodeId;
    try {
      Object questionData = XposedHelpers.callMethod(frag, "getQuestionData");
      Object result =
          questionData == null ? null : XposedHelpers.callMethod(questionData, "getResult");
      nodes = result == null ? null : (List<?>) XposedHelpers.callMethod(result, "getNodes");
      Object question =
          questionData == null ? null : XposedHelpers.callMethod(questionData, "getQuestion");
      answerNodeId =
          question == null
              ? Long.MIN_VALUE
              : ((Number) XposedHelpers.callMethod(question, "getAnswerNodeId")).longValue();
    } catch (Throwable t) {
      XposedBridge.log(t);
      return;
    }
    if (nodes == null || nodes.isEmpty()) {
      return;
    }
    int target = answerIdx;
    try {
      if (target < 0
          || target >= nodes.size()
          || ((Number) XposedHelpers.getObjectField(nodes.get(target), "id")).longValue()
              != answerNodeId) {
        // Rare: the correct node is not among the choices (e.g. "none of the above"
        // is the right answer). Click anything; recordResult forcing covers the verdict.
        target = 0;
      }
    } catch (Throwable ignored) {
      target = 0;
    }
    final int idx = target;
    post(
        frag,
        new Action() {
          @Override
          public void run(Object frag) throws Throwable {
            XposedHelpers.callMethod(frag, "onClick", idx);
          }
        },
        new DoneCheck() {
          @Override
          public boolean isDone(Object frag) {
            try {
              Object questionData = XposedHelpers.callMethod(frag, "getQuestionData");
              Object result =
                  questionData == null ? null : XposedHelpers.callMethod(questionData, "getResult");
              return result == null
                  || ((Number) XposedHelpers.callMethod(result, "getChosenNodeId")).longValue()
                      != -1L;
            } catch (Throwable t) {
              return true;
            }
          }
        },
        delay);
  }

  private static void scheduleTypeIn(final Object frag, long delay) {
    String text;
    try {
      text = answerNodeText(frag);
    } catch (Throwable t) {
      XposedBridge.log(t);
      return;
    }
    if (text == null || text.isEmpty()) {
      return;
    }
    final String answer = text;
    post(
        frag,
        new Action() {
          @Override
          public void run(Object frag) throws Throwable {
            Object userInput = XposedHelpers.callMethod(frag, "getUserInput");
            if (userInput instanceof String && !((String) userInput).isEmpty()) {
              return; // user has typed something already; don't interfere
            }
            for (int i = 0; i < answer.length(); i++) {
              XposedHelpers.callMethod(frag, "onKeyInput", String.valueOf(answer.charAt(i)));
            }
            XposedHelpers.callMethod(frag, "onDone");
          }
        },
        null,
        delay);
  }

  private static void scheduleDictation(final Object frag, long delay) {
    String text;
    try {
      text = answerNodeText(frag);
    } catch (Throwable t) {
      XposedBridge.log(t);
      return;
    }
    if (text == null || text.isEmpty()) {
      return;
    }
    final String answer = text;
    post(
        frag,
        new Action() {
          @Override
          public void run(Object frag) throws Throwable {
            View view = (View) XposedHelpers.callMethod(frag, "getView");
            EditText et = findEditText(view);
            if (et == null) {
              return; // view not ready yet; the done check keeps retrying
            }
            if (et.getText().length() > 0) {
              return; // user has typed something already; don't interfere
            }
            et.setText(answer);
            XposedHelpers.callMethod(frag, "onDone");
          }
        },
        new DoneCheck() {
          @Override
          public boolean isDone(Object frag) {
            Object view = XposedHelpers.callMethod(frag, "getView");
            if (!(view instanceof View)) {
              return true; // fragment view gone: nothing left to fill in
            }
            EditText et = findEditText((View) view);
            return et != null && et.getText().length() > 0;
          }
        },
        delay);
  }

  // ------------------------------------------------------------------

  /** Reads the current question's answer node text via reflection; null if unavailable. */
  private static String answerNodeText(Object frag) throws Throwable {
    Object questionData = XposedHelpers.callMethod(frag, "getQuestionData");
    Object question =
        questionData == null ? null : XposedHelpers.callMethod(questionData, "getQuestion");
    Object answerNode = question == null ? null : XposedHelpers.callMethod(question, "answerNode");
    return answerNode == null ? null : (String) XposedHelpers.getObjectField(answerNode, "text");
  }

  /** Resolves a target-app class by name (cached; null if the app does not contain it). */
  private static Class<?> cls(String name) {
    Optional<Class<?>> cached = CLASS_CACHE.get(name);
    if (cached != null) {
      return cached.orElse(null);
    }
    Class<?> c = null;
    if (appCl != null) {
      try {
        c = appCl.loadClass(name);
      } catch (Throwable t) {
        XposedBridge.log(TAG + "class not found in target app: " + name);
      }
    }
    CLASS_CACHE.put(name, Optional.ofNullable(c));
    return c;
  }

  /**
   * Runs the action on the main thread after the delay. The done check (when present) polls until
   * the answer is observed or the attempts run out. Everything aborts silently once the generation
   * moves on (new question, or cancelled).
   */
  private static void post(
      final Object frag, final Action action, final DoneCheck done, long delay) {
    final int myGeneration = generation;
    MAIN.postDelayed(
        new Runnable() {
          int attempts = 0;

          @Override
          public void run() {
            if (myGeneration != generation) {
              return;
            }
            if (!isAlive(frag) || (done != null && safeIsDone(done, frag))) {
              return;
            }
            if (attempts++ >= MAX_ATTEMPTS) {
              return;
            }
            try {
              action.run(frag);
            } catch (Throwable t) {
              XposedBridge.log(t);
              return;
            }
            if (done != null) {
              MAIN.postDelayed(this, RETRY_STEP_MS);
            }
          }
        },
        delay);
  }

  private static boolean safeIsDone(DoneCheck done, Object frag) {
    try {
      return done.isDone(frag);
    } catch (Throwable t) {
      return true;
    }
  }

  private static boolean isAlive(Object frag) {
    try {
      return XposedHelpers.callMethod(frag, "getActivity") != null;
    } catch (Throwable t) {
      return false;
    }
  }

  private static EditText findEditText(View v) {
    if (v == null) {
      return null;
    }
    if (v instanceof EditText) {
      return (EditText) v;
    }
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) {
        EditText r = findEditText(g.getChildAt(i));
        if (r != null) {
          return r;
        }
      }
    }
    return null;
  }
}
