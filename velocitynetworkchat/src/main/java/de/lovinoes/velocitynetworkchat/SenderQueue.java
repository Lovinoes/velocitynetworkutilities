package de.lovinoes.velocitynetworkchat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Keeps each player's messages in the order they were sent.
 *
 * Some messages now wait on a backend before they can be shown: one with [item] in it, one in a
 * distance-limited channel, one whose format uses PlaceholderAPI. Left alone, "look [item]"
 * followed straight away by "nice right?" would show the second line first, because it had
 * nothing to wait for. Each player's messages therefore run one after another, while different
 * players never wait on each other.
 *
 * A message with nothing to wait for, sent when nothing of that player's is still pending, runs
 * immediately on the calling thread, exactly as it did before any of this existed.
 */
public final class SenderQueue {

    private static final System.Logger LOGGER = System.getLogger(SenderQueue.class.getName());

    /** The most recently queued step for each player. Each new step waits for it. */
    private final Map<UUID, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

    /**
     * @param step produces the work for one message. It is only called once every earlier message
     *             from this player has finished, successfully or not.
     */
    public void submit(UUID player, Supplier<CompletableFuture<?>> step) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        // put() hands back whatever was queued before and installs this one in the same atomic
        // step, so two messages arriving at once can never both think they are first.
        CompletableFuture<Void> previous = tails.put(player, done);
        CompletableFuture<Void> start = previous == null ? CompletableFuture.completedFuture(null) : previous;

        start.whenComplete((ignored, earlierFailure) -> run(player, step)
                .whenComplete((result, failure) -> {
                    // Remove the entry only if it is still ours: a later message may already have
                    // queued behind this one, and dropping it would let the next one skip ahead.
                    tails.remove(player, done);
                    done.complete(null);
                }));
    }

    /** One failing message must never hold up every message after it. */
    private static CompletableFuture<?> run(UUID player, Supplier<CompletableFuture<?>> step) {
        CompletableFuture<?> work;
        try {
            work = step.get();
        } catch (RuntimeException e) {
            LOGGER.log(System.Logger.Level.ERROR, "A chat message from " + player + " failed", e);
            return CompletableFuture.completedFuture(null);
        }
        if (work == null) {
            return CompletableFuture.completedFuture(null);
        }
        return work.handle((result, failure) -> {
            if (failure != null) {
                LOGGER.log(System.Logger.Level.ERROR, "A chat message from " + player + " failed", failure);
            }
            return null;
        });
    }

    /** Called on disconnect. Anything still in flight finishes; nothing new waits on it. */
    public void forget(UUID player) {
        tails.remove(player);
    }
}
