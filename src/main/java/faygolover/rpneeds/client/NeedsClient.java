package faygolover.rpneeds.client;

import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.common.MinecraftForge;

/** Клиент: микросон — экран быстро гаснет и так же быстро возвращается. */
public final class NeedsClient {
    private NeedsClient() {}

    private static long startMs, endMs;

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(NeedsClient::onRender);
    }

    public static void microsleep(int ticks) {
        startMs = System.currentTimeMillis();
        endMs = startMs + ticks * 50L;
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
