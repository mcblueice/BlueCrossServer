package net.mcblueice.bluecrossserver;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * BlueCrossServer PlaceholderAPI 擴展。
 * 提供：
 * %bluecrossserver_playerlist% -> 以逗號+空格分隔的全部跨服玩家列表
 * %bluecrossserver_playeramout% -> 玩家數 (注意：原需求拼字 "amout" 保持原樣)
 * %bluecrossserver_player_1% -> 第一個玩家 (索引從 1 開始)
 */
public class BlueCrossServerExpansion extends PlaceholderExpansion {

    private final BlueCrossServer plugin;

    public BlueCrossServerExpansion(BlueCrossServer plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "bluecrossserver"; // %bluecrossserver_*
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() { return true; }

    @Override
    public boolean canRegister() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        params = params.toLowerCase();

        // 立即啟動刷新 (非強制，符合快取策略)
        plugin.refreshPlayerListIfNeeded(false);
        List<String> cached = plugin.getCachedNetworkPlayers();

        switch (params) {
            case "playerlist":
                return String.join(", ", cached);
            case "playeramout": // 保留需求的拼字
            case "playeramount": // 兼容正確寫法
                return Integer.toString(cached.size());
            default:
                if (params.startsWith("player_")) {
                    String idxStr = params.substring("player_".length());
                    try {
                        int index = Integer.parseInt(idxStr); // 1-based
                        if (index >= 1 && index <= cached.size()) {
                            return cached.get(index - 1);
                        }
                    } catch (NumberFormatException ignored) {}
                    return ""; // 無結果回空字串
                }
        }
        return null; // 未匹配交回 PAPI
    }
}
