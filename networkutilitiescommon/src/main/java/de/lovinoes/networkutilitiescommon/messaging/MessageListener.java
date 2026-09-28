package de.lovinoes.networkutilitiescommon.messaging;

@FunctionalInterface
public interface MessageListener {

    void onMessage(String topic, byte[] payload);
}
