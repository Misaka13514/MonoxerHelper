package net.apeiria.monoxerhelper;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * MonoxerHelper — Xposed module for com.monoxer.
 *
 * <p>All interaction with the target app goes through reflection (XposedHelpers, by class/method
 * names): the module's classes are loaded by an isolated ClassLoader that cannot resolve the target
 * app's classes, so the module bytecode must not reference them directly.
 *
 * <p>Answer overlay: hooks QuestionFragment.trySetup / BaseMiniTestFragment.trySetup and shows the
 * correct answer once a question is displayed; hooks StartMiniTestFragment.onStart and shows a held
 * test's passcode on its entry page (validated client-side against a plaintext copy, so it is
 * readable locally). Auto answering: choice questions are auto-answered, shuffle (narabekae) /
 * dictation questions get the answer typed in and submitted; QuestionResult.recordResult is forced
 * to true and MiniTest submissions to full score as catch-alls. Controls live in a persistent
 * notification (see TargetNotification).
 */
public class MainHook implements IXposedHookLoadPackage {

  public static final String TARGET_PACKAGE = "com.monoxer";
  private static final String TAG = "[MonoxerHelper] ";

  /**
   * Process each fragment instance at most once, so repeated trySetup calls don't double-schedule.
   */
  static final Set<Object> PROCESSED =
      Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());

  @Override
  public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
    if (!TARGET_PACKAGE.equals(lpparam.packageName)
        || !lpparam.packageName.equals(lpparam.processName)) {
      return;
    }
    ClassLoader cl = lpparam.classLoader;
    AutoAnswer.init(cl);

    // Control notification, shown from app start until the target app is stopped. Module load
    // time relative to app startup varies, so several entry points are hooked; start() is
    // idempotent and the first one to run wins.
    XC_MethodHook appStart =
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            try {
              Context context =
                  param.thisObject instanceof Context
                      ? (Context) param.thisObject
                      : (Context) param.args[0];
              Prefs.attach(context);
              TargetNotification.start(context);
            } catch (Throwable t) {
              XposedBridge.log(t);
            }
          }
        };
    hookSafely(cl, "android.app.Application", "attach", appStart, Context.class);
    hookSafely(
        cl, "android.app.Instrumentation", "callApplicationOnCreate", appStart, Application.class);
    hookSafely(cl, "android.app.Activity", "onResume", appStart);

    // ===== Study mode: a question has just been set up =====
    hookSafely(
        cl,
        "com.monoxer.view.study.QuestionFragment",
        "trySetup",
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            try {
              Object frag = param.thisObject;
              if (frag == null) {
                return;
              }
              // Only treat this as "question shown" if this trySetup call actually ran
              // setup (setuped flipped to true). Otherwise leave it unmarked and wait
              // for the next trySetup call.
              if (!isSetupDone(frag) || !PROCESSED.add(frag)) {
                return;
              }
              Activity activity = (Activity) XposedHelpers.callMethod(frag, "getActivity");
              Prefs.attach(activity);
              TargetNotification.start(activity);
              logQuestion(frag);
              AnswerOverlay.clear();
              Object questionData = XposedHelpers.callMethod(frag, "getQuestionData");
              Object question =
                  questionData == null
                      ? null
                      : XposedHelpers.callMethod(questionData, "getQuestion");
              if (question == null) {
                return;
              }
              if (Prefs.showAnswer()) {
                Object answerNode = XposedHelpers.callMethod(question, "answerNode");
                String text =
                    answerNode == null
                        ? null
                        : (String) XposedHelpers.getObjectField(answerNode, "text");
                AnswerOverlay.show(frag, text);
              }
              AutoAnswer.onStudyQuestionShown(frag);
            } catch (Throwable t) {
              XposedBridge.log(t);
            }
          }
        });

    // ===== MiniTest: a question has just been set up =====
    hookSafely(
        cl,
        "com.monoxer.view.miniTest.tests.BaseMiniTestFragment",
        "trySetup",
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            try {
              Object frag = param.thisObject;
              if (frag == null) {
                return;
              }
              if (!isSetupDone(frag) || !PROCESSED.add(frag)) {
                return;
              }
              Activity activity = (Activity) XposedHelpers.callMethod(frag, "getActivity");
              Prefs.attach(activity);
              TargetNotification.start(activity);
              logQuestion(frag);
              AnswerOverlay.clear();
              Object qa = XposedHelpers.callMethod(frag, "getQuestion");
              Object answer = qa == null ? null : XposedHelpers.callMethod(qa, "getAnswer");
              if (answer == null) {
                return;
              }
              if (Prefs.showAnswer()) {
                String text = (String) XposedHelpers.callMethod(answer, "getAnswerText");
                AnswerOverlay.show(frag, text);
              }
              AutoAnswer.onMiniTestQuestionShown(frag, qa);
            } catch (Throwable t) {
              XposedBridge.log(t);
            }
          }
        });

    // ===== MiniTest passcode entry page: reveal the held test's passcode =====
    // The passcode is checked client-side against MiniTestHeldTest.getPasscode() (plaintext,
    // delivered by the server with the held-test info), so it can be read straight off the
    // activity and shown in the same overlay, with the same toggle and gestures as answers.
    hookSafely(
        cl,
        "com.monoxer.view.miniTest.tests.StartMiniTestFragment",
        "onStart",
        new XC_MethodHook() {
          @Override
          protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            try {
              Object frag = param.thisObject;
              Activity activity = (Activity) XposedHelpers.callMethod(frag, "getActivity");
              if (activity == null) {
                return;
              }
              Prefs.attach(activity);
              TargetNotification.start(activity);
              Object heldTest = XposedHelpers.callMethod(activity, "getHeldTest");
              String passcode =
                  heldTest == null
                      ? null
                      : (String) XposedHelpers.callMethod(heldTest, "getPasscode");
              if (passcode == null || passcode.isEmpty()) {
                return; // this held test asks for no passcode
              }
              Object testInfo = XposedHelpers.callMethod(activity, "getTestInfo");
              Object myResult =
                  testInfo == null ? null : XposedHelpers.callMethod(testInfo, "getMyResult");
              if (myResult != null) {
                return; // already started for me: the page skips passcode entry
              }
              XposedBridge.log(TAG + "mini test passcode page: overlay=" + Prefs.showAnswer());
              if (Prefs.showAnswer()) {
                AnswerOverlay.show(frag, passcode);
              } else {
                AnswerOverlay.remember(frag, passcode);
              }
            } catch (Throwable t) {
              XposedBridge.log(t);
            }
          }
        });

    // ===== Verdict catch-all: force correct (every study question type) =====
    hookSafely(
        cl,
        "com.monoxer.models.study.QuestionResult",
        "recordResult",
        boolean.class,
        new XC_MethodHook() {
          @Override
          protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            if (Prefs.autoAnswer()) {
              param.args[0] = Boolean.TRUE;
            }
          }
        });

    // ===== MiniTest catch-all: force full score on submit =====
    try {
      Class<?> resultClass = cl.loadClass("com.monoxer.models.miniTest.MiniTestQuestionResult");
      XposedHelpers.findAndHookMethod(
          cl.loadClass("com.monoxer.view.miniTest.tests.BaseMiniTestFragment"),
          "next",
          resultClass,
          new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
              try {
                if (!Prefs.autoAnswer()) {
                  return;
                }
                Object qa = XposedHelpers.callMethod(param.thisObject, "getQuestion");
                Object result = param.args[0];
                if (qa == null || result == null) {
                  return;
                }
                Object answer = XposedHelpers.callMethod(qa, "getAnswer");
                if (answer == null) {
                  return;
                }
                String answerText = (String) XposedHelpers.callMethod(answer, "getAnswerText");
                int full = (Integer) XposedHelpers.callMethod(qa, "calculateScore", answerText);
                if (((Number) XposedHelpers.callMethod(result, "getScore")).intValue() < full) {
                  XposedHelpers.callMethod(result, "setScore", full);
                }
              } catch (Throwable t) {
                XposedBridge.log(t);
              }
            }
          });
    } catch (Throwable t) {
      XposedBridge.log(TAG + "failed to hook BaseMiniTestFragment.next: " + t);
    }

    XposedBridge.log(TAG + "hooks installed for " + lpparam.processName);
  }

  private static void hookSafely(
      ClassLoader cl, String className, String methodName, Object... paramTypesAndCallback) {
    try {
      XposedHelpers.findAndHookMethod(className, cl, methodName, paramTypesAndCallback);
    } catch (Throwable t) {
      XposedBridge.log(TAG + "failed to hook " + className + "." + methodName + ": " + t);
    }
  }

  /** True once the fragment's own setup has actually run (its "setuped" flag is set). */
  private static boolean isSetupDone(Object frag) {
    try {
      Object v = XposedHelpers.callMethod(frag, "getSetuped");
      return v instanceof Boolean && (Boolean) v;
    } catch (Throwable t) {
      XposedBridge.log(TAG + "getSetuped failed on " + frag.getClass().getName() + ": " + t);
      return false;
    }
  }

  /** One line per displayed question: fragment type and effective settings. */
  private static void logQuestion(Object frag) {
    XposedBridge.log(
        TAG
            + "question "
            + frag.getClass().getSimpleName()
            + ": show="
            + Prefs.showAnswer()
            + " auto="
            + Prefs.autoAnswer());
  }
}
