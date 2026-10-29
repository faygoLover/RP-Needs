package faygolover.rpneeds;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import faygolover.rpcore.api.event.RpConsumeEvent;
import faygolover.rpcore.api.event.RpPostureSleepEvent;
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
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Locale;

/** RP Needs: сон (0.1.0). Гигиена — следующей версией (план {@code docs/00_plan.md}). */
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
        bus.addListener(SleepService::onSleepInBed);
        bus.addListener(SleepService::onSleepTime);
        bus.addListener(SleepService::onLogin);
        bus.addListener(SleepService::onLogout);
        bus.addListener(SleepService::onClone);
        bus.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false, RpPostureSleepEvent.class, SleepService::onPostureSleep);
        bus.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false, RpConsumeEvent.class, SleepService::onConsume);
        bus.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false, RpTimeSkipEvent.class, SleepService::onSkip);
        bus.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false, RpTimeSkipOfflineEvent.class, SleepService::onSkipOffline);
        bus.addListener(RpNeeds::onCommands);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> faygolover.rpneeds.client.NeedsClient::init);
    }

    private void commonSetup(FMLCommonSetupEvent e) {
        NeedsNetwork.register();
    }

    /** {@code /needs info [игрок]}, {@code /needs set <игроки> <0–100>} — для ведущего. */
    private static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("needs").requires(s -> s.hasPermission(2))
                .then(Commands.literal("info")
                        .executes(c -> info(c.getSource(), c.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(c -> info(c.getSource(), EntityArgument.getPlayer(c, "player")))))
                .then(Commands.literal("set").then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("alertness", DoubleArgumentType.doubleArg(0, 100))
                                .executes(c -> set(c.getSource(), EntityArgument.getPlayers(c, "targets"),
                                        DoubleArgumentType.getDouble(c, "alertness")))))));
    }

    private static int info(CommandSourceStack src, ServerPlayer sp) {
        CompoundTag t = SleepService.data(sp);
        String alert = String.format(Locale.ROOT, "%.0f", t.getDouble("alert"));
        String debt = String.format(Locale.ROOT, "%.0f", t.getDouble("debt") / 60.0);
        String caf = String.format(Locale.ROOT, "%.0f", t.getDouble("cafLeft") / 60.0);
        int stage = SleepService.stage(t);
        src.sendSuccess(() -> Component.translatable("rpneeds.cmd.info", sp.getDisplayName(), alert, debt,
                Component.translatable("rpneeds.stage." + stage), caf).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static int set(CommandSourceStack src, Collection<ServerPlayer> targets, double value) {
        for (ServerPlayer sp : targets) {
            CompoundTag t = SleepService.data(sp);
            t.putDouble("alert", value);
            t.putDouble("debt", 0);
        }
        src.sendSuccess(() -> Component.translatable("rpneeds.cmd.set", targets.size(), String.format(Locale.ROOT, "%.0f", value))
                .withStyle(ChatFormatting.GOLD), true);
        return targets.size();
    }
}
