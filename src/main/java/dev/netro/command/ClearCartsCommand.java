package dev.netro.command;

import dev.netro.NetroPlugin;
import dev.netro.database.CartHeldCountRepository;
import dev.netro.database.CartRepository;
import dev.netro.database.JunctionHeldCountRepository;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * Clears all cart-related state from the DB (cart_segments, segment_occupancy, held counts).
 * Use when the DB is stuck with stale carts or after the auto-cleanup didn't catch something.
 */
public class ClearCartsCommand implements CommandExecutor {

    private final NetroPlugin plugin;

    public ClearCartsCommand(NetroPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var db = plugin.getDatabase();
        CartRepository cartRepo = new CartRepository(db);
        CartHeldCountRepository cartHeldRepo = new CartHeldCountRepository(db);
        JunctionHeldCountRepository junctionHeldRepo = new JunctionHeldCountRepository(db);

        cartRepo.clearAllCartState();
        cartHeldRepo.resetAllHeldCounts();
        junctionHeldRepo.resetAllHeldCounts();

        sender.sendMessage("Cleared all cart state: cart_segments, segment_occupancy, and held counts (node + junction) reset to zero.");
        return true;
    }
}
