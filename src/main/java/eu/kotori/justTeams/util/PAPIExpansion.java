package eu.kotori.justTeams.util;
import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.team.Team;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import java.text.DecimalFormat;
public class PAPIExpansion extends PlaceholderExpansion {
    private final JustTeams plugin;
    private final DecimalFormat kdrFormat = new DecimalFormat("#.##");
    public PAPIExpansion(JustTeams plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public @NotNull String getIdentifier() {
        return "justteams";
    }
    
    @Override
    public @NotNull String getAuthor() {
        return plugin.getDescription().getAuthors().get(0);
    }
    
    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }
    
    @Override
    public boolean persist() {
        return true;
    }
    
    @Override
    public boolean canRegister() {
        return true;
    }
    
    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        try {
            if (params.equalsIgnoreCase("has_team")) {
                return plugin.getTeamManager().getPlayerTeamCached(player.getUniqueId()) != null ? "true" : "false";
            }
            Team team = plugin.getTeamManager().getPlayerTeamCached(player.getUniqueId());
            if (params.equalsIgnoreCase("display")) {
                if (team == null) {
                    return plugin.getGuiConfigManager().getPlaceholder("team_display.no_team", "<gray>No Team</gray>");
                } else {
                    String format = plugin.getGuiConfigManager().getPlaceholder("team_display.format", "<team_color><team_icon><team_tag></team_color>");
                    String teamIcon = plugin.getGuiConfigManager().getPlaceholder("team_display.team_icon", "⚔ ");
                    String teamColor = plugin.getGuiConfigManager().getPlaceholder("team_display.team_color", "#4C9DDE");
                    String tagPrefix = plugin.getGuiConfigManager().getPlaceholder("team_display.tag_prefix", "[");
                    String tagSuffix = plugin.getGuiConfigManager().getPlaceholder("team_display.tag_suffix", "]");
                    String tagColor = plugin.getGuiConfigManager().getPlaceholder("team_display.tag_color", "#FFD700");
                    boolean showIcon = plugin.getGuiConfigManager().getPlaceholder("team_display.show_icon", "true").equals("true");
                    boolean showTag = plugin.getGuiConfigManager().getPlaceholder("team_display.show_tag", "true").equals("true") 
                                      && plugin.getConfigManager().isTeamTagEnabled(); 
                    boolean showName = plugin.getGuiConfigManager().getPlaceholder("team_display.show_name", "false").equals("true");
                    String result = format;
                    result = result.replace("%team_name%", team.getColoredName());
                    result = result.replace("%team_tag%", showTag ? tagPrefix + team.getColoredTag() + tagSuffix : "");
                    result = result.replace("%team_color%", teamColor);
                    result = result.replace("%team_icon%", showIcon ? teamIcon : "");
                    if (showTag) {
                        result = result.replace(tagPrefix + team.getColoredTag() + tagSuffix,
                            "<" + tagColor + ">" + tagPrefix + team.getColoredTag() + tagSuffix + "</" + tagColor + ">");
                    }
                    return result;
                }
            }
            if (team == null) {
                return plugin.getMessageManager().getRawMessage("no_team_placeholder");
            }
            switch (params.toLowerCase()) {
                case "name":
                case "team_name":
                    return team.getName();
                case "tag":
                case "team_tag":
                    return plugin.getConfigManager().isTeamTagEnabled() ? team.getTag() : "";
                case "color_name":
                    return team.getColoredName();
                case "color_tag":
                    return plugin.getConfigManager().isTeamTagEnabled() ? team.getColoredTag() : "";
                case "description":
                case "team_description":
                    return team.getDescription();
                case "owner":
                case "team_owner":
                    return plugin.getServer().getOfflinePlayer(team.getOwnerUuid()).getName();
                
                case "team_id":
                    return String.valueOf(team.getId());
                
                case "member_count":
                case "team_members":
                    return String.valueOf(team.getMembers().size());
                case "max_members":
                case "team_max_members":
                    return String.valueOf(plugin.getConfigManager().getMaxTeamSize());
                case "members_online":
                    return String.valueOf(team.getMembers().stream().filter(p -> p.isOnline()).count());
                
                case "role":
                    var member = team.getMember(player.getUniqueId());
                    return member != null ? member.getRole().name() : "Unknown";
                case "role_level":
                    var memberForLevel = team.getMember(player.getUniqueId());
                    if (memberForLevel == null) return "0";
                    switch (memberForLevel.getRole()) {
                        case OWNER: return "4";
                        case CO_OWNER: return "3";
                        case MEMBER: return "1";
                        default: return "0";
                    }
                case "is_owner":
                    return team.getOwnerUuid().equals(player.getUniqueId()) ? "true" : "false";
                case "is_admin":
                    var adminCheck = team.getMember(player.getUniqueId());
                    if (adminCheck == null) return "false";
                    return (adminCheck.getRole() == eu.kotori.justTeams.team.TeamRole.OWNER || 
                            adminCheck.getRole() == eu.kotori.justTeams.team.TeamRole.CO_OWNER) ? "true" : "false";
                case "is_co_owner":
                    var memberRole = team.getMember(player.getUniqueId());
                    return (memberRole != null && memberRole.getRole().name().equals("CO_OWNER")) ? "true" : "false";
                case "is_member":
                    return team.getMember(player.getUniqueId()) != null ? "true" : "false";
                
                case "kills":
                    return String.valueOf(team.getKills());
                case "deaths":
                    return String.valueOf(team.getDeaths());
                case "kd":
                case "kdr":
                    if (team.getDeaths() == 0) return String.valueOf(team.getKills());
                    return kdrFormat.format((double) team.getKills() / team.getDeaths());
                
                case "bank_balance":
                    DecimalFormat formatter = new DecimalFormat(plugin.getConfigManager().getCurrencyFormat());
                    return formatter.format(team.getBalance());
                case "bank_formatted":
                    DecimalFormat formatterWithSymbol = new DecimalFormat(plugin.getConfigManager().getCurrencyFormat());
                    return "$" + formatterWithSymbol.format(team.getBalance());
                case "bank_balance_raw":
                    return String.valueOf(team.getBalance());
                case "team_public":
                    return team.isPublic() ? "Public" : "Private";
                case "team_pvp":
                    return team.isPvpEnabled() ? "Enabled" : "Disabled";
                case "pvp_enabled":
                    return team.isPvpEnabled() ? "true" : "false";
                case "is_public":
                    return team.isPublic() ? "true" : "false";
                
                case "has_home":
                    return team.getHomeLocation() != null ? "true" : "false";
                case "home_location":
                    if (team.getHomeLocation() == null) return "No home set";
                    var loc = team.getHomeLocation();
                    return loc.getWorld().getName() + ", " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
                
                case "warp_count":
                    return "0";
                case "max_warps":
                    return String.valueOf(plugin.getConfigManager().getMaxWarpsPerTeam());
                
                case "team_size":
                    return String.valueOf(team.getMembers().size());
                case "team_capacity":
                    return String.valueOf(plugin.getConfigManager().getMaxTeamSize());
                case "team_full":
                    return team.getMembers().size() >= plugin.getConfigManager().getMaxTeamSize() ? "true" : "false";
                case "plain_name":
                    return team.getPlainName();
                case "plain_tag":
                    return plugin.getConfigManager().isTeamTagEnabled() ? team.getPlainTag() : "";
                case "join_date":
                    var memberInfo = team.getMember(player.getUniqueId());
                    if (memberInfo != null) {
                        java.time.format.DateTimeFormatter dateFormatter = java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy");
                        return memberInfo.getJoinDate().atZone(java.time.ZoneId.systemDefault()).format(dateFormatter);
                    }
                    return "Unknown";
                case "created_at":
                    return "Unknown";
                default:
                    return null;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error processing PlaceholderAPI request: " + params + " for player: " + player.getName() + " - " + e.getMessage());
            return "";
        }
    }
}
