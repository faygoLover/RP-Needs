package faygolover.rpneeds;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Когда в последний раз проспали ночь (реальное время, мс): вышедшие до этого при входе выспались. */
public final class NightMark extends SavedData {
    private long last;

    public static NightMark get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(NightMark::load, NightMark::new, "rpneeds_night");
    }

    private static NightMark load(CompoundTag tag) {
        NightMark m = new NightMark();
        m.last = tag.getLong("last");
        return m;
    }

    public long last() {
        return last;
    }

    public void mark(long ms) {
        last = ms;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong("last", last);
        return tag;
    }
}
