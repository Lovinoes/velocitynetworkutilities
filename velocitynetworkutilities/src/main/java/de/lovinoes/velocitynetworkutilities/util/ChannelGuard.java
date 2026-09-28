package de.lovinoes.velocitynetworkutilities.util;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;

/**
 * Stops anything on one of our proxy/backend channels from passing through the proxy.
 *
 * A channel registered with the proxy fires PluginMessageEvent for traffic in both directions,
 * and Velocity forwards the message on unless someone marks it handled. That includes messages a
 * CLIENT sends: a modified client can write to any channel name it likes, and without this it
 * would reach the backend looking exactly like an instruction from the proxy. PaperNetworkVanish
 * has no way to tell the difference, so a forged empty vanish snapshot would unhide every
 * vanished player on that server, and a forged local chat frame would post any text as anyone.
 *
 * The proxy's own sends go through ServerConnection#sendPluginMessage and never fire this event,
 * so marking everything handled costs nothing legitimate. Backend to client traffic on these
 * channels is dropped too: nothing on a backend should be talking to the client on them.
 */
public final class ChannelGuard {

    private final ChannelIdentifier channel;

    public ChannelGuard(ChannelIdentifier channel) {
        this.channel = channel;
    }

    /**
     * Last, so that no other plugin can set the result back to forward after this has decided.
     */
    @Subscribe(priority = -32767)
    public void onPluginMessage(PluginMessageEvent event) {
        if (event.getIdentifier().equals(channel)) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
        }
    }
}
