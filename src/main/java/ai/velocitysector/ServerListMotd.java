package ai.velocitysector;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

final class ServerListMotd implements SimpleCommand {
    static final String DEFAULT = "<gradient:#ff3838:#ffca55:#ff3838><bold>LASTPLAY.NET</bold></gradient>"
            + " <dark_gray>•</dark_gray> <gold>PRACE TECHNICZNE</gold>"
            + " | <dark_gray>✦</dark_gray> <gradient:#ff3838:#ffca55:#ff3838>Już wkrótce!</gradient>";
    private static final MiniMessage FORMAT = MiniMessage.builder().strict(true)
            .tags(TagResolver.resolver(StandardTags.color(), StandardTags.gradient(), StandardTags.decorations()))
            .build();
    private final Path file;
    private final Logger logger;
    private final Set<UUID> admins;
    private volatile Component description = render(DEFAULT);

    ServerListMotd(Path directory, Logger logger) {
        this.file = directory.resolve("motd.txt");
        this.logger = logger;
        admins = readAdmins(directory.resolve("motd-admins.txt"));
        try {
            if (Files.exists(file) && Files.size(file) <= 8192) description = render(Files.readString(file).strip());
        } catch (IOException | IllegalArgumentException error) {
            logger.warn("Nie można odczytać MOTD; używam domyślnego.", error);
        }
    }

    private Set<UUID> readAdmins(Path path) {
        try {
            if (Files.exists(path)) {
                return Files.readAllLines(path).stream().map(String::strip)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .map(UUID::fromString).collect(Collectors.toUnmodifiableSet());
            }
        } catch (IOException | IllegalArgumentException error) {
            logger.warn("Nie można odczytać administratorów MOTD.", error);
        }
        return Set.of();
    }

    static Component render(String text) {
        if (text.length() > 512 || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("MOTD może mieć maksymalnie 512 znaków i musi być w jednej linii polecenia.");
        }
        String[] lines = text.split("\\|", -1);
        if (lines.length != 2 || lines[0].isBlank() || lines[1].isBlank()) {
            throw new IllegalArgumentException("Podaj dwie linie rozdzielone znakiem |.");
        }
        return FORMAT.deserialize(lines[0].strip()).append(Component.newline()).append(FORMAT.deserialize(lines[1].strip()));
    }

    @Subscribe(order = PostOrder.LAST)
    public void onPing(ProxyPingEvent event) {
        event.setPing(event.getPing().asBuilder().description(description).build());
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        var source = invocation.source();
        return source instanceof ConsoleCommandSource || source.hasPermission("aisector.motd.manage")
                || source instanceof Player player && admins.contains(player.getUniqueId());
    }

    @Override
    public void execute(Invocation invocation) {
        if (!hasPermission(invocation)) return;
        var source = invocation.source();
        var args = invocation.arguments();
        try {
            if (args.length == 1 && args[0].equalsIgnoreCase("show")) {
                source.sendMessage(description);
            } else if (args.length == 1 && args[0].equalsIgnoreCase("reset")) {
                save(DEFAULT);
                source.sendMessage(Component.text(Messages.text("server-list-motd.przywrocono-domyslne-motd")));
            } else if (args.length > 1 && args[0].equalsIgnoreCase("set")) {
                save(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
                source.sendMessage(Component.text(Messages.text("server-list-motd.zapisano-motd")).append(Component.newline()).append(description));
            } else {
                source.sendMessage(Component.text(Messages.text("server-list-motd.motd-set-motd-show-motd-reset")));
            }
        } catch (IllegalArgumentException error) {
            source.sendMessage(Component.text(Messages.text("server-list-motd.nieprawidlowe-motd-sprawdz-format-tagow-i-rozdziel")));
        } catch (IOException error) {
            logger.warn("Nie można zapisać MOTD.", error);
            source.sendMessage(Component.text(Messages.text("server-list-motd.nie-udalo-sie-zapisac-motd-poprzedni-tekst")));
        }
    }

    synchronized void save(String text) throws IOException {
        Component next = render(text);
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "motd-", ".tmp");
        try {
            Files.writeString(temporary, text);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            description = next;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
