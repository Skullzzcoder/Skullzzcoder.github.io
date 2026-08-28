package dev.skullzz.mirage.client;

import java.util.Map;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import dev.skullzz.mirage.Mirage;

/**
 * The client half of Mirage: fake items shown only on your own screen.
 *
 * <p>This works on any server, including ones you cannot install mods on, because it never
 * talks to the server. That also means nobody else can see any of it -- for that, the mod
 * has to be installed server-side.
 */
public class MirageClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SelfFakes.load();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            dispatcher.register(ClientCommandManager.literal("fake")
                    .then(ClientCommandManager.literal("ui")
                            .executes(MirageClient::openUi))
                    .then(ClientCommandManager.literal("set")
                            .then(ClientCommandManager.argument("slot", StringArgumentType.word())
                                    .suggests((context, builder) ->
                                            CommandSource.suggestMatching(SelfFakes.slotNames(), builder))
                                    .then(ClientCommandManager.argument("item", StringArgumentType.word())
                                            .executes(context -> set(context, 1))
                                            .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 127))
                                                    .executes(context -> set(context,
                                                            IntegerArgumentType.getInteger(context, "count")))))))
                    .then(ClientCommandManager.literal("clear")
                            .executes(MirageClient::clearAll)
                            .then(ClientCommandManager.argument("slot", StringArgumentType.word())
                                    .suggests((context, builder) ->
                                            CommandSource.suggestMatching(SelfFakes.slotNames(), builder))
                                    .executes(MirageClient::clearSlot)))
                    .then(ClientCommandManager.literal("list")
                            .executes(MirageClient::list)));
        });

        // Repaint every tick: the server overwrites a slot whenever the real item changes.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null) SelfFakes.apply(client.player);
        });

        // Leaving a world invalidates what we thought was underneath each fake.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SelfFakes.forgetShadows());

        Mirage.LOGGER.info("Mirage client ready. /fake ui");
    }

    private static int openUi(CommandContext<FabricClientCommandSource> context) {
        MinecraftClient client = MinecraftClient.getInstance();
        // Deferred: the chat screen is still closing as this runs.
        client.execute(() -> client.setScreen(new FakeItemsScreen()));
        return 1;
    }

    private static int set(CommandContext<FabricClientCommandSource> context, int count) {
        String slotName = StringArgumentType.getString(context, "slot");
        int slot = SelfFakes.slotIndex(slotName);
        if (slot < 0) {
            context.getSource().sendFeedback(Text.literal("Unknown slot '" + slotName
                    + "'. Use hotbar1-hotbar9 or inv1-inv27.").formatted(Formatting.RED));
            return 0;
        }

        String itemName = StringArgumentType.getString(context, "item");
        Item item = SelfFakes.lookupItem(itemName);
        if (item == null) {
            context.getSource().sendFeedback(Text.literal("No item called '" + itemName + "'.")
                    .formatted(Formatting.RED));
            return 0;
        }

        SelfFakes.set(slot, new ItemStack(item, count));
        context.getSource().sendFeedback(Text.literal("Showing " + count + "x " + itemName
                + " in " + slotName + ".").formatted(Formatting.GREEN));
        return 1;
    }

    private static int clearSlot(CommandContext<FabricClientCommandSource> context) {
        String slotName = StringArgumentType.getString(context, "slot");
        int slot = SelfFakes.slotIndex(slotName);
        if (slot < 0) {
            context.getSource().sendFeedback(Text.literal("Unknown slot '" + slotName + "'.")
                    .formatted(Formatting.RED));
            return 0;
        }

        SelfFakes.clear(slot, MinecraftClient.getInstance().player);
        context.getSource().sendFeedback(Text.literal("Cleared " + slotName + ".")
                .formatted(Formatting.GREEN));
        return 1;
    }

    private static int clearAll(CommandContext<FabricClientCommandSource> context) {
        SelfFakes.clearAll(MinecraftClient.getInstance().player);
        context.getSource().sendFeedback(Text.literal("Cleared every fake.")
                .formatted(Formatting.GREEN));
        return 1;
    }

    private static int list(CommandContext<FabricClientCommandSource> context) {
        Map<Integer, ItemStack> fakes = SelfFakes.all();
        if (fakes.isEmpty()) {
            context.getSource().sendFeedback(Text.literal("No fake items set."));
            return 0;
        }

        context.getSource().sendFeedback(Text.literal("Fake items:").formatted(Formatting.AQUA));
        for (Map.Entry<Integer, ItemStack> entry : fakes.entrySet()) {
            ItemStack stack = entry.getValue();
            context.getSource().sendFeedback(Text.literal("  " + SelfFakes.slotName(entry.getKey())
                    + ": " + stack.getCount() + "x " + stack.getName().getString()));
        }
        return fakes.size();
    }
}
