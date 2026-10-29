package faygolover.rpneeds;

import faygolover.rpcore.api.StatusLines;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * Строки сна для RP Medicine: сам игрок — ощущениями в своих состояниях, другие при осмотре — только сильную
 * усталость и сон, ГМ сканером и в панели — цифры. Отдельной команды просмотра нет.
 */
final class NeedsStatus implements StatusLines.Source {
    static final NeedsStatus INSTANCE = new NeedsStatus();

    @Override
    public void lines(Player target, StatusLines.Viewer viewer, List<StatusLines.Line> out) {
        if (!(target instanceof ServerPlayer sp)) return;
        CompoundTag t = SleepService.data(sp);
        boolean asleep = SleepService.asleep(sp);
        int stage = SleepService.stage(sp, t);
        switch (viewer) {
            case SELF -> {
                if (asleep) out.add(new StatusLines.Line("rpneeds.status.asleep"));
                else if (t.getInt("rested") > 0 && stage == 0) out.add(new StatusLines.Line("rpneeds.status.rested"));
                if (stage > 0) out.add(new StatusLines.Line("rpneeds.status.stage" + stage));
                if (t.getDouble("cafLeft") > 0) out.add(new StatusLines.Line("rpneeds.status.caffeine"));
                if (!asleep && SleepService.sleepAid(sp) > 0) out.add(new StatusLines.Line("rpneeds.status.sleep_aid"));
                if (t.getInt("nightmare") > 0) out.add(new StatusLines.Line("rpneeds.status.nightmare"));
                if (t.getInt("heavy") > 0) out.add(new StatusLines.Line("rpneeds.status.heavy"));
            }
            case OTHER -> {
                if (asleep) out.add(new StatusLines.Line("rpneeds.status.other_asleep"));
                if (stage >= 3) out.add(new StatusLines.Line("rpneeds.status.other_exhausted"));
            }
            case GM -> {
                out.add(new StatusLines.Line("rpneeds.status.gm", (int) Math.round(t.getDouble("alert")),
                        (int) Math.round(t.getDouble("debt") / 60.0), (int) Math.round(t.getDouble("cafLeft") / 60.0),
                        t.getInt("cooldown")));
                out.add(new StatusLines.Line("rpneeds.status.gm_stage" + stage));
                if (asleep) out.add(new StatusLines.Line("rpneeds.status.gm_place" + Math.max(0, SleepService.place(sp))));
                else if (sp.isSleeping()) out.add(new StatusLines.Line("rpneeds.status.gm_in_bed"));
                double aid = SleepService.sleepAid(sp);
                if (aid > 0) out.add(new StatusLines.Line("rpneeds.status.gm_aid", (int) Math.round(aid * 100)));
            }
        }
    }
}
