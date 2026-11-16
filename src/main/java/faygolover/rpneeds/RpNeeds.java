package faygolover.rpneeds;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import faygolover.rpcore.api.RpCoreAPI;
import faygolover.rpcore.api.StatusLines;
import faygolover.rpcore.api.event.RpAlarmEvent;
import faygolover.rpcore.api.event.RpConsumeEvent;
import faygolover.rpcore.api.event.RpPostureSleepEvent;
import faygolover.rpcore.api.event.RpPostureWakeEvent;
import faygolover.rpcore.api.event.RpSleepCountEvent;
import faygolover.rpcore.api.event.RpTimeSkipEvent;
import faygolover.rpcore.api.event.RpTimeSkipOfflineEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Locale;

/** RP Needs — потребности: сон (0.2.0, дизайн «RP Needs: сон»), дальше чистота (план {@code docs/00_plan.md}). */
@Mod(RpNeeds.MODID)
public final class RpNeeds {
    public static final String MODID = "rpneeds";
    public static final Logger LOGGER = LoggerFactory.getLogger("RP Needs");

    public RpNeeds() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::commonSetup);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, NeedsConfig.SPEC);
        var bus = MinecraftForge.EVENT_BUS;
        bus.addListener(SleepService::onPlayerTick);
        bus.addListener(SleepEvents::onSleepTime);
        bus.addListener(SleepEvents::onHurt);
        bus.addListener(SleepEvents::onExplosion);
        bus.addListener(SleepEvents::onLogin);
        bus.addListener(SleepEvents::onLogout);
        SleepService.registerData();
        bus.addListener(EventPriority.NORMAL, false, RpPostureSleepEvent.class, SleepEvents::onPostureSleep);
        bus.addListener(EventPriority.NORMAL, false, RpPostureWakeEvent.class, SleepEvents::onPostureWake);
        bus.addListener(EventPriority.LOW, false, RpSleepCountEvent.class, SleepEvents::onSleepCount);
        bus.addListener(EventPriority.NORMAL, false, RpAlarmEvent.class, SleepEvents::onAlarm);
        bus.addListener(EventPriority.NORMAL, false, RpConsumeEvent.class, SleepEvents::onConsume);
        bus.addListener(EventPriority.NORMAL, false, RpTimeSkipEvent.class, SleepEvents::onSkip);
        bus.addListener(EventPriority.NORMAL, false, RpTimeSkipOfflineEvent.class, SleepEvents::onSkipOffline);
        bus.addListener(RpNeeds::onCommands);
        bus.addListener(EventPriority.NORMAL, false, faygolover.rpcore.api.event.RpCharacterWipeEvent.class, SleepEvents::onWipe);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> faygolover.rpneeds.client.NeedsClient::init);
    }

    private void commonSetup(FMLCommonSetupEvent e) {
        NeedsNetwork.register();
        e.enqueueWork(() -> {
            StatusLines.register(NeedsStatus.INSTANCE);
            RpCoreAPI.registerSpeechFilter(new net.minecraft.resources.ResourceLocation(MODID, "drowsy"), SleepService::filterSpeech);
            RpCoreAPI.registerModule(MODID, "потребности: сон");
            // Необязательные интеграции — в общий отчёт RP Core (/rpcore compat).
            faygolover.rpcore.api.RpCoreAPI.reportCompat(MODID, "tacz");
            faygolover.rpcore.api.RpCoreAPI.reportCompat(MODID, "voicechat");
            RpCoreAPI.registerSleep(new faygolover.rpcore.api.SleepProvider() {
                @Override
                public boolean asleep(net.minecraft.world.entity.player.Player p) {
                    return p instanceof net.minecraft.server.level.ServerPlayer sp && SleepService.asleep(sp);
                }

                @Override
                public int stage(net.minecraft.world.entity.player.Player p) {
                    return p instanceof net.minecraft.server.level.ServerPlayer sp && SleepService.counts(sp) ? SleepService.stage(sp, SleepService.data(sp)) : 0;
                }
            });
            if (ModList.get().isLoaded("tacz")) faygolover.rpneeds.integration.TaczShots.register();
        });
    }

    /**
     * Для ведущего: {@code /rpneeds set <игроки> sleep <0–100>} (потом — hygiene, hands) и {@code /rpneeds reset <игроки>}.
     * Посмотреть — сканером и в панели ГМа RP Medicine.
     */
    private static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("rpneeds").requires(s -> s.hasPermission(2))
                .then(Commands.literal("set").then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.literal("sleep").then(Commands.argument("value", DoubleArgumentType.doubleArg(0, 100))
                                .executes(c -> setSleep(c.getSource(), EntityArgument.getPlayers(c, "targets"),
                                        DoubleArgumentType.getDouble(c, "value")))))))
                .then(Commands.literal("reset").then(Commands.argument("targets", EntityArgument.players())
                        .executes(c -> reset(c.getSource(), EntityArgument.getPlayers(c, "targets"))))));
    }

    private static int setSleep(CommandSourceStack src, Collection<ServerPlayer> targets, double value) {
        for (ServerPlayer sp : targets) {
            CompoundTag t = SleepService.data(sp);
            t.putDouble("alert", value);
            t.putDouble("debt", 0);
        }
        src.sendSuccess(() -> Component.translatable("rpneeds.cmd.set_sleep", targets.size(), String.format(Locale.ROOT, "%.0f", value))
                .withStyle(ChatFormatting.GOLD), true);
        return targets.size();
    }

    private static int reset(CommandSourceStack src, Collection<ServerPlayer> targets) {
        for (ServerPlayer sp : targets) {
            CompoundTag t = SleepService.data(sp);
            for (String k : new String[]{"debt", "cafLeft", "cafDose", "cooldown", "nightmare", "heavy", "stage"}) t.remove(k);
            t.putDouble("alert", 100);
        }
        src.sendSuccess(() -> Component.translatable("rpneeds.cmd.reset", targets.size()).withStyle(ChatFormatting.GOLD), true);
        return targets.size();
    }
}
