package com.skullzz.glaze.config;

import com.skullzz.glaze.GlazeClient;
import com.skullzz.glaze.config.gui.Option;
import com.skullzz.glaze.config.gui.Theme;
import com.skullzz.glaze.core.GlazeConfig;
import com.skullzz.glaze.core.PriceBook;
import com.skullzz.glaze.hud.HudEditScreen;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * The settings screen: a category sidebar, a scrolling panel of rows, and a
 * search box that cuts across every category at once.
 *
 * <p>Rows are built fresh whenever the category or the search text changes, so
 * they always reflect the live config rather than a snapshot taken at open time.
 */
public final class GlazeScreen extends Screen {
	private static final int SIDEBAR_WIDTH = 96;
	private static final int HEADER_HEIGHT = 30;
	private static final int FOOTER_HEIGHT = 28;
	private static final int ROW_GAP = 3;
	private static final int SCROLLBAR_WIDTH = 3;

	private enum Category {
		GENERAL("General"),
		HUD("HUD"),
		ECONOMY("Economy"),
		INVENTORY("Inventory"),
		WARNINGS("Warnings"),
		AUTOMATION("Automation"),
		LISTS("Lists"),
		ABOUT("About");

		private final String label;

		Category(String label) {
			this.label = label;
		}
	}

	/** Which list the Lists tab is currently editing. */
	private enum ListTarget {
		WATCHLIST("Watchlist"),
		CONSUMABLES("Low-item thresholds"),
		LOADOUT("Active loadout items");

		private final String label;

		ListTarget(String label) {
			this.label = label;
		}
	}

	private final Screen parent;

	private Category category = Category.GENERAL;
	private ListTarget listTarget = ListTarget.WATCHLIST;

	private final List<Option> rows = new ArrayList<>();
	private String search = "";
	private int scroll;

	private TextFieldWidget searchField;
	private TextFieldWidget addField;

	/**
	 * The mouse position, captured each frame in {@link #render}.
	 *
	 * <p>The click event carries its own coordinates, but taking them from the
	 * render pass keeps this screen working regardless of how that record exposes
	 * them, and render runs every frame so the value is never stale.
	 */
	private int lastMouseX;
	private int lastMouseY;

	private Option dragging;
	private int draggingX;
	private int draggingY;
	private int draggingWidth;

	public GlazeScreen(Screen parent) {
		super(Text.literal("Glaze"));
		this.parent = parent;
	}

	// ------------------------------------------------------------------ layout

	private int panelX() {
		return Math.max(8, width / 2 - 220);
	}

	private int panelWidth() {
		return Math.min(width - 16, 440);
	}

	private int panelY() {
		return Math.max(8, height / 2 - 120);
	}

	private int panelHeight() {
		return Math.min(height - 16, 240);
	}

	private int contentX() {
		return panelX() + SIDEBAR_WIDTH;
	}

	private int contentY() {
		return panelY() + HEADER_HEIGHT;
	}

	private int contentWidth() {
		return panelWidth() - SIDEBAR_WIDTH;
	}

	private int contentHeight() {
		return panelHeight() - HEADER_HEIGHT - FOOTER_HEIGHT;
	}

	// -------------------------------------------------------------------- init

	@Override
	protected void init() {
		searchField = new TextFieldWidget(textRenderer,
				panelX() + panelWidth() - 118, panelY() + 8, 110, 14, Text.literal("Search"));
		searchField.setPlaceholder(Text.literal("search settings"));
		searchField.setMaxLength(48);
		searchField.setChangedListener(value -> {
			search = value == null ? "" : value.trim();
			scroll = 0;
			rebuild();
		});
		addDrawableChild(searchField);

		addField = new TextFieldWidget(textRenderer,
				contentX() + 4, panelY() + panelHeight() - FOOTER_HEIGHT + 6,
				contentWidth() - 60, 14, Text.literal("Add"));
		addField.setMaxLength(64);
		addDrawableChild(addField);

		rebuild();
	}

	/** Rebuilds the visible rows for the current category and search text. */
	private void rebuild() {
		rows.clear();
		GlazeConfig config = GlazeClient.config();

		if (search.isEmpty()) {
			build(category, config);
		} else {
			// A search looks across every category, otherwise you have to already
			// know where a setting lives in order to find it.
			for (Category c : Category.values()) {
				if (c != Category.ABOUT && c != Category.LISTS) {
					build(c, config);
				}
			}
		}

		rows.removeIf(row -> !row.matches(search));
		clampScroll();
		updateAddFieldVisibility();
	}

	private void updateAddFieldVisibility() {
		if (addField == null) {
			return;
		}

		boolean wanted = category == Category.LISTS && search.isEmpty();
		addField.visible = wanted;
		addField.setPlaceholder(Text.literal(switch (listTarget) {
			case WATCHLIST -> "item name";
			case CONSUMABLES -> "item name 8";
			case LOADOUT -> "item name 16";
		}));
	}

	private void build(Category which, GlazeConfig config) {
		Runnable save = GlazeClient::saveConfig;

		switch (which) {
			case GENERAL -> {
				rows.add(new Option.Toggle("Only on DonutSMP",
						"Stay dormant on other servers so prices stay clean",
						() -> config.donutOnly, v -> config.donutOnly = v, save));
				rows.add(new Option.Note("Server match: " + config.serverMatch
						+ "  (edit in config.json)", Theme.TEXT_FAINT));
				rows.add(new Option.Action("HUD layout", "Drag readouts where you want them",
						"Open", Theme.ACCENT,
						() -> client.setScreen(new HudEditScreen(this))));
				rows.add(new Option.Action("Reload config", "Re-read config.json from disk",
						"Reload", Theme.ACCENT, () -> {
							GlazeClient.reloadConfig();
							rebuild();
						}));
			}

			case HUD -> {
				rows.add(new Option.Toggle("Show HUD", "",
						() -> config.hud.enabled, v -> config.hud.enabled = v, save));
				rows.add(new Option.Toggle("Background", "Panel behind each readout",
						() -> config.hud.background, v -> config.hud.background = v, save));
				rows.add(Option.Slider.ofInt("Background opacity", "", 0, 255,
						() -> config.hud.backgroundOpacity,
						v -> config.hud.backgroundOpacity = v, "", save));
				rows.add(new Option.Toggle("Text shadow", "",
						() -> config.hud.textShadow, v -> config.hud.textShadow = v, save));
				rows.add(new Option.Slider("Scale", "Size of the readouts", 0.5, 3.0, 0.05,
						() -> config.hud.scale, v -> config.hud.scale = v,
						v -> String.format(Locale.ROOT, "%.2fx", v), save));
				rows.add(Option.Slider.ofInt("Combat tag length",
						"How long the server keeps you tagged", 1, 60,
						() -> config.hud.combatTagSeconds,
						v -> config.hud.combatTagSeconds = v, "s", save));
			}

			case ECONOMY -> {
				rows.add(new Option.Toggle("Price tooltips", "Median, range and sample count",
						() -> config.economy.priceTooltips,
						v -> config.economy.priceTooltips = v, save));
				rows.add(new Option.Toggle("Stack value", "What the hovered stack is worth",
						() -> config.economy.stackValueTooltip,
						v -> config.economy.stackValueTooltip = v, save));
				rows.add(new Option.Toggle("Record prices", "Learn from auction pages you open",
						() -> config.economy.recordPrices,
						v -> config.economy.recordPrices = v, save));
				rows.add(new Option.Toggle("Highlight deals", "Tint listings under median",
						() -> config.economy.highlightDeals,
						v -> config.economy.highlightDeals = v, save));
				rows.add(new Option.Slider("Deal threshold", "Fraction of median that counts as cheap",
						0.05, 1.0, 0.05,
						() -> config.economy.dealThreshold, v -> config.economy.dealThreshold = v,
						v -> Math.round(v * 100) + "%", save));
				rows.add(Option.Slider.ofInt("Minimum samples",
						"Prices seen before deals are called at all", 1, 50,
						() -> config.economy.dealMinSamples,
						v -> config.economy.dealMinSamples = v, "", save));
				rows.add(new Option.Toggle("Watchlist alerts", "Ping when a watched item appears",
						() -> config.economy.watchlistAlerts,
						v -> config.economy.watchlistAlerts = v, save));
				rows.add(new Option.Toggle("Compact money", "$1.2b rather than $1,200,000,000",
						() -> config.economy.compactMoney,
						v -> config.economy.compactMoney = v, save));
				rows.add(Option.Slider.ofInt("Keep prices for", "", 1, 90,
						() -> config.economy.priceRetentionDays,
						v -> config.economy.priceRetentionDays = v, " days", save));
				rows.add(Option.Slider.ofInt("Samples per item", "", 5, 1000,
						() -> config.economy.maxSamplesPerItem,
						v -> config.economy.maxSamplesPerItem = v, "", save));
			}

			case INVENTORY -> {
				rows.add(new Option.Toggle("Shulker preview", "List contents in the tooltip",
						() -> config.inventory.shulkerPreview,
						v -> config.inventory.shulkerPreview = v, save));
				rows.add(new Option.Toggle("Shulker value", "What the contents are worth",
						() -> config.inventory.shulkerValue,
						v -> config.inventory.shulkerValue = v, save));
				rows.add(new Option.Toggle("Carried totals", "How many you hold in total",
						() -> config.inventory.totalCounts,
						v -> config.inventory.totalCounts = v, save));
				rows.add(new Option.Toggle("Loadout checker", "",
						() -> config.inventory.loadoutChecker,
						v -> config.inventory.loadoutChecker = v, save));
				rows.add(new Option.Cycle("Active loadout", "Right-click to go back",
						() -> new ArrayList<>(config.loadouts.keySet()),
						() -> config.inventory.activeLoadout,
						v -> config.inventory.activeLoadout = v, save));
			}

			case WARNINGS -> {
				rows.add(new Option.Toggle("Durability warnings", "",
						() -> config.warnings.durability,
						v -> config.warnings.durability = v, save));
				rows.add(Option.Slider.ofInt("Warn at durability", "", 1, 99,
						() -> config.warnings.durabilityPercent,
						v -> config.warnings.durabilityPercent = v, "%", save));
				rows.add(new Option.Toggle("Low item warnings", "",
						() -> config.warnings.lowConsumables,
						v -> config.warnings.lowConsumables = v, save));
				rows.add(new Option.Toggle("Warning sounds", "",
						() -> config.warnings.playSound,
						v -> config.warnings.playSound = v, save));
				rows.add(Option.Slider.ofInt("Repeat cooldown",
						"Silence before the same warning fires again", 1, 300,
						() -> config.warnings.cooldownSeconds,
						v -> config.warnings.cooldownSeconds = v, "s", save));
			}

			case AUTOMATION -> {
				rows.add(new Option.Note("The only feature that clicks for you.",
						Theme.TEXT_DIM));
				rows.add(new Option.Note("Check your server's rules before enabling it.",
						Theme.DANGER));
				rows.add(new Option.Toggle("Quick-stash",
						"Shift-click matching items into an open container",
						() -> config.automation.quickStash,
						v -> config.automation.quickStash = v, save));
				rows.add(Option.Slider.ofInt("Click delay", "Gap between moves", 50, 2000,
						() -> config.automation.clickDelayMillis,
						v -> config.automation.clickDelayMillis = v, "ms", save));
				rows.add(Option.Slider.ofInt("Random jitter", "Added to each gap", 0, 1000,
						() -> config.automation.clickJitterMillis,
						v -> config.automation.clickJitterMillis = v, "ms", save));
				rows.add(Option.Slider.ofInt("Maximum moves", "Refuse anything larger", 1, 500,
						() -> config.automation.maxClicksPerAction,
						v -> config.automation.maxClicksPerAction = v, "", save));
			}

			case LISTS -> buildLists(config, save);

			case ABOUT -> {
				PriceBook book = GlazeClient.priceBook();
				rows.add(new Option.Note("Glaze - client-side tools for DonutSMP", Theme.ACCENT));
				rows.add(new Option.Note("Prices known: " + book.itemCount() + " items, "
						+ book.sampleCount() + " samples", Theme.TEXT_DIM));
				rows.add(new Option.Note("Config: .minecraft/config/glaze/config.json",
						Theme.TEXT_DIM));
				rows.add(new Option.Note("Chat patterns and price patterns live in that file.",
						Theme.TEXT_FAINT));
				rows.add(new Option.Note("Readouts blank? Run /glaze chatlog, copy the real",
						Theme.TEXT_FAINT));
				rows.add(new Option.Note("line, fix the pattern, then /glaze reload.",
						Theme.TEXT_FAINT));
				rows.add(new Option.Note("Everything except quick-stash is read-only.",
						Theme.TEXT_FAINT));
			}
		}
	}

	private void buildLists(GlazeConfig config, Runnable save) {
		rows.add(new Option.Cycle("Editing", "Which list the box below adds to",
				() -> {
					List<String> names = new ArrayList<>();

					for (ListTarget target : ListTarget.values()) {
						names.add(target.label);
					}

					return names;
				},
				() -> listTarget.label,
				value -> {
					for (ListTarget target : ListTarget.values()) {
						if (target.label.equals(value)) {
							listTarget = target;
						}
					}

					updateAddFieldVisibility();
				},
				() -> {
				}));

		switch (listTarget) {
			case WATCHLIST -> rows.add(new Option.ListEdit("Watchlist",
					"Alert when these appear on a page you open",
					() -> new ArrayList<>(config.economy.watchlist),
					entry -> {
						config.economy.watchlist.remove(entry);
						config.economy.watchlistMaxPrice.remove(PriceBook.key(entry));
						save.run();
						rebuild();
					}));

			case CONSUMABLES -> rows.add(new Option.ListEdit("Low-item thresholds",
					"Warn when you drop below these counts",
					() -> describe(config.warnings.consumableThresholds),
					entry -> {
						config.warnings.consumableThresholds.remove(nameOf(entry));
						save.run();
						rebuild();
					}));

			case LOADOUT -> {
				String name = config.inventory.activeLoadout;
				Map<String, Integer> kit = config.loadouts.get(name);

				if (kit == null) {
					rows.add(new Option.Note("No loadout called '" + name + "'", Theme.DANGER));
					return;
				}

				rows.add(new Option.ListEdit("Loadout: " + name,
						"What the checker expects you to carry",
						() -> describe(kit),
						entry -> {
							kit.remove(nameOf(entry));
							save.run();
							rebuild();
						}));
			}
		}
	}

	/** Renders an item-to-count map as "name  x8" lines. */
	private static List<String> describe(Map<String, Integer> values) {
		List<String> out = new ArrayList<>();
		values.forEach((item, count) -> out.add(item + "  x" + count));
		return out;
	}

	/** Recovers the item name from a line produced by {@link #describe}. */
	private static String nameOf(String entry) {
		int marker = entry.lastIndexOf("  x");
		return marker < 0 ? entry : entry.substring(0, marker);
	}

	/** Adds whatever is in the text box to the list currently being edited. */
	private void commitAdd() {
		String raw = addField.getText().trim();

		if (raw.isEmpty()) {
			return;
		}

		GlazeConfig config = GlazeClient.config();

		switch (listTarget) {
			case WATCHLIST -> {
				String key = PriceBook.key(raw);

				if (!config.economy.watchlist.contains(key)) {
					config.economy.watchlist.add(key);
				}
			}

			case CONSUMABLES -> parseEntry(raw).ifPresent(entry ->
					config.warnings.consumableThresholds.put(entry.getKey(), entry.getValue()));

			case LOADOUT -> {
				Map<String, Integer> kit = config.loadouts
						.computeIfAbsent(config.inventory.activeLoadout, unused -> new LinkedHashMap<>());
				parseEntry(raw).ifPresent(entry -> kit.put(entry.getKey(), entry.getValue()));
			}
		}

		addField.setText("");
		GlazeClient.saveConfig();
		rebuild();
	}

	/**
	 * Reads "ender pearl 8" into a name and a count.
	 *
	 * <p>A missing count defaults to 1 rather than rejecting the entry, since the
	 * common case for a loadout is "one of these".
	 */
	private static Optional<Map.Entry<String, Integer>> parseEntry(String raw) {
		String[] parts = raw.trim().split("\\s+");

		if (parts.length == 0) {
			return Optional.empty();
		}

		int count = 1;
		String name = raw.trim();

		try {
			count = Integer.parseInt(parts[parts.length - 1]);
			name = String.join(" ", Arrays.copyOf(parts, parts.length - 1));
		} catch (NumberFormatException ignored) {
			// No trailing number, so the whole string is the item name.
		}

		if (name.isBlank() || count <= 0) {
			return Optional.empty();
		}

		return Optional.of(Map.entry(PriceBook.key(name), count));
	}

	// ------------------------------------------------------------------ render

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		lastMouseX = mouseX;
		lastMouseY = mouseY;

		context.fill(0, 0, width, height, Theme.SCREEN_DIM);

		int px = panelX();
		int py = panelY();
		int pw = panelWidth();
		int ph = panelHeight();

		Theme.rounded(context, px, py, pw, ph, Theme.PANEL);
		Theme.outline(context, px, py, pw, ph, Theme.BORDER_BRIGHT);
		Theme.header(context, px, py, pw, HEADER_HEIGHT);

		context.fill(px, py + HEADER_HEIGHT, px + SIDEBAR_WIDTH, py + ph - 1, Theme.SIDEBAR);

		context.drawTextWithShadow(textRenderer,
				Text.literal("Glaze"), px + 10, py + 11, Theme.ACCENT);

		renderSidebar(context, mouseX, mouseY);
		renderRows(context, mouseX, mouseY);
		renderFooter(context, mouseX, mouseY);

		// Widgets last so the search box and add box sit above the panel.
		super.render(context, mouseX, mouseY, delta);
	}

	private void renderSidebar(DrawContext context, int mouseX, int mouseY) {
		int x = panelX();
		int y = contentY() + 4;

		for (Category value : Category.values()) {
			boolean selected = value == category && search.isEmpty();
			boolean hovered = Theme.within(mouseX, mouseY, x, y, SIDEBAR_WIDTH, 18);

			if (selected) {
				context.fill(x, y, x + SIDEBAR_WIDTH, y + 18, Theme.ACCENT_SOFT);
				context.fill(x, y, x + 2, y + 18, Theme.ACCENT);
			} else if (hovered) {
				context.fill(x, y, x + SIDEBAR_WIDTH, y + 18, Theme.HOVER);
			}

			context.drawText(textRenderer, Text.literal(value.label),
					x + 12, y + 5, selected ? Theme.TEXT : Theme.TEXT_DIM, false);
			y += 18;
		}
	}

	private void renderRows(DrawContext context, int mouseX, int mouseY) {
		int x = contentX() + 4;
		int w = contentWidth() - 12;
		int top = contentY() + 4;
		int bottom = contentY() + contentHeight();

		// Clip to the panel so rows scrolling past the edge do not paint over the
		// header, the footer or the sidebar.
		context.enableScissor(contentX(), top, contentX() + contentWidth(), bottom);

		int y = top - scroll;

		for (Option row : rows) {
			if (y + row.height() >= top && y <= bottom) {
				row.render(context, textRenderer, x, y, w, mouseX, mouseY);
			}

			y += row.height() + ROW_GAP;
		}

		context.disableScissor();

		if (rows.isEmpty()) {
			String message = search.isEmpty() ? "Nothing here" : "No settings match '" + search + "'";
			context.drawText(textRenderer, Text.literal(message),
					x + 6, top + 8, Theme.TEXT_FAINT, false);
		}

		renderScrollbar(context, top, bottom);
	}

	private void renderScrollbar(DrawContext context, int top, int bottom) {
		int visible = bottom - top;
		int total = contentLength();

		if (total <= visible) {
			return;
		}

		int trackX = contentX() + contentWidth() - SCROLLBAR_WIDTH - 2;
		int barHeight = Math.max(16, visible * visible / total);
		int travel = visible - barHeight;
		int barY = top + (int) ((double) scroll / (total - visible) * travel);

		context.fill(trackX, top, trackX + SCROLLBAR_WIDTH, bottom, 0x30FFFFFF);
		context.fill(trackX, barY, trackX + SCROLLBAR_WIDTH, barY + barHeight, Theme.ACCENT);
	}

	private void renderFooter(DrawContext context, int mouseX, int mouseY) {
		int y = panelY() + panelHeight() - FOOTER_HEIGHT;
		context.fill(panelX() + 1, y, panelX() + panelWidth() - 1, y + 1, Theme.BORDER);

		if (category == Category.LISTS && search.isEmpty()) {
			int bx = contentX() + contentWidth() - 52;
			boolean over = Theme.within(mouseX, mouseY, bx, y + 6, 44, 14);

			Theme.rounded(context, bx, y + 6, 44, 14, over ? Theme.PANEL_RAISED : Theme.PANEL);
			Theme.outline(context, bx, y + 6, 44, 14, over ? Theme.ACCENT : Theme.BORDER);
			context.drawText(textRenderer, Text.literal("Add"), bx + 15, y + 9,
					over ? Theme.ACCENT : Theme.TEXT_DIM, false);
			return;
		}

		int bx = panelX() + panelWidth() - 60;
		boolean over = Theme.within(mouseX, mouseY, bx, y + 6, 52, 14);

		Theme.rounded(context, bx, y + 6, 52, 14, over ? Theme.PANEL_RAISED : Theme.PANEL);
		Theme.outline(context, bx, y + 6, 52, 14, over ? Theme.ACCENT : Theme.BORDER);
		context.drawText(textRenderer, Text.literal("Done"), bx + 18, y + 9,
				over ? Theme.ACCENT : Theme.TEXT_DIM, false);

		if (contentLength() > contentHeight()) {
			context.drawText(textRenderer, Text.literal("scroll for more"),
					contentX() + 4, y + 9, Theme.TEXT_FAINT, false);
		}
	}

	private int contentLength() {
		int total = 0;

		for (Option row : rows) {
			total += row.height() + ROW_GAP;
		}

		return total;
	}

	private void clampScroll() {
		int max = Math.max(0, contentLength() - contentHeight() + 8);
		scroll = Math.max(0, Math.min(scroll, max));
	}

	// ------------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		int mouseX = lastMouseX;
		int mouseY = lastMouseY;

		if (super.mouseClicked(click, doubled)) {
			return true;
		}

		if (clickSidebar(mouseX, mouseY) || clickFooter(mouseX, mouseY)) {
			return true;
		}

		return clickRows(mouseX, mouseY, click.button());
	}

	private boolean clickSidebar(int mouseX, int mouseY) {
		int x = panelX();
		int y = contentY() + 4;

		for (Category value : Category.values()) {
			if (Theme.within(mouseX, mouseY, x, y, SIDEBAR_WIDTH, 18)) {
				category = value;
				scroll = 0;

				if (!search.isEmpty()) {
					search = "";
					searchField.setText("");
				}

				rebuild();
				return true;
			}

			y += 18;
		}

		return false;
	}

	private boolean clickFooter(int mouseX, int mouseY) {
		int y = panelY() + panelHeight() - FOOTER_HEIGHT;

		if (category == Category.LISTS && search.isEmpty()) {
			int bx = contentX() + contentWidth() - 52;

			if (Theme.within(mouseX, mouseY, bx, y + 6, 44, 14)) {
				commitAdd();
				return true;
			}

			return false;
		}

		int bx = panelX() + panelWidth() - 60;

		if (Theme.within(mouseX, mouseY, bx, y + 6, 52, 14)) {
			close();
			return true;
		}

		return false;
	}

	private boolean clickRows(int mouseX, int mouseY, int button) {
		int x = contentX() + 4;
		int w = contentWidth() - 12;
		int top = contentY() + 4;
		int bottom = contentY() + contentHeight();

		if (mouseY < top || mouseY > bottom || mouseX < contentX()) {
			return false;
		}

		int y = top - scroll;

		for (Option row : rows) {
			if (row.click(x, y, w, mouseX, mouseY, button)) {
				if (row.draggable()) {
					dragging = row;
					draggingX = x;
					draggingY = y;
					draggingWidth = w;
				}

				return true;
			}

			y += row.height() + ROW_GAP;
		}

		return false;
	}

	@Override
	public boolean mouseDragged(Click click, double deltaX, double deltaY) {
		if (dragging != null) {
			dragging.drag(draggingX, draggingY, draggingWidth, lastMouseX);
			return true;
		}

		return super.mouseDragged(click, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (dragging != null) {
			dragging = null;
			GlazeClient.saveConfig();
			return true;
		}

		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (Theme.within((int) mouseX, (int) mouseY, contentX(), contentY(),
				contentWidth(), contentHeight())) {
			scroll -= (int) (vertical * 16);
			clampScroll();
			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public void close() {
		GlazeClient.saveConfig();
		client.setScreen(parent);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
