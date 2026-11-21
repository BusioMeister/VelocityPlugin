package ai.velocitysector;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** UTF-8 message templates. A bad reload never replaces the active catalog. */
public final class Messages {
    public static final String RELOAD_CHANNEL = "aisector:core:reload-messages";
    private static final Pattern PARAMETER = Pattern.compile("\\{([0-9]{1,3})}");
    private static final Map<String, String> DEFAULTS = defaults();
    private static volatile Map<String, String> active = DEFAULTS;
    private static Path file;

    private Messages() {}

    public static synchronized void initialize(Path directory) throws IOException {
        file = directory.resolve("messages.properties");
        Files.createDirectories(directory);
        if (Files.notExists(file)) {
            try (var source = Messages.class.getResourceAsStream("/messages.properties")) {
                if (source == null) throw new IOException("Missing default messages.properties");
                try { Files.copy(source, file); }
                catch (java.nio.file.FileAlreadyExistsException ignored) { }
            }
        }
        reload();
    }

    public static synchronized void reload() throws IOException {
        if (file == null || !Files.isRegularFile(file)) throw new IOException("Missing messages.properties");
        if (Files.size(file) > 1_048_576) throw new IOException("messages.properties is too large");
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Malformed messages.properties", malformed);
        }
        var next = new HashMap<>(DEFAULTS);
        for (String key : properties.stringPropertyNames()) {
            String original = DEFAULTS.get(key);
            if (original == null) continue;
            String override = properties.getProperty(key);
            int limit = PARAMETER.matcher(original).results()
                    .mapToInt(match -> Integer.parseInt(match.group(1))).max().orElse(-1);
            if (PARAMETER.matcher(override).results().anyMatch(match -> Integer.parseInt(match.group(1)) > limit))
                throw new IOException("Unexpected placeholder in " + key);
            next.put(key, override);
        }
        active = Map.copyOf(next);
    }

    public static String text(String key, Object... args) {
        String value = active.getOrDefault(key, key);
        Matcher matcher = PARAMETER.matcher(value);
        return matcher.replaceAll(match -> {
            int index = Integer.parseInt(match.group(1));
            return Matcher.quoteReplacement(index < args.length ? String.valueOf(args[index]) : match.group());
        });
    }

    private static Map<String, String> defaults() {
        Properties properties = new Properties();
        try (var source = Messages.class.getResourceAsStream("/messages.properties")) {
            if (source == null) throw new IOException("Missing default messages.properties");
            properties.load(new InputStreamReader(source, StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
        var values = new HashMap<String, String>();
        properties.forEach((key, value) -> values.put((String) key, (String) value));
        return Map.copyOf(values);
    }
}
