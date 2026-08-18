package dev.skullzz.donutflipper.mod;

import dev.skullzz.donutflipper.service.FlipDto;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Semi-automatic assist: the mod does the finding, you do the buying.
 *
 * <p>The slow part of flipping by hand is not clicking -- it is knowing which of
 * four thousand listings is worth clicking. That part is fully automated. What
 * remains is one deliberate action by you, which keeps this squarely a client
 * display that types a search command, rather than a script that plays the game.
 *
 * <p>Everything here goes through the same paths a player uses: running the
 * search command you would have typed, and copying text to your clipboard. It
 * does not click inventory slots or drive the auction GUI.
 */
public final class AssistController {

    private AssistController() {
    }

    /**
     * Runs the auction house search for a candidate and puts the seller's name
     * on the clipboard, so the listing can be identified at a glance.
     */
    public static void assist(FlipDto flip) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }

        client.keyboard.setClipboard(flip.searchTerm());

        // Close the flip screen first so the auction house GUI can open cleanly.
        client.setScreen(null);
        client.player.networkHandler.sendChatCommand("ah search " + flip.searchTerm());

        client.player.sendMessage(summary(flip), false);
    }

    /** Puts details on the clipboard without running any command. */
    public static void copyDetails(FlipDto flip) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.keyboard.setClipboard(
                "%s x%d | buy %,d | est %,d | profit %,d (%.0f%%) | seller %s".formatted(
                        flip.itemName(), flip.count(), flip.buyPrice(),
                        flip.estimatedValue(), flip.netProfit(), flip.roiPercent(),
                        flip.seller()));
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("[Flipper] details copied").formatted(Formatting.GRAY), true);
        }
    }

    /**
     * The confirmation line. Restates the numbers so the decision is made against
     * the figures, not against the fact that a tool highlighted something.
     */
    private static Text summary(FlipDto flip) {
        return Text.literal("[Flipper] ").formatted(Formatting.GOLD)
                .append(Text.literal(flip.itemName()).formatted(Formatting.WHITE))
                .append(Text.literal(" - buy ").formatted(Formatting.GRAY))
                .append(Text.literal(String.format("%,d", flip.buyPrice()))
                        .formatted(Formatting.YELLOW))
                .append(Text.literal(", worth ~").formatted(Formatting.GRAY))
                .append(Text.literal(String.format("%,d", flip.estimatedValue()))
                        .formatted(Formatting.GREEN))
                .append(Text.literal(", seller ").formatted(Formatting.GRAY))
                .append(Text.literal(flip.seller()).formatted(Formatting.AQUA))
                .append(Text.literal(" (" + flip.confidence().toLowerCase()
                        + ", " + flip.sampleCount() + " sales)").formatted(Formatting.DARK_GRAY))
                .setStyle(Style.EMPTY.withClickEvent(
                        new ClickEvent.SuggestCommand("/ah search " + flip.searchTerm())));
    }
}
