package faygolover.rpneeds;

import faygolover.rpcore.api.BodyProvider;
import faygolover.rpcore.api.Consumables;
import faygolover.rpcore.api.NutritionProvider;
import faygolover.rpcore.api.RpCoreAPI;
import faygolover.rpcore.api.RpCoreAPI.Posture;
import faygolover.rpcore.api.RpIds;
import faygolover.rpcore.api.event.RpAlarmEvent;
import faygolover.rpcore.api.event.RpConsumeEvent;
import faygolover.rpcore.api.event.RpMoodEvent;
import faygolover.rpcore.api.event.RpPostureSleepEvent;
import faygolover.rpcore.api.event.RpPostureWakeEvent;
import faygolover.rpcore.api.event.RpSleepCountEvent;
import faygolover.rpcore.api.event.RpTimeSkipEvent;
import faygolover.rpcore.api.event.RpTimeSkipOfflineEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.SleepingTimeCheckEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.Event;

import java.util.UUID;

/**
 * Сон (сервер), дизайн «RP Needs: сон» 29–30.10. Бодрость 0–100 тратится по времени RP, восстанавливается во сне.
 * <p><b>Лечь и уснуть — разное.</b> В кровать можно лечь когда угодно; засыпает игрок, только если бодрость ниже порога
 * (ночью 90 %, днём 70 %), нет отката, кофеина и сильной боли; снотворное (RP Medicine) снимает все запреты. Сидя,
 * лёжа, на сиденье и на больничной койке засыпают командой {@code /sleep} (RP Core) с теми же условиями. Проснувшись,
 * игрок не встаёт сам: из кровати — кнопкой, из позы — Shift.
 * <p>Будят: урон, Shift (поза), голос ближе 5 блоков (не шёпот), выстрелы, взрывы, кошмар, будильник; черты меняют
 * радиусы. Данные — в {@code getPersistentData()} игрока, раздел {@code rpneeds}.
 */
public final class SleepService {
    private SleepService() {}

    static final String TAG = "rpneeds";
    /**
     * Раздел данных сна в {@code getPersistentData()} игрока; перенос при возрождении ведёт RP Core. После смерти и
     * возврата из Края данные остаются, но без сна и таймера «валится с ног».
     */
    static faygolover.rpcore.api.data.PersistentTag STORE;

    /** Описать раздел для RP Core. Вызывается из конструктора мода. */
    public static void registerData() {
        STORE = faygolover.rpcore.api.data.PersistentTag.of(TAG)
                .clone((t, ctx) -> {
                    t.putBoolean("asleep", false);
                    t.putInt("heavy", 0);
                    return t;
                })
                .register();
    }
    public static final TagKey<Block> SLEEPING_BAGS = BlockTags.create(new ResourceLocation(RpNeeds.MODID, "sleeping_bags"));
    private static final UUID SLOW = UUID.fromString("3b1e9f0a-7c52-4d8e-a1f6-2e9b5c7d4a10");
    private static final UUID HEAVY = UUID.fromString("5a2c8e41-9b07-4f3d-8c6e-1d4f7a9b3e22");
    private static final UUID MAX_STAMINA = UUID.fromString("8e7d4c12-3f6a-4b59-a0d7-6c1e9f2b5d33");

    // ------------------------------------------------------------------ черты (RP Perks: rpneeds:<id>)

    static final ResourceLocation UNFUSSY = trait("unfussy"), DEEP = trait("deep_sleeper"), SHORT = trait("short_sleeper"),
            TIRELESS = trait("tireless"), EARLY_BIRD = trait("early_bird"), NIGHT_OWL = trait("night_owl"),
            INSOMNIA = trait("insomnia"), LIGHT = trait("light_sleeper"), SLEEPYHEAD = trait("sleepyhead"),
            NIGHTMARES = trait("nightmares"), HEAVY_WAKING = trait("heavy_waking"), SNORER = trait("snorer");

    private static ResourceLocation trait(String id) {
        return new ResourceLocation(RpNeeds.MODID, id);
    }

    static boolean has(Player p, ResourceLocation t) {
        return RpCoreAPI.hasTrait(p, t);
    }

    // ------------------------------------------------------------------ данные

    public static CompoundTag data(Player p) {
        CompoundTag root = p.getPersistentData();
        if (!root.contains(TAG)) {
            CompoundTag t = new CompoundTag();
            t.putDouble("alert", 100);
            root.put(TAG, t);
        }
        return root.getCompound(TAG);
    }

    /** Сутки тела в реальных секундах: длина суток и {@code /time speed} RP Time (RP Core 0.13.0, слои 06.11). */
    static double day() {
        double r = RpCoreAPI.rpPerReal();
        return Math.max(60, r > 0 ? 86400.0 / r : RpCoreAPI.dayLengthSeconds());
    }

    /** Стадии: 0 бодр, 1 хочется спать, 2 сонливость, 3 валится с ног, 4 засыпает на ходу. */
    public static int stage(Player p, CompoundTag t) {
        double alert = t.getDouble("alert"), debt = t.getDouble("debt"), day = day();
        double k = p != null && has(p, TIRELESS) ? 2 : 1;
        if (alert > NeedsConfig.SLEEPY_BELOW.get()) return 0;
        if (alert > 0 || debt <= 0) return 1;
        if (debt < NeedsConfig.DROWSY_DEBT_SHARE.get() * day * k) return 2;
        if (debt < NeedsConfig.STAGGER_DEBT_SHARE.get() * day * k) return 3;
        return 4;
    }

    /** Бодрость в % и недосып в секундах. Ниже нуля бодрость не уходит — дальше растёт недосып. */
    static void change(CompoundTag t, double delta) {
        double alert = t.getDouble("alert"), debt = t.getDouble("debt");
        double perSecondAwake = 100.0 / (NeedsConfig.AWAKE_SHARE.get() * day());
        if (delta >= 0) {
            double pay = Math.min(debt * perSecondAwake, delta);
            debt -= pay / perSecondAwake;
            alert = Math.min(100, alert + delta - pay);
        } else {
            alert += delta;
            if (alert < 0) {
                debt += -alert / perSecondAwake;
                alert = 0;
            }
        }
        t.putDouble("alert", alert);
        t.putDouble("debt", Math.max(0, debt));
    }

    static boolean counts(ServerPlayer sp) {
        return !sp.isCreative() && !sp.isSpectator() && sp.isAlive();
    }

    /** Спит ли сейчас: в кровати (и уснул) или сидя/лёжа/на койке по {@code /sleep}. */
    public static boolean asleep(ServerPlayer sp) {
        return (sp.isSleeping() && data(sp).getBoolean("asleep")) || RpCoreAPI.postureSleeping(sp);
    }

    static double sleepAid(Player p) {
        BodyProvider b = RpCoreAPI.body();
        return b == null ? 0 : b.sleepAid(p);
    }

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

    private static boolean cold(ServerPlayer sp) {
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
    private static double conditionMult(ServerPlayer sp, int place) {
        double m = 1;
        if (RpCoreAPI.stress(sp) >= NeedsConfig.STRESS_SLOW_FROM.get()) m *= NeedsConfig.STRESS_SLEEP_MULT.get();
        if (drunk(sp)) m *= NeedsConfig.DRUNK_SLEEP_MULT.get();
        if (temperatureLevel(sp, place) == 1) m *= NeedsConfig.TEMP_SLEEP_MULT.get();
        return m;
    }

    // ------------------------------------------------------------------ тик

    public static void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.player instanceof ServerPlayer sp)) return;
        CompoundTag t = data(sp);
        // Тяжёлый подъём: не бегает.
        if (t.getInt("heavy") > 0 && sp.isSprinting()) sp.setSprinting(false);
        if (sp.tickCount % 20 != 0) return;
        if (!counts(sp)) {
            slow(sp, SLOW, 0);
            maxStamina(sp, 0);
            sendStage(sp, 0);
            // Творческий: потребности не идут, но в кровати засыпает сразу и спит, пока не встанет.
            if (sp.isCreative() && sp.isSleeping() && !t.getBoolean("asleep")) setBedAsleep(sp, t, true);
            if (!sp.isSleeping() && t.getBoolean("asleep")) setBedAsleep(sp, t, false);
            return;
        }
        timers(sp, t);
        if (!RpCoreAPI.timeRunning()) {
            sendStage(sp, asleep(sp) ? 0 : stage(sp, t));
            return;
        }
        double day = day();

        // Кровать: лёг — не значит уснул.
        if (sp.isSleeping() && !t.getBoolean("asleep")) tryFallAsleepInBed(sp, t);
        if (!sp.isSleeping() && t.getBoolean("asleep")) setBedAsleep(sp, t, false);

        // Без сознания и под наркозом: не устаёт, бодрость понемногу возвращается, как сон на полу; недосыпа не видно.
        if (RpCoreAPI.unconscious(sp)) {
            change(t, 100.0 / (NeedsConfig.FULL_SLEEP_SHARE.get() * day) * NeedsConfig.POSTURE_MULT.get());
            caffeineTick(sp, t, 1);
            sendStage(sp, 0);
            return;
        }

        boolean asleep = asleep(sp);
        if (asleep && temperatureLevel(sp, place(sp)) >= 2) {
            wake(sp, cold(sp) ? "rpneeds.msg.woke_cold" : "rpneeds.msg.woke_heat", false);
            asleep = false;
        }
        if (asleep) {
            int place = place(sp);
            double rate = 100.0 / (NeedsConfig.FULL_SLEEP_SHARE.get() * day) * placeMult(sp, place);
            if (has(sp, SHORT)) rate *= 1.5;
            if (has(sp, INSOMNIA)) rate *= 0.7;
            NutritionProvider n = RpCoreAPI.nutrition();
            if (n != null && (n.hungerStage(sp) >= 2 || n.thirstStage(sp) >= 2)) rate *= NeedsConfig.HUNGRY_SLEEP_MULT.get();
            rate *= conditionMult(sp, place);
            if (drunk(sp)) t.putBoolean("drunkSleep", true);
            change(t, rate);
            t.putInt("place", place);
            t.putInt("slept", t.getInt("slept") + 1);
            Wakers.asleepTick(sp, t, place);
            if (t.getDouble("alert") >= 100 && t.getDouble("debt") <= 0) {
                // После пьянки просыпается, но не «выспался».
                boolean drunkSleep = t.getBoolean("drunkSleep");
                if (!drunkSleep) t.putInt("rested", (int) (day / 6));
                wake(sp, null, false);
                if (!drunkSleep) sense(sp, "rpneeds.sense.rested" + sp.getRandom().nextInt(2));
            }
        } else {
            double mult = RpCoreAPI.posture(sp) != Posture.NONE || sp.isSleeping() ? NeedsConfig.RESTING_DRAIN_MULT.get() : 1.0;
            if (t.getDouble("cafLeft") > 0) mult *= NeedsConfig.CAFFEINE_DRAIN_MULT.get();
            else if (RpCoreAPI.hasTrait(sp, RpIds.CAFFEINE_ADDICT)) mult *= 1.3;
            if (has(sp, SLEEPYHEAD)) mult *= 1.2;
            mult *= hourMult(sp);
            change(t, -100.0 / (NeedsConfig.AWAKE_SHARE.get() * day) * mult);
            // Снотворное: пока не спит, бодрость падает до порога.
            if (sleepAid(sp) > 0) {
                double floor = NeedsConfig.SLEEP_AID_FLOOR.get(), a = t.getDouble("alert");
                if (a > floor) t.putDouble("alert", Math.max(floor, a - NeedsConfig.SLEEP_AID_DROP_PER_SECOND.get()));
            }
        }
        caffeineTick(sp, t, 1);
        effects(sp, t);
    }

    /** Жаворонок и Сова: по часам RP (0 тиков = 6:00). */
    private static double hourMult(ServerPlayer sp) {
        boolean lark = has(sp, EARLY_BIRD), owl = has(sp, NIGHT_OWL);
        if (!lark && !owl) return 1;
        long dt = sp.server.overworld().getDayTime() % 24000L;
        double hour = ((dt / 1000.0) + 6) % 24;
        if (lark) return hour >= 6 && hour < 12 ? 0.7 : (hour >= 22 || hour < 2) ? 1.3 : 1;
        return (hour >= 18 || hour < 2) ? 0.7 : (hour >= 6 && hour < 12) ? 1.3 : 1;
    }

    private static void timers(ServerPlayer sp, CompoundTag t) {
        for (String k : new String[]{"cooldown", "rested", "nightmare", "retry"}) {
            int v = t.getInt(k);
            if (v > 0) t.putInt(k, v - 1);
        }
        int heavy = t.getInt("heavy");
        if (heavy > 0) {
            t.putInt("heavy", heavy - 1);
            slow(sp, HEAVY, heavy - 1 > 0 ? 0.3 : 0);
        }
    }

    private static void tryFallAsleepInBed(ServerPlayer sp, CompoundTag t) {
        if (t.getInt("retry") > 0) return;
        String why = noSleepReason(sp, t);
        if (why == null && insomniaFails(sp)) why = "rpneeds.msg.insomnia";
        if (why != null) {
            // Повторная попытка — через 10 с; причину — один раз за лежание.
            t.putInt("retry", 10);
            if (!why.equals(t.getString("lastWhy"))) {
                t.putString("lastWhy", why);
                sp.displayClientMessage(Component.translatable(why), true);
            }
            return;
        }
        t.remove("lastWhy");
        setBedAsleep(sp, t, true);
        sp.displayClientMessage(Component.translatable("rpneeds.msg.falling_asleep"), true);
    }

    static void setBedAsleep(ServerPlayer sp, CompoundTag t, boolean value) {
        t.putBoolean("asleep", value);
        NeedsNetwork.send(sp, new NeedsNetwork.BedSleep(value));
    }

    /**
     * Разбудить: в кровати — остаётся лежать, в позе — сидеть или лежать. {@code quiet} — без сообщения. После
     * пробуждения, если бодрость выше порога, — откат; «Тяжёлый подъём» — минута вялости.
     */
    public static void wake(ServerPlayer sp, String messageKey, boolean quiet) {
        CompoundTag t = data(sp);
        boolean wasBed = sp.isSleeping() && t.getBoolean("asleep");
        boolean wasPosture = RpCoreAPI.postureSleeping(sp);
        if (!wasBed && !wasPosture) return;
        if (wasBed) setBedAsleep(sp, t, false);
        if (wasPosture) RpCoreAPI.wakeFromPosture(sp); // RpPostureWakeEvent → afterWake
        else afterWake(sp, t);
        if (!quiet && messageKey != null) sp.displayClientMessage(Component.translatable(messageKey), true);
    }

    static void afterWake(ServerPlayer sp, CompoundTag t) {
        t.putInt("retry", 0);
        // Психике — сколько проспал в нормальном месте (доля полного сна): снимает стресс, возвращает рассудок.
        int slept = t.getInt("slept"), place = t.getInt("place");
        boolean drunkSleep = t.getBoolean("drunkSleep");
        t.remove("drunkSleep");
        t.putInt("slept", 0);
        if (slept > 0 && !drunkSleep && (place == 0 || place == 2 || has(sp, UNFUSSY))) {
            double full = NeedsConfig.FULL_SLEEP_SHARE.get() * day();
            MinecraftForge.EVENT_BUS.post(new RpMoodEvent(sp, RpMoodEvent.Kind.RESTED, Math.min(1, slept / full)));
        }
        if (t.getDouble("alert") > NeedsConfig.COOLDOWN_ABOVE.get())
            t.putInt("cooldown", has(sp, INSOMNIA) ? 300 : NeedsConfig.COOLDOWN_SECONDS.get());
        if (has(sp, HEAVY_WAKING)) {
            t.putInt("heavy", 60);
            slow(sp, HEAVY, 0.3);
        }
    }

    private static void caffeineTick(ServerPlayer sp, CompoundTag t, double seconds) {
        double left = t.getDouble("cafLeft");
        if (left <= 0) return;
        left -= seconds;
        if (left <= 0) {
            change(t, -NeedsConfig.CAFFEINE_CRASH.get() * t.getDouble("cafDose"));
            t.putDouble("cafDose", 0);
            left = 0;
            // Откат: бодрость кофеина кончилась, а недосып остался.
            if (stage(sp, t) >= 1) sense(sp, "rpneeds.sense.caffeine_crash");
        }
        t.putDouble("cafLeft", left);
    }

    /** Стадии: ощущение при ухудшении и напоминания, глаза клиенту, замедление, микросны, падение без сил. */
    private static void effects(ServerPlayer sp, CompoundTag t) {
        int stage = stage(sp, t);
        int was = t.getInt("stage");
        if (stage != was) {
            t.putInt("stage", stage);
            if (stage > was && stage > 0) {
                sense(sp, stage);
                t.putInt("remind", remindSeconds(stage));
            }
            t.putInt("micro", 0);
            t.putInt("collapse", 0);
        } else if (stage >= 1 && stage <= 3 && !asleep(sp)) {
            int left = t.getInt("remind") - 1;
            if (left <= 0) {
                sense(sp, stage);
                left = remindSeconds(stage);
            }
            t.putInt("remind", left);
        }
        sendStage(sp, asleep(sp) ? 0 : stage);
        slow(sp, SLOW, stage == 2 ? NeedsConfig.DROWSY_SPEED.get() : stage >= 3 ? NeedsConfig.STAGGER_SPEED.get() : 0);
        maxStamina(sp, stage == 2 ? NeedsConfig.STAGE2_MAX_STAMINA.get() : stage >= 3 ? NeedsConfig.STAGE3_MAX_STAMINA.get() : 0);
        if (asleep(sp) || sp.isSleeping()) return;
        if (stage == 3) {
            int left = t.getInt("micro");
            if (left <= 0) {
                int lo = NeedsConfig.MICROSLEEP_MIN_SECONDS.get(), hi = Math.max(lo, NeedsConfig.MICROSLEEP_MAX_SECONDS.get());
                t.putInt("micro", lo + sp.getRandom().nextInt(hi - lo + 1));
                if (was == 3) NeedsNetwork.send(sp, new NeedsNetwork.Microsleep(25 + sp.getRandom().nextInt(16)));
            } else t.putInt("micro", left - 1);
        } else if (stage == 4) {
            int left = t.getInt("collapse");
            if (left <= 0) {
                if (RpCoreAPI.collapseAsleep(sp)) sp.displayClientMessage(Component.translatable("rpneeds.msg.collapse"), true);
                t.putInt("collapse", NeedsConfig.COLLAPSE_EVERY_SECONDS.get());
            } else t.putInt("collapse", left - 1);
        }
    }

    /** Кому какую стадию уже сказали (для глаз клиента). */
    private static final java.util.Map<UUID, Integer> SENT_STAGE = new java.util.HashMap<>();

    private static void sendStage(ServerPlayer sp, int stage) {
        boolean blink = NeedsConfig.BLINK.get(), nod = NeedsConfig.NOD.get();
        int key = stage | (blink ? 8 : 0) | (nod ? 16 : 0);
        Integer sent = SENT_STAGE.get(sp.getUUID());
        if (sent != null && sent == key) return;
        SENT_STAGE.put(sp.getUUID(), key);
        NeedsNetwork.send(sp, new NeedsNetwork.Stage(stage, blink, nod));
    }

    private static int remindSeconds(int stage) {
        return switch (stage) {
            case 1 -> NeedsConfig.REMIND_STAGE1.get();
            case 2 -> NeedsConfig.REMIND_STAGE2.get();
            default -> NeedsConfig.REMIND_STAGE3.get();
        };
    }

    /** Ощущение стадии (одно из двух). */
    private static void sense(ServerPlayer sp, int stage) {
        sense(sp, "rpneeds.sense.s" + Math.min(3, stage) + "." + sp.getRandom().nextInt(2));
    }

    /** Ощущение в строке RP Medicine; без неё — в строке действия. Галочка {@code symptoms.sensations}. */
    static void sense(ServerPlayer sp, String key) {
        if (!NeedsConfig.SENSATIONS.get()) return;
        var body = RpCoreAPI.body();
        if (body != null && body.sensation(sp, key)) return;
        sp.displayClientMessage(Component.translatable(key).withStyle(net.minecraft.ChatFormatting.GRAY, net.minecraft.ChatFormatting.ITALIC), true);
    }

    /** Речь со стадии «Валится с ног»: редкие обрывы «...» (фильтр речи RP Core, только речь рядом, текстом). Зовётся и не с главного потока. */
    public static String filterSpeech(ServerPlayer sp, String text, RpCoreAPI.SpeechChannel channel) {
        if (channel != RpCoreAPI.SpeechChannel.VOICE_NEARBY || !NeedsConfig.SPEECH.get() || !counts(sp) || stage(sp, data(sp)) < 3 || text.isEmpty()) return text;
        String[] words = text.split(" ", -1);
        double p = NeedsConfig.SPEECH_CUT_CHANCE.get();
        int cuts = 0;
        for (int i = 1; i < words.length && cuts < 2; i++) {
            String w = words[i];
            if (w.length() < 4 || !w.chars().allMatch(ch -> Character.isLetter(ch) || ".,!?-".indexOf(ch) >= 0)
                    || java.util.concurrent.ThreadLocalRandom.current().nextDouble() >= p) continue;
            words[i] = w.substring(0, Math.max(2, w.length() / 2)) + "...";
            cuts++;
        }
        return cuts == 0 ? text : String.join(" ", words);
    }

    static void slow(ServerPlayer sp, UUID id, double amount) {
        AttributeInstance a = sp.getAttribute(Attributes.MOVEMENT_SPEED);
        if (a == null) return;
        AttributeModifier m = a.getModifier(id);
        if (m != null && Math.abs(m.getAmount() + amount) < 1e-6) return;
        a.removeModifier(id);
        if (amount > 0) a.addTransientModifier(new AttributeModifier(id, "rpneeds sleep", -amount, AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    /** Недосып: максимум выносливости меньше на долю {@code amount} (0 — снять). */
    static void maxStamina(ServerPlayer sp, double amount) {
        var attribute = RpCoreAPI.staminaAttribute(faygolover.rpcore.api.StaminaProvider.Stat.MAX);
        AttributeInstance a = attribute == null ? null : sp.getAttribute(attribute);
        if (a == null) return;
        AttributeModifier m = a.getModifier(MAX_STAMINA);
        if (m != null && Math.abs(m.getAmount() + amount) < 1e-6) return;
        a.removeModifier(MAX_STAMINA);
        if (amount > 0) a.addTransientModifier(new AttributeModifier(MAX_STAMINA, "rpneeds sleep debt", -amount, AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

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

    static void snore(ServerPlayer sp) {
        sp.serverLevel().playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.FOX_SLEEP, SoundSource.PLAYERS, 1.0f, 0.6f);
    }
}
