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
    public static final TagKey<Block> SLEEPING_BAGS = BlockTags.create(new ResourceLocation(RpNeeds.MODID, "sleeping_bags"));
    private static final UUID SLOW = UUID.fromString("3b1e9f0a-7c52-4d8e-a1f6-2e9b5c7d4a10");
    private static final UUID HEAVY = UUID.fromString("5a2c8e41-9b07-4f3d-8c6e-1d4f7a9b3e22");

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

    static double day() {
        return Math.max(60, RpCoreAPI.dayLengthSeconds());
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
        if (sleepAid(sp) > 0) return null;
        if (t.getInt("cooldown") > 0) return "rpneeds.msg.just_woke";
        if (t.getDouble("cafLeft") > 0 && t.getDouble("alert") > 0) return "rpneeds.msg.caffeine";
        BodyProvider b = RpCoreAPI.body();
        if (b != null && b.pain(sp) > NeedsConfig.PAIN_NO_SLEEP.get() && b.sedation(sp) < 20) return "rpneeds.msg.pain";
        double limit = sp.level().isNight() ? NeedsConfig.NIGHT_SLEEP_BELOW.get() : NeedsConfig.DAY_SLEEP_BELOW.get();
        if (t.getDouble("alert") >= limit) return "rpneeds.msg.not_sleepy";
        return null;
    }

    /** Бессонница: 30 % попыток уснуть не выходят. */
    static boolean insomniaFails(ServerPlayer sp) {
        return sleepAid(sp) <= 0 && has(sp, INSOMNIA) && sp.getRandom().nextDouble() < 0.3;
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
            // Творческий: потребности не идут, но в кровати засыпает сразу и спит, пока не встанет.
            if (sp.isCreative() && sp.isSleeping() && !t.getBoolean("asleep")) setBedAsleep(sp, t, true);
            if (!sp.isSleeping() && t.getBoolean("asleep")) setBedAsleep(sp, t, false);
            return;
        }
        timers(sp, t);
        if (!RpCoreAPI.timeRunning()) return;
        double day = day();

        // Кровать: лёг — не значит уснул.
        if (sp.isSleeping() && !t.getBoolean("asleep")) tryFallAsleepInBed(sp, t);
        if (!sp.isSleeping() && t.getBoolean("asleep")) setBedAsleep(sp, t, false);

        boolean asleep = asleep(sp);
        if (asleep) {
            int place = place(sp);
            double rate = 100.0 / (NeedsConfig.FULL_SLEEP_SHARE.get() * day) * placeMult(sp, place);
            if (has(sp, SHORT)) rate *= 1.5;
            if (has(sp, INSOMNIA)) rate *= 0.7;
            NutritionProvider n = RpCoreAPI.nutrition();
            if (n != null && (n.hungerStage(sp) >= 2 || n.thirstStage(sp) >= 2)) rate *= NeedsConfig.HUNGRY_SLEEP_MULT.get();
            change(t, rate);
            t.putInt("place", place);
            Wakers.asleepTick(sp, t, place);
            if (t.getDouble("alert") >= 100 && t.getDouble("debt") <= 0) {
                wake(sp, "rpneeds.msg.rested", false);
                t.putInt("rested", (int) (day / 6));
                if (place == 0 || place == 2 || has(sp, UNFUSSY)) MinecraftForge.EVENT_BUS.post(new RpMoodEvent(sp, RpMoodEvent.Kind.RESTED, 1));
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
        }
        t.putDouble("cafLeft", left);
    }

    /** Стадии: сообщение при ухудшении, замедление, микросны, падение без сил. */
    private static void effects(ServerPlayer sp, CompoundTag t) {
        int stage = stage(sp, t);
        int was = t.getInt("stage");
        if (stage != was) {
            t.putInt("stage", stage);
            if (stage > was && stage > 0) sp.displayClientMessage(Component.translatable("rpneeds.msg.stage" + stage), true);
            t.putInt("micro", 0);
            t.putInt("collapse", 0);
        }
        slow(sp, SLOW, stage == 2 ? NeedsConfig.DROWSY_SPEED.get() : stage >= 3 ? NeedsConfig.STAGGER_SPEED.get() : 0);
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

    static void slow(ServerPlayer sp, UUID id, double amount) {
        AttributeInstance a = sp.getAttribute(Attributes.MOVEMENT_SPEED);
        if (a == null) return;
        AttributeModifier m = a.getModifier(id);
        if (m != null && Math.abs(m.getAmount() + amount) < 1e-6) return;
        a.removeModifier(id);
        if (amount > 0) a.addTransientModifier(new AttributeModifier(id, "rpneeds sleep", -amount, AttributeModifier.Operation.MULTIPLY_TOTAL));
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

    /** Удар будит и в кровати (позу будит RP Core). */
    public static void onHurt(LivingHurtEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp && e.getAmount() > 0 && sp.isSleeping() && data(sp).getBoolean("asleep"))
            wake(sp, "rpneeds.msg.woke_hurt", false);
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
        double day = day(), secs = e.seconds();
        if (e.sleeping()) change(t, 100.0 / (NeedsConfig.FULL_SLEEP_SHARE.get() * day) * secs);
        else {
            double before = t.getDouble("alert");
            change(t, -100.0 / (NeedsConfig.AWAKE_SHARE.get() * day) * secs);
            double floor = Math.min(RpCoreAPI.skipFloor() * 100, before);
            if (t.getDouble("alert") < floor) {
                t.putDouble("alert", floor);
                t.putDouble("debt", 0);
            }
        }
        caffeineTick(sp, t, secs);
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

    /** После смерти данные сна остаются (без сна и таймеров). */
    public static void onClone(PlayerEvent.Clone e) {
        CompoundTag old = e.getOriginal().getPersistentData();
        if (!old.contains(TAG)) return;
        CompoundTag t = old.getCompound(TAG).copy();
        t.putBoolean("asleep", false);
        t.putInt("heavy", 0);
        e.getEntity().getPersistentData().put(TAG, t);
    }

    static void snore(ServerPlayer sp) {
        sp.serverLevel().playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.FOX_SLEEP, SoundSource.PLAYERS, 1.0f, 0.6f);
    }
}
