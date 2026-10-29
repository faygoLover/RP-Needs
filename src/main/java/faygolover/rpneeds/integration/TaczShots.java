package faygolover.rpneeds.integration;

import com.tacz.guns.api.event.common.GunShootEvent;
import faygolover.rpneeds.Wakers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.MinecraftForge;

/** TaCZ (необязательный): выстрел рядом будит спящих. Грузится, только если TaCZ стоит. */
public final class TaczShots {
    private TaczShots() {}

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(TaczShots::onShoot);
    }

    private static void onShoot(GunShootEvent e) {
        if (!e.getLogicalSide().isServer()) return;
        LivingEntity shooter = e.getShooter();
        if (shooter != null && shooter.level() instanceof ServerLevel level) Wakers.onShot(level, shooter.position());
    }
}
