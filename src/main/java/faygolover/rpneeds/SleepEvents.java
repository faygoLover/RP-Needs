package faygolover.rpneeds;

import static faygolover.rpneeds.SleepService.*;
import static faygolover.rpneeds.SleepPlace.*;
import faygolover.rpcore.api.Consumables;
import faygolover.rpcore.api.RpCoreAPI;
import faygolover.rpcore.api.event.RpAlarmEvent;
import faygolover.rpcore.api.event.RpConsumeEvent;
import faygolover.rpcore.api.event.RpPostureSleepEvent;
import faygolover.rpcore.api.event.RpPostureWakeEvent;
import faygolover.rpcore.api.event.RpSleepCountEvent;
import faygolover.rpcore.api.event.RpTimeSkipEvent;
import faygolover.rpcore.api.event.RpTimeSkipOfflineEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.SleepingTimeCheckEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.Event;

/**
 * События, на которые отвечает сон: ванильная проверка времени сна, счёт спящих для ускорения ночи, позы RP Core,
 * урон и взрывы (будят), будильник, кофеин, прокрутка времени, вход и выход игрока, вайп персонажа.
 * Сама механика сна — в {@link SleepService}, вопросы о месте сна — в {@link SleepPlace}.
 */
final class SleepEvents {
    private SleepEvents() {}

    // ------------------------------------------------------------------ события сна

    /** Лечь в кровать можно в любое время суток (уснуть — по правилам выше). */
    public static void onSleepTime(SleepingTimeCheckEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp && !sp.isSpectator()) e.setResult(Event.Result.ALLOW);
    }

    /** Для ночи RP Time: лежит в кровати, но не уснул — не спит. */
    public static void onSleepCount(RpSleepCountEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp && sp.isSleeping() && !data(sp).getBoolean("asleep"))
            e.setCount(RpSleepCountEvent.Count.BLOCKS);
    }

    /** Сидя, лёжа, на сиденье, на койке ({@code /sleep}): те же условия, что в кровати. */
    public static void onPostureSleep(RpPostureSleepEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp) || !counts(sp)) return;
        String why = noSleepReason(sp, data(sp));
        if (why == null && insomniaFails(sp)) why = "rpneeds.msg.insomnia";
        if (why != null) e.deny(Component.translatable(why));
    }

    /** Проснулся сидя или лёжа (сам, удар, Shift, мы): откат и вялость. */
    public static void onPostureWake(RpPostureWakeEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) afterWake(sp, data(sp));
    }

    /** Удар будит и в кровати — ванилла при этом поднимает с кровати (позу будит RP Core, поза остаётся). */
    public static void onHurt(LivingHurtEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp) || e.getAmount() <= 0) return;
        CompoundTag t = data(sp);
        if (!t.getBoolean("asleep")) return;
        // Ванилла от удара уже подняла его с кровати — здесь только «проснулся»: откат, вялость, сообщение.
        if (sp.isSleeping()) wake(sp, "rpneeds.msg.woke_hurt", false);
        else {
            setBedAsleep(sp, t, false);
            afterWake(sp, t);
            sp.displayClientMessage(Component.translatable("rpneeds.msg.woke_hurt"), true);
        }
    }

    /** Взрывы рядом будят (Крепкий сон и снотворное — только совсем рядом). */
    public static void onExplosion(ExplosionEvent.Detonate e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        Vec3 at = e.getExplosion().getPosition();
        for (ServerPlayer sp : level.players()) {
            if (!asleep(sp)) continue;
            double r = Wakers.sound(sp) ? NeedsConfig.EXPLOSION_RADIUS.get() : 8;
            if (sp.position().distanceToSqr(at) <= r * r) wake(sp, "rpneeds.msg.woke_noise", false);
        }
    }

    /** Будильник (RP Core alarm, потом — КПК). */
    public static void onAlarm(RpAlarmEvent e) {
        for (ServerPlayer sp : e.level().players())
            if (asleep(sp) && sp.position().distanceToSqr(e.pos()) <= e.radius() * e.radius()) wake(sp, "rpneeds.msg.woke_alarm", false);
    }

    // ------------------------------------------------------------------ кофеин

    public static void onConsume(RpConsumeEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp) || !counts(sp)) return;
        Consumables.Entry c = Consumables.forItem(e.stack());
        if (c == null || c.caffeine() <= 0) return;
        double dose = c.caffeine() * Math.max(0, e.fraction());
        CompoundTag t = data(sp);
        change(t, NeedsConfig.CAFFEINE_BOOST.get() * dose);
        t.putDouble("cafDose", t.getDouble("cafDose") + dose);
        t.putDouble("cafLeft", Math.max(t.getDouble("cafLeft"), NeedsConfig.CAFFEINE_SHARE.get() * day()));
    }

    // ------------------------------------------------------------------ прокрутка и выход из игры

    /** Прокрутка: спящий высыпается по времени RP, бодрствующий устаёт мягко — не ниже порога Core и не ниже, чем был. */
    public static void onSkip(RpTimeSkipEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp) || !counts(sp) || e.scope() != RpTimeSkipEvent.Scope.ALL) return;
        CompoundTag t = data(sp);
        // Секунды события посчитаны по длине суток без /time speed — и доли суток считаем так же.
        double day = Math.max(60, RpCoreAPI.dayLengthSeconds()), secs = e.seconds();
        if (e.sleeping()) {
            // Место сна (кровать, спальник, поза) и качество от команды (усадили спать — ×0,8).
            int place = place(sp);
            double mult = placeMult(sp, place < 0 ? 5 : place) * e.quality() * conditionMult(sp, place < 0 ? 5 : place);
            if (has(sp, SHORT)) mult *= 1.5;
            if (has(sp, INSOMNIA)) mult *= 0.7;
            change(t, 100.0 / (NeedsConfig.FULL_SLEEP_SHARE.get() * day) * secs * mult);
            t.putInt("place", place < 0 ? 5 : place);
            t.putInt("slept", (int) Math.min(Integer.MAX_VALUE / 2, t.getInt("slept") + secs * mult));
        } else {
            double before = t.getDouble("alert");
            change(t, -100.0 / (NeedsConfig.AWAKE_SHARE.get() * day) * secs);
            double floor = Math.min(RpCoreAPI.skipFloor() * 100, before);
            if (t.getDouble("alert") < floor) {
                t.putDouble("alert", floor);
                t.putDouble("debt", 0);
            }
        }
        caffeineTick(sp, t, secs);
        // /rptime sleep: после прокрутки все просыпаются, как утром (поза и кровать остаются).
        if (e.wakeAfter()) {
            wake(sp, "rpneeds.msg.woke_morning", false);
            t.putInt("cooldown", 0);
        }
    }

    public static void onSkipOffline(RpTimeSkipOfflineEvent e) {
        if (e.sleeping()) NightMark.get(e.server()).mark(System.currentTimeMillis());
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) {
            CompoundTag t = data(sp);
            t.putLong("logout", System.currentTimeMillis());
            t.putBoolean("asleep", false);
            slow(sp, SLOW, 0);
            slow(sp, HEAVY, 0);
            SENT_STAGE.remove(sp.getUUID());
        }
    }

    /** Вход: не был в сети два полных сна — выспался (раз в игровые сутки); ночь проспали без него — тоже. */
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp)) return;
        CompoundTag t = data(sp);
        t.putBoolean("asleep", false);
        long logout = t.getLong("logout");
        if (logout <= 0) return;
        long now = System.currentTimeMillis();
        double away = (now - logout) / 1000.0;
        double need = NeedsConfig.OFFLINE_REST_SLEEPS.get() * NeedsConfig.FULL_SLEEP_SHARE.get() * day();
        long gameDay = sp.server.overworld().getDayTime() / 24000L;
        boolean night = NightMark.get(sp.server).last() > logout;
        boolean longAway = need > 0 && away >= need && t.getLong("restDay") != gameDay + 1;
        if (night || longAway) {
            t.putDouble("alert", 100);
            t.putDouble("debt", 0);
            t.putInt("stage", 0);
            t.putInt("rested", (int) (day() / 6));
            if (longAway) t.putLong("restDay", gameDay + 1);
        }
    }

    /** Вайп персонажа: бодрость как у нового. */
    public static void onWipe(faygolover.rpcore.api.event.RpCharacterWipeEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp)) return;
        sp.getPersistentData().remove(TAG);
        slow(sp, SLOW, 0);
        slow(sp, HEAVY, 0);
        maxStamina(sp, 0);
        SENT_STAGE.remove(sp.getUUID());
    }
}
