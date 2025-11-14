package eu.kotori.justTeams.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.kotori.justTeams.JustTeams;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;

public class VersionChecker implements Listener {

    private final JustTeams plugin;
    private final String currentVersion;
    private final String apiUrl;

    public VersionChecker(JustTeams plugin) {
        this.plugin = plugin;
        this.currentVersion = plugin.getDescription().getVersion();
        this.apiUrl = "https://api.kotori.ink/v1/version?product=justTeams";
        this.plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void check() {
        plugin.getTaskRunner().runAsync(() -> {
            try {
                plugin.getLogger().info("Checking for updates...");
                URI uri = URI.create(this.apiUrl);
                HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("User-Agent", "JustTeams Version Checker");

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (InputStreamReader reader = new InputStreamReader(connection.getInputStream())) {
                        JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                        String latestVersion = jsonObject.get("version").getAsString();

                        plugin.getLogger().info("Current version: " + currentVersion + " | Latest version: " + latestVersion);

                        if (!currentVersion.equalsIgnoreCase(latestVersion)) {
                            plugin.updateAvailable = true;
                            plugin.latestVersion = latestVersion;
                            plugin.getLogger().info("A new version is available: " + latestVersion);
                            StartupMessage.sendUpdateNotification(plugin);
                        } else {
                            plugin.updateAvailable = false;
                            plugin.getLogger().info("You are running the latest version!");
                        }
                    }
                } else {
                    plugin.getLogger().warning("Version check failed with response code: " + responseCode);
                }
                connection.disconnect();

            } catch (Exception e) {
                plugin.getLogger().warning("Could not check for updates: " + e.getMessage());
                if (plugin.getConfigManager().isDebugLoggingEnabled()) {
                    e.printStackTrace();
                }
            }
        });
    }
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("justteams.admin") && plugin.updateAvailable) {
            plugin.getTaskRunner().runEntityTaskLater(player, () -> {
                if (player.isOnline()) {
                    StartupMessage.sendUpdateNotification(player, plugin);
                }
            }, 60L);
        }
    }
}
