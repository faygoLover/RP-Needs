package faygolover.rpneeds;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

/**
 * Simple Voice Distance (необязательный): режим дальности игрока на сервере. Самая короткая дальность — шёпот, он
 * спящих не будит. API у мода нет — читаем его карту режимов отражением; не вышло — шёпотом считаем только шёпот SVC.
 */
final class VoiceDistance {
    private VoiceDistance() {}

    private static Map<?, ?> modes;
    private static boolean tried;

    static boolean whispering(UUID player) {
        Map<?, ?> m = modes();
        if (m == null) return false;
        Object mode = m.get(player);
        return mode instanceof Enum<?> e && e.name().equals("WHISPER");
    }

    private static synchronized Map<?, ?> modes() {
        if (tried) return modes;
        tried = true;
        try {
            Class<?> c = Class.forName("de.awolfynight.simplevoicedistance.voicemanager.VoiceDistanceData");
            Field f = c.getDeclaredField("playerModes");
            f.setAccessible(true);
            modes = (Map<?, ?>) f.get(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            modes = null;
        }
        return modes;
    }
}
