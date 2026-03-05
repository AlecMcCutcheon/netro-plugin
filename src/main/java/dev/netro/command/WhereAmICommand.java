package dev.netro.command;

import dev.netro.NetroPlugin;
import dev.netro.util.AddressHelper;
import dev.netro.util.DimensionHelper;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

/**
 * Command that derives the player's 2D address and local (X,Z) within the localnet cell,
 * then reconstructs world coordinates from that address + local only (no use of actual
 * player position for the reconstruction), and reports whether they match.
 */
public final class WhereAmICommand implements CommandExecutor, TabCompleter {

    private final NetroPlugin plugin;

    public WhereAmICommand(NetroPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be run by a player.");
            return true;
        }

        int blockX = player.getLocation().getBlockX();
        int blockZ = player.getLocation().getBlockZ();
        int dimension = DimensionHelper.dimensionFromEnvironment(player.getWorld().getEnvironment());

        // 1) Get player's region (address) and local X,Z in the 100×100 cell (forward)
        String region = AddressHelper.regionPrefix2D(dimension, blockX, blockZ);
        int mainnetX = AddressHelper.mainnetFromX(blockX);
        int mainnetZ = AddressHelper.mainnetFromZ(blockZ);
        int cq = AddressHelper.clusterQuadrant1to4(blockX, blockZ);
        int lq = AddressHelper.localnetQuadrant1to4(blockX, blockZ);
        int localX = AddressHelper.localOffsetInCellX(blockX, blockZ);
        int localZ = AddressHelper.localOffsetInCellZ(blockX, blockZ);

        // 2) Reconstruct world X,Z from region (mainnet + cq + lq) + local only (inverse; no player position used)
        int reconstructedX = AddressHelper.worldXFromRegionAndLocal(mainnetX, mainnetZ, cq, lq, localX, localZ);
        int reconstructedZ = AddressHelper.worldZFromRegionAndLocal(mainnetX, mainnetZ, cq, lq, localX, localZ);

        boolean match = (reconstructedX == blockX && reconstructedZ == blockZ);

        // Build message (reconstructed from address + local only; actual shown for verification)
        plugin.sendMessage(sender, Component.text("Region (2D): ", NamedTextColor.GRAY)
            .append(Component.text(region, NamedTextColor.WHITE)));
        plugin.sendMessage(sender, Component.text("Local (X,Z) in cell: ", NamedTextColor.GRAY)
            .append(Component.text("(" + localX + ", " + localZ + ")", NamedTextColor.AQUA))
            .append(Component.text("  (0–99 within 100m×100m quadrant)", NamedTextColor.DARK_GRAY)));
        plugin.sendMessage(sender, Component.text("Reconstructed world (X,Z): ", NamedTextColor.GRAY)
            .append(Component.text("(" + reconstructedX + ", " + reconstructedZ + ")", NamedTextColor.GREEN)));
        plugin.sendMessage(sender, Component.text("Actual world (X,Z): ", NamedTextColor.GRAY)
            .append(Component.text("(" + blockX + ", " + blockZ + ")", NamedTextColor.YELLOW))
            .append(Component.text(match ? "  ✓ match" : "  ✗ mismatch", match ? NamedTextColor.GREEN : NamedTextColor.RED)));

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
