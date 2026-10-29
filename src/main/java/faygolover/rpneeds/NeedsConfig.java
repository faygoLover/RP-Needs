package faygolover.rpneeds;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Настройки сервера ({@code serverconfig/rpneeds-server.toml}). Времена — доли RP-суток (день + ночь RP Time, через
 * RP Core), чтобы всё менялось вместе с длиной суток. Примеры в скобках — для суток 3 ч.
 */
public final class NeedsConfig {
    private NeedsConfig() {}

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.DoubleValue AWAKE_SHARE;
    public static final ForgeConfigSpec.DoubleValue FULL_SLEEP_SHARE;
    public static final ForgeConfigSpec.DoubleValue BAG_MULT;
    public static final ForgeConfigSpec.DoubleValue POSTURE_MULT;
    public static final ForgeConfigSpec.DoubleValue RESTING_DRAIN_MULT;
    public static final ForgeConfigSpec.DoubleValue HUNGRY_SLEEP_MULT;
    public static final ForgeConfigSpec.DoubleValue DAY_SLEEP_BELOW;
    public static final ForgeConfigSpec.DoubleValue POSTURE_SLEEP_BELOW;
    public static final ForgeConfigSpec.DoubleValue SLEEPY_BELOW;
    public static final ForgeConfigSpec.DoubleValue DROWSY_DEBT_SHARE;
    public static final ForgeConfigSpec.DoubleValue STAGGER_DEBT_SHARE;
    public static final ForgeConfigSpec.DoubleValue DROWSY_SPEED;
    public static final ForgeConfigSpec.DoubleValue STAGGER_SPEED;
    public static final ForgeConfigSpec.IntValue MICROSLEEP_MIN_SECONDS;
    public static final ForgeConfigSpec.IntValue MICROSLEEP_MAX_SECONDS;
    public static final ForgeConfigSpec.IntValue COLLAPSE_EVERY_SECONDS;
    public static final ForgeConfigSpec.DoubleValue CAFFEINE_BOOST;
    public static final ForgeConfigSpec.DoubleValue CAFFEINE_CRASH;
    public static final ForgeConfigSpec.DoubleValue CAFFEINE_SHARE;
    public static final ForgeConfigSpec.DoubleValue CAFFEINE_DRAIN_MULT;
    public static final ForgeConfigSpec.DoubleValue OFFLINE_REST_SLEEPS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("alertness");
        AWAKE_SHARE = b.comment("Бодрость 100 → 0 за такую долю суток бодрствования (5/6 = 0.833: при сутках 3 ч — 2 ч 30 мин).")
                .defineInRange("awake_share", 5.0 / 6.0, 0.05, 10.0);
        RESTING_DRAIN_MULT = b.comment("Сидя или лёжа (не во сне) бодрость тратится медленнее: множитель.")
                .defineInRange("resting_drain_mult", 0.5, 0.0, 2.0);
        SLEEPY_BELOW = b.comment("Ниже этого (%) — «глаза слипаются».")
                .defineInRange("sleepy_below", 20.0, 0.0, 100.0);
        DROWSY_DEBT_SHARE = b.comment("Недосып (время после нуля) до такой доли суток — сонливость; дальше — валится с ног.",
                        "1/12 = 0.0833: при сутках 3 ч — 15 мин.")
                .defineInRange("drowsy_debt_share", 1.0 / 12.0, 0.001, 10.0);
        STAGGER_DEBT_SHARE = b.comment("Недосып больше такой доли суток — засыпает на ходу (1/6: при сутках 3 ч — 30 мин).")
                .defineInRange("stagger_debt_share", 1.0 / 6.0, 0.001, 10.0);
        DROWSY_SPEED = b.comment("Сонливость: скорость ходьбы минус столько (доля).")
                .defineInRange("drowsy_speed", 0.05, 0.0, 0.9);
        STAGGER_SPEED = b.comment("Валится с ног: скорость ходьбы минус столько (доля).")
                .defineInRange("stagger_speed", 0.10, 0.0, 0.9);
        MICROSLEEP_MIN_SECONDS = b.comment("Валится с ног: микросон (экран гаснет на 1–2 с) раз в столько секунд — от…")
                .defineInRange("microsleep_min_seconds", 60, 5, 3600);
        MICROSLEEP_MAX_SECONDS = b.comment("…до столько секунд.")
                .defineInRange("microsleep_max_seconds", 120, 5, 3600);
        COLLAPSE_EVERY_SECONDS = b.comment("Засыпает на ходу: если не спит, валится и засыпает раз в столько секунд.")
                .defineInRange("collapse_every_seconds", 30, 5, 3600);
        b.pop();

        b.push("sleep");
        FULL_SLEEP_SHARE = b.comment("Полный сон 0 → 100 в кровати — такая доля суток (1/12: при сутках 3 ч — 15 мин).")
                .defineInRange("full_sleep_share", 1.0 / 12.0, 0.001, 10.0);
        BAG_MULT = b.comment("Спальник (тег блоков rpneeds:sleeping_bags): скорость сна.")
                .defineInRange("bag_mult", 0.7, 0.0, 5.0);
        POSTURE_MULT = b.comment("Сидя, лёжа на земле или на сиденье (/sleep RP Core): скорость сна — заметно хуже.")
                .defineInRange("posture_mult", 0.4, 0.0, 5.0);
        HUNGRY_SLEEP_MULT = b.comment("Очень голоден или очень хочет пить: сон медленнее во столько раз.")
                .defineInRange("hungry_sleep_mult", 0.7, 0.0, 1.0);
        DAY_SLEEP_BELOW = b.comment("Днём в кровать пускает, если бодрость ниже этого (%). Ночью — всегда.")
                .defineInRange("day_sleep_below", 70.0, 0.0, 100.0);
        POSTURE_SLEEP_BELOW = b.comment("Уснуть сидя или лёжа можно, если бодрость ниже этого (%).")
                .defineInRange("posture_sleep_below", 90.0, 0.0, 100.0);
        OFFLINE_REST_SLEEPS = b.comment("Не был в сети столько полных снов в кровати (2 — при сутках 3 ч 30 мин) — при входе выспался.",
                        "Не чаще раза в игровые сутки. Был вне сети, когда проспали ночь, — тоже выспался.")
                .defineInRange("offline_rest_sleeps", 2.0, 0.0, 100.0);
        b.pop();

        b.push("caffeine");
        CAFFEINE_BOOST = b.comment("Кофеин (база напитков RP Core): бодрость + столько % за единицу кофеина, сразу.")
                .defineInRange("boost", 15.0, 0.0, 100.0);
        CAFFEINE_CRASH = b.comment("Когда кофеин вышел — бодрость минус столько % за единицу («в долг»).")
                .defineInRange("crash", 7.0, 0.0, 100.0);
        CAFFEINE_SHARE = b.comment("Кофеин действует такую долю суток (1/8: при сутках 3 ч — 22 мин).")
                .defineInRange("duration_share", 1.0 / 8.0, 0.0, 10.0);
        CAFFEINE_DRAIN_MULT = b.comment("Пока кофеин действует, бодрость тратится медленнее: множитель. Уснуть нельзя, пока бодрость выше 0.")
                .defineInRange("drain_mult", 0.5, 0.0, 2.0);
        b.pop();
        SPEC = b.build();
    }
}
