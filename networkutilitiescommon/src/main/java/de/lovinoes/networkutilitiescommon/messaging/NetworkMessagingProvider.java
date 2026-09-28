package de.lovinoes.networkutilitiescommon.messaging;

public interface NetworkMessagingProvider {

    void start();

    void stop();

    void publish(String topic, byte[] payload);

    void subscribe(String topic, MessageListener listener);

    void unsubscribe(String topic, MessageListener listener);
}
