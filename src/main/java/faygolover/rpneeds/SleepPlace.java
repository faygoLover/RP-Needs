package faygolover.rpneeds;

import static faygolover.rpneeds.SleepService.*;
import faygolover.rpcore.api.BodyProvider;
import faygolover.rpcore.api.RpCoreAPI;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/**
 * Где и можно ли спать: место сна (кровать, спальник, койка, поза), его качество, причины «не уснуть», ночь,
 * бессонница, холод. Только вопросы — состояние сна не меняют. Выделено из {@link SleepService}.
 */
final class SleepPlace {
    private SleepPlace() {}

    // ------------------------------------------------------------------ место сна

    /** Где спит (для скорости и кошмаров): 0 кровать, 1 спальник, 2 койка, 3 сиденье, 4 лёжа, 5 сидя, −1 не спит. */
    static int place(ServerPlayer sp) {
        if (sp.isSleeping()) {
            BlockPos pos = sp.getSleepingPos().orElse(null);
            return pos != null && sp.level().getBlockState(pos).is(SLEEPING_BAGS) ? 1 : 0;
        }
        return switch (RpCoreAPI.posture(sp)) {
            case HOSPITAL_BED -> 2;
            case SEAT -> 3;
            case LAY -> 4;
            case SIT -> 5;
            default -> -1;
        };
    }

    static double placeMult(ServerPlayer sp, int place) {
        if (has(sp, UNFUSSY)) return 1;
        double m = switch (place) {
            case 1 -> NeedsConfig.BAG_MULT.get();
            case 3 -> NeedsConfig.SEAT_MULT.get();
            case 4 -> NeedsConfig.POSTURE_MULT.get();
            case 5 -> NeedsConfig.SIT_MULT.get();
            default -> 1.0;
        };
        if (place >= 3 && has(sp, SLEEPYHEAD)) m = Math.max(m, 0.6);
        return m;
    }

    static double nightmarePlace(ServerPlayer sp, int place) {
        if (has(sp, UNFUSSY)) return 1;
        return switch (place) {
            case 1 -> 1.5;
            case 3, 4 -> 2.0;
            case 5 -> 2.5;
            default -> 1.0;
        };
    }

    // ------------------------------------------------------------------ можно ли уснуть

    /** Почему нельзя уснуть (ключ перевода) или null — можно. Без случайности (Бессонница — отдельно). */
    static String noSleepReason(ServerPlayer sp, CompoundTag t) {
        if (temperatureLevel(sp, place(sp)) >= 2) return cold(sp) ? "rpneeds.msg.too_cold" : "rpneeds.msg.too_hot";
        if (sleepAid(sp) > 0) return null;
        if (t.getInt("cooldown") > 0) return "rpneeds.msg.just_woke";
        if (t.getDouble("cafLeft") > 0 && t.getDouble("alert") > 0) return "rpneeds.msg.caffeine";
        BodyProvider b = RpCoreAPI.body();
        if (b != null && b.stimulated(sp) && t.getDouble("alert") > 0) return "rpneeds.msg.stimulant";
        if (RpCoreAPI.stress(sp) >= NeedsConfig.STRESS_NO_SLEEP.get() && (b == null || b.sedation(sp) < 20)) return "rpneeds.msg.stress";
        if (b != null && b.pain(sp) > NeedsConfig.PAIN_NO_SLEEP.get() && b.sedation(sp) < 20) return "rpneeds.msg.pain";
        double limit = night(sp) ? NeedsConfig.NIGHT_SLEEP_BELOW.get() : NeedsConfig.DAY_SLEEP_BELOW.get();
        if (drunk(sp)) limit += NeedsConfig.DRUNK_EASIER.get();
        if (t.getDouble("alert") >= limit) return "rpneeds.msg.not_sleepy";
        return null;
    }

    /** Ночь по часам (как в RP Time: с 18:30 до 5:30), а не по яркости неба — та обновляется с опозданием. */
    static boolean night(ServerPlayer sp) {
        long t = sp.level().getDayTime() % 24000L;
        return t >= 12500 && t < 23500;
    }

    /** Бессонница (черта или рассудок на исходе): 30 % попыток уснуть не выходят. */
    static boolean insomniaFails(ServerPlayer sp) {
        if (sleepAid(sp) > 0) return false;
        double p = has(sp, INSOMNIA) ? 0.3 : 0;
        if (RpCoreAPI.sanity(sp) < NeedsConfig.LOW_SANITY.get()) p = Math.max(p, NeedsConfig.LOW_SANITY_FAIL.get());
        return p > 0 && sp.getRandom().nextDouble() < p;
    }

    static boolean drunk(ServerPlayer sp) {
        BodyProvider b = RpCoreAPI.body();
        return b != null && b.intoxication(sp) >= NeedsConfig.DRUNK_FROM.get();
    }

    static boolean cold(ServerPlayer sp) {
        BodyProvider b = RpCoreAPI.body();
        return b != null && b.temperatureOffset(sp) < 0;
    }

    /** Температура для сна: 0 терпимо, 1 умеренный холод или жара, 2 опасно. Кровать, спальник и койка прощают небольшой холод. */
    static int temperatureLevel(ServerPlayer sp, int place) {
        BodyProvider b = RpCoreAPI.body();
        if (b == null) return 0;
        double off = b.temperatureOffset(sp);
        if (off < 0 && place >= 0 && place <= 2 && -off < NeedsConfig.TEMP_BED_FORGIVES.get()) return 0;
        double a = Math.abs(off);
        return a >= NeedsConfig.TEMP_DANGER.get() ? 2 : a >= NeedsConfig.TEMP_MILD.get() ? 1 : 0;
    }

    /** Что замедляет сон, кроме места: стресс, хмель, холод или жара. */
    static double conditionMult(ServerPlayer sp, int place) {
        double m = 1;
        if (RpCoreAPI.stress(sp) >= NeedsConfig.STRESS_SLOW_FROM.get()) m *= NeedsConfig.STRESS_SLEEP_MULT.get();
        if (drunk(sp)) m *= NeedsConfig.DRUNK_SLEEP_MULT.get();
        if (temperatureLevel(sp, place) == 1) m *= NeedsConfig.TEMP_SLEEP_MULT.get();
        return m;
    }
}
