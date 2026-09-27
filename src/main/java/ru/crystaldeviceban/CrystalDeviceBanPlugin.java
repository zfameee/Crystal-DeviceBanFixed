package ru.crystaldeviceban;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CrystalDeviceBanPlugin extends JavaPlugin implements Listener {

    private final Map<String, BanRecord> bans = new ConcurrentHashMap<>();
    private final Map<String, MuteRecord> mutes = new ConcurrentHashMap<>();

    private final LegacyComponentSerializer legacy =
            LegacyComponentSerializer.legacyAmpersand();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();

        Bukkit.getPluginManager().registerEvents(this, this);

        register("deviceban", new DeviceBanCommand());
        register("deviceunban", new DeviceUnbanCommand());
        register("devicemute", new DeviceMuteCommand());
        register("deviceunmute", new DeviceUnmuteCommand());
        register("deviceinfo", new DeviceInfoCommand());
        register("devicebanreload", new ReloadCommand());

        getLogger().info("Crystal-DeviceBan 1.1.2 enabled.");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private void register(String name, CommandExecutor executor) {
        Objects.requireNonNull(getCommand(name), "Command not found: " + name)
                .setExecutor(executor);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        String fp = fingerprint(event.getRawAddress());

        BanRecord ban = bans.get(fp);
        if (ban != null) {
            Map<String, String> vars = vars(
                    "player", event.getName(),
                    "reason", ban.reason(),
                    "admin", ban.admin(),
                    "fingerprint", fp,
                    "duration", "Permanent",
                    "expires", "Never"
            );
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    linesComponent("messages.ban-screen", vars)
            );
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent event) {
        String fp = fingerprint(event.getPlayer().getAddress() == null
                ? null
                : event.getPlayer().getAddress().getAddress());

        MuteRecord mute = mutes.get(fp);
        if (mute == null) return;

        if (!mute.isActive()) {
            mutes.remove(fp, mute);
            saveData();
            return;
        }

        event.setCancelled(true);

        Map<String, String> vars = vars(
                "player", event.getPlayer().getName(),
                "reason", mute.reason(),
                "admin", mute.admin(),
                "fingerprint", fp,
                "duration", formatDuration(mute.expires()),
                "expires", formatExpiry(mute.expires())
        );

        event.getPlayer().sendMessage(linesComponent("messages.mute-chat", vars));
    }

    private String fingerprint(InetAddress address) {
        String raw = address == null ? "unknown" : address.getHostAddress();
        return sha256(raw);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void loadData() {
        bans.clear();
        mutes.clear();

        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder.");
        }

        File file = new File(getDataFolder(), "data.yml");
        if (!file.exists()) return;

        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection bs = c.getConfigurationSection("bans");
        if (bs != null) {
            for (String id : bs.getKeys(false)) {
                String base = "bans." + id + ".";
                bans.put(id, new BanRecord(
                        c.getString(base + "player", "unknown"),
                        c.getString(base + "reason", "Без причины"),
                        c.getString(base + "admin", "Console"),
                        c.getLong(base + "created", 0)
                ));
            }
        }

        ConfigurationSection ms = c.getConfigurationSection("mutes");
        if (ms != null) {
            for (String id : ms.getKeys(false)) {
                String base = "mutes." + id + ".";
                mutes.put(id, new MuteRecord(
                        c.getString(base + "player", "unknown"),
                        c.getString(base + "reason", "Без причины"),
                        c.getString(base + "admin", "Console"),
                        c.getLong(base + "created", 0),
                        c.getLong(base + "expires", 0)
                ));
            }
        }
    }

    private synchronized void saveData() {
        YamlConfiguration c = new YamlConfiguration();

        for (var entry : bans.entrySet()) {
            String base = "bans." + entry.getKey() + ".";
            BanRecord r = entry.getValue();
            c.set(base + "player", r.player());
            c.set(base + "reason", r.reason());
            c.set(base + "admin", r.admin());
            c.set(base + "created", r.created());
        }

        for (var entry : mutes.entrySet()) {
            String base = "mutes." + entry.getKey() + ".";
            MuteRecord r = entry.getValue();
            c.set(base + "player", r.player());
            c.set(base + "reason", r.reason());
            c.set(base + "admin", r.admin());
            c.set(base + "created", r.created());
            c.set(base + "expires", r.expires());
        }

        try {
            c.save(new File(getDataFolder(), "data.yml"));
        } catch (Exception e) {
            getLogger().warning("Cannot save data.yml: " + e.getMessage());
        }
    }

    private boolean has(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        send(sender, "messages.errors.no-permission", Map.of());
        return false;
    }

    private void send(CommandSender sender, String path, Map<String, String> vars) {
        for (String line : getLines(path)) {
            sender.sendMessage(component(line, vars));
        }
    }

    private Component linesComponent(String path, Map<String, String> vars) {
        List<String> lines = getLines(path);
        if (lines.isEmpty()) return Component.empty();

        Component result = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) result = result.append(Component.newline());
            result = result.append(component(lines.get(i), vars));
        }
        return result;
    }

    private Component component(String raw, Map<String, String> vars) {
        String text = raw == null ? "" : raw;
        String prefix = getConfig().getString("messages.prefix", "");

        Map<String, String> all = new HashMap<>(vars);
        all.putIfAbsent("prefix", prefix);

        for (var e : all.entrySet()) {
            text = text.replace("{" + e.getKey() + "}", Objects.toString(e.getValue(), ""));
        }

        return legacy.deserialize(text);
    }

    private List<String> getLines(String path) {
        Object value = getConfig().get(path);

        // String message
        if (value instanceof String s) {
            return List.of(s);
        }

        // YAML list of messages
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object o : list) {
                if (o != null) result.add(String.valueOf(o));
            }
            return result;
        }

        // Never stringify a ConfigurationSection.
        // This prevents messages such as:
        // MemorySection[path='messages.banned'...]
        if (value instanceof ConfigurationSection section) {
            // Support a convenient section format too:
            // messages:
            //   banned:
            //     message: "..."
            //     lines:
            //       - "..."
            Object lines = section.get("lines");
            if (lines instanceof List<?> list) {
                List<String> result = new ArrayList<>();
                for (Object o : list) {
                    if (o != null) result.add(String.valueOf(o));
                }
                return result;
            }

            String message = section.getString("message");
            if (message != null) return List.of(message);

            String text = section.getString("text");
            if (text != null) return List.of(text);
        }

        return List.of();
    }

    private Map<String, String> vars(String... values) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            result.put(values[i], values[i + 1]);
        }
        return result;
    }

    private String reason(String[] args, int start) {
        if (args.length <= start) return "Без причины";
        return String.join(" ", Arrays.copyOfRange(args, start, args.length));
    }

    private String currentFingerprint(Player player) {
        return fingerprint(player.getAddress() == null
                ? null
                : player.getAddress().getAddress());
    }

    private BanRecord latestBanForPlayer(String player) {
        return bans.values().stream()
                .filter(b -> b.player().equalsIgnoreCase(player))
                .max(Comparator.comparingLong(BanRecord::created))
                .orElse(null);
    }

    private MuteRecord latestMuteForPlayer(String player) {
        return mutes.values().stream()
                .filter(m -> m.player().equalsIgnoreCase(player))
                .max(Comparator.comparingLong(MuteRecord::created))
                .orElse(null);
    }

    private boolean isFingerprint(String value) {
        return value.matches("[0-9a-fA-F]{64}");
    }

    private String formatDuration(long expires) {
        if (expires == 0) return "Permanent";
        long seconds = Math.max(0, (expires - System.currentTimeMillis()) / 1000);
        long days = seconds / 86400; seconds %= 86400;
        long hours = seconds / 3600; seconds %= 3600;
        long minutes = seconds / 60; seconds %= 60;

        List<String> parts = new ArrayList<>();
        if (days > 0) parts.add(days + "d");
        if (hours > 0) parts.add(hours + "h");
        if (minutes > 0) parts.add(minutes + "m");
        if (seconds > 0 || parts.isEmpty()) parts.add(seconds + "s");
        return String.join(" ", parts);
    }

    private String formatExpiry(long expires) {
        if (expires == 0) return "Never";
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date(expires));
    }

    private Long parseDuration(String input) {
        if (input.equalsIgnoreCase("permanent") || input.equalsIgnoreCase("perm")) return 0L;

        if (input.length() < 2) return null;

        char unit = Character.toLowerCase(input.charAt(input.length() - 1));
        long amount;
        try {
            amount = Long.parseLong(input.substring(0, input.length() - 1));
        } catch (NumberFormatException e) {
            return null;
        }

        if (amount <= 0) return null;

        long multiplier = switch (unit) {
            case 's' -> 1000L;
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'd' -> 86_400_000L;
            case 'w' -> 604_800_000L;
            default -> -1L;
        };

        if (multiplier < 0 || amount > Long.MAX_VALUE / multiplier) return null;
        return System.currentTimeMillis() + amount * multiplier;
    }

    private record BanRecord(String player, String reason, String admin, long created) {}

    private record MuteRecord(
            String player,
            String reason,
            String admin,
            long created,
            long expires
    ) {
        boolean isActive() {
            return expires == 0 || expires > System.currentTimeMillis();
        }
    }

    private final class DeviceBanCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!has(sender, "crystaldeviceban.ban")) return true;

            if (args.length < 1) {
                send(sender, "messages.ban.usage", Map.of());
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                send(sender, "messages.ban.player-not-found", vars("player", args[0]));
                return true;
            }

            if (target.getAddress() == null) {
                send(sender, "messages.ban.address-error", Map.of());
                return true;
            }

            String fp = currentFingerprint(target);
            if (bans.containsKey(fp)) {
                send(sender, "messages.ban.already-banned", vars("fingerprint", fp, "player", target.getName()));
                return true;
            }

            String why = reason(args, 1);
            String admin = sender.getName();

            bans.put(fp, new BanRecord(
                    target.getName(), why, admin, System.currentTimeMillis()
            ));
            saveData();

            Map<String, String> vars = vars(
                    "player", target.getName(),
                    "reason", why,
                    "admin", admin,
                    "fingerprint", fp,
                    "duration", "Permanent",
                    "expires", "Never"
            );

            send(sender, "messages.ban.success", vars);

            target.kick(linesComponent("messages.ban-screen", vars));
            return true;
        }
    }

    private final class DeviceUnbanCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!has(sender, "crystaldeviceban.unban")) return true;

            if (args.length != 1) {
                send(sender, "messages.unban.usage", Map.of());
                return true;
            }

            String input = args[0];
            String fp = isFingerprint(input) ? input.toLowerCase(Locale.ROOT) : null;
            BanRecord record = null;

            if (fp != null) {
                record = bans.get(fp);
            } else {
                record = latestBanForPlayer(input);
                if (record != null) {
                    fp = bans.entrySet().stream()
                            .filter(e -> e.getValue() == record)
                            .map(Map.Entry::getKey)
                            .findFirst().orElse(null);
                }
            }

            if (record == null || fp == null) {
                send(sender, "messages.unban.not-found", vars("player", input, "fingerprint", input));
                return true;
            }

            bans.remove(fp);
            saveData();

            send(sender, "messages.unban.success", vars(
                    "player", record.player(),
                    "fingerprint", fp
            ));
            return true;
        }
    }

    private final class DeviceMuteCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!has(sender, "crystaldeviceban.mute")) return true;

            if (args.length < 1) {
                send(sender, "messages.mute-command.usage", Map.of());
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                send(sender, "messages.mute-command.player-not-found", vars("player", args[0]));
                return true;
            }

            if (target.getAddress() == null) {
                send(sender, "messages.mute-command.address-error", Map.of());
                return true;
            }

            String fp = currentFingerprint(target);
            if (mutes.containsKey(fp) && mutes.get(fp).isActive()) {
                send(sender, "messages.mute-command.already-muted", vars("fingerprint", fp, "player", target.getName()));
                return true;
            }

            int reasonStart = 1;
            long expires = 0;

            if (args.length >= 2) {
                Long parsed = parseDuration(args[1]);
                if (parsed != null) {
                    expires = parsed;
                    reasonStart = 2;
                } else if (args.length >= 3) {
                    send(sender, "messages.mute-command.invalid-duration", Map.of());
                    return true;
                }
            }

            String why = reason(args, reasonStart);
            String admin = sender.getName();

            mutes.put(fp, new MuteRecord(
                    target.getName(), why, admin,
                    System.currentTimeMillis(), expires
            ));
            saveData();

            Map<String, String> vars = vars(
                    "player", target.getName(),
                    "reason", why,
                    "admin", admin,
                    "fingerprint", fp,
                    "duration", expires == 0 ? "Permanent" : formatDuration(expires),
                    "expires", formatExpiry(expires)
            );

            send(sender, "messages.mute-command.success", vars);
            send(target, "messages.mute.player", vars);
            send(target, "messages.mute.details", vars);

            return true;
        }
    }

    private final class DeviceUnmuteCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!has(sender, "crystaldeviceban.unmute")) return true;

            if (args.length != 1) {
                send(sender, "messages.unmute.usage", Map.of());
                return true;
            }

            String input = args[0];
            String fp = isFingerprint(input) ? input.toLowerCase(Locale.ROOT) : null;
            MuteRecord record = null;

            if (fp != null) {
                record = mutes.get(fp);
            } else {
                record = latestMuteForPlayer(input);
                if (record != null) {
                    MuteRecord finalRecord = record;
                    fp = mutes.entrySet().stream()
                            .filter(e -> e.getValue() == finalRecord)
                            .map(Map.Entry::getKey)
                            .findFirst().orElse(null);
                }
            }

            if (record == null || fp == null) {
                send(sender, "messages.unmute.not-found", vars("player", input));
                return true;
            }

            mutes.remove(fp);
            saveData();

            send(sender, "messages.unmute.success", vars(
                    "player", record.player(),
                    "fingerprint", fp
            ));
            return true;
        }
    }

    private final class DeviceInfoCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!has(sender, "crystaldeviceban.info")) return true;

            Player target;
            if (args.length == 0 && sender instanceof Player p) {
                target = p;
            } else if (args.length == 1) {
                target = Bukkit.getPlayerExact(args[0]);
            } else {
                send(sender, "messages.info.usage", Map.of());
                return true;
            }

            if (target == null || target.getAddress() == null) {
                send(sender, "messages.info.player-not-found", Map.of());
                return true;
            }

            String fp = currentFingerprint(target);
            Map<String, String> vars = vars(
                    "player", target.getName(),
                    "fingerprint", fp,
                    "ban-status", bans.containsKey(fp)
                            ? getConfig().getString("messages.status.banned", "&cЗАБЛОКИРОВАН")
                            : getConfig().getString("messages.status.not-banned", "&aНЕ ЗАБЛОКИРОВАН"),
                    "mute-status", mutes.containsKey(fp) && mutes.get(fp).isActive()
                            ? getConfig().getString("messages.status.muted", "&cЗАМЬЮЧЕН")
                            : getConfig().getString("messages.status.not-muted", "&aНЕ ЗАМЬЮЧЕН")
            );

            send(sender, "messages.info.result", vars);
            return true;
        }
    }

    private final class ReloadCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!has(sender, "crystaldeviceban.reload")) return true;
            reloadConfig();
            send(sender, "messages.reload.success", Map.of());
            return true;
        }
    }
}
