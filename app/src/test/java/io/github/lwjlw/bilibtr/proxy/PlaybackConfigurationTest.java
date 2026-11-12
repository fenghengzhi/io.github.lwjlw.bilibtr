package io.github.lwjlw.bilibtr.proxy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;

/** Regression coverage for native playback speed and automatic buffer settings. */
public class PlaybackConfigurationTest {
    private final Map<Field, Object> savedFields = new LinkedHashMap<>();
    private final Map<String, String> savedLive = new LinkedHashMap<>();
    private Map<String, String> live;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        // Keep the existing settings cache warm so these JVM tests do not need an
        // Android Context, provider, or the host app's external storage directory.
        set(ProxyConfig.class, "lastLoad", System.currentTimeMillis() + 60_000L);
        set(ProxyConfig.class, "playbackSpeed", 0f);
        set(ProxyConfig.class, "lastWaterMs", 0);
        set(ProxyConfig.class, "bufferTimeMs", 0);
        set(ProxyConfig.class, "maxBufferS", 180);
        set(PlaySpeed.class, "override", 0f);
        set(PlaySpeed.class, "appSpeed", 1f);
        Field liveField = ConfigServer.class.getDeclaredField("LIVE");
        liveField.setAccessible(true);
        live = (Map<String, String>) liveField.get(null);
        savedLive.putAll(live);
        live.clear();
    }

    @After
    public void tearDown() throws Exception {
        for (Map.Entry<Field, Object> entry : savedFields.entrySet()) {
            entry.getKey().set(null, entry.getValue());
        }
        if (live != null) {
            live.clear();
            live.putAll(savedLive);
        }
    }

    @Test
    public void nativeBilibiliDoubleSpeedScalesTheDownloadTarget() {
        PlaySpeed.noteAppSpeed(2f);
        assertEquals(2f, PlaySpeed.activeSpeed(), 0f);
    }

    @Test
    public void moduleSpeedOverridesKeepTheirPrecedence() throws Exception {
        PlaySpeed.noteAppSpeed(2f);
        set(ProxyConfig.class, "playbackSpeed", 1.5f);
        assertEquals(1.5f, PlaySpeed.activeSpeed(), 0f);
        set(PlaySpeed.class, "override", 4f);
        assertEquals(4f, PlaySpeed.activeSpeed(), 0f);
        // Returning to follow mode must use the still-remembered native speed.
        set(PlaySpeed.class, "override", 0f);
        set(ProxyConfig.class, "playbackSpeed", 0f);
        assertEquals(2f, PlaySpeed.activeSpeed(), 0f);
    }

    @Test
    public void slowPlaybackKeepsTheExistingMinimumDownloadTarget() {
        PlaySpeed.noteAppSpeed(0.5f);
        assertEquals(1f, PlaySpeed.activeSpeed(), 0f);
    }

    @Test
    public void unsetBufferDurationPersistsAutomaticModeInsteadOfTheFallback() throws Exception {
        assertEquals(4_000L, ProxyConfig.lastWaterMs());
        assertEquals("0", persistedSettings().getProperty("lastms"));
    }

    @Test
    public void automaticWatermarkRecalculatesAfterSavingAndChangingBufferDuration() throws Exception {
        set(ProxyConfig.class, "bufferTimeMs", 60_000);
        assertEquals(45_000L, ProxyConfig.lastWaterMs());
        Properties saved = persistedSettings();
        assertEquals("0", saved.getProperty("lastms"));

        // Read the saved setting as the host would on restart, then increase the
        // duration in the settings UI. Automatic mode must remain automatic.
        set(ProxyConfig.class, "lastWaterMs", Integer.parseInt(saved.getProperty("lastms")));
        set(ProxyConfig.class, "bufferTimeMs", 120_000);
        assertEquals(90_000L, ProxyConfig.lastWaterMs());
    }

    @Test
    public void manualWatermarkSurvivesSavingAndDurationChanges() throws Exception {
        set(ProxyConfig.class, "lastWaterMs", 12_000);
        set(ProxyConfig.class, "bufferTimeMs", 60_000);
        assertEquals("12000", persistedSettings().getProperty("lastms"));
        set(ProxyConfig.class, "bufferTimeMs", 120_000);
        assertEquals(12_000L, ProxyConfig.lastWaterMs());
    }

    @Test
    public void liveManualWatermarkTakesPrecedenceInSavedSettings() throws Exception {
        set(ProxyConfig.class, "lastWaterMs", 12_000);
        live.put("lastms", "15000");
        assertEquals("15000", persistedSettings().getProperty("lastms"));
    }

    private Properties persistedSettings() throws Exception {
        Method serialize = ConfigServer.class.getDeclaredMethod("persistentConfigText");
        serialize.setAccessible(true);
        Properties properties = new Properties();
        properties.load(new StringReader((String) serialize.invoke(null)));
        return properties;
    }

    private void set(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        if (!savedFields.containsKey(field)) savedFields.put(field, field.get(null));
        field.set(null, value);
    }
}
