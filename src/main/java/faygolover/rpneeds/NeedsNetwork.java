package faygolover.rpneeds;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

/** Сетевой канал {@code rpneeds:main}: микросон и «уснул ли в кровати» — самому игроку. */
public final class NeedsNetwork {
    private NeedsNetwork() {}

    private static final String VERSION = "3";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RpNeeds.MODID, "main"), () -> VERSION, VERSION::equals, VERSION::equals);

    public static void register() {
        CHANNEL.registerMessage(0, Microsleep.class, Microsleep::encode, Microsleep::decode, Microsleep::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, BedSleep.class, BedSleep::encode, BedSleep::decode, BedSleep::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2, Stage.class, Stage::encode, Stage::decode, Stage::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void send(ServerPlayer sp, Object msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), msg);
    }

    /** Экран гаснет на {@code ticks} тиков. */
    public record Microsleep(int ticks) {
        static void encode(Microsleep m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.ticks);
        }

        static Microsleep decode(FriendlyByteBuf buf) {
            return new Microsleep(buf.readVarInt());
        }

        static void handle(Microsleep m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> faygolover.rpneeds.client.NeedsClient.microsleep(m.ticks)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Стадия недосыпа для глаз (0–4) и что из симптомов включено на сервере. */
    public record Stage(int stage, boolean blink, boolean nod) {
        static void encode(Stage m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.stage);
            buf.writeBoolean(m.blink);
            buf.writeBoolean(m.nod);
        }

        static Stage decode(FriendlyByteBuf buf) {
            return new Stage(buf.readVarInt(), buf.readBoolean(), buf.readBoolean());
        }

        static void handle(Stage m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> faygolover.rpneeds.client.NeedsClient.stage(m.stage, m.blink, m.nod)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** В кровати уснул или ещё (уже) не спит: пока не спит, экран не темнеет. */
    public record BedSleep(boolean asleep) {
        static void encode(BedSleep m, FriendlyByteBuf buf) {
            buf.writeBoolean(m.asleep);
        }

        static BedSleep decode(FriendlyByteBuf buf) {
            return new BedSleep(buf.readBoolean());
        }

        static void handle(BedSleep m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> faygolover.rpneeds.client.NeedsClient.bedAsleep(m.asleep)));
            ctx.get().setPacketHandled(true);
        }
    }
}
