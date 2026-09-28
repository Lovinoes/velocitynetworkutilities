package de.lovinoes.velocitynetworkchat;

import de.lovinoes.networkutilitiescommon.chat.ChatSettingsDao;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks who has turned their mention sound off.
 *
 * Backed by the database so the choice survives a relog, but held in memory for lookups: a chat
 * message must not wait on a query to decide whether to play a sound. The opt-out list is small
 * (only players who actively disabled it) so the whole thing is loaded once at startup.
 */
public final class MentionSettings {

    private final ChatSettingsDao dao;
    private final Set<UUID> soundDisabled = ConcurrentHashMap.newKeySet();

    public MentionSettings(ChatSettingsDao dao) {
        this.dao = dao;
    }

    public void loadPersistedState() {
        dao.loadMentionSoundDisabled().thenAccept(soundDisabled::addAll);
    }

    public boolean isSoundEnabled(UUID playerId) {
        return !soundDisabled.contains(playerId);
    }

    /** @return the new enabled state after toggling. */
    public boolean toggleSound(UUID playerId) {
        boolean nowEnabled = soundDisabled.remove(playerId);
        if (!nowEnabled) {
            soundDisabled.add(playerId);
        }
        dao.setMentionSoundDisabled(playerId, !nowEnabled);
        return nowEnabled;
    }
}
