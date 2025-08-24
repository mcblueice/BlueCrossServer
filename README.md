
# BlueCrossServer

一個用於 Spigot 伺服器的跨分流訊息、指令、玩家列表同步插件。

## 功能特色

- 跨分流傳送訊息給所有或指定分流的玩家
- 跨分流傳送訊息給擁有特定權限的玩家
- 跨分流傳送指令給所有或指定分流執行
- 支援 PlaceholderAPI，顯示跨分流玩家資訊
- 提供 Java API 供其他插件調用
- 查詢跨分流同步的玩家列表與玩家數量

## 指令列表

| 指令 | 用途 | 權限節點 |
|------|------|----------|
| `/bluecrossmsg <server|-all> <message>` | 傳送訊息到指定或全部分流給全部玩家 | `bluecrossserver.msg.broadcast` |
| `/bluecrosspermmsg <server|-all> <permission> <message>` | 傳送訊息到指定或全部分流給有特定權限玩家 | `bluecrossserver.msg.permbroadcast` |
| `/bluecrosscmd <server|-all> <command>` | 傳送指令到指定或全部分流執行 | `bluecrossserver.cmd.execute` |
| `/bluecrossdebug` | 切換 Debug 模式 | `bluecrossserver.debug` |

> `server` 可填分流名稱，`-all` 代表全部分流。

## PlaceholderAPI 支援

| 變數名稱 | 說明 |
|----------|------|
| `%bluecrossserver_playerlist%` | 跨分流所有玩家名稱（以逗號分隔） |
| `%bluecrossserver_playeramout%` | 跨分流玩家總數 |
| `%bluecrossserver_player_<序號>%` | 指定序號的玩家名稱（如 `%bluecrossserver_player_1%`） |

## 授權 License

本專案採用 MIT License  
