package net.mcblueice.bluecrossserver;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BlueCrossServer extends JavaPlugin implements PluginMessageListener {

    private static final String CHANNEL = "bluecross:channel";
    private static final Pattern RGB_HEX_PATTERN = Pattern.compile("\\{#[0-9a-fA-F]{6}\\}");
    private static final Pattern AMPERSAND_RGB_PATTERN = Pattern.compile("(&[xX])((&[0-9a-fA-F]){6})");
    private static final String BUNGEE_SUB_PLAYER_LIST = "PlayerList";
    
    private boolean debugMode = false;

    private volatile List<String> cachedNetworkPlayers = Collections.emptyList();
    private volatile long lastFetchTime = 0L;
    private volatile boolean fetchingPlayerList = false;
    private final List<Consumer<List<String>>> pendingPlayerListCallbacks = new CopyOnWriteArrayList<>();
    private static final long PLAYER_LIST_CACHE_TTL_MS = 5000;

    private static BlueCrossServer instance;

    @Override
    public void onEnable() {
        instance = this;
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        getServer().getMessenger().registerIncomingPluginChannel(this, "BungeeCord", this);
        
        // 玩家列表刷新
        TaskScheduler.runRepeatingTask(this, () -> {
            try {
                refreshPlayerListIfNeeded(true);
            } catch (Exception ignored) {}
        }, 20L, 200L);

        // 註冊 PAPI 擴展
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new BlueCrossServerExpansion(this).register();
            if (debugMode) getLogger().info("[DEBUG] 已註冊 PlaceholderAPI 擴展");
        }

        getLogger().info("BlueCrossServer 已啟用!");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
    }

    /**
     * 取得插件實例 (其他外掛可透過此方法再取得服務)
     */
    public static BlueCrossServer getInstance() { return instance; }

    /**
     * 提供給其他外掛的跨服玩家列表服務介面
     */
    public CrossServerPlayerService getPlayerService() {
        return new CrossServerPlayerService() {
            @Override
            public void requestAllPlayers(Consumer<List<String>> callback) {
                BlueCrossServer.this.requestAllPlayers(callback);
            }

            @Override
            public List<String> getCachedPlayerList() {
                return getCachedNetworkPlayers();
            }

            @Override
            public int getCachedPlayerCount() {
                return getCachedNetworkPlayers().size();
            }
        };
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String cmdName = cmd.getName().toLowerCase();
        
        if (cmdName.equals("bluecrossmsg")) {
            return handleBlueCrossMsg(sender, args);
        } else if (cmdName.equals("bluecrosspermmsg")) {
            return handleBlueCrossPermMsg(sender, args);
        } else if (cmdName.equals("bluecrosscmd")) {
            return handleBlueCrossCmd(sender, args);
        } else if (cmdName.equals("bluecrossdebug")) {
// Debug模式開關
            if (sender.hasPermission("bluecrossserver.debug")) {
                debugMode = !debugMode;
                return true;
            } else {
                sender.sendMessage("§c你沒有權限使用此指令");
                return true;
            }
        }
        return false;
    }

    private boolean handleBlueCrossMsg(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /bluecrossmsg <server|-all> <message>");
            return false;
        }
        return handleMessage(sender, args, "msg", null);
    }

    private boolean handleBlueCrossPermMsg(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§c用法: /bluecrosspermmsg <server|-all> <permission> <message>");
            return false;
        }
        return handleMessage(sender, args, "permmsg", args[1]);
    }

    private boolean handleBlueCrossCmd(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /bluecrosscmd <server|-all> <command>");
            return false;
        }
        
        String target = args[0];
        boolean isAll = target.equalsIgnoreCase("-all");
        
// cmd 構建
        StringBuilder commandBuilder = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            commandBuilder.append(args[i]).append(" ");
        }
        String command = commandBuilder.toString().trim();
        
// 解析cmd PAPI 
        command = parsePlaceholders(sender, command);
        
        if (debugMode) {
            getLogger().info("[DEBUG] 命令: " + command);
            getLogger().info("[DEBUG] 目標: " + (isAll ? "ALL" : target));
        }
        
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("cmd");
        out.writeUTF(isAll ? "ALL" : target);
        out.writeUTF(command);

        if (isAll) {
            processReceivedData(out.toByteArray(), true);
            broadcastToBungeeCord(out.toByteArray());
        } else {
            sendToServer(target, out.toByteArray());
        }
        
        sender.sendMessage("§a指令已發送到 " + (isAll ? "所有伺服器" : target));
        return true;
    }

    private boolean handleMessage(CommandSender sender, String[] args, String type, String permission) {
        String target = args[0];
        boolean isAll = target.equalsIgnoreCase("-all");
        
// msg permmsg 構建
        int messageStartIndex = type.equals("permmsg") ? 2 : 1;

        StringBuilder messageBuilder = new StringBuilder();
        for (int i = messageStartIndex; i < args.length; i++) {
            messageBuilder.append(args[i]).append(" ");
        }
        String message = messageBuilder.toString().trim();
        
// 解析 msg permmsg PAPI
        message = parsePlaceholders(sender, message);
        
// 處理 msg permmsg 顏色代碼
        if (type.equals("msg") || type.equals("permmsg")) {
            message = parseColorCodes(message);
        }
        
        if (debugMode) {
            getLogger().info("[DEBUG] 類型: " + type);
            getLogger().info("[DEBUG] 權限: " + (permission != null ? permission : "N/A"));
            getLogger().info("[DEBUG] 消息: " + message);
            getLogger().info("[DEBUG] 目標: " + (isAll ? "ALL" : target));
        }
        
// 發送
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(type);
        out.writeUTF(isAll ? "ALL" : target);
        
        if (type.equals("permmsg")) {
            out.writeUTF(permission);
        }
        
        out.writeUTF(message);

        if (isAll) {
            processReceivedData(out.toByteArray(), true);
            broadcastToBungeeCord(out.toByteArray());
        } else {
            sendToServer(target, out.toByteArray());
        }
        
        sender.sendMessage("§a消息已發送到 " + (isAll ? "所有伺服器" : target));
        return true;
    }

    private String parsePlaceholders(CommandSender sender, String text) {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            if (debugMode) {
                getLogger().info("[DEBUG] PlaceholderAPI 不可用，跳過解析");
            }
            return text;
        }
        
        try {
            String parsed;
            if (sender instanceof Player) {
// 解析player PAPI
                Player player = (Player) sender;
                parsed = PlaceholderAPI.setPlaceholders(player, text);
                if (debugMode) {
                    getLogger().info("[DEBUG] 玩家佔位符解析: " + text + " -> " + parsed);
                }
            } else if (sender instanceof ConsoleCommandSender) {
// 解析null PAPI
                parsed = PlaceholderAPI.setPlaceholders(null, text);
                if (debugMode) {
                    getLogger().info("[DEBUG] 控制台佔位符解析: " + text + " -> " + parsed);
                }
            } else {
                parsed = text;
            }
            return parsed;
        } catch (Exception e) {
            getLogger().warning("解析佔位符時發生錯誤: " + e.getMessage());
            if (sender != null) sender.sendMessage("§c佔位符解析失敗: " + e.getMessage());
            return text;
        }
    }

    private String parseColorCodes(String text) {
        if (debugMode) {
            getLogger().info("[DEBUG] 原始顏色代碼: " + text);
        }
        
// 先把 & 的傳統顏色碼轉為 §（保留 &x HEX 格式給後續步驟處理）
        text = text.replaceAll("(?i)&([0-9A-FK-OR])", "§$1");
        
// 處理 HEX 格式
        Matcher hexMatcher = RGB_HEX_PATTERN.matcher(text);
        StringBuffer hexBuffer = new StringBuffer();
        while (hexMatcher.find()) {
            String hex = hexMatcher.group();
            String cleanHex = hex.replace("{", "").replace("}", "");
            StringBuilder replacement = new StringBuilder("§x");
            for (int i = 1; i < cleanHex.length(); i++) {
                replacement.append("§").append(cleanHex.charAt(i));
            }
            hexMatcher.appendReplacement(hexBuffer, replacement.toString());
        }
        hexMatcher.appendTail(hexBuffer);
        text = hexBuffer.toString();
        
// 處理 &x 格式
        Matcher ampMatcher = AMPERSAND_RGB_PATTERN.matcher(text);
        StringBuffer ampBuffer = new StringBuffer();
        while (ampMatcher.find()) {
            String seq = ampMatcher.group();
            String clean = seq.replace("&x", "").replace("&X", "")
                             .replace("&", "");
            StringBuilder replacement = new StringBuilder("§x");
            for (int i = 0; i < clean.length(); i++) {
                replacement.append("§").append(clean.charAt(i));
            }
            ampMatcher.appendReplacement(ampBuffer, replacement.toString());
        }
        ampMatcher.appendTail(ampBuffer);
        text = ampBuffer.toString();
        
        if (debugMode) {
            getLogger().info("[DEBUG] 處理後顏色代碼: " + text);
        }
        
        return text;
    }

    private void sendToServer(String server, byte[] data) {
        if (debugMode) {
            getLogger().info("[DEBUG] 發送數據到伺服器: " + server);
        }
        
        ByteArrayDataOutput bungeeOut = ByteStreams.newDataOutput();
        bungeeOut.writeUTF("Forward");
        bungeeOut.writeUTF(server);
        bungeeOut.writeUTF(CHANNEL);
        bungeeOut.writeShort(data.length);
        bungeeOut.write(data);
        
        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            Player player = Bukkit.getOnlinePlayers().iterator().next();
            player.sendPluginMessage(this, "BungeeCord", bungeeOut.toByteArray());
        } else {
            getLogger().warning("無法發送消息：沒有在線玩家作為通道載體");
        }
    }

    private void broadcastToBungeeCord(byte[] data) {
        if (debugMode) {
            getLogger().info("[DEBUG] 廣播數據到所有伺服器");
        }
        
        ByteArrayDataOutput bungeeOut = ByteStreams.newDataOutput();
        bungeeOut.writeUTF("Forward");
        bungeeOut.writeUTF("ALL");
        bungeeOut.writeUTF(CHANNEL);
        bungeeOut.writeShort(data.length);
        bungeeOut.write(data);
        
        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            Player player = Bukkit.getOnlinePlayers().iterator().next();
            player.sendPluginMessage(this, "BungeeCord", bungeeOut.toByteArray());
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals("BungeeCord")) return;
        
        ByteArrayDataInput in = ByteStreams.newDataInput(message);
        String subChannel = in.readUTF();
        
        if (subChannel.equals(BUNGEE_SUB_PLAYER_LIST)) {
            // BungeeCord PlayerList 回應
            String server = in.readUTF(); // "ALL" 或伺服器名
            String list = in.readUTF();   // 逗號+空格分隔的玩家名稱

            List<String> players;
            if (list == null || list.isEmpty()) {
                players = Collections.emptyList();
            } else {
                players = new ArrayList<>(Arrays.asList(list.split(", ")));
                players.sort(String.CASE_INSENSITIVE_ORDER);
            }

            // 更新快取
            cachedNetworkPlayers = Collections.unmodifiableList(players);
            lastFetchTime = System.currentTimeMillis();
            fetchingPlayerList = false;

            if (debugMode) {
                getLogger().info("[DEBUG] 收到 PlayerList (" + server + ") 共 " + players.size() + " 名: " + String.join(", ", players));
            }

            // 通知所有等待 callback
            if (!pendingPlayerListCallbacks.isEmpty()) {
                for (Consumer<List<String>> cb : pendingPlayerListCallbacks) {
                    try { cb.accept(players); } catch (Exception e) { getLogger().warning("PlayerList callback 例外: " + e.getMessage()); }
                }
                pendingPlayerListCallbacks.clear();
            }
            return; // 已處理
        } else if (subChannel.equals(CHANNEL)) {
            short dataLength = in.readShort();
            byte[] dataBytes = new byte[dataLength];
            in.readFully(dataBytes);
            
            if (debugMode) {
                getLogger().info("[DEBUG] 收到插件消息，長度: " + dataLength);
            }
            
            processReceivedData(dataBytes, false);
        }
    }

    private void processReceivedData(byte[] data, boolean isLocal) {
        ByteArrayDataInput in = ByteStreams.newDataInput(data);
        String type = in.readUTF();
        String target = in.readUTF();
        
        if (debugMode) {
            getLogger().info("[DEBUG] 處理接收數據 - 類型: " + type + ", 目標: " + target);
        }
        
        switch (type) {
            case "msg": {
                String message = in.readUTF();
                if (debugMode) {
                    getLogger().info("[DEBUG] 廣播消息: " + message);
                }
                TaskScheduler.runTask(this, () -> {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        p.sendMessage(message);
                    }
                });
                break;
            }
            case "permmsg": {
                final String permission = in.readUTF();
                final String permMessage = in.readUTF();
                if (debugMode) {
                    getLogger().info("[DEBUG] 權限消息 - 權限: " + permission + ", 內容: " + permMessage);
                }
                TaskScheduler.runTask(this, () -> {
                    int count = 0;
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        boolean hasPermission = p.hasPermission(permission);
                        if (debugMode) {
                            getLogger().info("[DEBUG] 檢查玩家 " + p.getName() + " 權限 " + permission + ": " + hasPermission);
                        }
                        if (hasPermission) {
                            p.sendMessage(permMessage);
                            count++;
                        }
                    }
                    if (debugMode) {
                        getLogger().info("[DEBUG] 權限消息已發送給 " + count + " 名玩家");
                    }
                });
                break;
            }
            case "cmd": {
                final String command = in.readUTF();
                if (debugMode) {
                    getLogger().info("[DEBUG] 執行命令: " + command);
                }
                TaskScheduler.runTask(this, () -> {
                    try {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                    } catch (Exception e) {
                        getLogger().warning("執行遠程命令時出錯: " + command);
                        getLogger().warning("錯誤信息: " + e.getMessage());
                    }
                });
                break;
            }
        }
    }

    // ====== 跨服玩家列表 相關方法 ======
    /**
     * 非同步請求整個網路的玩家列表；結果排序 (CASE_INSENSITIVE_ORDER)。
     * 若快取仍有效，立即回傳快取；否則透過 BungeeCord Plugin Messaging 取得。
     */
    public void requestAllPlayers(Consumer<List<String>> callback) {
        // 快取仍有效 -> 立即返回
        if (System.currentTimeMillis() - lastFetchTime <= PLAYER_LIST_CACHE_TTL_MS && !cachedNetworkPlayers.isEmpty()) {
            callback.accept(cachedNetworkPlayers);
            return;
        }

        pendingPlayerListCallbacks.add(callback);

        // 若已在請求中則等待回應
        if (fetchingPlayerList) return;

        if (Bukkit.getOnlinePlayers().isEmpty()) {
            if (debugMode) getLogger().warning("[DEBUG] 無在線玩家可用作 PlayerList 載體");
            fetchingPlayerList = false;
            // 直接回傳空 (避免 callback 永不觸發)
            for (Consumer<List<String>> cb : pendingPlayerListCallbacks) {
                try { cb.accept(Collections.emptyList()); } catch (Exception ignored) {}
            }
            pendingPlayerListCallbacks.clear();
            return;
        }

        fetchingPlayerList = true;
        Player carrier = Bukkit.getOnlinePlayers().iterator().next();
        try {
            if (debugMode) getLogger().info("[DEBUG] 發送 PlayerList 請求 (ALL) 由載體玩家: " + carrier.getName());
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF(BUNGEE_SUB_PLAYER_LIST);
            out.writeUTF("ALL");
            carrier.sendPluginMessage(this, "BungeeCord", out.toByteArray());
        } catch (Exception e) {
            fetchingPlayerList = false;
            getLogger().warning("發送 PlayerList 請求失敗: " + e.getMessage());
        }

        // 超時保護 (5 秒)
        TaskScheduler.runTaskLater(this, () -> {
            if (fetchingPlayerList) {
                fetchingPlayerList = false;
                if (debugMode) getLogger().warning("[DEBUG] PlayerList 請求逾時");
                List<String> fallback = cachedNetworkPlayers; // 可能為空
                for (Consumer<List<String>> cb : pendingPlayerListCallbacks) {
                    try { cb.accept(fallback); } catch (Exception ignored) {}
                }
                pendingPlayerListCallbacks.clear();
            }
        }, 100L); // 5 秒
    }

    /** 強制或視需要刷新玩家列表 */
    public void refreshPlayerListIfNeeded(boolean force) {
        if (force || System.currentTimeMillis() - lastFetchTime > PLAYER_LIST_CACHE_TTL_MS) {
            requestAllPlayers(list -> { /* 已於 requestAllPlayers 處理快取 */ });
        }
    }

    /** 取得目前快取的跨服玩家列表 (已排序) */
    public List<String> getCachedNetworkPlayers() { return cachedNetworkPlayers; }

    /** 取得玩家數 */
    public int getCachedNetworkPlayerCount() { return cachedNetworkPlayers.size(); }
}