package net.mcblueice.bluecrossserver;

import java.util.List;
import java.util.function.Consumer;

/**
 * 對其他外掛公開的跨服玩家列表服務 API。
 */
public interface CrossServerPlayerService {
    /**
     * 請求跨服全部在線玩家列表 (非同步)。
     * @param callback 回呼取得排序後玩家列表
     */
    void requestAllPlayers(Consumer<List<String>> callback);

    /**
     * 取得目前快取的玩家列表 (排序 case-insensitive)。
     */
    List<String> getCachedPlayerList();

    /**
     * 取得目前快取玩家數。
     */
    int getCachedPlayerCount();
}
