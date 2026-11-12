package io.github.lwjlw.bilibtr.recon.probes;

import java.lang.reflect.Method;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.libxposed.api.XposedInterface;

import io.github.lwjlw.bilibtr.proxy.PlaySpeed;
import io.github.lwjlw.bilibtr.proxy.ProxyConfig;
import io.github.lwjlw.bilibtr.recon.Hooks;
import io.github.lwjlw.bilibtr.recon.Recon;
import io.github.lwjlw.bilibtr.recon.Reflect;

/**
 * **播放页悬浮球：根据播放器上限提供倍速快捷档位**。
 *
 * ## 交互
 * - **点一下画面才出现**，几秒不操作**自动隐藏**（和播放器自带控件一个脾气）；
 * - **可以拖动**（按住球拖到任意位置）；
 * - 三个按钮：两个可用倍速和 `跟`（跟随 B站），当前选中的高亮成绿色；
 * - 选倍速档位 = **强制覆盖**；选"跟" = **设回 B站 自己的倍速**（不是"什么都不做"）；
 * - App 里有总开关（`btr.conf` 的 `ball=`）；**关掉后已经画出来的球会立刻消失**。
 *
 * ## 实现要点
 * - 球画在 **B站 进程里**（挂到 Activity 的 `android.R.id.content`），不需要悬浮窗权限；
 * - 只有**真的在播放**时才响应（`PlaySpeed.hasPlayer()`），否则首页滑动也会冒球；
 * - 触摸靠 hook `Activity.dispatchTouchEvent`，**只观察不消费**，不抢播放器的触摸。
 */
public final class SpeedBallProbe {

    private static final long AUTO_HIDE_MS = 4000L;
    /** 球的 tag（任意唯一 int 即可）。 */
    private static final int TAG_ID = 0x7f0b7001;

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static Runnable hideTask;

    private SpeedBallProbe() {
    }

    public static void install(XposedInterface x, ClassLoader cl) {
        Class<?> activity = Reflect.load(cl, "android.app.Activity");
        if (activity == null) {
            Recon.note("PROBE", "SpeedBallProbe: 找不到 android.app.Activity，跳过");
            return;
        }
        // ① Activity 出现时准备好球（先隐藏）
        for (Method m : Reflect.declaredNamed(activity, "onResume", "onCreate")) {
            Hooks.install(x, m, "ball-life-" + m.getName(), chain -> {
                Object r = chain.proceed();
                try {
                    if (chain.getThisObject() instanceof Activity) {
                        Activity act = (Activity) chain.getThisObject();
                        if (ProxyConfig.ballEnabled()) {
                            ensureBall(act);
                        } else {
                            removeBall(act);     // 开关关了就撤掉
                        }
                    }
                } catch (Throwable ignored) {
                }
                return r;
            });
        }
        // ② 触摸：点一下画面就显示（只观察，不消费事件）
        for (Method m : Reflect.declaredNamed(activity, "dispatchTouchEvent")) {
            Hooks.install(x, m, "ball-touch", chain -> {
                try {
                    Object a = chain.getArgs().isEmpty() ? null : chain.getArg(0);
                    if (a instanceof MotionEvent
                            && ((MotionEvent) a).getActionMasked() == MotionEvent.ACTION_DOWN
                            && chain.getThisObject() instanceof Activity) {
                        Activity act = (Activity) chain.getThisObject();
                        boolean on = ProxyConfig.ballEnabled();
                        // 诊断用：每次"开关状态变了"记一条，方便确认开关是否真的传到注入侧
                        if (Recon.first("BALL:TOUCH", String.valueOf(on))) {
                            Recon.note("BALL:TOUCH", "触摸时 ballEnabled=" + on
                                    + " 配置{LIVE=" + io.github.lwjlw.bilibtr.proxy.ConfigServer.live("ball") + "}");
                        }
                        if (!on) {
                            removeBall(act);        // ★ 关掉开关后，已画出来的球要立刻消失
                        } else if (PlaySpeed.hasPlayer()) {
                            showBall(act);
                        }
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
        }
        // ③ 抓**真播放器**实例（IjkMediaPlayer）—— 悬浮球点 3x/4x 时要主动对它 setSpeed。
        //    踩过：`IjkMediaPlayerItemClient` 是代理端，**没有 setSpeed(float)**。
        Class<?> player = Reflect.load(cl, "tv.danmaku.ijk.media.player.IjkMediaPlayer");
        int cap = 0;
        if (player != null) {
            for (Method m : Reflect.declaredNamed(player,
                    "setDataSource", "setSpeed", "setOption", "start", "prepareAsync")) {
                Hooks.install(x, m, "ball-cap-" + m.getName(), chain -> {
                    try {
                        PlaySpeed.capture(chain.getThisObject());
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
                cap++;
            }
        }
        Recon.note("PROBE", "SpeedBallProbe: 已安装（开关=ball，"
                + (ProxyConfig.ballEnabled() ? "当前开启" : "当前关闭")
                + "，抓播放器入口 " + cap + " 个）");
    }

    // ------------------------------------------------------------------ 视图

    private static ViewGroup contentOf(Activity act) {
        View root = act.findViewById(android.R.id.content);
        return root instanceof ViewGroup ? (ViewGroup) root : null;
    }

    private static LinearLayout ballOf(Activity act) {
        ViewGroup c = contentOf(act);
        if (c == null) return null;
        View v = c.findViewWithTag(TAG_ID);
        return v instanceof LinearLayout ? (LinearLayout) v : null;
    }

    private static void ensureBall(Activity act) {
        try {
            ViewGroup content = contentOf(act);
            if (content == null || content.findViewWithTag(TAG_ID) != null) return;

            LinearLayout bar = new LinearLayout(act);
            bar.setTag(TAG_ID);
            bar.setOrientation(LinearLayout.VERTICAL);
            bar.setVisibility(View.GONE);
            float[] speeds = PlaySpeed.buttonSpeeds();
            bar.addView(chip(act, bar, speedLabel(speeds[0]), speeds[0]));
            bar.addView(chip(act, bar, speedLabel(speeds[1]), speeds[1]));
            bar.addView(chip(act, bar, "跟", 0f));
            TextView limit = new TextView(act);
            limit.setTextColor(Color.LTGRAY);
            limit.setTextSize(10f);
            limit.setGravity(Gravity.CENTER);
            limit.setPadding(dp(act, 3), dp(act, 4), dp(act, 3), dp(act, 4));
            limit.setBackgroundColor(0xCC222222);
            bar.addView(limit);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            lp.rightMargin = dp(act, 12);
            content.addView(bar, lp);
            Recon.note("BALL:ADD", "已挂上悬浮球 @" + act.getClass().getSimpleName());
        } catch (Throwable t) {
            Recon.note("BALL:ERR", "挂悬浮球失败：" + t);
        }
    }

    /** 开关关掉时把球撤掉（不只是隐藏）。 */
    private static void removeBall(Activity act) {
        try {
            LinearLayout bar = ballOf(act);
            if (bar != null) {
                bar.setVisibility(View.GONE);
                ViewGroup c = contentOf(act);
                if (c != null) c.removeView(bar);
                Recon.note("BALL:REMOVE", "已移除悬浮球 @" + act.getClass().getSimpleName());
            } else {
                Recon.note("BALL:REMOVE", "想移除但没找到球 @" + act.getClass().getSimpleName());
            }
        } catch (Throwable t) {
            Recon.note("BALL:ERR", "移除失败：" + t);
        }
    }

    private static TextView chip(final Activity act, final LinearLayout bar,
                                 final String label, final float speed) {
        final TextView tv = new TextView(act);
        tv.setText(label);
        tv.setTag(speed);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13f);
        tv.setGravity(Gravity.CENTER);
        int pad = dp(act, 10);
        tv.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(act, 6);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(v -> {
            Object selected = tv.getTag();
            if (selected instanceof Number) PlaySpeed.setOverride(((Number) selected).floatValue());
            refresh(act);
            scheduleHide(act);
        });
        // 拖动（和容器共用同一套逻辑）
        tv.setOnTouchListener(new DragTouch(act, bar, tv));
        return tv;
    }

    /** 三个按钮的高亮：选中绿、未选中半透明黑。 */
    private static void refresh(Activity act) {
        try {
            LinearLayout bar = ballOf(act);
            if (bar == null) return;
            float cur = PlaySpeed.override();
            float[] speeds = PlaySpeed.buttonSpeeds();
            float maximum = PlaySpeed.maxSpeed();
            String limit = maximum > 0f ? "最高 " + speedLabel(maximum) : "上限未读取";
            bar.setContentDescription("播放倍速，" + limit);
            for (int i = 0; i < Math.min(3, bar.getChildCount()); i++) {
                TextView tv = (TextView) bar.getChildAt(i);
                float s = i < 2 ? speeds[i] : 0f;
                tv.setTag(s);
                tv.setText(i < 2 ? speedLabel(s) : "跟");
                tv.setContentDescription(i < 2 ? "播放 " + speedLabel(s) + "，" + limit : "跟随 B站 倍速");
                boolean on = Math.abs(s - cur) < 0.01f;
                tv.setBackgroundColor(on ? 0xFF2E7D32 : 0xCC222222);
            }
            if (bar.getChildCount() > 3) ((TextView) bar.getChildAt(3)).setText(limit);
        } catch (Throwable ignored) {
        }
    }

    private static void showBall(final Activity act) {
        try {
            LinearLayout bar = ballOf(act);
            if (bar == null) {
                ensureBall(act);
                bar = ballOf(act);
            }
            if (bar == null) return;
            refresh(act);
            if (bar.getVisibility() != View.VISIBLE) {
                bar.setVisibility(View.VISIBLE);
                Recon.note("BALL:SHOW", "显示悬浮球 @" + act.getClass().getSimpleName());
            }
            scheduleHide(act);
        } catch (Throwable ignored) {
        }
    }

    private static void scheduleHide(final Activity act) {
        if (hideTask != null) UI.removeCallbacks(hideTask);
        hideTask = () -> {
            try {
                LinearLayout bar = ballOf(act);
                if (bar != null) bar.setVisibility(View.GONE);
            } catch (Throwable ignored) {
            }
        };
        UI.postDelayed(hideTask, AUTO_HIDE_MS);
    }

    private static int dp(Activity act, int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    private static String speedLabel(float value) {
        return (value == (int) value ? String.valueOf((int) value) : String.valueOf(value)) + "x";
    }

    /**
     * 拖动 + 点击 二合一。
     *
     * 手指移动超过 touchSlop 算拖动（移动整个球），没超过就算点击
     * （手动调 `performClick()` —— 因为 `onTouch` 返回 true 会把点击事件吞掉）。
     */
    private static final class DragTouch implements View.OnTouchListener {
        private final Activity act;
        private final LinearLayout bar;
        private final TextView chip;
        private final int slop;
        private float downRawX, downRawY;
        private int startLeft, startTop;
        private boolean dragging;

        DragTouch(Activity act, LinearLayout bar, TextView chip) {
            this.act = act;
            this.bar = bar;
            this.chip = chip;
            this.slop = ViewConfiguration.get(act).getScaledTouchSlop();
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    startLeft = bar.getLeft();
                    startTop = bar.getTop();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downRawX;
                    float dy = e.getRawY() - downRawY;
                    if (!dragging && Math.abs(dx) + Math.abs(dy) > slop) dragging = true;
                    if (dragging) moveBy(dx, dy);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!dragging) chip.performClick();
                    scheduleHide(act);
                    return true;
                default:
                    return false;
            }
        }

        private void moveBy(float dx, float dy) {
            try {
                ViewGroup parent = (ViewGroup) bar.getParent();
                if (parent == null) return;
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.rightMargin = 0;
                lp.leftMargin = clamp(startLeft + (int) dx, 0, parent.getWidth() - bar.getWidth());
                lp.topMargin = clamp(startTop + (int) dy, 0, parent.getHeight() - bar.getHeight());
                bar.setLayoutParams(lp);
            } catch (Throwable ignored) {
            }
        }

        private static int clamp(int v, int lo, int hi) {
            if (hi < lo) return lo;
            return v < lo ? lo : (v > hi ? hi : v);
        }
    }
}
