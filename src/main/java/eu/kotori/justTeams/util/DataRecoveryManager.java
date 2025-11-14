package eu.kotori.justTeams.util;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.storage.IDataStorage;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.team.TeamPlayer;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class DataRecoveryManager {
    
    private final JustTeams plugin;
    private final IDataStorage storage;
    private final Map<Integer, TeamSnapshot> teamSnapshots = new ConcurrentHashMap<>();
    private final Map<Integer, Instant> lastSaveTimestamps = new ConcurrentHashMap<>();
    private final File backupDirectory;
    private boolean autoSaveEnabled = true;
    
    private final Set<Integer> changedTeams = ConcurrentHashMap.newKeySet();
    
    public DataRecoveryManager(JustTeams plugin) {
        this.plugin = plugin;
        this.storage = plugin.getStorageManager().getStorage();
        this.backupDirectory = new File(plugin.getDataFolder(), "backups");
        
        if (!backupDirectory.exists()) {
            backupDirectory.mkdirs();
        }
        
        startAutoSaveTask();
        startBackupTask();
    }
    
    private void startAutoSaveTask() {
        plugin.getTaskRunner().runAsyncTaskTimer(() -> {
            if (autoSaveEnabled) {
                performAutoSave();
            }
        }, 6000L, 6000L);
    }
    
    private void startBackupTask() {
        plugin.getTaskRunner().runAsyncTaskTimer(() -> {
            if (autoSaveEnabled) {
                createBackupSnapshot();
            }
        }, 36000L, 36000L);
    }
    
    public void performAutoSave() {
        if (changedTeams.isEmpty()) {
            return;
        }
        
        plugin.getLogger().info("Auto-save starting for " + changedTeams.size() + " modified teams...");
        int savedCount = 0;
        int errorCount = 0;
        
        Set<Integer> teamsToSave = new HashSet<>(changedTeams);
        changedTeams.clear();
        
        for (int teamId : teamsToSave) {
            try {
                Optional<Team> teamOpt = storage.findTeamById(teamId);
                if (teamOpt.isPresent()) {
                    Team team = teamOpt.get();
                    saveTeamData(team);
                    savedCount++;
                    
                    teamSnapshots.put(teamId, new TeamSnapshot(team));
                    lastSaveTimestamps.put(teamId, Instant.now());
                }
            } catch (Exception e) {
                errorCount++;
                plugin.getLogger().severe("Error auto-saving team " + teamId + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        plugin.getLogger().info("Auto-save completed: " + savedCount + " saved, " + errorCount + " errors");
    }
    
    private void saveTeamData(Team team) {
        for (TeamPlayer member : team.getMembers()) {
            try {
                storage.updateMemberPermissions(
                    team.getId(),
                    member.getPlayerUuid(),
                    member.canWithdraw(),
                    member.canUseEnderChest(),
                    member.canSetHome(),
                    member.canUseHome()
                );
                
                storage.updateMemberEditingPermissions(
                    team.getId(),
                    member.getPlayerUuid(),
                    member.canEditMembers(),
                    member.canEditCoOwners(),
                    member.canKickMembers(),
                    member.canPromoteMembers(),
                    member.canDemoteMembers()
                );
            } catch (Exception e) {
                plugin.getLogger().severe("Error saving member " + member.getPlayerUuid() + " in team " + team.getName() + ": " + e.getMessage());
            }
        }
        
        try {
            storage.setPvpStatus(team.getId(), team.isPvpEnabled());
            storage.setPublicStatus(team.getId(), team.isPublic());
            storage.updateTeamBalance(team.getId(), team.getBalance());
            storage.updateTeamStats(team.getId(), team.getKills(), team.getDeaths());
        } catch (Exception e) {
            plugin.getLogger().severe("Error saving team settings for " + team.getName() + ": " + e.getMessage());
        }
    }
    
    public void markTeamChanged(int teamId) {
        changedTeams.add(teamId);
    }
    
    public void createBackupSnapshot() {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            File backupFile = new File(backupDirectory, "teams_backup_" + timestamp + ".log");
            
            plugin.getLogger().info("Creating backup snapshot: " + backupFile.getName());
            
            try (FileWriter writer = new FileWriter(backupFile)) {
                writer.write("=".repeat(80) + "\n");
                writer.write("DonutTeams Data Backup\n");
                writer.write("Timestamp: " + timestamp + "\n");
                writer.write("=".repeat(80) + "\n\n");
                
                List<Team> allTeams = storage.getAllTeams();
                writer.write("Total Teams: " + allTeams.size() + "\n\n");
                
                for (Team team : allTeams) {
                    writer.write("-".repeat(80) + "\n");
                    writer.write("Team ID: " + team.getId() + "\n");
                    writer.write("Team Name: " + team.getName() + "\n");
                    writer.write("Team Tag: " + team.getTag() + "\n");
                    writer.write("Owner: " + team.getOwnerUuid() + "\n");
                    writer.write("Member Count: " + team.getMembers().size() + "\n");
                    writer.write("Balance: $" + String.format("%.2f", team.getBalance()) + "\n");
                    writer.write("PvP Enabled: " + team.isPvpEnabled() + "\n");
                    writer.write("Public: " + team.isPublic() + "\n");
                    writer.write("Kills: " + team.getKills() + "\n");
                    writer.write("Deaths: " + team.getDeaths() + "\n");
                    
                    writer.write("\nMembers:\n");
                    for (TeamPlayer member : team.getMembers()) {
                        writer.write("  - " + member.getPlayerUuid() + " (" + member.getRole() + ")\n");
                        writer.write("    Permissions: ");
                        writer.write("withdraw=" + member.canWithdraw() + ", ");
                        writer.write("enderchest=" + member.canUseEnderChest() + ", ");
                        writer.write("sethome=" + member.canSetHome() + ", ");
                        writer.write("usehome=" + member.canUseHome() + "\n");
                    }
                    
                    List<IDataStorage.TeamWarp> warps = storage.getTeamWarps(team.getId());
                    if (!warps.isEmpty()) {
                        writer.write("\nWarps (" + warps.size() + "):\n");
                        for (IDataStorage.TeamWarp warp : warps) {
                            writer.write("  - " + warp.name() + " @ " + warp.serverName() + "\n");
                            writer.write("    Location: " + warp.location() + "\n");
                            writer.write("    Password Protected: " + (warp.password() != null) + "\n");
                        }
                    }
                    
                    writer.write("\n");
                }
                
                writer.write("=".repeat(80) + "\n");
                writer.write("Backup completed successfully\n");
            }
            
            plugin.getLogger().info("Backup snapshot created successfully: " + backupFile.getName());
            
            cleanOldBackups();
            
        } catch (IOException e) {
            plugin.getLogger().severe("Error creating backup snapshot: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void cleanOldBackups() {
        File[] backupFiles = backupDirectory.listFiles((dir, name) -> name.startsWith("teams_backup_") && name.endsWith(".log"));
        
        if (backupFiles == null || backupFiles.length <= 10) {
            return;
        }
        
        Arrays.sort(backupFiles, Comparator.comparingLong(File::lastModified).reversed());
        
        for (int i = 10; i < backupFiles.length; i++) {
            if (backupFiles[i].delete()) {
                plugin.getLogger().info("Deleted old backup: " + backupFiles[i].getName());
            }
        }
    }
    
    public ValidationReport validateTeamData(Team team) {
        ValidationReport report = new ValidationReport(team.getId(), team.getName());
        
        if (team.getOwnerUuid() == null) {
            report.addError("Team has no owner");
        }
        
        if (team.getMembers().isEmpty()) {
            report.addError("Team has no members");
        }
        
        boolean ownerInMembers = team.getMembers().stream()
            .anyMatch(m -> m.getPlayerUuid().equals(team.getOwnerUuid()));
        if (!ownerInMembers) {
            report.addError("Owner not found in members list");
        }
        
        for (TeamPlayer member : team.getMembers()) {
            if (member.getRole() == null) {
                report.addWarning("Member " + member.getPlayerUuid() + " has null role");
            }
        }
        
        if (team.getBalance() < 0) {
            report.addWarning("Team has negative balance: " + team.getBalance());
        }
        
        return report;
    }
    
    public Map<Integer, ValidationReport> validateAllTeams() {
        Map<Integer, ValidationReport> reports = new HashMap<>();
        List<Team> allTeams = storage.getAllTeams();
        
        for (Team team : allTeams) {
            ValidationReport report = validateTeamData(team);
            if (!report.isValid()) {
                reports.put(team.getId(), report);
            }
        }
        
        return reports;
    }
    
    public void forceSaveTeam(Team team) {
        try {
            saveTeamData(team);
            teamSnapshots.put(team.getId(), new TeamSnapshot(team));
            lastSaveTimestamps.put(team.getId(), Instant.now());
            plugin.getLogger().info("Force saved team: " + team.getName());
        } catch (Exception e) {
            plugin.getLogger().severe("Error force saving team " + team.getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public Optional<Instant> getLastSaveTime(int teamId) {
        return Optional.ofNullable(lastSaveTimestamps.get(teamId));
    }
    
    public void setAutoSaveEnabled(boolean enabled) {
        this.autoSaveEnabled = enabled;
        plugin.getLogger().info("Auto-save " + (enabled ? "enabled" : "disabled"));
    }
    
    private static class TeamSnapshot {
        final int memberCount;
        final int warpCount;
        final double balance;
        final Instant timestamp;
        
        TeamSnapshot(Team team) {
            this.memberCount = team.getMembers().size();
            this.warpCount = 0; 
            this.balance = team.getBalance();
            this.timestamp = Instant.now();
        }
    }
    
    public static class ValidationReport {
        private final int teamId;
        private final String teamName;
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        
        public ValidationReport(int teamId, String teamName) {
            this.teamId = teamId;
            this.teamName = teamName;
        }
        
        public void addError(String error) {
            errors.add(error);
        }
        
        public void addWarning(String warning) {
            warnings.add(warning);
        }
        
        public boolean isValid() {
            return errors.isEmpty();
        }
        
        public int getTeamId() {
            return teamId;
        }
        
        public String getTeamName() {
            return teamName;
        }
        
        public List<String> getErrors() {
            return new ArrayList<>(errors);
        }
        
        public List<String> getWarnings() {
            return new ArrayList<>(warnings);
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Validation Report for Team: ").append(teamName).append(" (ID: ").append(teamId).append(")\n");
            
            if (errors.isEmpty() && warnings.isEmpty()) {
                sb.append("  ✓ All checks passed\n");
            }
            
            if (!errors.isEmpty()) {
                sb.append("  ERRORS:\n");
                for (String error : errors) {
                    sb.append("    ✗ ").append(error).append("\n");
                }
            }
            
            if (!warnings.isEmpty()) {
                sb.append("  WARNINGS:\n");
                for (String warning : warnings) {
                    sb.append("    ⚠ ").append(warning).append("\n");
                }
            }
            
            return sb.toString();
        }
    }
}
