package faygolover.rpneeds;

import faygolover.rpcore.api.Consumables;
import faygolover.rpcore.api.NutritionProvider;
import faygolover.rpcore.api.RpCoreAPI;
import faygolover.rpcore.api.event.RpConsumeEvent;
import faygolover.rpcore.api.event.RpPostureSleepEvent;
import faygolover.rpcore.api.event.RpTimeSkipEvent;
import faygolover.rpcore.api.event.RpTimeSkipOfflineEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.event.entity.player.SleepingTimeCheckEvent;
import net.minecraftforge.eventbus.api.Event;

import java.util.UUID;

/**
 * Сон (сервер). Бодрость 0–100 тратится по времени RP (пауза — стоит, прокрутка — идёт мягко, не ниже порога Core),
 * восстанавливается во сне: кровать, спальник, сидя или лёжа (RP Core {@code /sleep}). Ниже нуля копится недосып:
 * сонливость → валится с ног (микросны) → засыпает где стоит. Урона и смерти от недосыпа нет.
 * Данные — в {@code getPersistentData()} игрока, раздел {@code rpneeds}.
 */
public final class SleepService {
    private SleepService() {}

    static final String TAG = "rpneeds";
    public static final TagKey<Block> SLEEPING_BAGS = BlockTags.create(new ResourceLocation(RpNeeds.MODID, "sleeping_bags"));
    private static final UUID SLOW = UUID.fromString("3b1e9f0a-7c52-4d8e-a1f6-2e9b5c7d4a10");

    /** Стадии: 0 бодр, 1 хочется спать, 2 сонливость, 3 валится с ног, 4 засыпает на ходу. */
    public static int stage(CompoundTag t) {
        double alert = t.getDouble("alert"), debt = t.getDouble("debt"), day = day();
        if (alert > NeedsConfig.SLEEPY_BELOW.get()) return 0;
        if (alert > 0 || debt <= 0) return 1;
        if (debt < NeedsConfig.DROWSY_DEBT_SHARE.get() * day) return 2;
        if (debt < NeedsConfig.STAGGER_DEBT_SHARE.get() * day) return 3;
        return 4;
    }

    static double day() {
        return Math.max(60, RpCoreAPI.dayLengthSeconds());
    }

    public static CompoundTag data(Player p) {
        CompoundTag root = p.getPersistentData();
        if (!root.contains(TAG)) {
            CompoundTag t = new CompoundTag();
            t.putDouble("alert", 100);
            root.put(TAG, t);
        }
        return root.getCompound(TAG);
    }

    /** Бодрость в процентах и недосып в секундах. Ниже нуля бодрость не уходит — дальше растёт недосып. */
    static void change(CompoundTag t, double delta) {
        double alert = t.getDouble("alert"), debt = t.getDouble("debt");
        if (delta >= 0) {
            // Сон сначала гасит недосып (в процентах бодрости по той же скорости), потом поднимает бодрость.
            double perSecondAwake = 100.0 / (NeedsConfig.AWAKE_SHARE.get() * day());
            double debtPct = debt * perSecondAwake;
            double pay = Math.min(debtPct, delta);
            debt -= pay / perSecondAwake;
            alert = Math.min(100, alert + delta - pay);
        } else {
            alert += delta;
            if (alert < 0) {
                double perSecondAwake = 100.0 / (NeedsConfig.AWAKE_SHARE.get() * day());
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

    // ------------------------------------------------------------------ тик

    public static void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.player instanceof ServerPlayer sp) || sp.tickCount % 20 != 0) return;
        CompoundTag t = data(sp);
        if (!counts(sp) || !RpCoreAPI.timeRunning()) {
            slow(sp, 0);
            return;
        }
        double day = day();
        boolean bed = sp.isSleeping(), posture = RpCoreAPI.postureSleeping(sp);
        if (bed || posture) {
            double rate = 100.0 / (NeedsConfig.FULL_SLEEP_SHARE.get() * day) * sleepMult(sp, bed);
            change(t, rate);
            if (t.getDouble("alert") >= 100 && t.getDouble("debt") <= 0) {
                if (bed) sp.stopSleepInBed(false, true);
                else RpCoreAPI.wakeFromPosture(sp);
                sp.displayClientMessage(Component.translatable("rpneeds.msg.rested"), true);
            }
        } else {
            double mult = RpCoreAPI.posture(sp) != RpCoreAPI.Posture.NONE ? NeedsConfig.RESTING_DRAIN_MULT.get() : 1.0;
            if (t.getDouble("cafLeft") > 0) mult *= NeedsConfig.CAFFEINE_DRAIN_MULT.get();
            change(t, -100.0 / (NeedsConfig.AWAKE_SHARE.get() * day) * mult);
        }
        caffeineTick(sp, t, 1);
        effects(sp, t);
    }

    private static double sleepMult(ServerPlayer sp, boolean bed) {
        double m;
        if (!bed) m = NeedsConfig.POSTURE_MULT.get();
        else {
            BlockPos pos = sp.getSleepingPos().orElse(null);
            m = pos != null && sp.level().getBlockState(pos).is(SLEEPING_BAGS) ? NeedsConfig.BAG_MULT.get() : 1.0;
        }
        NutritionProvider n = RpCoreAPI.nutrition();
        if (n != null && (n.hungerStage(sp) >= 2 || n.thirstStage(sp) >= 2)) m *= NeedsConfig.HUNGRY_SLEEP_MULT.get();
        return m;
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

    /** Стадии: сообщения при смене, замедление, микросны, падение без сил. */
    private static void effects(ServerPlayer sp, CompoundTag t) {
        int stage = stage(t);
        int was = t.getInt("stage");
        if (stage != was) {
            t.putInt("stage", stage);
            if (stage > was && stage > 0) sp.displayClientMessage(Component.translatable("rpneeds.msg.stage" + stage), true);
            t.putInt("micro", 0);
            t.putInt("collapse", 0);
        }
        slow(sp, stage == 2 ? NeedsConfig.DROWSY_SPEED.get() : stage >= 3 ? NeedsConfig.STAGGER_SPEED.get() : 0);
        if (sp.isSleeping() || RpCoreAPI.postureSleeping(sp)) return;
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

    private static void slow(ServerPlayer sp, double amount) {
        AttributeInstance a = sp.getAttribute(Attributes.MOVEMENT_SPEED);
        if (a == null) return;
        AttributeModifier m = a.getModifier(SLOW);
        if (m != null && Math.abs(m.getAmount() + amount) < 1e-6) return;
        a.removeModifier(SLOW);
        if (amount > 0) a.addTransientModifier(new AttributeModifier(SLOW, "rpneeds sleep", -amount, AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    // ------------------------------------------------------------------ можно ли уснуть

    /** Кровать: днём — только уставшим; под кофеином (пока бодрость выше 0) — нельзя. */
    public static void onSleepInBed(PlayerSleepInBedEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp) || !counts(sp)) return;
        CompoundTag t = data(sp);
        if (t.getDouble("cafLeft") > 0 && t.getDouble("alert") > 0) {
            e.setResult(Player.BedSleepingProblem.OTHER_PROBLEM);
            sp.displayClientMessage(Component.translatable("rpneeds.msg.caffeine"), true);
        }
    }

    /** Днём в кровати можно спать, если бодрость ниже порога. */
    public static void onSleepTime(SleepingTimeCheckEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp) || !counts(sp) || !sp.level().isDay()) return;
        if (data(sp).getDouble("alert") < NeedsConfig.DAY_SLEEP_BELOW.get()) e.setResult(Event.Result.ALLOW);
    }

    /** Сидя или лёжа ({@code /sleep}): бодрому не спится, под кофеином — тоже. */
    public static void onPostureSleep(RpPostureSleepEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp) || !counts(sp)) return;
        CompoundTag t = data(sp);
        if (t.getDouble("cafLeft") > 0 && t.getDouble("alert") > 0) e.deny(Component.translatable("rpneeds.msg.caffeine"));
        else if (t.getDouble("alert") >= NeedsConfig.POSTURE_SLEEP_BELOW.get()) e.deny(Component.translatable("rpneeds.msg.not_sleepy"));
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

    /**
     * Прокрутка {@code /rptime} и ускоренная ночь RP Time: спящий высыпается по прошедшему времени RP; бодрствующий
     * устаёт мягко — не ниже порога Core (как еда и вода) и не ниже, чем был.
     */
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

    /** Ночь проспали, пока кто-то был вне сети: при входе он выспался. */
    public static void onSkipOffline(RpTimeSkipOfflineEvent e) {
        if (e.sleeping()) NightMark.get(e.server()).mark(System.currentTimeMillis());
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) {
            data(sp).putLong("logout", System.currentTimeMillis());
            slow(sp, 0);
        }
    }

    /**
     * Вход: не был в сети не меньше {@code offline_rest_sleeps} полных снов — выспался (не чаще раза в игровые сутки);
     * ночь проспали без него — тоже выспался.
     */
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp)) return;
        CompoundTag t = data(sp);
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
            if (longAway) t.putLong("restDay", gameDay + 1);
        }
    }

    /** После смерти данные сна остаются. */
    public static void onClone(PlayerEvent.Clone e) {
        CompoundTag old = e.getOriginal().getPersistentData();
        if (old.contains(TAG)) e.getEntity().getPersistentData().put(TAG, old.getCompound(TAG).copy());
    }
}
