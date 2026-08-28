package dev.skullzz.mirage.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Point-and-click front end for {@link SelfFakes}: type an item, click the slots it should
 * appear in. The left grid is laid out like the real inventory, main storage above the
 * hotbar; the right grid is the nine slots of a dispenser or dropper.
 */
public class FakeItemsScreen extends Screen {
    private static final int COLUMNS = 9;
    private static final int CELL = 22;
    private static final int BUTTON = 20;
    private static final int GAP = 20;

    private static final int INVENTORY_WIDTH = COLUMNS * CELL - (CELL - BUTTON);
    private static final int DISPENSER_WIDTH = 3 * CELL - (CELL - BUTTON);
    private static final int TOTAL_WIDTH = INVENTORY_WIDTH + GAP + DISPENSER_WIDTH;

    private final ButtonWidget[] slotButtons = new ButtonWidget[SelfFakes.SLOT_COUNT];
    private final ButtonWidget[] containerButtons = new ButtonWidget[SelfFakes.CONTAINER_SLOT_COUNT];

    private TextFieldWidget itemField;
    private TextFieldWidget countField;
    private Text status = Text.empty();

    private int gridLeft;
    private int gridTop;
    private int dispenserLeft;

    public FakeItemsScreen() {
        super(Text.literal("Mirage - Fake Items"));
    }

    @Override
    protected void init() {
        this.gridLeft = (this.width - TOTAL_WIDTH) / 2;
        this.gridTop = 88;
        this.dispenserLeft = this.gridLeft + INVENTORY_WIDTH + GAP;

        this.itemField = new TextFieldWidget(this.textRenderer, this.gridLeft, 44, 150, 20,
                Text.literal("item"));
        this.itemField.setMaxLength(64);
        this.addDrawableChild(this.itemField);

        this.countField = new TextFieldWidget(this.textRenderer, this.gridLeft + 156, 44, 40, 20,
                Text.literal("count"));
        this.countField.setMaxLength(3);
        this.countField.setText("1");
        this.addDrawableChild(this.countField);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Clear all"), button -> {
            ClientPlayerEntity player = this.client == null ? null : this.client.player;
            SelfFakes.clearAll(player);
            SelfFakes.clearAllContainer(player);
            refreshButtons();
            this.status = Text.literal("Cleared every fake.").formatted(Formatting.GREEN);
        }).dimensions(this.gridLeft + 202, 44, 82, 20).build());

        // Main inventory on top, hotbar underneath, matching the real inventory screen.
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                addSlotButton(9 + row * COLUMNS + column,
                        this.gridLeft + column * CELL, this.gridTop + row * CELL);
            }
        }
        int hotbarY = this.gridTop + 3 * CELL + 8;
        for (int column = 0; column < COLUMNS; column++) {
            addSlotButton(column, this.gridLeft + column * CELL, hotbarY);
        }

        // The dispenser grid, aligned with the top of the inventory grid.
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                addContainerButton(row * 3 + column,
                        this.dispenserLeft + column * CELL, this.gridTop + row * CELL);
            }
        }

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> this.close())
                .dimensions(this.width / 2 - 50, hotbarY + 34, 100, 20).build());

        refreshButtons();
    }

    private void addSlotButton(int slot, int x, int y) {
        ButtonWidget button = ButtonWidget.builder(Text.empty(), ignored -> onSlotClicked(slot, false))
                .dimensions(x, y, BUTTON, BUTTON)
                .build();
        this.slotButtons[slot] = button;
        this.addDrawableChild(button);
    }

    private void addContainerButton(int slot, int x, int y) {
        ButtonWidget button = ButtonWidget.builder(Text.empty(), ignored -> onSlotClicked(slot, true))
                .dimensions(x, y, BUTTON, BUTTON)
                .build();
        this.containerButtons[slot] = button;
        this.addDrawableChild(button);
    }

    /** Empty item field clears the slot; otherwise the slot takes the typed item. */
    private void onSlotClicked(int slot, boolean container) {
        ClientPlayerEntity player = this.client == null ? null : this.client.player;
        String typed = this.itemField.getText().trim();
        String where = container ? ("dispenser slot " + slot) : SelfFakes.slotName(slot);

        if (typed.isEmpty()) {
            if (container) {
                SelfFakes.clearContainer(slot, player);
            } else {
                SelfFakes.clear(slot, player);
            }
            refreshButtons();
            this.status = Text.literal("Cleared " + where + ".").formatted(Formatting.GREEN);
            return;
        }

        Item item = SelfFakes.lookupItem(typed);
        if (item == null) {
            this.status = Text.literal("No item called '" + typed + "'.").formatted(Formatting.RED);
            return;
        }

        int count = 1;
        try {
            count = Integer.parseInt(this.countField.getText().trim());
        } catch (NumberFormatException ignored) {
            // an unparseable count just means one
        }

        ItemStack stack = SelfFakes.buildStack(item, count);
        if (container) {
            SelfFakes.setContainer(slot, stack);
        } else {
            SelfFakes.set(slot, stack);
        }

        refreshButtons();
        this.status = Text.literal(stack.getCount() + "x " + typed + " in " + where
                + (FakeLore.hasPriceFor(stack) ? " (priced)" : " (no price set)"))
                .formatted(Formatting.GREEN);
    }

    private void refreshButtons() {
        // The icon is drawn on top in render(); the buttons themselves stay blank.
        for (ButtonWidget button : this.slotButtons) {
            if (button != null) button.setMessage(Text.empty());
        }
        for (ButtonWidget button : this.containerButtons) {
            if (button != null) button.setMessage(Text.empty());
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 14, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Type an item, then click a slot. Empty field clears a slot.")
                        .formatted(Formatting.GRAY),
                this.width / 2, 26, 0xFFAAAAAA);

        context.drawTextWithShadow(this.textRenderer,
                Text.literal("Your inventory").formatted(Formatting.GRAY),
                this.gridLeft, this.gridTop - 12, 0xFFAAAAAA);
        context.drawTextWithShadow(this.textRenderer,
                Text.literal("Dispenser").formatted(Formatting.GRAY),
                this.dispenserLeft, this.gridTop - 12, 0xFFAAAAAA);

        drawIcons(context, this.slotButtons, false);
        drawIcons(context, this.containerButtons, true);

        if (!this.status.getString().isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, this.status,
                    this.width / 2, this.height - 24, 0xFFFFFFFF);
        }
    }

    private void drawIcons(DrawContext context, ButtonWidget[] buttons, boolean container) {
        for (int slot = 0; slot < buttons.length; slot++) {
            ButtonWidget button = buttons[slot];
            if (button == null) continue;

            boolean set = container ? SelfFakes.hasContainer(slot) : SelfFakes.has(slot);
            if (!set) continue;

            ItemStack stack = container ? SelfFakes.getContainer(slot) : SelfFakes.get(slot);
            context.drawItem(stack, button.getX() + 2, button.getY() + 2);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
