package io.github.lwjlw.bilibtr.proxy;

import java.lang.reflect.Method;

import io.github.lwjlw.bilibtr.recon.Recon;

/**
 * **播放倍速覆盖**（悬浮球用）。
 *
 * ## 语义（用户明确要求）
 * - 悬浮球选 3x / 4x → **强制覆盖** B站 的设置；
 * - 悬浮球选"跟随" → `override = 0`，**回到 B站 自己的倍速**。
 *
 * ## 两个必须做对的地方（都踩过）
 * **① 光 hook `setSpeed` 不够**：B站 只在需要时调它（进视频调一次，之后你不动它就不再调），
 * 所以用户点 3x 时必须**主动**对当前播放器实例调一次 `setSpeed`。
 *
 * **② 选"跟随"时必须把 B站 原来的倍速设回去**（踩过）：
 * 一开始 `override = 0` 就直接 `return` 什么都不做，结果播放器就**永远停在 3x**，
 * 点"跟"也回不到 1x。所以这里要记住 **B站 自己设过的那个值**（`appSpeed`），
 * 跟随的时候把它设回去。
 */
public final class PlaySpeed {

    /** 0 = 跟随 B站；>0 = 强制覆盖。 */
    private static volatile float override = 0f;
    /** B站 自己设过的倍速（默认 1.0）；"跟随"时要设回它。 */
    private static volatile float appSpeed = 1f;
    /** 点击“跟随”后，优先恢复 B站 设置，不再使用配置里的测试倍速。 */
    private static volatile boolean followApp = false;
    /** 当前播放器实例（弱引用，避免把 Activity 拖住）。 */
    private static volatile java.lang.ref.WeakReference<Object> player = null;

    /**
     * 重入保护（**踩过**）。
     *
     * 我们主动调 `setSpeed(4)` 时，**同样会经过我们自己的 hook**，
     * 于是 `noteAppSpeed(4)` 把"我们自己设的值"记成了"B站 的值" ——
     * 结果点"跟随"恢复出来的是 4x 而不是 1x（实测日志：`倍速跟随 B站（恢复 4.0x）`）。
     */
    private static final ThreadLocal<Boolean> APPLYING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private PlaySpeed() {
    }

    public static void capture(Object p) {
        if (p == null) return;
        java.lang.ref.WeakReference<Object> old = player;
        if (old != null && old.get() == p) return;
        player = new java.lang.ref.WeakReference<>(p);
        // 带上实例身份（诊断用，2026-10-13）：B站 会创建很多播放器实例（疑似预加载池），
        // 只有把 hashCode 打出来，才能判断"我们设倍速的那个"是不是"正在播的那个"。
        Recon.note("SPEED:BALL", "已抓到播放器实例 " + p.getClass().getSimpleName()
                + "@" + Integer.toHexString(System.identityHashCode(p)));
    }

    /** 当前是否处于"我们自己正在设置倍速"的重入中（诊断/日志用）。 */
    public static boolean isApplying() {
        return APPLYING.get();
    }

    // ============ 诊断用读数（2026-10-13 加，只读，不改变任何播放行为） ============
    // 为什么不用 SpeedSession 里已有的那两个：实测本机上
    //   `IjkMediaPlayerItem.getPlayPosition()` 恒为 -1/0、
    //   `IjkMediaPlayerTracker.getBufferTime(0)` 恒为 0 —— 都测不出东西。
    // 于是改成问**真播放器自己**要（我们本来就抓着它的实例）。

    private static Object playerRef() {
        java.lang.ref.WeakReference<Object> r = player;
        return r == null ? null : r.get();
    }

    /** 当前抓到的真播放器实例（可能为 null）。给缓冲重算等诊断/调参用。 */
    public static Object player() {
        return playerRef();
    }

    /** 播放器报告的倍速上限；读取不到返回 -1，不能把默认按钮档位当成已知上限。 */
    public static float maxSpeed() {
        return maxSpeed(playerRef());
    }

    private static float maxSpeed(Object p) {
        return readSpeed(p, "getPlayerPropertyFloat", new Class<?>[]{int.class, float.class}, 10010, -1f);
    }

    /** 播放器当前实际倍速；与请求值分开，未知时返回 -1。 */
    public static float actualSpeed() {
        return actualSpeed(playerRef());
    }

    private static float actualSpeed(Object p) {
        float value = readSpeed(p, "getSpeed", new Class<?>[]{float.class}, -1f);
        if (value < 0f) value = readSpeed(p, "getSpeed", new Class<?>[0]);
        if (value < 0f) value = readSpeed(p, "getPlaybackSpeed", new Class<?>[0]);
        return value;
    }

    /** 只查询已知 getter，兼容 getter 位于父类或为 private 的播放器版本。 */
    private static float readSpeed(Object p, String name, Class<?>[] types, Object... args) {
        if (p == null) return -1f;
        int depth = 0;
        for (Class<?> c = p.getClass(); c != null && depth++ < 8; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod(name, types);
                method.setAccessible(true);
                Object value = method.invoke(p, args);
                if (value instanceof Number) {
                    float speed = ((Number) value).floatValue();
                    return validSpeed(speed) ? speed : -1f;
                }
                return -1f;
            } catch (NoSuchMethodException ignored) {
                // 继续找父类。
            } catch (Throwable ignored) {
                return -1f;
            }
        }
        return -1f;
    }

    private static boolean validSpeed(float value) {
        return Float.isFinite(value) && value >= 0.5f && value <= 20f;
    }

    private static float clampToPlayer(float value, Object p) {
        float maximum = maxSpeed(p);
        return maximum > 0f ? Math.min(value, maximum) : value;
    }

    /** 两个快捷档位按当前播放器能力显示；未知上限时保留原来的 3x / 4x。 */
    public static float[] buttonSpeeds() {
        float maximum = maxSpeed();
        if (maximum < 0f || maximum >= 4f) return new float[]{3f, 4f};
        if (maximum >= 3f) return new float[]{2f, 3f};
        if (maximum >= 2f) return new float[]{1.5f, 2f};
        return new float[]{Math.min(1f, maximum), maximum};
    }

    /** 真播放器的当前播放位置（毫秒）；读不到返回 -1。 */
    public static long currentPositionMs() {
        Object p = playerRef();
        if (p == null) return -1;
        for (String n : new String[]{"getCurrentPosition", "_getCurrentPosition"}) {
            try {
                Method m = p.getClass().getMethod(n);
                m.setAccessible(true);
                Object v = m.invoke(p);
                if (v instanceof Number) return ((Number) v).longValue();
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    /** 真播放器的缓冲百分比（0~100）；读不到返回 -1。 */
    public static long bufferPercent() {
        Object p = playerRef();
        if (p == null) return -1;
        for (String n : new String[]{"getBufferPercentage", "_getBufferPercentage"}) {
            try {
                Method m = p.getClass().getMethod(n);
                m.setAccessible(true);
                Object v = m.invoke(p);
                if (v instanceof Number) return ((Number) v).longValue();
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    /**
     * **真播放器当前缓存了多少毫秒的视频**（`getVideoCachedDuration()`）；读不到返回 -1。
     *
     * 这是"往前缓冲了多少秒"唯一可信的读数（2026-10-13 加）：
     * `IjkMediaPlayerTracker.getBufferTime()` / `getBufferPercentage()` 在本机恒为 0/-1，
     * 而这个是 IJK 暴露"已缓存时长"的标准入口 —— 用户问的"缓冲到几秒开始补"
     * 只能靠它观测。
     */
    public static long videoCachedMs() {
        return callLongByName(new String[]{"getVideoCachedDuration", "_getVideoCachedDuration"});
    }

    /** 缓存了多少毫秒的音频；读不到返回 -1。 */
    public static long audioCachedMs() {
        return callLongByName(new String[]{"getAudioCachedDuration", "_getAudioCachedDuration"});
    }

    /** 已缓存的视频字节数；读不到返回 -1。 */
    public static long videoCachedBytes() {
        return callLongByName(new String[]{"getVideoCachedBytes", "_getVideoCachedBytes"});
    }

    private static long callLongByName(String[] names) {
        Object p = playerRef();
        if (p == null) return -1;
        for (String n : names) {
            try {
                Method m = p.getClass().getMethod(n);
                m.setAccessible(true);
                Object v = m.invoke(p);
                if (v instanceof Number) return ((Number) v).longValue();
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    /**
     * 记录 **B站 自己** 传进来的倍速（在我们覆盖之前）。
     * 这就是"跟随"时要恢复的值。
     */
    public static void noteAppSpeed(float v) {
        if (APPLYING.get()) return;          // 我们自己设的不算
        if (validSpeed(v)) appSpeed = v;
    }

    public static float appSpeed() {
        return appSpeed;
    }

    public static boolean hasPlayer() {
        java.lang.ref.WeakReference<Object> r = player;
        return r != null && r.get() != null;
    }

    public static float override() {
        return validSpeed(override) ? clampToPlayer(override, playerRef()) : 0f;
    }

    /** 用户/配置请求的档位，尚未按当前播放器上限收敛。 */
    public static float requestedSpeed() {
        if (validSpeed(override)) return override;
        if (!followApp) {
            float configured = ProxyConfig.playbackSpeed();
            if (validSpeed(configured)) return configured;
        }
        return validSpeed(appSpeed) ? appSpeed : 1f;
    }

    /** 当前应当下发的档位；这不是实际读数，界面要与 actualSpeed 分开。 */
    public static float targetSpeed() {
        return clampToPlayer(requestedSpeed(), playerRef());
    }

    /**
     * **当前实际生效的倍速**（≥1）：悬浮球覆盖值 > 配置里的测试值 > B站 自己的倍速。
     *
     * 用途（2026-10-13 加）：倍速 3x 时播放器**消耗**的码率也是 3 倍，
     * 所以"够不够快"的目标（并发控制器的 target）必须跟着乘上去 ——
     * 实测漏了这一步：3x 下实际只跑到 1.97x，因为控制器还按 1x 的目标判断"够了"。
     */
    public static float activeSpeed() {
        float v = actualSpeed();
        if (v < 0f) v = targetSpeed();
        return v > 1f ? v : 1f;
    }

    /** 悬浮球点了 3x/4x/跟随 时调用。 */
    public static void setOverride(float v) {
        if (v != 0f && !validSpeed(v)) {
            Recon.note("SPEED:BALL-ERR", "忽略无效倍速 " + v);
            return;
        }
        override = v;
        followApp = v == 0f;
        Recon.note("SPEED:BALL", override <= 0f
                ? ("倍速跟随 B站（恢复 " + appSpeed + "x）")
                : ("请求倍速 " + override + "x，当前目标 " + targetSpeed() + "x"));
        applyNow();   // ★ 两种情况都要主动设一次（跟随 = 设回 B站 的值）
    }

    /**
     * 计算结果：悬浮球选了就用悬浮球的，否则用配置里的（`btr.conf` 的 `speed=`），
     * 都为 0 就返回原值（不干预）。
     */
    public static float effective(float fromApp) {
        noteAppSpeed(fromApp);
        return targetSpeed();
    }

    /** 立刻对当前播放器生效（含"跟随"时恢复 B站 的值）。 */
    public static void applyNow() {
        java.lang.ref.WeakReference<Object> r = player;
        Object p = r == null ? null : r.get();
        if (p == null) {
            Recon.note("SPEED:BALL", "还没有播放器实例，等它下次初始化时生效");
            return;
        }
        float requested = requestedSpeed();
        float v = clampToPlayer(requested, p);
        APPLYING.set(Boolean.TRUE);
        try {
            if (invokeSetSpeed(p, v)) {
                Recon.note("SPEED:BALL", "已对 " + p.getClass().getSimpleName()
                        + "@" + Integer.toHexString(System.identityHashCode(p))
                        + " 请求 " + requested + "x，下发 " + v + "x，播放器读数 " + actualSpeed(p) + "x");
            } else {
                Recon.note("SPEED:BALL-ERR", "这个播放器上没有找到 setSpeed(float)");
            }
        } finally {
            APPLYING.set(Boolean.FALSE);
        }
    }

    private static boolean invokeSetSpeed(Object p, float v) {
        for (String name : new String[]{"setSpeed", "_setSpeed", "setPlaybackSpeed"}) {
            try {
                Method m = p.getClass().getMethod(name, float.class);
                m.invoke(p, v);
                return true;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                Recon.note("SPEED:BALL-ERR", String.valueOf(t));
                return false;
            }
        }
        for (Method m : p.getClass().getDeclaredMethods()) {
            if (!m.getName().toLowerCase().contains("setspeed")) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1 || ps[0] != float.class) continue;
            try {
                m.setAccessible(true);
                m.invoke(p, v);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
}
