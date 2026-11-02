package ai.velocitysector;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Invalidates async join work when the same UUID starts another proxy session. */
final class ConnectionGeneration {
    private record Session(UUID generation, Object owner) { }
    private final ConcurrentHashMap<UUID, Session> generations = new ConcurrentHashMap<>();

    UUID begin(UUID uuid) {
        return begin(uuid, uuid);
    }

    UUID begin(UUID uuid, Object owner) {
        UUID generation = UUID.randomUUID();
        generations.put(uuid, new Session(generation, owner));
        return generation;
    }

    void invalidate(UUID uuid) {
        generations.remove(uuid);
    }

    void invalidate(UUID uuid, Object owner) {
        generations.computeIfPresent(uuid, (ignored, current) -> current.owner() == owner ? null : current);
    }

    boolean isCurrent(UUID uuid, UUID generation) {
        Session current = generations.get(uuid);
        return generation != null && current != null && generation.equals(current.generation());
    }
}
