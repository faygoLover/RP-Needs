package faygolover.rpneeds.client;

import faygolover.rpcore.api.RpCoreAPI;
import faygolover.rpcore.pose.PostureView;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

/**
 * Клиент: микросон (экран гаснет на миг), кровать без сна (лёг, но не уснул — экран не темнеет) и 1-е лицо, когда
 * лёг в кровать или по {@code /lay}: от 3-го лица лежачий виден криво (тест 03.11); вернуть вид игрок может сам.
 */
public final class NeedsClient {
    private NeedsClient() {}

    private static long startMs, endMs;
    private static boolean bedAsleep, lying;

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(NeedsClient::onRender);
        MinecraftForge.EVENT_BUS.addListener(NeedsClient::onOverlay);
        MinecraftForge.EVENT_BUS.addListener(NeedsClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> bedAsleep = false);
    }

    public static void microsleep(int ticks) {
        startMs = System.currentTimeMillis();
        endMs = startMs + ticks * 50L;
    }

    public static void bedAsleep(boolean value) {
        bedAsleep = value;
    }

    /** Только что лёг — камера в 1-е лицо, один раз. */
    private static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        var p = mc.player;
        boolean now = p != null && (p.isSleeping() || PostureView.get(p.getId()) == RpCoreAPI.Posture.LAY);
        if (now && !lying && !mc.options.getCameraType().isFirstPerson()) mc.options.setCameraType(CameraType.FIRST_PERSON);
        lying = now;
    }

    /** Ванильное затемнение в кровати — только когда уснул. */
    private static void onOverlay(RenderGuiOverlayEvent.Pre e) {
        if (!e.getOverlay().id().equals(VanillaGuiOverlay.SLEEP_FADE.id())) return;
        var p = Minecraft.getInstance().player;
        if (p != null && p.isSleeping() && !bedAsleep) e.setCanceled(true);
    }

    private static void onRender(RenderGuiEvent.Post e) {
        long now = System.currentTimeMillis();
        if (now >= endMs) return;
        long fade = 250;
        float a = Math.min(1f, Math.min(now - startMs, endMs - now) / (float) fade);
        int w = e.getWindow().getGuiScaledWidth(), h = e.getWindow().getGuiScaledHeight();
        e.getGuiGraphics().fill(0, 0, w, h, ((int) (a * 255)) << 24);
    }
}
