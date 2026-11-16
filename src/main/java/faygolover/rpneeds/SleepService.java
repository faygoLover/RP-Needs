package faygolover.rpneeds;

import static faygolover.rpneeds.SleepPlace.*;
import faygolover.rpcore.api.BodyProvider;
import faygolover.rpcore.api.NutritionProvider;
import faygolover.rpcore.api.RpCoreAPI;
import faygolover.rpcore.api.RpCoreAPI.Posture;
import faygolover.rpcore.api.RpIds;
import faygolover.rpcore.api.event.RpMoodEvent;
import faygolover.rpcore.api.event.RpPostureWakeEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

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
    static final UUID SLOW = UUID.fromString("3b1e9f0a-7c52-4d8e-a1f6-2e9b5c7d4a10");
    static final UUID HEAVY = UUID.fromString("5a2c8e41-9b07-4f3d-8c6e-1d4f7a9b3e22");
    static final UUID MAX_STAMINA = UUID.fromString("8e7d4c12-3f6a-4b59-a0d7-6c1e9f2b5d33");

    // ------------------------------------------------------------------ черты (RP Perks: rpneeds:<id>)

    static final ResourceLocation UNFUSSY = trait("unfussy"), DEEP = trait("deep_sleeper"), SHORT = trait("short_sleeper"),
            TIRELESS = trait("tireless"), EARLY_BIRD = trait("early_bird"), NIGHT_OWL = trait("night_owl"),
            INSOMNIA = trait("insomnia"), LIGHT = trait("light_sleeper"), SLEEPYHEAD = trait("sleepyhead"),
            NIGHTMARES = trait("nightmares"), HEAVY_WAKING = trait("heavy_waking"), SNORER = trait("snorer");

    static ResourceLocation trait(String id) {
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
    static double hourMult(ServerPlayer sp) {
        boolean lark = has(sp, EARLY_BIRD), owl = has(sp, NIGHT_OWL);
        if (!lark && !owl) return 1;
        long dt = sp.server.overworld().getDayTime() % 24000L;
        double hour = ((dt / 1000.0) + 6) % 24;
        if (lark) return hour >= 6 && hour < 12 ? 0.7 : (hour >= 22 || hour < 2) ? 1.3 : 1;
        return (hour >= 18 || hour < 2) ? 0.7 : (hour >= 6 && hour < 12) ? 1.3 : 1;
    }

    static void timers(ServerPlayer sp, CompoundTag t) {
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

    static void tryFallAsleepInBed(ServerPlayer sp, CompoundTag t) {
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

    static void caffeineTick(ServerPlayer sp, CompoundTag t, double seconds) {
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
    static void effects(ServerPlayer sp, CompoundTag t) {
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
    static final java.util.Map<UUID, Integer> SENT_STAGE = new java.util.HashMap<>();

    static void sendStage(ServerPlayer sp, int stage) {
        boolean blink = NeedsConfig.BLINK.get(), nod = NeedsConfig.NOD.get();
        int key = stage | (blink ? 8 : 0) | (nod ? 16 : 0);
        Integer sent = SENT_STAGE.get(sp.getUUID());
        if (sent != null && sent == key) return;
        SENT_STAGE.put(sp.getUUID(), key);
        NeedsNetwork.send(sp, new NeedsNetwork.Stage(stage, blink, nod));
    }

    static int remindSeconds(int stage) {
        return switch (stage) {
            case 1 -> NeedsConfig.REMIND_STAGE1.get();
            case 2 -> NeedsConfig.REMIND_STAGE2.get();
            default -> NeedsConfig.REMIND_STAGE3.get();
        };
    }

    /** Ощущение стадии (одно из двух). */
    static void sense(ServerPlayer sp, int stage) {
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

    static void snore(ServerPlayer sp) {
        sp.serverLevel().playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.FOX_SLEEP, SoundSource.PLAYERS, 1.0f, 0.6f);
    }
}
