package faygolover.rpneeds.gametest;

import faygolover.rpneeds.RpNeeds;
import faygolover.rpneeds.SleepService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Края состояния сна: смерть, возврат из Края, перезаход. Данные лежат в {@code getPersistentData()} игрока, раздел
 * {@code rpneeds}; ваниль его при возрождении не переносит, это делает {@code SleepService.onClone}. Страховка перед
 * переводом на общий механизм RP Core (план {@code _family/refactor}, задача T0.3): тесты обязаны проходить и до, и после.
 */
@GameTestHolder(RpNeeds.MODID)
@PrefixGameTestTemplate(false)
public final class NeedsEdgeTests {
    private NeedsEdgeTests() {}

    static final String T = NeedsGameTests.T;
    /** Раздел в ForgeData игрока. Менять нельзя: так лежит в сохранениях живого сервера. */
    static final String TAG = "rpneeds";

    static void fill(ServerPlayer p) {
        CompoundTag t = SleepService.data(p);
        t.putDouble("debt", 5.5);
        t.putInt("stage", 2);
        t.putDouble("cafLeft", 1.5);
        t.putDouble("cafDose", 2.5);
        t.putInt("rested", 3);
        t.putBoolean("asleep", true);
        t.putInt("heavy", 4);
    }

    /** Возрождение переносит раздел целиком, но сон и тяжёлое пробуждение снимает. Одинаково при смерти и при возврате из Края. */
    private static void respawnCopies(GameTestHelper h, boolean keepEverything) {
        ServerPlayer p = NeedsGameTests.player(h);
        fill(p);
        CompoundTag expected = p.getPersistentData().getCompound(TAG).copy();
        expected.putBoolean("asleep", false);
        expected.putInt("heavy", 0);
        ServerPlayer n = h.getLevel().getServer().getPlayerList().respawn(p, keepEverything);
        try {
            CompoundTag now = n.getPersistentData().getCompound(TAG);
            if (!expected.equals(now)) h.fail("После возрождения раздел rpneeds: ждали " + expected + ", получили " + now);
        } finally {
            NeedsGameTests.remove(h, n);
        }
        h.succeed();
    }

    @GameTest(template = T, batch = "needs_edge_death")
    public static void deathCopiesTagAndWakes(GameTestHelper h) {
        respawnCopies(h, false);
    }

    @GameTest(template = T, batch = "needs_edge_end")
    public static void endReturnCopiesTagAndWakes(GameTestHelper h) {
        respawnCopies(h, true);
    }

    /** Перезаход: раздел записан в ForgeData игрока и прочитан без изменений. */
    @GameTest(template = T, batch = "needs_edge_relog")
    public static void relogKeeps(GameTestHelper h) {
        ServerPlayer p = NeedsGameTests.player(h);
        ServerPlayer q = NeedsGameTests.player(h);
        try {
            fill(p);
            CompoundTag before = p.getPersistentData().getCompound(TAG).copy();
            CompoundTag saved = p.saveWithoutId(new CompoundTag());
            if (!saved.getCompound("ForgeData").contains(TAG)) h.fail("В сохранении игрока нет раздела ForgeData." + TAG);
            q.load(saved);
            CompoundTag after = q.getPersistentData().getCompound(TAG);
            if (!before.equals(after)) h.fail("После перезахода раздел rpneeds: было " + before + ", стало " + after);
        } finally {
            NeedsGameTests.remove(h, p);
            NeedsGameTests.remove(h, q);
        }
        h.succeed();
    }

    /** Игрок без раздела (старое сохранение): возрождение не создаёт его из ничего и не падает. */
    @GameTest(template = T, batch = "needs_edge_empty")
    public static void respawnWithoutTag(GameTestHelper h) {
        ServerPlayer p = NeedsGameTests.player(h);
        p.getPersistentData().remove(TAG);
        ServerPlayer n = h.getLevel().getServer().getPlayerList().respawn(p, false);
        try {
            if (n.getPersistentData().contains(TAG) && !n.getPersistentData().getCompound(TAG).isEmpty()
                    && n.getPersistentData().getCompound(TAG).getBoolean("asleep"))
                h.fail("У игрока без раздела после возрождения появился сон");
        } finally {
            NeedsGameTests.remove(h, n);
        }
        h.succeed();
    }
}
