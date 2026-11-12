package io.github.lwjlw.bilibtr.proxy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** Fake players exercise the same reflection boundary as the injected IJK player. */
public class PlaySpeedCapabilityTest {
    private final Map<Field, Object> saved = new LinkedHashMap<>();

    @Before public void setUp() throws Exception {
        set(ProxyConfig.class, "lastLoad", System.currentTimeMillis() + 60_000L);
        set(ProxyConfig.class, "playbackSpeed", 0f);
        set(PlaySpeed.class, "player", null);
        set(PlaySpeed.class, "override", 0f);
        set(PlaySpeed.class, "appSpeed", 1f);
        set(PlaySpeed.class, "followApp", false);
    }

    @After public void tearDown() throws Exception {
        for (Map.Entry<Field, Object> entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
    }

    @Test public void threeTimesPlayerClampsFourTimesAndOffersTwoAndThree() {
        Player player = new Player(3f);
        PlaySpeed.capture(player);
        PlaySpeed.setOverride(4f);
        assertEquals(4f, PlaySpeed.requestedSpeed(), 0f);
        assertEquals(3f, player.requested, 0f);
        assertEquals(3f, PlaySpeed.override(), 0f);
        assertEquals(3f, PlaySpeed.actualSpeed(), 0f);
        assertArrayEquals(new float[]{2f, 3f}, PlaySpeed.buttonSpeeds(), 0f);
    }

    @Test public void twoTimesPlayerOffersOnePointFiveAndTwo() {
        Player player = new Player(2f);
        PlaySpeed.capture(player);
        PlaySpeed.setOverride(3f);
        assertEquals(2f, player.requested, 0f);
        assertArrayEquals(new float[]{1.5f, 2f}, PlaySpeed.buttonSpeeds(), 0f);
    }

    @Test public void capablePlayerKeepsFourTimesOption() {
        Player player = new Player(4f);
        PlaySpeed.capture(player);
        PlaySpeed.setOverride(4f);
        assertEquals(4f, player.requested, 0f);
        assertArrayEquals(new float[]{3f, 4f}, PlaySpeed.buttonSpeeds(), 0f);
    }

    @Test public void unknownMaximumPreservesOptionsAndSupportsNoArgumentSpeedGetter() {
        NoMaximumPlayer player = new NoMaximumPlayer();
        PlaySpeed.capture(player);
        PlaySpeed.setOverride(4f);
        assertEquals(-1f, PlaySpeed.maxSpeed(), 0f);
        assertEquals(4f, PlaySpeed.actualSpeed(), 0f);
        assertArrayEquals(new float[]{3f, 4f}, PlaySpeed.buttonSpeeds(), 0f);
    }

    @Test public void invalidMaximumReadingsAreUnknownRatherThanFakeLimits() {
        Player player = new Player(3f);
        PlaySpeed.capture(player);
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1f, 0.49f, 20.01f}) {
            player.maximum = invalid;
            assertEquals(-1f, PlaySpeed.maxSpeed(), 0f);
            assertArrayEquals(new float[]{3f, 4f}, PlaySpeed.buttonSpeeds(), 0f);
        }
    }

    @Test public void configuredSpeedAlsoRespectsTheKnownMaximum() throws Exception {
        PlaySpeed.capture(new Player(3f));
        set(ProxyConfig.class, "playbackSpeed", 4f);
        assertEquals(3f, PlaySpeed.effective(1f), 0f);
        assertEquals(4f, PlaySpeed.requestedSpeed(), 0f);
        assertEquals(3f, PlaySpeed.targetSpeed(), 0f);
    }

    @Test public void followRestoresNativeSpeedEvenWithTestSpeedConfigured() throws Exception {
        Player player = new Player(3f);
        PlaySpeed.capture(player);
        PlaySpeed.noteAppSpeed(1.5f);
        set(ProxyConfig.class, "playbackSpeed", 4f);
        PlaySpeed.setOverride(4f);
        PlaySpeed.setOverride(0f);
        assertEquals(1.5f, player.requested, 0f);
        assertEquals(1.5f, PlaySpeed.actualSpeed(), 0f);
        assertEquals(0f, PlaySpeed.override(), 0f);
    }

    @Test public void changedCapabilityIsReadAgainForTheSamePlayer() {
        Player player = new Player(3f);
        PlaySpeed.capture(player);
        PlaySpeed.setOverride(3f);
        player.maximum = 2f;
        PlaySpeed.applyNow();
        assertEquals(2f, player.requested, 0f);
        assertArrayEquals(new float[]{1.5f, 2f}, PlaySpeed.buttonSpeeds(), 0f);
    }

    @Test public void actualReadingAndStatsRemainDistinctFromRequestedSpeed() throws Exception {
        Player player = new Player(3f) {
            @Override public void setSpeed(float value) {
                super.setSpeed(value);
                actual = 2.5f;
            }
        };
        PlaySpeed.capture(player);
        PlaySpeed.setOverride(4f);
        assertEquals(3f, PlaySpeed.maxSpeed(), 0f); // Private getter in a superclass.
        assertEquals(2.5f, PlaySpeed.actualSpeed(), 0f);
        assertEquals(2.5f, PlaySpeed.activeSpeed(), 0f);
        Method build = StatsPusher.class.getDeclaredMethod("buildJson", long.class);
        build.setAccessible(true);
        String stats = (String) build.invoke(null, 0L);
        assertTrue(stats.contains("\"requestedSpeed\":4.0"));
        assertTrue(stats.contains("\"targetSpeed\":3.0"));
        assertTrue(stats.contains("\"actualSpeed\":2.5"));
        assertTrue(stats.contains("\"maxSpeed\":3.0"));
    }

    @Test public void missingOrInvalidActualReadingsStayUnknown() {
        PlaySpeed.capture(new SetterOnlyPlayer());
        PlaySpeed.setOverride(4f);
        assertEquals(-1f, PlaySpeed.actualSpeed(), 0f);
        assertEquals(4f, PlaySpeed.targetSpeed(), 0f);
        Player player = new Player(3f);
        player.actual = Float.NaN;
        PlaySpeed.capture(player);
        assertEquals(-1f, PlaySpeed.actualSpeed(), 0f);
    }

    @Test public void invalidRequestsNeverReachThePlayer() {
        Player player = new Player(3f);
        PlaySpeed.capture(player);
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1f, 0.49f, 20.01f}) {
            PlaySpeed.setOverride(invalid);
        }
        assertEquals(0, player.calls);
        assertEquals(1f, PlaySpeed.requestedSpeed(), 0f);
    }

    public static class Player {
        float maximum;
        float actual = 1f;
        float requested;
        int calls;
        Player(float maximum) { this.maximum = maximum; }
        @SuppressWarnings("unused")
        private float getPlayerPropertyFloat(int property, float fallback) {
            return property == 10010 ? maximum : fallback;
        }
        public float getSpeed(float fallback) { return actual; }
        public void setSpeed(float value) {
            calls++;
            requested = value;
            actual = Math.min(value, maximum);
        }
    }

    public static class NoMaximumPlayer {
        private float actual = 1f;
        public float getSpeed() { return actual; }
        public void setSpeed(float value) { actual = value; }
    }

    public static class SetterOnlyPlayer {
        public void setSpeed(float value) { }
    }

    private void set(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        if (!saved.containsKey(field)) saved.put(field, field.get(null));
        field.set(null, value);
    }
}
