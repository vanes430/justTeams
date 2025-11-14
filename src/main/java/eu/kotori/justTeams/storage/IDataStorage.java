package eu.kotori.justTeams.storage;
import eu.kotori.justTeams.team.BlacklistedPlayer;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.team.TeamPlayer;
import eu.kotori.justTeams.team.TeamRole;
import org.bukkit.Location;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
public interface IDataStorage {
    record TeamHome(Location location, String serverName) {}
    record TeamWarp(String name, String location, String serverName, String password) {}
    record TeamEnderChestLock(int teamId, String serverName, Timestamp lockTime) {}
    record CrossServerUpdate(int id, int teamId, String updateType, String playerUuid, String serverName, Timestamp timestamp) {}
    record CrossServerMessage(int id, int teamId, String playerUuid, String message, String serverName, Timestamp timestamp) {}
    record TeamInvite(int teamId, String teamName, UUID inviterUuid, String inviterName, Timestamp createdAt) {}
    record PlayerSession(UUID playerUuid, String serverName, Timestamp lastSeen) {}
    
    boolean init();
    void shutdown();
    void cleanup();
    boolean isConnected();
    Optional<Team> createTeam(String name, String tag, UUID ownerUuid, boolean defaultPvpStatus, boolean defaultPublicStatus);
    void deleteTeam(int teamId);
    boolean addMemberToTeam(int teamId, UUID playerUuid);
    void removeMemberFromTeam(UUID playerUuid);
    Optional<Team> findTeamByPlayer(UUID playerUuid);
    Optional<Team> findTeamByName(String name);
    Optional<Team> findTeamById(int id);
    List<Team> getAllTeams();
    List<TeamPlayer> getTeamMembers(int teamId);
    void setTeamHome(int teamId, Location location, String serverName);
    void deleteTeamHome(int teamId);
    Optional<TeamHome> getTeamHome(int teamId);
    void setTeamTag(int teamId, String tag);
    void setTeamDescription(int teamId, String description);
    void transferOwnership(int teamId, UUID newOwnerUuid, UUID oldOwnerUuid);
    void setPvpStatus(int teamId, boolean status);
    void setPublicStatus(int teamId, boolean isPublic);
    void updateTeamBalance(int teamId, double balance);
    void updateTeamStats(int teamId, int kills, int deaths);
    void saveEnderChest(int teamId, String serializedInventory);
    String getEnderChest(int teamId);
    void updateMemberPermissions(int teamId, UUID memberUuid, boolean canWithdraw, boolean canUseEnderChest, boolean canSetHome, boolean canUseHome) throws SQLException;
    void updateMemberPermission(int teamId, UUID memberUuid, String permission, boolean value) throws SQLException;
    void updateMemberRole(int teamId, UUID memberUuid, TeamRole role);
    void updateMemberEditingPermissions(int teamId, UUID memberUuid, boolean canEditMembers, boolean canEditCoOwners, boolean canKickMembers, boolean canPromoteMembers, boolean canDemoteMembers);
    Map<Integer, Team> getTopTeamsByKills(int limit);
    Map<Integer, Team> getTopTeamsByBalance(int limit);
    Map<Integer, Team> getTopTeamsByMembers(int limit);
    void updateServerHeartbeat(String serverName);
    Map<String, Timestamp> getActiveServers();
    void addPendingTeleport(UUID playerUuid, String serverName, Location location);
    Optional<Location> getAndRemovePendingTeleport(UUID playerUuid, String currentServer);
    boolean acquireEnderChestLock(int teamId, String serverIdentifier);
    void releaseEnderChestLock(int teamId);
    Optional<TeamEnderChestLock> getEnderChestLock(int teamId);
    void addJoinRequest(int teamId, UUID playerUuid);
    void removeJoinRequest(int teamId, UUID playerUuid);
    List<UUID> getJoinRequests(int teamId);
    boolean hasJoinRequest(int teamId, UUID playerUuid);
    void clearAllJoinRequests(UUID playerUuid);
    void setWarp(int teamId, String warpName, Location location, String serverName, String password);
    void deleteWarp(int teamId, String warpName);
    Optional<TeamWarp> getWarp(int teamId, String warpName);
    List<TeamWarp> getWarps(int teamId);
    int getTeamWarpCount(int teamId);
    boolean teamWarpExists(int teamId, String warpName);
    boolean setTeamWarp(int teamId, String warpName, String locationString, String serverName, String password);
    boolean deleteTeamWarp(int teamId, String warpName);
    Optional<TeamWarp> getTeamWarp(int teamId, String warpName);
    List<TeamWarp> getTeamWarps(int teamId);
    void addCrossServerUpdate(int teamId, String updateType, String playerUuid, String serverName);
    void addCrossServerUpdatesBatch(List<CrossServerUpdate> updates);
    List<CrossServerUpdate> getCrossServerUpdates(String serverName);
    void removeCrossServerUpdate(int updateId);
    void addCrossServerMessage(int teamId, String playerUuid, String message, String serverName);
    List<CrossServerMessage> getCrossServerMessages(String serverName);
    void removeCrossServerMessage(int messageId);
    void cleanupAllEnderChestLocks();
    void cleanupStaleEnderChestLocks(int hoursOld);
    boolean addPlayerToBlacklist(int teamId, UUID playerUuid, String playerName, String reason, UUID blacklistedByUuid, String blacklistedByName) throws SQLException;
    boolean removePlayerFromBlacklist(int teamId, UUID playerUuid) throws SQLException;
    boolean isPlayerBlacklisted(int teamId, UUID playerUuid) throws SQLException;
    List<BlacklistedPlayer> getTeamBlacklist(int teamId) throws SQLException;
    
    Optional<UUID> getPlayerUuidByName(String playerName);
    void cachePlayerName(UUID playerUuid, String playerName);
    Optional<String> getPlayerNameByUuid(UUID playerUuid);
    
    void addTeamInvite(int teamId, UUID playerUuid, UUID inviterUuid);
    void removeTeamInvite(int teamId, UUID playerUuid);
    boolean hasTeamInvite(int teamId, UUID playerUuid);
    List<Integer> getPlayerInvites(UUID playerUuid);
    List<TeamInvite> getPlayerInvitesWithDetails(UUID playerUuid);
    void clearPlayerInvites(UUID playerUuid);
    
    void updatePlayerSession(UUID playerUuid, String serverName);
    Optional<PlayerSession> getPlayerSession(UUID playerUuid);
    Map<UUID, PlayerSession> getTeamPlayerSessions(int teamId);
    void cleanupStaleSessions(int minutesOld);
    
    void setServerAlias(String serverName, String alias);
    Optional<String> getServerAlias(String serverName);
    Map<String, String> getAllServerAliases();
    void removeServerAlias(String serverName);
    
    void setTeamRenameTimestamp(int teamId, Timestamp timestamp);
    Optional<Timestamp> getTeamRenameTimestamp(int teamId);
    void setTeamName(int teamId, String newName);
}
