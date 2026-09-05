package dev.skullzz.mirage.client;

import java.util.List;

/**
 * The colours the screen is drawn in.
 *
 * <p>Written down as numbers rather than asked of anything, for the same reason the map
 * palette is: these are values, not an API, so they cannot break between Minecraft
 * versions. A theme slightly off is a theme; a guessed method name is a build.
 *
 * <p>Every colour is 0xAARRGGBB. The alpha matters -- the panels are meant to sit over
 * the game rather than block it out.
 */
public final class RyneTheme {

    /** One theme: what it is called, and the handful of colours everything is drawn from. */
    public static final class Theme {
        public final String name;
        public final String blurb;
        /** The colour of the dot beside the name, and of anything selected. */
        public final int accent;
        public final int accentSoft;
        public final int page;
        public final int panel;
        public final int card;
        public final int cardHover;
        public final int line;
        public final int text;
        public final int dim;

        Theme(String name, String blurb, int accent, int accentSoft, int page, int panel,
              int card, int cardHover, int line, int text, int dim) {
            this.name = name;
            this.blurb = blurb;
            this.accent = accent;
            this.accentSoft = accentSoft;
            this.page = page;
            this.panel = panel;
            this.card = card;
            this.cardHover = cardHover;
            this.line = line;
            this.text = text;
            this.dim = dim;
        }
    }

    // The greys are shared: only the accent really changes between these, which is what
    // keeps them looking like one set rather than seven unrelated skins.
    private static final int PAGE = 0xF00B0D12;
    private static final int PANEL = 0xF0121620;
    private static final int CARD = 0xFF171C28;
    private static final int HOVER = 0xFF1E2432;
    private static final int LINE = 0xFF262D3D;
    private static final int TEXT = 0xFFE8EAED;
    private static final int DIM = 0xFF8A90A0;

    public static final List<Theme> ALL = List.of(
            new Theme("Nova Purple", "Violet and electric blue",
                    0xFF8B5CF6, 0x408B5CF6, PAGE, PANEL, CARD, HOVER, LINE, TEXT, DIM),
            new Theme("Amethyst", "Velvet plum and lilac",
                    0xFFA855F7, 0x40A855F7, PAGE, PANEL, CARD, HOVER, LINE, TEXT, DIM),
            new Theme("Crimson", "Rose red and warm ember",
                    0xFFF43F5E, 0x40F43F5E, PAGE, PANEL, CARD, HOVER, LINE, TEXT, DIM),
            new Theme("Azure", "Ocean blue and cyan",
                    0xFF38BDF8, 0x4038BDF8, PAGE, PANEL, CARD, HOVER, LINE, TEXT, DIM),
            new Theme("Prism", "Living spectrum on midnight",
                    0xFF2DD4BF, 0x402DD4BF, PAGE, PANEL, CARD, HOVER, LINE, TEXT, DIM),
            new Theme("Emberfall", "Burnished copper and amber",
                    0xFFF59E0B, 0x40F59E0B, PAGE, PANEL, CARD, HOVER, LINE, TEXT, DIM),
            new Theme("Winterglass", "Arctic cyan and moonlit ice",
                    0xFF7DD3FC, 0x407DD3FC, PAGE, PANEL, CARD, HOVER, LINE, TEXT, DIM));

    private static int chosen = 0;

    private RyneTheme() {
    }

    public static Theme current() {
        return ALL.get(Math.max(0, Math.min(chosen, ALL.size() - 1)));
    }

    public static int index() {
        return chosen;
    }

    public static void choose(int index) {
        if (index >= 0 && index < ALL.size()) chosen = index;
    }

    /** Picks by name, so the config keeps a name rather than a position that could shift. */
    public static boolean choose(String name) {
        for (int i = 0; i < ALL.size(); i++) {
            if (ALL.get(i).name.equalsIgnoreCase(name)) {
                chosen = i;
                return true;
            }
        }
        return false;
    }

    public static List<String> names() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Theme theme : ALL) out.add(theme.name);
        return out;
    }
}
