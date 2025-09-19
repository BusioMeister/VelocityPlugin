package ai.velocitysector;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.bson.Document;
import redis.clients.jedis.Jedis;

import java.util.*;
import java.util.stream.Collectors;

public class WhitelistCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final RedisManager redis;
    private final MongoDBManager mongo;

    public WhitelistCommand(ProxyServer proxy, RedisManager redis, MongoDBManager mongo) {
        this.proxy = proxy;
        this.redis = redis;
        this.mongo = mongo;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        // LuckPerms/Velocity native permission check
        if (!(source instanceof Player)) {
            // Konsola: zawsze pozwól
        } else if (!source.hasPermission("aisector.whitelist.manage")) {
            source.sendMessage(Component.text("§cBrak uprawnień."));
            return;
        }

        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            help(source);
            return;
        }

        String sub = args[0].toLowerCase();

        try (Jedis j = redis.getJedis()) {
            switch (sub) {
                case "add": {
                    if (args.length < 2) {
                        source.sendMessage(Component.text("§eUżycie: /vwhitelist add <nick|uuid>"));
                        return;
                    }
                    Optional<UUID> opt = resolveUuid(args[1]);
                    if (!opt.isPresent()) {
                        source.sendMessage(Component.text("§cNie znaleziono gracza: " + args[1]));
                        return;
                    }
                    j.sadd("whitelist:players", opt.get().toString());
                    source.sendMessage(Component.text("§aDodano do whitelist: " + args[1] + " §8(" + opt.get() + ")"));
                    return;
                }
                case "remove": {
                    if (args.length < 2) {
                        source.sendMessage(Component.text("§eUżycie: /vwhitelist remove <nick|uuid>"));
                        return;
                    }
                    Optional<UUID> opt = resolveUuid(args[1]);
                    if (!opt.isPresent()) {
                        source.sendMessage(Component.text("§cNie znaleziono gracza: " + args[1]));
                        return;
                    }
                    j.srem("whitelist:players", opt.get().toString());
                    source.sendMessage(Component.text("§aUsunięto z whitelist: " + args[1] + " §8(" + opt.get() + ")"));
                    return;
                }
                case "list": {
                    Set<String> set = j.smembers("whitelist:players");
                    if (set == null || set.isEmpty()) {
                        source.sendMessage(Component.text("§7Whitelist jest pusta."));
                        return;
                    }
                    source.sendMessage(Component.text("§6Whitelist (" + set.size() + "):"));
                    for (String s : set.stream().limit(200).collect(Collectors.toList())) {
                        source.sendMessage(Component.text(" §7- §f" + s));
                    }
                    if (set.size() > 200) {
                        source.sendMessage(Component.text("§8… ucięto listę"));
                    }
                    return;
                }
                case "status": {
                    boolean enabled = Boolean.TRUE.equals(j.exists("maintenance:enabled"));
                    source.sendMessage(Component.text("§7Przerwa: " + (enabled ? "§cWŁĄCZONA" : "§aWYŁĄCZONA")));
                    source.sendMessage(Component.text("§7Rozmiar whitelist: §f" + j.scard("whitelist:players")));
                    return;
                }
                default:
                    help(source);
            }
        } catch (Exception e) {
            source.sendMessage(Component.text("§cBłąd Redis: " + e.getMessage()));
        }
    }

    private void help(CommandSource src) {
        src.sendMessage(Component.text("§e/vwhitelist add <nick|uuid>"));
        src.sendMessage(Component.text("§e/vwhitelist remove <nick|uuid>"));
        src.sendMessage(Component.text("§e/vwhitelist list"));
        src.sendMessage(Component.text("§e/vwhitelist status"));
    }

    private Optional<UUID> resolveUuid(String input) {
        // 1) bezpośredni UUID
        try { return Optional.of(UUID.fromString(input)); } catch (Exception ignored) {}

        // 2) online player na proxy
        for (Player p : proxy.getAllPlayers()) {
            if (p.getUsername().equalsIgnoreCase(input)) {
                return Optional.of(p.getUniqueId());
            }
        }

        // 3) lookup w Mongo (kolekcja "users" z polem "uuid")
        try {
            if (mongo != null) {
                Document doc = mongo.getCollection("users").find(new Document("name", input)).first();
                if (doc != null && doc.getString("uuid") != null) {
                    return Optional.of(UUID.fromString(doc.getString("uuid")));
                }
            }
        } catch (Exception ignored) {}

        return Optional.empty();
    }
}
