package dev.khoa.plugin.cookieportal.command;

import dev.khoa.plugin.cookieportal.CookiePortalPlugin;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

/**
 * Command executor and tab completer for /cookieportal (or /cp).
 */
public final class CookiePortalCommand implements CommandExecutor, TabCompleter {

    private final CookiePortalPlugin plugin;

    public CookiePortalCommand(CookiePortalPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length != 0 && !args[0].equalsIgnoreCase("info")) {
            if (args[0].equalsIgnoreCase("reload")) {
                this.plugin.reloadRuntime();
                sender.sendMessage(ChatColor.GREEN + "CookiePortal reloaded successfully.");
                return true;
            } else {
                return false;
            }
        } else {
            String message = ChatColor.DARK_PURPLE + "CookiePortal " + this.plugin.getDescription().getVersion()
                + ChatColor.GRAY + " | Portals: " + ChatColor.WHITE + this.plugin.registry().all().size()
                + ChatColor.GRAY + " | Active Views: " + ChatColor.WHITE + this.plugin.renderer().activeViews()
                + ChatColor.GRAY + " | Dimension Stack: " + ChatColor.WHITE + (this.plugin.dimensionStack().enabled() ? "Active" : "Disabled");
            sender.sendMessage(message);
            return true;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        } else {
            String prefix = args[0].toLowerCase();
            return List.of("reload", "info").stream().filter(option -> option.startsWith(prefix)).toList();
        }
    }
}
