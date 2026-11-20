package ai.velocitysector;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ServerListMotdTest {
    @TempDir Path directory;

    @Test
    void rendersTwoLinesWithRgbGradient() {
        var title = ServerListMotd.render(ServerListMotd.DEFAULT);
        assertEquals("LASTPLAY.NET • PRACE TECHNICZNE\n✦ Już wkrótce!",
                PlainTextComponentSerializer.plainText().serialize(title));
        var gradient = ServerListMotd.render("<gradient:#ff3838:#ffca55:#ff3838>ABC</gradient> | test");
        assertEquals(List.of(TextColor.color(0xff3838), TextColor.color(0xffca55), TextColor.color(0xff3838)),
                colors(gradient).toList());
    }

    private static Stream<TextColor> colors(Component component) {
        return Stream.concat(Stream.ofNullable(component.color()), component.children().stream().flatMap(ServerListMotdTest::colors));
    }

    @Test
    void persistsValidTextWithoutOverwritingItOnInvalidInput() throws Exception {
        var motd = new ServerListMotd(directory, LoggerFactory.getLogger(getClass()));
        String valid = "<red>LASTPLAY.NET</red> | Już wkrótce!";
        motd.save(valid);
        assertThrows(IllegalArgumentException.class, () -> motd.save("only one line"));
        assertThrows(IllegalArgumentException.class, () -> motd.save("three | lines | rejected"));
        assertEquals(valid, Files.readString(directory.resolve("motd.txt")));
    }
}
