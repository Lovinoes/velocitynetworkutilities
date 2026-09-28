package de.lovinoes.networkutilitiescommon.messaging;

import java.util.List;

@FunctionalInterface
public interface MessageListener {

    void onMessage(String topic, byte[] payload);

    /**
     * Hands a message to every listener, each on its own. One that throws is logged and the rest
     * still get the message, and the exception never reaches the sender or the transport.
     */
    static void deliver(List<MessageListener> listeners, String topic, byte[] payload) {
        for (MessageListener listener : listeners) {
            try {
                listener.onMessage(topic, payload);
            } catch (RuntimeException e) {
                System.getLogger(MessageListener.class.getName()).log(System.Logger.Level.ERROR,
                        "A listener for '" + topic + "' failed.", e);
            }
        }
    }
}
