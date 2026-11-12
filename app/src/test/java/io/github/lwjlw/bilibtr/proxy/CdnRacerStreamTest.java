package io.github.lwjlw.bilibtr.proxy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** Regressions for the 9.17.0 low-bitrate video and per-stream CDN selection. */
public class CdnRacerStreamTest {
    private static final String ORIGINAL = "upos-sz-mirrorcosov.bilivideo.com";
    private static final String ALTERNATE = "upos-sz-mirrorali.bilivideo.com";
    private final Map<Field, Object> savedFields = new LinkedHashMap<>();
    private final Map<Map<Object, Object>, Map<Object, Object>> savedMaps = new java.util.IdentityHashMap<>();
    private ArrayDeque<Long> switchTimes;
    private ArrayList<Long> savedSwitchTimes;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        set(ProxyConfig.class, "lastLoad", System.currentTimeMillis() + 60_000L);
        set(ProxyConfig.class, "autoPick", false);
        set(ProxyConfig.class, "toast", false);
        set(CdnRacer.class, "lastUrl", null);
        set(CdnRacer.class, "lastCid", "");
        set(CdnRacer.class, "lastCands", Collections.emptyList());
        set(CdnRacer.class, "lastHeaders", Collections.emptyMap());
        set(CdnRacer.class, "lastUsed", null);
        set(CdnRacer.class, "activeHost", null);
        set(CdnRacer.class, "pinHost", null);
        set(CdnRacer.class, "pinApplied", true);
        set(CdnRacer.class, "autoMode", false);
        set(CdnRacer.class, "autoHost", null);
        set(CdnRacer.class, "autoHostAt", 0L);
        set(CdnRacer.class, "lastSwitchAt", 0L);
        // No network probes in these deterministic routing tests.
        set(CdnRacer.class, "lastRaceAt", System.currentTimeMillis());
        for (String name : new String[]{"HOST_BYTES", "PROBE_BPS", "PROBE", "EMA_REAL", "SPEEDTEST"}) {
            Map<Object, Object> map = (Map<Object, Object>) field(CdnRacer.class, name).get(null);
            savedMaps.put(map, new LinkedHashMap<>(map));
            map.clear();
        }
        switchTimes = (ArrayDeque<Long>) field(CdnRacer.class, "SWITCH_TIMES").get(null);
        savedSwitchTimes = new ArrayList<>(switchTimes);
        switchTimes.clear();
    }

    @After
    public void tearDown() throws Exception {
        for (Map.Entry<Field, Object> entry : savedFields.entrySet()) entry.getKey().set(null, entry.getValue());
        for (Map.Entry<Map<Object, Object>, Map<Object, Object>> entry : savedMaps.entrySet()) {
            entry.getKey().clear();
            entry.getKey().putAll(entry.getValue());
        }
        if (switchTimes != null) {
            switchTimes.clear();
            switchTimes.addAll(savedSwitchTimes);
        }
    }

    @Test
    public void lowBitrateDashVideoIsStillVideo() {
        assertTrue(CdnRacer.isVideoStream(url(ORIGINAL, "11111111111", 30077, 532306)));
        assertTrue(CdnRacer.isVideoStream(url(ORIGINAL, "11111111111", 30066, 274544)));
        assertTrue(CdnRacer.isVideoStream(url(ORIGINAL, "11111111111", 30127, 1)));
    }

    @Test
    public void highBitrateAndNewAudioIdsDoNotBecomeVideo() {
        assertFalse(CdnRacer.isVideoStream(url(ORIGINAL, "11111111111", 30251, 10_000_000)));
        assertFalse(CdnRacer.isVideoStream(url(ORIGINAL, "11111111111", 30252, 10_000_000)));
        assertFalse(CdnRacer.isVideoStream("https://example.com/track.m4a?bw=10000000"));
    }

    @Test
    public void unrecognizedDashWithoutMetadataIsNotAssumedToBeVideo() {
        assertFalse(CdnRacer.isVideoStream("https://example.com/unknown.m4s"));
        assertFalse(CdnRacer.isVideoStream(null));
        assertTrue(CdnRacer.isVideoStream("https://example.com/unknown.m4s?bw=2000000"));
    }

    @Test
    public void lowBitrateVideoReplacesOldCidCandidatesAndUsage() {
        String oldVideo = url(ORIGINAL, "22222222222", 30080, 2_000_000);
        CdnRacer.resolve(null, oldVideo, Collections.emptyMap());
        CdnRacer.record(ORIGINAL, 100_000, 100);
        assertFalse(CdnRacer.hostStats().isEmpty());

        String current = url(ALTERNATE, "11111111111", 30077, 532306);
        assertEquals(current, CdnRacer.resolve(null, current, Collections.emptyMap()));
        assertEquals(current, CdnRacer.lastUrl());
        assertEquals(current, CdnRacer.lastUsed());
        assertEquals(ALTERNATE, CdnRacer.activeHost());
        assertTrue(CdnRacer.hostStats().isEmpty());
        for (String candidate : CdnRacer.candidates()) {
            assertEquals("11111111111", CdnRacer.cidOf(candidate));
        }
    }

    @Test
    public void audioRequestKeepsTheCurrentVideoPanel() {
        String video = url(ORIGINAL, "11111111111", 30077, 532306);
        CdnRacer.resolve(null, video, Collections.emptyMap());
        String audio = url(ALTERNATE, "11111111111", 30232, 76550);
        assertEquals(audio, CdnRacer.resolve(null, audio, Collections.emptyMap()));
        assertEquals(video, CdnRacer.lastUrl());
        assertEquals(video, CdnRacer.lastUsed());
        assertEquals(ORIGINAL, CdnRacer.activeHost());
    }

    @Test
    public void pinUsesTheCurrentLowBitrateVideoPath() throws Exception {
        String video = url(ORIGINAL, "11111111111", 30077, 532306);
        set(CdnRacer.class, "pinHost", ALTERNATE);
        String chosen = CdnRacer.resolve(null, video, Collections.emptyMap());
        assertEquals(CdnCandidates.swapHost(video, ALTERNATE), chosen);
        assertEquals(chosen, CdnRacer.lastUsed());
        assertEquals(ALTERNATE, CdnRacer.activeHost());
    }

    @Test
    public void automaticAudioSwitchNeverReturnsTheVideoFile() throws Exception {
        String video = url(ORIGINAL, "11111111111", 30077, 532306);
        CdnRacer.resolve(null, video, Collections.emptyMap());
        enableProbeWinner(ALTERNATE);
        String audio = url(ORIGINAL, "11111111111", 30232, 76550);
        String chosen = CdnRacer.resolve(null, audio, Collections.emptyMap());
        assertEquals(CdnCandidates.swapHost(audio, ALTERNATE), chosen);
        assertEquals(video, CdnRacer.lastUsed());
    }

    @Test
    public void automaticQualitySwitchKeepsTheRequestedQuality() throws Exception {
        CdnRacer.resolve(null, url(ORIGINAL, "11111111111", 30080, 2_000_000), Collections.emptyMap());
        enableProbeWinner(ALTERNATE);
        String lowerQuality = url(ORIGINAL, "11111111111", 30077, 532306);
        assertEquals(CdnCandidates.swapHost(lowerQuality, ALTERNATE),
                CdnRacer.resolve(null, lowerQuality, Collections.emptyMap()));
    }

    @SuppressWarnings("unchecked")
    private void enableProbeWinner(String host) throws Exception {
        set(CdnRacer.class, "autoMode", true);
        ((Map<String, Double>) field(CdnRacer.class, "PROBE_BPS").get(null)).put(host, 10_000_000d);
    }

    private static String url(String host, String cid, int stream, long bw) {
        return "https://" + host + "/upgcxcode/18/11/" + cid + "/" + cid + "-1-" + stream + ".m4s?bw=" + bw;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private void set(Class<?> type, String name, Object value) throws Exception {
        Field field = field(type, name);
        if (!savedFields.containsKey(field)) savedFields.put(field, field.get(null));
        field.set(null, value);
    }
}
