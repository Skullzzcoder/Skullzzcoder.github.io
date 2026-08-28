package dev.skullzz.mirage.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Point-and-click front end for {@link SelfFakes}: type an item, click the slots it should
 * appear in. The grid is laid out like the real inventory, main storage above the hotbar.
 */
public class FakeItemsScreen extends Screen {
    private static final int COLUMNS = 9;
    private static final int CELL = 22;
    private static final int BUTTON = 20;

    private final ButtonWidget[] slotButtons = new ButtonWidget[SelfFakes.SLOT_COUNT];

    private TextFieldWidget itemField;
    private TextFieldWidget countField;
    private Text status = Text.empty();

    public FakeItemsScreen() {
        super(Text.literal("Mirage - Fake Items"));
    }

    @Override
    protected void init() {
        int gridWidth = COLUMNS * CELL - (CELL - BUTTON);
        int left = (this.width - gridWidth) / 2;

        this.itemField = new TextFieldWidget(this.textRenderer, left, 40, 140, 20,
                Text.literal("item"));
        this.itemField.setMaxLength(64);
        this.itemField.setPlaceholder(Text.literal("diamond_block").formatted(Formatting.DARK_GRAY));
        this.addDrawableChild(this.itemField);

        this.countField = new TextFieldWidget(this.textRenderer, left + 146, 40, 40, 20,
                Text.literal("count"));
        this.countField.setMaxLength(3);
        this.countField.setText("1");
        this.addDrawableChild(this.countField);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Clear all"), button -> {
            SelfFakes.clearAll(this.client == null ? null : this.client.player);
            refreshButtons();
            this.status = Text.literal("Cleared every fake.").formatted(Formatting.GREEN);
        }).dimensions(left + 192, 40, 66, 20).build());

        // Main inventory on top, hotbar underneath, matching the real inventory screen.
        int top = 76;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                addSlotButton(9 + row * COLUMNS + column, left + column * CELL, top + row * CELL);
            }
        }
        int hotbarY = top + 3 * CELL + 8;
        for (int column = 0; column < COLUMNS; column++) {
            addSlotButton(column, left + column * CELL, hotbarY);
        }

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> this.close())
                .dimensions(this.width / 2 - 50, hotbarY + 34, 100, 20).build());

        refreshButtons();
    }

    private void addSlotButton(int slot, int x, int y) {
        ButtonWidget button = ButtonWidget.builder(Text.empty(), ignored -> onSlotClicked(slot))
                .dimensions(x, y, BUTTON, BUTTON)
                .build();
        this.slotButtons[slot] = button;
        this.addDrawableChild(button);
    }

    /** Empty item field clears the slot; otherwise the slot takes the typed item. */
    private void onSlotClicked(int slot) {
        String typed = this.itemField.getText().trim();

        if (typed.isEmpty()) {
            SelfFakes.clear(slot, this.client == null ? null : this.client.player);
            refreshButtons();
            this.status = Text.literal("Cleared " + SelfFakes.slotName(slot) + ".")
                    .formatted(Formatting.GREEN);
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
        count = Math.max(1, Math.min(count, 127));

        SelfFakes.set(slot, new ItemStack(item, count));
        refreshButtons();
        this.status = Text.literal(count + "x " + typed + " in " + SelfFakes.slotName(slot))
                .formatted(Formatting.GREEN);
    }

    private void refreshButtons() {
        for (int slot = 0; slot < this.slotButtons.length; slot++) {
            ButtonWidget button = this.slotButtons[slot];
            if (button == null) continue;
            // The icon is drawn on top in render(); the dot keeps it visible on a busy row.
            button.setMessage(SelfFakes.has(slot) ? Text.literal("") : Text.empty());
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 16, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Type an item, then click a slot. Empty field clears a slot.")
                        .formatted(Formatting.GRAY),
                this.width / 2, 27, 0xFFAAAAAA);

        // Show what each faked slot actually holds.
        for (int slot = 0; slot < this.slotButtons.length; slot++) {
            ButtonWidget button = this.slotButtons[slot];
            if (button == null || !SelfFakes.has(slot)) continue;
            context.drawItem(SelfFakes.get(slot), button.getX() + 2, button.getY() + 2);
        }

        if (!this.status.getString().isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, this.status,
                    this.width / 2, this.height - 26, 0xFFFFFFFF);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
