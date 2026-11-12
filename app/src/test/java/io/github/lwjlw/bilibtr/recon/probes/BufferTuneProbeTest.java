package io.github.lwjlw.bilibtr.recon.probes;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.lwjlw.bilibtr.proxy.CdnRacer;
import io.github.lwjlw.bilibtr.proxy.PlaySpeed;
import io.github.lwjlw.bilibtr.proxy.ProxyConfig;

import static org.junit.Assert.*;

/** Exercises live configuration changes against a reused or replaced player. */
public class BufferTuneProbeTest {
    private final Map<Field, Object> saved = new LinkedHashMap<>();
    private Map<String, String> appRaw;
    private Map<String, String> savedRaw;
    private Player player;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        set(ProxyConfig.class, "lastLoad", System.currentTimeMillis() + 60_000L);
        set(ProxyConfig.class, "bufferSizeKb", 32768);
        set(ProxyConfig.class, "bufferTimeMs", 60_000);
        set(ProxyConfig.class, "lastWaterMs", 0);
        set(ProxyConfig.class, "firstWaterMs", 3_000);
        set(ProxyConfig.class, "nextWaterMs", 15_000);
        set(ProxyConfig.class, "maxBufferS", 180);
        // Both 60s and 120s are below the configured 32MiB at this video's bitrate.
        set(CdnRacer.class, "lastUrl", "https://example.com/11111111111-1-30077.m4s?bw=532306");
        set(BufferTuneProbe.class, "lastAppliedPlayer", new WeakReference<>(null));
        set(BufferTuneProbe.class, "lastAppliedMaxBytes", -1L);
        set(BufferTuneProbe.class, "lastAppliedFirstMs", -1L);
        set(BufferTuneProbe.class, "lastAppliedNextMs", -1L);
        set(BufferTuneProbe.class, "lastAppliedLastMs", -1L);
        Field raw = field(BufferTuneProbe.class, "APP_RAW");
        appRaw = (Map<String, String>) raw.get(null);
        savedRaw = new LinkedHashMap<>(appRaw);
        appRaw.clear();
        player = new Player();
        set(PlaySpeed.class, "player", new WeakReference<>(player));
    }

    @After public void tearDown() throws Exception {
        for (Map.Entry<Field, Object> entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
        if (appRaw != null) {
            appRaw.clear();
            appRaw.putAll(savedRaw);
        }
    }

    @Test public void firstApplicationSetsCapacityAndAllWatermarks() {
        BufferTuneProbe.reassertForCurrentStream();
        assertEquals(Long.valueOf(32L * 1024 * 1024), player.values.get("max-buffer-size"));
        assertEquals(Long.valueOf(3_000), player.values.get("first-high-water-mark-ms"));
        assertEquals(Long.valueOf(15_000), player.values.get("next-high-water-mark-ms"));
        assertEquals(Long.valueOf(45_000), player.values.get("last-high-water-mark-ms"));
        assertEquals(4, player.calls.size());
    }

    @Test public void durationChangeUpdatesWatermarkEvenWhenCapacityIsUnchanged() throws Exception {
        BufferTuneProbe.reassertForCurrentStream();
        long previousBytes = ProxyConfig.bufferSizeBytes();
        set(ProxyConfig.class, "bufferTimeMs", 120_000);
        assertEquals(previousBytes, ProxyConfig.bufferSizeBytes());
        BufferTuneProbe.reassertForCurrentStream();
        assertEquals(Long.valueOf(90_000), player.values.get("last-high-water-mark-ms"));
        assertEquals(5, player.calls.size());
        assertEquals("last-high-water-mark-ms", player.calls.get(4));
    }

    @Test public void replacementPlayerReceivesTheSameConfiguration() throws Exception {
        BufferTuneProbe.reassertForCurrentStream();
        Player replacement = new Player();
        set(PlaySpeed.class, "player", new WeakReference<>(replacement));
        BufferTuneProbe.reassertForCurrentStream();
        assertEquals(player.values, replacement.values);
        assertEquals(4, replacement.calls.size());
    }

    @Test public void unchangedPlayerAndConfigurationDoNotRepeatWrites() {
        BufferTuneProbe.reassertForCurrentStream();
        BufferTuneProbe.reassertForCurrentStream();
        assertEquals(4, player.calls.size());
    }

    @Test public void firstAndNextWatermarksAlsoParticipateInChangeDetection() throws Exception {
        BufferTuneProbe.reassertForCurrentStream();
        set(ProxyConfig.class, "firstWaterMs", 2_000);
        set(ProxyConfig.class, "nextWaterMs", 10_000);
        BufferTuneProbe.reassertForCurrentStream();
        assertEquals(Long.valueOf(2_000), player.values.get("first-high-water-mark-ms"));
        assertEquals(Long.valueOf(10_000), player.values.get("next-high-water-mark-ms"));
        assertEquals(6, player.calls.size());
    }

    @Test public void disablingCapacityOverrideRestoresTheObservedHostValue() throws Exception {
        appRaw.put("max-buffer-size", "104857600");
        BufferTuneProbe.reassertForCurrentStream();
        disableBufferOverride();
        BufferTuneProbe.reassertForCurrentStream();
        assertEquals(Long.valueOf(104_857_600), player.values.get("max-buffer-size"));
        assertEquals(Long.valueOf(4_000), player.values.get("last-high-water-mark-ms"));
        int writes = player.calls.size();
        BufferTuneProbe.reassertForCurrentStream();
        assertEquals(writes, player.calls.size());
    }

    @Test public void unknownHostCapacityIsNeverGuessedAndCanBeRestoredLater() throws Exception {
        BufferTuneProbe.reassertForCurrentStream();
        disableBufferOverride();
        BufferTuneProbe.reassertForCurrentStream();
        assertEquals(Long.valueOf(32L * 1024 * 1024), player.values.get("max-buffer-size"));
        appRaw.put("max-buffer-size", "104857600");
        BufferTuneProbe.reassertForCurrentStream();
        assertEquals(Long.valueOf(104_857_600), player.values.get("max-buffer-size"));
    }

    @Test public void unsetCapacityOnANewPlayerLeavesItsCapacityAlone() throws Exception {
        disableBufferOverride();
        BufferTuneProbe.reassertForCurrentStream();
        assertFalse(player.values.containsKey("max-buffer-size"));
        assertEquals(3, player.calls.size());
    }

    private void disableBufferOverride() throws Exception {
        set(ProxyConfig.class, "bufferSizeKb", 0);
        set(ProxyConfig.class, "bufferTimeMs", 0);
    }

    public static class Player {
        final Map<String, Long> values = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        public void setOption(int category, String name, long value) {
            assertEquals(4, category);
            values.put(name, value);
            calls.add(name);
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void set(Class<?> type, String name, Object value) throws Exception {
        Field field = field(type, name);
        if (!saved.containsKey(field)) saved.put(field, field.get(null));
        field.set(null, value);
    }
}
