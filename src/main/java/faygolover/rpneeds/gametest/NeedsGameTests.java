package faygolover.rpneeds.gametest;

import com.mojang.authlib.GameProfile;
import faygolover.rpcore.api.RpCoreAPI;
import faygolover.rpcore.api.RpCoreAPI.Posture;
import faygolover.rpcore.api.StatusLines;
import faygolover.rpneeds.RpNeeds;
import faygolover.rpneeds.SleepService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Сон, время и питание вместе с семьёй RP в настоящем сервере (без клиента). Каждый тест — своя очередь (batch):
 * они двигают время мира и не должны мешать друг другу.
 */
@GameTestHolder(RpNeeds.MODID)
@PrefixGameTestTemplate(false)
public final class NeedsGameTests {
    private NeedsGameTests() {}

    static final String T = "platform";
    private static int counter;

    // ------------------------------------------------------------------ помощники

    static ServerPlayer player(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer p = new ServerPlayer(server, h.getLevel(), new GameProfile(UUID.randomUUID(), "rpn-test-" + (counter++)));
        Connection conn = new Connection(PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(conn);
        server.getPlayerList().placeNewPlayer(conn, p);
        p.setGameMode(GameType.SURVIVAL);
        Vec3 pos = h.absoluteVec(new Vec3(2.5, 1, 2.5));
        p.moveTo(pos.x, pos.y, pos.z, 0, 0);
        p.setOnGround(true);
        return p;
    }

    static void remove(GameTestHelper h, ServerPlayer p) {
        if (!p.isRemoved()) h.getLevel().getServer().getPlayerList().remove(p);
    }

    /** «Секунда» сна: обработчик RP Needs считает на тике, кратном 20, — у фальшивого игрока счётчик стоит на 0. */
    static void seconds(ServerPlayer p, int n) {
        for (int i = 0; i < n; i++) MinecraftForge.EVENT_BUS.post(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, p));
    }

    static void cmd(GameTestHelper h, String command) {
        MinecraftServer server = h.getLevel().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withPermission(4).withSuppressedOutput(), command);
    }

    static void as(ServerPlayer p, String command) {
        p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(4).withSuppressedOutput(), command);
    }

    static CompoundTag needs(ServerPlayer p) {
        return SleepService.data(p);
    }

    static double alert(ServerPlayer p) {
        return needs(p).getDouble("alert");
    }

    static void setAlert(ServerPlayer p, double v) {
        needs(p).putDouble("alert", v);
        needs(p).putDouble("debt", 0);
        needs(p).putInt("cooldown", 0);
        needs(p).putInt("retry", 0);
    }

    static double day() {
        return RpCoreAPI.dayLengthSeconds();
    }

    static long dayTime(GameTestHelper h) {
        return h.getLevel().getServer().overworld().getDayTime();
    }

    static void setDayTime(GameTestHelper h, long t) {
        for (var l : h.getLevel().getServer().getAllLevels()) l.setDayTime(t);
    }

    static faygolover.rpculinary.core.NutritionState food(ServerPlayer p) {
        return faygolover.rpculinary.server.CulinaryService.state(p);
    }

    static double satiety(ServerPlayer p) {
        return faygolover.rpculinary.core.Metabolism.satiety(food(p), faygolover.rpculinary.core.Settings.get());
    }

    static void setSatiety(ServerPlayer p, double share) {
        var m = food(p);
        m.stomach = 0;
        m.energy = share * faygolover.rpculinary.core.Settings.get().reserveKcal;
    }

    static BlockPos bed(GameTestHelper h) {
        BlockPos foot = new BlockPos(1, 1, 1), head = new BlockPos(1, 1, 2);
        h.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.FOOT));
        h.setBlock(head, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.HEAD));
        return h.absolutePos(head);
    }

    static void near(GameTestHelper h, double a, double b, double eps, String what) {
        h.assertTrue(Math.abs(a - b) <= eps, what + ": " + String.format("%.3f", a) + " вместо ~" + String.format("%.3f", b));
    }

    // ------------------------------------------------------------------ время

    /** /rptime add 2h: солнце на 2000 тиков, бодрость −10 % (2/24 суток из 5/6 суток бодрствования), еда тратится. */
    @GameTest(template = T, batch = "t_add")
    public static void addMovesSunAndBodies(GameTestHelper h) {
        ServerPlayer p = player(h);
        try {
            cmd(h, "rptime resume");
            setAlert(p, 100);
            setSatiety(p, 0.9);
            double sat = satiety(p);
            long before = dayTime(h);
            cmd(h, "rptime add 2h");
            h.assertTrue(dayTime(h) - before == 2000, "солнце сдвинулось на " + (dayTime(h) - before) + " тиков вместо 2000");
            near(h, alert(p), 90, 0.6, "бодрость после 2 ч RP");
            h.assertTrue(satiety(p) < sat, "еда не потратилась при /rptime add");
        } finally {
            remove(h, p);
        }
        h.succeed();
    }

    /** /rptime set 8:00 — вперёд до 8:00 (2000 тиков от 6:00). */
    @GameTest(template = T, batch = "t_set")
    public static void setForwardToClock(GameTestHelper h) {
        setDayTime(h, 12000);
        cmd(h, "rptime set 8:00");
        long t = dayTime(h);
        h.assertTrue(t % 24000 == 2000 && t > 12000, "после set 8:00 время " + t);
        h.succeed();
    }

    /** Мягкая прокрутка: сутки голода — еда, вода и бодрость не ниже 1/5 (и не ниже, чем были). */
    @GameTest(template = T, batch = "t_floor")
    public static void skipKeepsFloor(GameTestHelper h) {
        ServerPlayer p = player(h);
        try {
            cmd(h, "rptime resume");
            setAlert(p, 30);
            setSatiety(p, 0.3);
            food(p).water = 30;
            cmd(h, "rptime add 2d");
            h.assertTrue(alert(p) >= 19.9, "бодрость ниже порога: " + alert(p));
            h.assertTrue(needs(p).getDouble("debt") <= 0, "недосып от прокрутки");
            h.assertTrue(satiety(p) >= 0.199, "сытость ниже порога: " + satiety(p));
            h.assertTrue(food(p).water >= 19.9, "вода ниже порога: " + food(p).water);
        } finally {
            remove(h, p);
        }
        h.succeed();
    }

    /** Пауза: бодрость и еда стоят; после resume — идут. */
    @GameTest(template = T, batch = "t_pause")
    public static void pauseStopsEverything(GameTestHelper h) {
        ServerPlayer p = player(h);
        try {
            setAlert(p, 80);
            setSatiety(p, 0.8);
            cmd(h, "rptime pause");
            h.assertTrue(!RpCoreAPI.timeRunning(), "после /rptime pause время RP идёт");
            double a = alert(p), sat = satiety(p);
            seconds(p, 120);
            near(h, alert(p), a, 1e-9, "бодрость в паузе");
            near(h, satiety(p), sat, 1e-9, "сытость в паузе");
            cmd(h, "rptime resume");
            h.assertTrue(RpCoreAPI.timeRunning(), "после /rptime resume время RP стоит");
            seconds(p, 60);
            h.assertTrue(alert(p) < a, "после resume бодрость не тратится");
        } finally {
            cmd(h, "rptime resume");
            remove(h, p);
        }
        h.succeed();
    }

    /** /rptime sleep add 1h: стоящий садится и спит сидя ×0,3 ×0,8 (+12 %), после — просыпается и сидит. */
    @GameTest(template = T, batch = "t_sleep")
    public static void sleepSkipSitsStanding(GameTestHelper h) {
        ServerPlayer p = player(h);
        try {
            cmd(h, "rptime resume");
            setAlert(p, 50);
            cmd(h, "rptime sleep add 1h");
            h.assertTrue(RpCoreAPI.posture(p) == Posture.SIT, "стоящий не сел: " + RpCoreAPI.posture(p));
            h.assertTrue(!RpCoreAPI.postureSleeping(p), "после прокрутки сна не проснулся");
            near(h, alert(p), 62, 0.6, "бодрость после часа сна сидя");
        } finally {
            remove(h, p);
        }
        h.succeed();
    }

    /** /rptime player add: тела — да, солнце — нет. */
    @GameTest(template = T, batch = "t_player")
    public static void playerAddKeepsSun(GameTestHelper h) {
        ServerPlayer p = player(h);
        try {
            cmd(h, "rptime resume");
            setAlert(p, 100);
            long before = dayTime(h);
            cmd(h, "rptime player add " + p.getGameProfile().getName() + " 2h");
            h.assertTrue(dayTime(h) == before, "солнце сдвинулось");
            near(h, alert(p), 90, 0.6, "бодрость после 2 ч RP");
        } finally {
            remove(h, p);
        }
        h.succeed();
    }

    /** Все ветки /rptime на месте (Core + RP Time в одном узле), старых нет; ванильный /time без наших веток. */
    @GameTest(template = T, batch = "t_tree")
    public static void commandTree(GameTestHelper h) {
        var root = h.getLevel().getServer().getCommands().getDispatcher().getRoot();
        var rp = root.getChild("rptime");
        h.assertTrue(rp != null, "нет /rptime");
        for (String n : new String[]{"pause", "resume", "add", "set", "sleep", "player", "info", "length", "speed", "night", "growth"})
            h.assertTrue(rp.getChild(n) != null, "нет /rptime " + n);
        for (String n : new String[]{"start", "stop", "skip", "exclude"})
            h.assertTrue(rp.getChild(n) == null, "осталась старая /rptime " + n);
        var time = root.getChild("time");
        for (String n : new String[]{"info", "pause", "night", "exclude", "length"})
            h.assertTrue(time == null || time.getChild(n) == null, "в ванильной /time осталась ветка " + n);
        h.succeed();
    }

    // ------------------------------------------------------------------ кровать

    /** Днём лечь можно; при 80 % не засыпает, при 50 % — засыпает. */
    @GameTest(template = T, batch = "b_day")
    public static void bedDayThreshold(GameTestHelper h) {
        ServerPlayer p = player(h);
        try {
            cmd(h, "rptime resume");
            setDayTime(h, 6000);
            BlockPos bed = bed(h);
            setAlert(p, 80);
            var r = p.startSleepInBed(bed);
            h.assertTrue(p.isSleeping(), "днём не лёг в кровать: " + r.left().map(Object::toString).orElse("?"));
            seconds(p, 3);
            h.assertTrue(!needs(p).getBoolean("asleep"), "уснул днём при 80 %");
            setAlert(p, 50);
            seconds(p, 2);
            h.assertTrue(needs(p).getBoolean("asleep"), "не уснул днём при 50 %");
            double a = alert(p);
            seconds(p, 10);
            h.assertTrue(alert(p) > a, "во сне бодрость не растёт");
        } finally {
            remove(h, p);
        }
        h.succeed();
    }

    /** Ночью засыпает ниже 90 %. Удар будит (и поднимает с кровати), откат 2 минуты. */
    @GameTest(template = T, batch = "b_night")
    public static void bedNightHurtCooldown(GameTestHelper h) {
        ServerPlayer p = player(h);
        try {
            cmd(h, "rptime resume");
            setDayTime(h, 18000);
            BlockPos bed = bed(h);
            setAlert(p, 85);
            p.startSleepInBed(bed);
            h.assertTrue(p.isSleeping(), "ночью не лёг");
            seconds(p, 1);
            h.assertTrue(needs(p).getBoolean("asleep"), "ночью не уснул при 85 %");
            p.hurt(p.damageSources().fellOutOfWorld(), 1);
            h.assertTrue(!needs(p).getBoolean("asleep"), "удар не разбудил");
            h.assertTrue(needs(p).getInt("cooldown") > 0, "нет отката после пробуждения");
            // Удар поднимает с кровати (ванилла); ложится снова — в откат не засыпает.
            if (!p.isSleeping()) p.startSleepInBed(bed);
            seconds(p, 20);
            h.assertTrue(!needs(p).getBoolean("asleep"), "уснул во время отката");
        } finally {
            remove(h, p);
        }
        h.succeed();
    }

    /** Творческий: ложится днём и засыпает сразу. */
    @GameTest(template = T, batch = "b_creative")
    public static void creativeSleeps(GameTestHelper h) {
        ServerPlayer p = player(h);
        try {
            setDayTime(h, 6000);
            BlockPos bed = bed(h);
            p.setGameMode(GameType.CREATIVE);
            p.startSleepInBed(bed);
            h.assertTrue(p.isSleeping(), "творческий не лёг днём");
            seconds(p, 1);
            h.assertTrue(needs(p).getBoolean("asleep"), "творческий не уснул");
        } finally {
            remove(h, p);
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ позы

    /** /sit и /lay: поза и хитбокс; /sleep бодрому — нельзя, уставшему — можно; удар будит, поза остаётся. */
    @GameTest(template = T, batch = "p_pose")
    public static void posturesAndSleep(GameTestHelper h) {
        ServerPlayer p = player(h);
        try {
            cmd(h, "rptime resume");
            setDayTime(h, 6000);
            as(p, "sit");
            h.assertTrue(RpCoreAPI.posture(p) == Posture.SIT, "/sit не посадил");
            near(h, p.getBbHeight(), 1.0, 0.01, "рост хитбокса сидя");
            as(p, "lay");
            h.assertTrue(RpCoreAPI.posture(p) == Posture.LAY, "/lay не уложил");
            near(h, p.getBbHeight(), 0.6, 0.01, "рост хитбокса лёжа");
            setAlert(p, 100);
            as(p, "sleep");
            h.assertTrue(!RpCoreAPI.postureSleeping(p), "уснул бодрым днём");
            setAlert(p, 50);
            as(p, "sleep");
            h.assertTrue(RpCoreAPI.postureSleeping(p), "не уснул лёжа при 50 %");
            p.hurt(p.damageSources().fellOutOfWorld(), 1);
            h.assertTrue(!RpCoreAPI.postureSleeping(p), "удар не разбудил");
            h.assertTrue(RpCoreAPI.posture(p) == Posture.LAY, "проснулся и встал сам");
            as(p, "lay");
            h.assertTrue(RpCoreAPI.posture(p) == Posture.NONE, "повторный /lay не поднял");
            near(h, p.getBbHeight(), 1.8, 0.01, "рост хитбокса стоя");
        } finally {
            remove(h, p);
        }
        h.succeed();
    }

    /** Будильник будит спящего рядом. */
    @GameTest(template = T, batch = "p_alarm")
    public static void alarmWakes(GameTestHelper h) {
        ServerPlayer p = player(h);
        try {
            cmd(h, "rptime resume");
            setAlert(p, 40);
            as(p, "lay");
            as(p, "sleep");
            h.assertTrue(RpCoreAPI.postureSleeping(p), "не уснул");
            RpCoreAPI.alarm(h.getLevel(), p.position(), 5);
            h.assertTrue(!RpCoreAPI.postureSleeping(p), "будильник не разбудил");
        } finally {
            remove(h, p);
        }
        h.succeed();
    }

    /** Недосып больше 1/6 суток: валится и засыпает на земле. */
    @GameTest(template = T, batch = "p_collapse")
    public static void collapseAsleep(GameTestHelper h) {
        ServerPlayer p = player(h);
        try {
            cmd(h, "rptime resume");
            setAlert(p, 0);
            needs(p).putDouble("debt", day() / 6 + 60);
            seconds(p, 1);
            h.assertTrue(RpCoreAPI.posture(p) == Posture.LAY && RpCoreAPI.postureSleeping(p),
                    "не свалился спать: поза " + RpCoreAPI.posture(p) + ", спит " + RpCoreAPI.postureSleeping(p));
        } finally {
            remove(h, p);
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ Медицина

    /** Нитразепам: действует сразу, засыпает при 100 % и в откат; бодрость падает на 0,5 %/с. */
    @GameTest(template = T, batch = "m_pill")
    public static void sleepingPill(GameTestHelper h) {
        ServerPlayer p = player(h);
        try {
            cmd(h, "rptime resume");
            setDayTime(h, 6000);
            var m = faygolover.rpmedicine.server.Medical.state(p);
            var d = faygolover.rpmedicine.core.DrugLevels.resolver.apply("rpmedicine:nitrazepam");
            if (d == null) d = faygolover.rpmedicine.core.DrugLevels.resolver.apply("nitrazepam");
            h.assertTrue(d != null, "нет препарата nitrazepam");
            faygolover.rpmedicine.core.DrugLevels.give(m, d, 1, faygolover.rpmedicine.core.DrugLevels.Route.ORAL,
                    faygolover.rpmedicine.core.MedicalSettings.get());
            h.assertTrue(RpCoreAPI.body().sleepAid(p) > 0, "снотворное не действует сразу");
            setAlert(p, 100);
            seconds(p, 10);
            h.assertTrue(alert(p) < 96, "бодрость не падает от снотворного: " + alert(p));
            needs(p).putInt("cooldown", 100);
            as(p, "lay");
            as(p, "sleep");
            h.assertTrue(RpCoreAPI.postureSleeping(p), "со снотворным не уснул бодрым в откат");
        } finally {
            remove(h, p);
        }
        h.succeed();
    }

    /** «Отлежал»: после сна лёжа — боль в мышцах на 1/36 суток. */
    @GameTest(template = T, batch = "m_numb")
    public static void numbAfterLying(GameTestHelper h) {
        ServerPlayer p = player(h);
        var s = faygolover.rpmedicine.core.MedicalSettings.get();
        double chance = s.numbChance, min = s.numbMinSleepSeconds;
        try {
            s.numbChance = 1;
            s.numbMinSleepSeconds = 0;
            cmd(h, "rptime resume");
            setAlert(p, 40);
            as(p, "lay");
            as(p, "sleep");
            as(p, "sleep");
            var m = faygolover.rpmedicine.server.Medical.state(p);
            boolean found = false;
            for (var ps : m.parts) {
                if (ps.muscle == 1) {
                    found = true;
                    near(h, ps.muscleSeconds, day() / 36, 2, "длительность «отлежал»");
                }
            }
            h.assertTrue(found, "после сна лёжа не затекло");
        } finally {
            s.numbChance = chance;
            s.numbMinSleepSeconds = min;
            remove(h, p);
        }
        h.succeed();
    }

    /** Строки для Медицины: ГМ видит цифры, сам — ощущение стадии, другие — только сильную усталость. */
    @GameTest(template = T, batch = "m_lines")
    public static void statusLines(GameTestHelper h) {
        ServerPlayer p = player(h);
        try {
            setAlert(p, 10);
            List<StatusLines.Line> gm = StatusLines.collect(p, StatusLines.Viewer.GM);
            h.assertTrue(gm.stream().anyMatch(l -> l.key().equals("rpneeds.status.gm")), "нет строки для ГМа");
            List<StatusLines.Line> self = StatusLines.collect(p, StatusLines.Viewer.SELF);
            h.assertTrue(self.stream().anyMatch(l -> l.key().equals("rpneeds.status.stage1")), "сам не чувствует «хочется спать»");
            List<StatusLines.Line> other = StatusLines.collect(p, StatusLines.Viewer.OTHER);
            h.assertTrue(other.stream().noneMatch(l -> l.key().contains("exhausted")), "другие видят лёгкую усталость");
            h.assertTrue(other.stream().anyMatch(l -> l.key().equals("rpneeds.status.other_stage1")), "другие не видят, что зевает");
            h.assertTrue(other.stream().noneMatch(l -> l.key().startsWith("rpneeds.status.doc_")), "не врач видит признаки врача");
            List<StatusLines.Line> doctor = StatusLines.collect(p, StatusLines.Viewer.OTHER, 3);
            h.assertTrue(doctor.stream().anyMatch(l -> l.key().equals("rpneeds.status.doc_circles")), "врач не видит кругов под глазами");
            h.assertTrue(faygolover.rpneeds.SleepService.filterSpeech(p, "раз два три", faygolover.rpcore.api.RpCoreAPI.SpeechChannel.values()[0])
                    .equals("раз два три"), "речь на 1-й стадии не должна рваться");
        } finally {
            remove(h, p);
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ выход из игры и еда

    /** Не был в сети два полных сна (30 мин при сутках 3 ч) — выспался. */
    @GameTest(template = T, batch = "o_rest")
    public static void offlineRest(GameTestHelper h) {
        ServerPlayer p = player(h);
        try {
            setAlert(p, 30);
            double need = 2 * day() / 12;
            needs(p).putLong("logout", System.currentTimeMillis() - (long) (need * 1000) - 5000);
            needs(p).putLong("restDay", 0);
            MinecraftForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(p));
            near(h, alert(p), 100, 1e-6, "бодрость после долгого выхода");
        } finally {
            remove(h, p);
        }
        h.succeed();
    }

    /** Скорость расхода еды: при 2 за то же время тратится вдвое больше. */
    @GameTest(template = T, batch = "f_speed")
    public static void consumptionSpeed(GameTestHelper h) {
        ServerPlayer p = player(h);
        var s = faygolover.rpculinary.core.Settings.get();
        double old = s.consumptionSpeed;
        try {
            cmd(h, "rptime resume");
            String name = p.getGameProfile().getName();
            setSatiety(p, 0.9);
            double a = satiety(p);
            cmd(h, "rptime player add " + name + " 2h");
            double one = a - satiety(p);
            s.consumptionSpeed = 2;
            setSatiety(p, 0.9);
            a = satiety(p);
            cmd(h, "rptime player add " + name + " 2h");
            double two = a - satiety(p);
            h.assertTrue(one > 0, "еда не тратится");
            near(h, two / one, 2, 0.25, "отношение расхода при скорости 2");
        } finally {
            s.consumptionSpeed = old;
            remove(h, p);
        }
        h.succeed();
    }
}
