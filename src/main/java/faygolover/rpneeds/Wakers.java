package faygolover.rpneeds;

import faygolover.rpcore.api.RpCoreAPI;
import faygolover.rpcore.api.event.RpMoodEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Что будит спящего, кроме удара и Shift: кошмары, храп соседа, голос, выстрелы (взрывы и будильник — в {@link SleepService}). */
public final class Wakers {
    private Wakers() {}

    /** Будят ли его звуки (голос, выстрелы): нет у «Крепкого сна» и под снотворным. */
    static boolean sound(ServerPlayer sp) {
        return !SleepService.has(sp, SleepService.DEEP) && SleepService.sleepAid(sp) <= 0;
    }

    static boolean light(ServerPlayer sp) {
        return SleepService.has(sp, SleepService.LIGHT);
    }

    /** Каждую секунду сна: кошмар, храп. */
    static void asleepTick(ServerPlayer sp, CompoundTag t, int place) {
        double per = NeedsConfig.NIGHTMARE_PER_SLEEPS.get();
        if (per > 0) {
            double full = NeedsConfig.FULL_SLEEP_SHARE.get() * SleepService.day();
            double sanity = Math.max(0, Math.min(100, RpCoreAPI.sanity(sp)));
            double p = 1.0 / (per * full) * SleepPlace.nightmarePlace(sp, place) * (1 + 2 * (1 - sanity / 100.0));
            if (SleepService.has(sp, SleepService.NIGHTMARES)) p *= 2;
            if (SleepPlace.temperatureLevel(sp, place) == 1) p *= NeedsConfig.TEMP_NIGHTMARE_MULT.get();
            if (sp.getRandom().nextDouble() < p) {
                t.putInt("nightmare", 120);
                SleepService.wake(sp, "rpneeds.msg.nightmare", false);
                MinecraftForge.EVENT_BUS.post(new RpMoodEvent(sp, RpMoodEvent.Kind.NIGHTMARE, 1));
                return;
            }
        }
        if (SleepService.has(sp, SleepService.SNORER) && sp.tickCount % 80 < 20) {
            SleepService.snore(sp);
            for (ServerPlayer o : sp.serverLevel().players())
                if (o != sp && light(o) && sound(o) && SleepService.asleep(o) && o.distanceToSqr(sp) <= 64)
                    SleepService.wake(o, "rpneeds.msg.woke_snore", false);
        }
    }

    // ------------------------------------------------------------------ голос (Simple Voice Chat)

    private static final Map<UUID, Long> LAST_VOICE = new ConcurrentHashMap<>();

    /** Говорит игрок (поток голосового чата). Шёпот — режим «шёпот» Simple Voice Distance или шёпот самого SVC. */
    public static void onVoice(UUID speaker, boolean whisper) {
        long now = System.currentTimeMillis();
        Long last = LAST_VOICE.get(speaker);
        if (last != null && now - last < 1000) return;
        LAST_VOICE.put(speaker, now);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        boolean quiet = whisper || VoiceDistance.whispering(speaker);
        server.execute(() -> {
            ServerPlayer from = server.getPlayerList().getPlayer(speaker);
            if (from == null) return;
            for (ServerPlayer sp : from.serverLevel().players()) {
                if (sp == from || !SleepService.asleep(sp) || !sound(sp)) continue;
                boolean isLight = light(sp);
                if (quiet && !isLight) continue;
                double r = isLight ? 8 : NeedsConfig.VOICE_RADIUS.get();
                if (sp.distanceToSqr(from) <= r * r) SleepService.wake(sp, "rpneeds.msg.woke_voice", false);
            }
        });
    }

    // ------------------------------------------------------------------ выстрелы (TaCZ)

    public static void onShot(ServerLevel level, Vec3 at) {
        for (ServerPlayer sp : level.players()) {
            if (!SleepService.asleep(sp) || !sound(sp)) continue;
            double r = light(sp) ? 24 : NeedsConfig.SHOT_RADIUS.get();
            if (sp.position().distanceToSqr(at) <= r * r) SleepService.wake(sp, "rpneeds.msg.woke_noise", false);
        }
    }
}
