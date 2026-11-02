package ai.aisector.shared;

/** Canonical Redis channel names used by both platform plugins. */
public final class RedisChannels {
    public static final String PACKET = "aisector:packet";
    public static final String GLOBAL_PLAYER_LIST = "aisector:global_playerlist_update";
    public static final String GLOBAL_WEATHER_CHANGE = "aisector:global_weather_change";
    public static final String GLOBAL_TIME_CHANGE = "aisector:global_time_change";
    public static final String SECTOR_STATS = "aisector:sector_stats";
    public static final String SECTOR_TRANSFER = "sector-transfer";
    public static final String SAVE_PLAYER_DATA = "aisector:save_player_data";
    public static final String SAVE_PLAYER_DATA_ACK = "aisector:save_player_data:ack";
    public static final String ADMIN_LOCATION_RESPONSE = "aisector:admin_location_response";
    public static final String BAN_BROADCAST = "aisector:ban_broadcast";
    public static final String BAN_KICK = "aisector:ban_kick";
    public static final String GUILD_DELETED = "aisector:guild_deleted";
    public static final String MAINTENANCE_TOGGLE = "aisector:maintenance_toggle";
    public static final String MAINTENANCE_PREPARE = "aisector:maintenance_prepare";
    public static final String TP_EXECUTE_LOCAL = "aisector:tp_execute_local";
    public static final String GET_LOCATION_FOR_ADMIN_TP = "aisector:get_location_for_admin_tp";
    public static final String ADMIN_TP_REQUEST = "aisector:admin_tp_request";
    public static final String TPA_REQUEST = "aisector:tpa_request";
    public static final String TPA_ACCEPT = "aisector:tpa_accept";
    public static final String SUMMON_REQUEST = "aisector:summon_request";
    public static final String SECTOR_REQUEST = "aisector:sektor_request";
    public static final String SEND_REQUEST = "aisector:send_request";
    public static final String GUI_DATA_REQUEST = "aisector:gui_data_request";
    public static final String GUI_DATA_RESPONSE_PREFIX = "aisector:gui_data_response:";
    public static final String GUI_DATA_RESPONSE_PATTERN = GUI_DATA_RESPONSE_PREFIX + "*";
    public static final String INVSEE_REQUEST = "aisector:invsee_request";
    public static final String INVSEE_GET_DATA = "aisector:invsee_get_data";
    public static final String LEGACY_TP_REQUEST = "aisector:tp_request";
    public static final String VANISH_UPDATE = "aisector:vanish_update";
    public static final String VANISH_STATE = "aisector:vanish:state";
    public static final String GLOBAL_SPAWN = "aisector:global_spawn";
    public static final String ONLINE_PLAYERS_PATTERN = "sector-online:*";

    private RedisChannels() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static String packetForServer(String serverName) {
        return PACKET + ":" + requireName(serverName);
    }

    public static String onlinePlayersForServer(String serverName) {
        return "sector-online:" + requireName(serverName);
    }

    private static String requireName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Server name must be a 1-64 character identifier");
        }
        return name;
    }
}
