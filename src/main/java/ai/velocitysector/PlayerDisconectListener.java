package ai.velocitysector;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import org.bson.Document;

import java.util.Optional;

public class PlayerDisconectListener {
    private final MongoDBManager database;

    public PlayerDisconectListener(MongoDBManager database) {
        this.database = database;
    }

    @Subscribe
    public void onPlayerDisconect(DisconnectEvent event) {
        Player player = event.getPlayer();

        // Bezpiecznie sprawdź, czy gracz był połączony z jakimkolwiek serwerem
        Optional<ServerConnection> serverConnectionOptional = player.getCurrentServer();

        // Wykonaj logikę zapisu do bazy TYLKO WTEDY, gdy gracz faktycznie był na jakimś serwerze
        serverConnectionOptional.ifPresent(serverConnection -> {
            String lastSector = serverConnection.getServerInfo().getName();

            // Twój oryginalny dokument do aktualizacji
            Document updateDocument = new Document("uuid", player.getUniqueId().toString())
                    .append("sector", lastSector);

            // Zakładam, że masz metodę upsertByUuid, która aktualizuje lub tworzy dokument
            database.upsertByUuid("users", player.getUniqueId().toString(), updateDocument);
            System.out.println("Zapisano ostatni sektor '" + lastSector + "' dla gracza " + player.getUsername());
        });
    }
}