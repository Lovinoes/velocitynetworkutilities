package de.lovinoes.playeruuidcachevelocity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class MojangLookupService {

    private static final System.Logger LOGGER = System.getLogger(MojangLookupService.class.getName());
    private static final String LOOKUP_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final java.util.regex.Pattern VALID_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final HttpClient httpClient;
    private final Executor executor;
    private final long timeoutMs;

    public MojangLookupService(Executor executor, long timeoutMs) {
        this.executor = executor;
        this.timeoutMs = timeoutMs;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    public CompletableFuture<Optional<UUID>> lookupUuid(String username) {
        // The name goes into the URL. Anything that cannot be a Minecraft name is not asked
        // about at all, so a typo cannot reach another endpoint or break the URL.
        if (username == null || !VALID_NAME.matcher(username).matches()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(LOOKUP_URL + username))
                        .timeout(Duration.ofMillis(timeoutMs))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    return Optional.<UUID>empty();
                }
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                String rawId = json.get("id").getAsString();
                return Optional.of(parseUndashedUuid(rawId));
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOGGER.log(Level.WARNING, "Mojang UUID lookup failed for " + username, e);
                return Optional.<UUID>empty();
            } catch (RuntimeException e) {
                // Covers malformed/unexpected JSON in the response body (JsonSyntaxException,
                // IllegalStateException from getAsJsonObject, missing "id" field, etc.) so an
                // unusual API response degrades to "not found" instead of failing the future.
                LOGGER.log(Level.WARNING, "Mojang UUID lookup returned an unexpected response for " + username, e);
                return Optional.<UUID>empty();
            }
        }, executor);
    }

    private UUID parseUndashedUuid(String raw) {
        String dashed = raw.replaceFirst(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
        return UUID.fromString(dashed);
    }
}
