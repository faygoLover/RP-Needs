package faygolover.rpneeds.integration;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import faygolover.rpneeds.RpNeeds;
import faygolover.rpneeds.Wakers;

/** Плагин Simple Voice Chat: кто говорит и шёпотом ли — спящих рядом будит голос. Загружается самим Simple Voice Chat. */
@ForgeVoicechatPlugin
public class NeedsVoicePlugin implements VoicechatPlugin {
    @Override
    public String getPluginId() {
        return RpNeeds.MODID;
    }

    @Override
    public void registerEvents(EventRegistration reg) {
        reg.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
    }

    private void onMicrophone(MicrophonePacketEvent e) {
        VoicechatConnection sender = e.getSenderConnection();
        if (sender == null || e.isCancelled()) return;
        Wakers.onVoice(sender.getPlayer().getUuid(), e.getPacket().isWhispering());
    }
}
