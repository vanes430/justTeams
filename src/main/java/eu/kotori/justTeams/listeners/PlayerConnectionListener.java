package eu.kotori.justTeams.listeners;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.team.TeamManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerConnectionListener implements Listener {
    private final JustTeams plugin;
    private final TeamManager teamManager;

    public PlayerConnectionListener(JustTeams plugin) {
        this.plugin = plugin;
        this.teamManager = plugin.getTeamManager();
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        plugin.getTaskRunner().runAsync(() -> {
            plugin.getStorageManager().getStorage().cachePlayerName(player.getUniqueId(), player.getName());
            
            if (plugin.getConfigManager().isBedrockSupportEnabled() && plugin.getBedrockSupport().isBedrockPlayer(player)) {
                String gamertag = plugin.getBedrockSupport().getBedrockGamertag(player);
                if (gamertag != null && !gamertag.equals(player.getName())) {
                    plugin.getStorageManager().getStorage().cachePlayerName(player.getUniqueId(), gamertag);
                    plugin.getLogger().info("Cached Bedrock player: " + player.getName() + " (Gamertag: " + gamertag + ")");
                }
            }
            
            if (plugin.getConfigManager().isCrossServerSyncEnabled()) {
                String serverIdentifier = plugin.getConfigManager().getServerIdentifier();
                plugin.getStorageManager().getStorage().updatePlayerSession(player.getUniqueId(), serverIdentifier);
            }
        });
        
        if (plugin.getConfigManager().isBedrockSupportEnabled() && plugin.getBedrockSupport().isBedrockPlayer(player)) {
            plugin.getLogger().info("Bedrock player joined: " + player.getName() +
                " (UUID: " + player.getUniqueId() + ")");
            if (plugin.getConfigManager().isShowGamertags()) {
                String gamertag = plugin.getBedrockSupport().getBedrockGamertag(player);
                if (gamertag != null && !gamertag.equals(player.getName())) {
                    plugin.getLogger().info("Bedrock player gamertag: " + gamertag);
                }
            }
        }
        
        teamManager.handlePendingTeleport(player);
        teamManager.loadPlayerTeam(player);
        
        plugin.getTaskRunner().runAsyncTaskLater(() -> {
            java.util.List<eu.kotori.justTeams.team.Team> pendingInvites = teamManager.getPendingInvites(player.getUniqueId());
            if (!pendingInvites.isEmpty()) {
                plugin.getTaskRunner().runOnEntity(player, () -> {
                    for (eu.kotori.justTeams.team.Team team : pendingInvites) {
                        plugin.getMessageManager().sendRawMessage(player,
                            plugin.getMessageManager().getRawMessage("prefix") +
                            plugin.getMessageManager().getRawMessage("invite_received").replace("<team>", team.getName()));
                    }
                    if (pendingInvites.size() == 1) {
                        plugin.getMessageManager().sendMessage(player, "pending_invites_singular");
                    } else {
                        plugin.getMessageManager().sendRawMessage(player,
                            plugin.getMessageManager().getRawMessage("pending_invites_plural")
                                .replace("<count>", String.valueOf(pendingInvites.size())));
                    }
                });
            }
        }, 40L);
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        
        if (plugin.getConfigManager().isBedrockSupportEnabled()) {
            plugin.getBedrockSupport().clearPlayerCache(player.getUniqueId());
        }
        
        teamManager.unloadPlayer(player);
    }
}
