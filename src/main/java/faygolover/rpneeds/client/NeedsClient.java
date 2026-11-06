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
 * <p>Симптомы недосыпа (0.3.0): моргание — веки медленно закрываются (1-я стадия редко, 2-я чаще и дольше, с лёгкой
 * мутью по краям), «клюёт носом» на 3-й — взгляд плавно опускается и рывком возвращается. Управление не отбирается.
 */
public final class NeedsClient {
    private NeedsClient() {}

    private static long startMs, endMs;
    private static boolean bedAsleep, lying;
    /** Стадия недосыпа от сервера и включённые симптомы. */
    private static int stage;
    private static boolean blinkOn, nodOn;
    /** Моргание: следующее и текущее (мс). */
    private static long nextBlink, blinkStart, blinkEnd;
    /** Клюёт носом: следующий раз (тики), сколько тиков ещё опускается, сколько градусов добавлено. */
    private static int nodIn = -1, nodLeft;
    private static float nodAdded;
    private static final java.util.Random RND = new java.util.Random();

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(NeedsClient::onRender);
        MinecraftForge.EVENT_BUS.addListener(NeedsClient::onOverlay);
        MinecraftForge.EVENT_BUS.addListener(NeedsClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            bedAsleep = false;
            stage(0, false, false);
        });
    }

    public static void microsleep(int ticks) {
        startMs = System.currentTimeMillis();
        endMs = startMs + ticks * 50L;
    }

    public static void bedAsleep(boolean value) {
        bedAsleep = value;
    }

    public static void stage(int value, boolean blink, boolean nod) {
        stage = value;
        blinkOn = blink;
        nodOn = nod;
        nextBlink = System.currentTimeMillis() + blinkGap();
        nodIn = stage == 3 && nodOn ? 20 * (30 + RND.nextInt(30)) : -1;
        nodLeft = 0;
        nodAdded = 0;
    }

    /** Пауза до следующего моргания, мс: 1-я стадия — 40–90 с, 2-я — 15–40, 3-я — 10–25. */
    private static long blinkGap() {
        return switch (stage) {
            case 1 -> 40_000 + RND.nextInt(50_000);
            case 2 -> 15_000 + RND.nextInt(25_000);
            default -> 10_000 + RND.nextInt(15_000);
        };
    }

    /** Только что лёг — камера в 1-е лицо, один раз. */
    private static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        var p = mc.player;
        boolean now = p != null && (p.isSleeping() || PostureView.get(p.getId()) == RpCoreAPI.Posture.LAY);
        if (now && !lying && !mc.options.getCameraType().isFirstPerson()) mc.options.setCameraType(CameraType.FIRST_PERSON);
        lying = now;
        nod(mc);
    }

    /** Клюёт носом: ~1,5 с взгляд плавно уходит вниз (до 25°), потом рывком назад; раз в 50–80 с. */
    private static void nod(Minecraft mc) {
        var p = mc.player;
        if (p == null || stage != 3 || !nodOn || mc.isPaused() || p.isSleeping()) return;
        if (nodLeft > 0) {
            float step = 25f / 30f;
            p.setXRot(Math.min(90f, p.getXRot() + step));
            nodAdded += step;
            if (--nodLeft == 0) {
                p.setXRot(Math.max(-90f, p.getXRot() - nodAdded));
                nodAdded = 0;
                nodIn = 20 * (50 + RND.nextInt(30));
            }
            return;
        }
        if (nodIn > 0 && --nodIn == 0) nodLeft = 30;
    }

    /** Ванильное затемнение в кровати — только когда уснул. */
    private static void onOverlay(RenderGuiOverlayEvent.Pre e) {
        if (!e.getOverlay().id().equals(VanillaGuiOverlay.SLEEP_FADE.id())) return;
        var p = Minecraft.getInstance().player;
        if (p != null && p.isSleeping() && !bedAsleep) e.setCanceled(true);
    }

    private static void onRender(RenderGuiEvent.Post e) {
        eyes(e);
        long now = System.currentTimeMillis();
        if (now >= endMs) return;
        long fade = 250;
        float a = Math.min(1f, Math.min(now - startMs, endMs - now) / (float) fade);
        int w = e.getWindow().getGuiScaledWidth(), h = e.getWindow().getGuiScaledHeight();
        e.getGuiGraphics().fill(0, 0, w, h, ((int) (a * 255)) << 24);
    }

    /** Веки и муть по краям. */
    private static void eyes(RenderGuiEvent.Post e) {
        var mc = Minecraft.getInstance();
        if (stage < 1 || stage > 3 || !blinkOn || mc.player == null || mc.player.isSleeping()) return;
        int w = e.getWindow().getGuiScaledWidth(), h = e.getWindow().getGuiScaledHeight();
        var g = e.getGuiGraphics();
        // Со 2-й стадии — лёгкая муть по краям.
        if (stage >= 2) {
            int edge = h / 6, a = stage == 2 ? 0x30 : 0x48;
            g.fillGradient(0, 0, w, edge, a << 24, 0);
            g.fillGradient(0, h - edge, w, h, 0, a << 24);
        }
        long now = System.currentTimeMillis();
        if (now >= blinkEnd && now >= nextBlink) {
            blinkStart = now;
            blinkEnd = now + (stage == 1 ? 300 : 500);
            nextBlink = blinkEnd + blinkGap();
        }
        if (now >= blinkEnd) return;
        // Веки сходятся и расходятся: 0 → 1 → 0.
        float t = (now - blinkStart) / (float) (blinkEnd - blinkStart);
        float close = (float) Math.sin(Math.PI * t);
        int lid = (int) (h / 2f * close);
        g.fill(0, 0, w, lid, 0xFF000000);
        g.fill(0, h - lid, w, h, 0xFF000000);
    }
}
