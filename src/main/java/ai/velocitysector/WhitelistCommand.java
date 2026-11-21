package ai.velocitysector;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.mongodb.client.model.Filters.eq;

public final class WhitelistCommand implements SimpleCommand {
    private static final String WHITELIST_KEY = "whitelist:players";

    private final ProxyServer proxy;
    private final Object pluginOwner;
    private final RedisManager redis;
    private final MongoDBManager mongo;
    private final AtomicBoolean closed = new AtomicBoolean();

    public WhitelistCommand(ProxyServer proxy, Object pluginOwner, RedisManager redis, MongoDBManager mongo) {
        this.proxy = proxy;
        this.pluginOwner = pluginOwner;
        this.redis = redis;
        this.mongo = mongo;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (source instanceof Player && !source.hasPermission("aisector.whitelist.manage")) {
            source.sendMessage(Component.text(Messages.text("whitelist-command.brak-uprawnien")));
            return;
        }
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            help(source);
            return;
        }

        String subcommand = args[0].toLowerCase(java.util.Locale.ROOT);
        switch (subcommand) {
            case "add":
            case "remove":
                changeEntry(source, subcommand, args);
                break;
            case "list":
                listEntries(source);
                break;
            case "status":
                showStatus(source);
                break;
            default:
                help(source);
        }
    }

    private void changeEntry(CommandSource source, String action, String[] args) {
        if (args.length < 2) {
            source.sendMessage(Component.text(Messages.text("whitelist-command.uzycie-vwhitelist", action)));
            return;
        }

        String input = args[1];
        resolveUuid(input).thenCompose(uuid -> {
            if (uuid.isEmpty()) return CompletableFuture.completedFuture(Optional.<UUID>empty());
            return redis.executeAsync(jedis -> {
                if (action.equals("add")) jedis.sadd(WHITELIST_KEY, uuid.get().toString());
                else jedis.srem(WHITELIST_KEY, uuid.get().toString());
                return uuid;
            });
        }).whenComplete((uuid, error) -> {
            scheduleResponse(source, () -> {
            if (error != null) {
                source.sendMessage(Component.text(Messages.text("whitelist-command.nie-udalo-sie-zaktualizowac-whitelisty")));
            } else if (uuid == null || uuid.isEmpty()) {
                source.sendMessage(Component.text(Messages.text("whitelist-command.nie-znaleziono-gracza", input)));
            } else if (action.equals("add")) {
                source.sendMessage(Component.text(Messages.text("whitelist-command.dodano-do-whitelist", input, uuid.get())));
            } else {
                source.sendMessage(Component.text(Messages.text("whitelist-command.usunieto-z-whitelist", input, uuid.get())));
            }
            });
        });
    }

    private void listEntries(CommandSource source) {
        redis.executeAsync(jedis -> jedis.smembers(WHITELIST_KEY)).whenComplete((entries, error) -> {
            scheduleResponse(source, () -> {
            if (error != null) {
                source.sendMessage(Component.text(Messages.text("whitelist-command.nie-udalo-sie-odczytac-whitelisty")));
                return;
            }
            if (entries == null || entries.isEmpty()) {
                source.sendMessage(Component.text(Messages.text("whitelist-command.whitelist-jest-pusta")));
                return;
            }
            source.sendMessage(Component.text(Messages.text("whitelist-command.whitelist", entries.size())));
            entries.stream().sorted().limit(200).forEach(entry ->
                    source.sendMessage(Component.text(" §7- §f" + entry)));
            if (entries.size() > 200) source.sendMessage(Component.text(Messages.text("whitelist-command.ucieto-liste")));
            });
        });
    }

    private void showStatus(CommandSource source) {
        redis.executeAsync(jedis -> new WhitelistStatus(
                Boolean.TRUE.equals(jedis.exists("maintenance:enabled")), jedis.scard(WHITELIST_KEY)))
                .whenComplete((status, error) -> {
                    scheduleResponse(source, () -> {
                    if (error != null) {
                        source.sendMessage(Component.text(Messages.text("whitelist-command.nie-udalo-sie-odczytac-statusu-whitelisty")));
                        return;
                    }
                    source.sendMessage(Component.text(Messages.text("whitelist-command.przerwa", (status.maintenanceEnabled() ? "§cWŁĄCZONA" : "§aWYŁĄCZONA"))));
                    source.sendMessage(Component.text(Messages.text("whitelist-command.rozmiar-whitelist", status.whitelistSize())));
                    });
                });
    }

    private void scheduleResponse(CommandSource source, Runnable response) {
        if (closed.get()) return;
        try {
            proxy.getScheduler().buildTask(pluginOwner, () -> {
                if (closed.get()) return;
                if (source instanceof Player player
                        && proxy.getPlayer(player.getUniqueId()).orElse(null) != player) return;
                response.run();
            }).schedule();
        } catch (RuntimeException shuttingDown) {
            closed.set(true);
        }
    }

    public void close() {
        closed.set(true);
    }

    private CompletableFuture<Optional<UUID>> resolveUuid(String input) {
        try {
            return CompletableFuture.completedFuture(Optional.of(UUID.fromString(input)));
        } catch (IllegalArgumentException ignored) {
            // Try online names and then MongoDB.
        }

        Optional<UUID> onlineUuid = proxy.getAllPlayers().stream()
                .filter(player -> player.getUsername().equalsIgnoreCase(input))
                .map(Player::getUniqueId)
                .findFirst();
        if (onlineUuid.isPresent() || mongo == null) {
            return CompletableFuture.completedFuture(onlineUuid);
        }

        Bson filter = eq("name", input);
        return mongo.findOneAsync("users", filter).thenApply(document -> uuidFromDocument(document));
    }

    private Optional<UUID> uuidFromDocument(Document document) {
        if (document == null) return Optional.empty();
        String uuid = document.getString("uuid");
        if (uuid == null) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(uuid));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private void help(CommandSource source) {
        source.sendMessage(Component.text(Messages.text("whitelist-command.vwhitelist-add")));
        source.sendMessage(Component.text(Messages.text("whitelist-command.vwhitelist-remove")));
        source.sendMessage(Component.text(Messages.text("whitelist-command.vwhitelist-list")));
        source.sendMessage(Component.text(Messages.text("whitelist-command.vwhitelist-status")));
    }

    private record WhitelistStatus(boolean maintenanceEnabled, long whitelistSize) { }
}
