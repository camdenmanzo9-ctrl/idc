package dev.profit.dashboard;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Snowy's Dashboard: sidebar with Dashboard, Players, Payments, Auctions and Streamer, over an animated scene. */
public class OverviewScreen extends Screen {
    // ---------------- colors ----------------
    // Fixed dark obsidian panels; only the accent follows your color (Streamer tab > Accent color).
    private static final int BG = 0xFF060509, FRAME = 0xFF0D0B14, PANEL = 0xFF12101C, BORDER = 0xFF221E33;
    private static int PURPLE = 0xFF8B5CF6, PURPLE_LIGHT = 0xFFB794F6;
    private static final int TEXT = 0xFFF2F0FA, MUTED = 0xFFADA9C4, DIM = 0xFF8A86A3,
            GOLD = 0xFFE0B24A, BLUE = 0xFF7B8CFF, RED = 0xFFFF5A6E, GREEN = 0xFF4ADE80, AMBER = 0xFFE0A030;

    /** Load the accent color. */
    private static void applyPalette() {
        PURPLE = 0xFF000000 | (Settings.S.accent & 0xFFFFFF);
        PURPLE_LIGHT = mixColor(PURPLE, 0xFFFFFFFF, 0.40f);
    }

    /** Text color that reads on top of the accent (dark text on light accents). */
    private static int onAccent() {
        int r = (PURPLE >> 16) & 0xFF, g = (PURPLE >> 8) & 0xFF, b = PURPLE & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 > 170 ? 0xFF111111 : TEXT;
    }

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final String[] RANGE_LABELS = {"SESSION", "24H", "7D", "30D", "ALL"};
    private static final long[] RANGES = {-1, DAY, 7 * DAY, 30 * DAY, 0};   // -1 = since the session started
    private static final String[] TAB_LABELS = {"Dashboard", "Players", "Payments", "Auctions", "Streamer"};

    private static int tab = 0;
    private static int range = 2;           // 7D like the picture
    private static int filter = 0;          // 0 all, 1 in, 2 out (payment feed)
    private static int payFilter = 0;       // same, for the Payments page
    private static int playersView = 0;     // 0 all players, 1 rakeback
    private static int payShown = 12;       // Payments page: rows loaded (LOAD MORE adds 12)
    private static boolean paused = false;
    private static long frozenAt = 0;
    private static long revealAt = 0;       // when the current page started its reveal animation

    private final List<Button> rangeButtons = new ArrayList<>();
    private final List<Button> tabButtons = new ArrayList<>();
    private final List<Button> filterButtons = new ArrayList<>();
    private final List<Button> viewButtons = new ArrayList<>();
    private Button sessionButton, headerSettings, loadMoreBtn;
    private EditBox searchBox;
    private Object pendingFocus;   // the search box to focus on the next frame
    private static String search = "", paySearch = "";
    private long confirmUntil = 0;
    private static int scroll = 0;

    private int ox, ow, side;      // whole window and the sidebar width
    private int fx, fy, fw, fh;    // content area (right of the sidebar)

    public OverviewScreen() {
        super(Component.literal("Snowy's Dashboard"));
    }

    /** Open on a given tab next time (used by the Streamer keybind). */
    public static void openTab(int t) {
        tab = Math.max(0, Math.min(TAB_LABELS.length - 1, t));
        scroll = 0;
        wlPlayer = null;
        reveal();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private long now() { return paused ? frozenAt : System.currentTimeMillis(); }

    private long since() {
        long r = RANGES[range];
        if (r < 0) return PaymentLedger.isSessionActive() ? PaymentLedger.sessionStartTime() : now() + 1;
        return r == 0 ? 0 : now() - r;
    }

    // ---------------- font ----------------
    private static final Style INTER = Style.EMPTY.withFont(
            new FontDescription.Resource(Identifier.fromNamespaceAndPath("bettors_dashboard", "inter")));
    private static final Style INTER_BOLD = Style.EMPTY.withFont(
            new FontDescription.Resource(Identifier.fromNamespaceAndPath("bettors_dashboard", "inter_bold")));

    private static Component c(String s, boolean bold) {
        return Component.literal(s).withStyle(bold ? INTER_BOLD : INTER);
    }

    private static final float TEXT_SIZE = 1.25f;   // overall text size

    private void text(GuiGraphicsExtractor g, String s, int x, int y, int color, float scale, boolean bold) {
        Fonts.draw(g, font, s, x, y, color, scale * TEXT_SIZE, bold);
    }

    private int w(String s, float scale) { return Fonts.width(font, s, scale * TEXT_SIZE, false); }
    private int wb(String s, float scale) { return Fonts.width(font, s, scale * TEXT_SIZE, true); }

    private int caps(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        int cx = x;
        for (char ch : s.toCharArray()) {
            String t = String.valueOf(ch);
            text(g, t, cx, y, color, 0.76f, true);
            cx += wb(t, 0.76f);
        }
        return cx - x;
    }

    private int capsW(String s) {
        int t = 0;
        for (char ch : s.toCharArray()) t += wb(String.valueOf(ch), 0.76f);
        return t;
    }


    // ---------------- keys ----------------
    private void togglePause() {
        paused = !paused;
        if (paused) frozenAt = System.currentTimeMillis();
    }

    private static void reveal() { revealAt = System.currentTimeMillis(); }

    private void go(int t) {
        tab = t;
        scroll = 0;
        wlPlayer = null;
        bindingKey = false;
        reveal();
        rebuildWidgets();
    }

    /** 1-5 change the range, Space pauses, Tab switches pages, Esc closes. */
    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int k = event.key();
        if (bindingKey) {   // Streamer tab: waiting for the new key
            KeyMapping km = DashboardClient.streamerKey;
            if (km != null && k != GLFW.GLFW_KEY_ESCAPE) {
                boolean clear = k == GLFW.GLFW_KEY_BACKSPACE || k == GLFW.GLFW_KEY_DELETE;
                km.setKey(clear ? InputConstants.UNKNOWN : InputConstants.Type.KEYSYM.getOrCreate(k));
                KeyMapping.resetMapping();
                if (minecraft != null) minecraft.options.save();
            }
            bindingKey = false;
            return true;
        }
        for (EditBox eb : new EditBox[]{searchBox, itemSearch, nameBox, qtyBox, secBox, hexBox})
            if (eb != null && eb.isFocused() && k != GLFW.GLFW_KEY_ESCAPE) return super.keyPressed(event);
        if (k >= GLFW.GLFW_KEY_1 && k <= GLFW.GLFW_KEY_5) { range = k - GLFW.GLFW_KEY_1; return true; }
        if (k == GLFW.GLFW_KEY_SPACE) { togglePause(); return true; }
        if (k == GLFW.GLFW_KEY_TAB) { go((tab + 1) % TAB_LABELS.length); return true; }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY) * 3);
        if ((tab == 1 && wlPlayer == null && playersView == 0) || tab == 4) rebuildWidgets();
        return true;
    }

    // ---------------- layout ----------------
    private int small(String s) { return w(s, 0.75f) + 12; }

    private Button btn(String label, int x, int y, int bw, Runnable r) {
        return addWidget(Button.builder(Component.literal(label), b -> r.run()).bounds(x, y, bw, 16).build());
    }

    private int contentY() { return fy + 38; }   // a little room under the header for the scene's lights
    private int contentBottom() { return fy + fh - 22; }

    @Override
    protected void init() {
        applyPalette();
        int mx = Math.max(6, width / 70), my = Math.max(5, height / 60);
        ow = width - mx * 2;
        fh = height - my * 2;
        ox = mx;
        fy = my;
        side = Math.max(84, Math.min(124, ow / 7));
        fx = ox + side;
        fw = ow - side;

        rangeButtons.clear();
        tabButtons.clear();
        filterButtons.clear();
        viewButtons.clear();
        streamButtons.clear();
        sessionButton = null;
        loadMoreBtn = null;
        searchBox = null;
        wlRows.clear();
        wlBack = null;
        itemSearch = null;
        nameBox = null; qtyBox = null; secBox = null; hexBox = null;
        auctionButtons.clear();

        // sidebar: the 5 tabs, then Settings near the bottom
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int t = i;
            Button b = btn("", ox + 6, fy + 36 + i * 22, side - 12, () -> go(t));
            b.setHeight(18);
            tabButtons.add(b);
        }
        headerSettings = btn("", ox + 6, fy + fh - 52, side - 12, () -> {
            if (minecraft != null) minecraft.setScreen(new SettingsScreen(this));
        });
        headerSettings.setHeight(18);

        // header: [SESSION 24H 7D 30D ALL]  [RESET SESSION]
        int rw = wb("RESET SESSION", 0.7f) + 26;
        sessionButton = btn("", fx + fw - 10 - rw, fy + 5, rw, this::onSession);
        int bx = sessionButton.getX() - 8;
        for (int i = RANGE_LABELS.length - 1; i >= 0; i--) {
            final int r = i;
            int bw = wb(RANGE_LABELS[i], 0.7f) + 12;
            rangeButtons.add(0, btn(RANGE_LABELS[i], bx - bw, fy + 5, bw, () -> range = r));
            bx -= bw + 1;
        }

        if (tab == 0) {
            int[] feed = feedBox();
            int px = feed[0] + feed[2] - 8;
            String[] f = {"OUT", "IN", "ALL"};
            for (int i = 0; i < 3; i++) {
                final int idx = 2 - i;
                int pw = small(f[i]);
                filterButtons.add(0, btn(f[i], px - pw, feed[1] + 6, pw, () -> filter = idx));
                px -= pw + 3;
            }
        } else if (tab == 1 && wlPlayer != null) {
            int bw = w("All players", 0.8f) + 26;
            wlBack = btn("All players", fx + 10, contentY() + 4, bw, () -> { wlPlayer = null; scroll = 0; reveal(); rebuildWidgets(); });
            wlBack.setHeight(18);
        } else if (tab == 1) {
            // ALL PLAYERS / RAKEBACK switch, top right
            String[] v = {"RAKEBACK", "ALL PLAYERS"};
            int px = fx + fw - 10;
            for (int i = 0; i < 2; i++) {
                final int idx = 1 - i;
                int pw = wb(v[i], 0.7f) + 16;
                viewButtons.add(0, btn(v[i], px - pw, contentY() + 6, pw, () -> {
                    if (playersView != idx) { playersView = idx; scroll = 0; reveal(); rebuildWidgets(); }
                }));
                px -= pw + 1;
            }
            int sbw = Math.min(170, fw / 4), sbx = fx + fw - 10 - sbw, sby = contentY() + 30 + 8;
            searchBox = new EditBox(font, -4000, -4000, 200, 12, Component.literal("Search"));   // hidden: we draw it ourselves
            searchBox.setMaxLength(32);
            searchBox.setValue(search);
            searchBox.setResponder(val -> { search = val; scroll = 0; });
            addWidget(searchBox);
            Button click = btn("", sbx, sby, sbw, () -> pendingFocus = searchBox);
            click.setHeight(20);
            if (playersView == 0) addWinLossRowButtons();
        } else if (tab == 2) {
            initPayments();
        } else if (tab == 3) {
            initAuctions();
        } else if (tab == 4) {
            initStreamer();
        }
    }

    // RESET SESSION: first click asks, second click ends the session and starts a fresh one.
    private void onSession() {
        long t = System.currentTimeMillis();
        if (PaymentLedger.isSessionActive()) {
            if (t > confirmUntil) { confirmUntil = t + 3000; return; }
            confirmUntil = 0;
            PaymentLedger.endSession();
        }
        PaymentLedger.startSession();
        reveal();
        rebuildWidgets();
    }

    // Display copy of you (empty hands, no cape), made once per world
    private static PreviewPlayer previewPlayer;
    private static Object previewLevel;

    private PreviewPlayer preview() {
        if (previewPlayer == null || previewLevel != minecraft.level) {
            previewPlayer = new PreviewPlayer(minecraft.level, minecraft.player.getGameProfile());
            previewLevel = minecraft.level;
        }
        previewPlayer.refreshParts();
        return previewPlayer;
    }

    // ---- Win / Loss: click a player to open their page ----
    private static String wlPlayer = null;
    private Button wlBack;
    private final java.util.Map<Button, String> wlRows = new java.util.LinkedHashMap<>();

    /** The Win / Loss list in display order (search applied), shared by drawing and click areas. */
    private List<PaymentLedger.Entry> winLossList() {
        List<PaymentLedger.Entry> all = new ArrayList<>();
        String q = search.trim().toLowerCase(Locale.US);
        for (PaymentLedger.Entry e : PaymentLedger.entries(false))
            if (q.isEmpty() || e.name.toLowerCase(Locale.US).contains(q)) all.add(e);
        all.sort(Comparator.comparingLong(PaymentLedger.Entry::net).reversed());
        return all;
    }

    private int wlTableY() { return contentY() + 30 + 44; }
    private int wlRowsY() { return wlTableY() + 8 + 19; }
    private static final int WL_ROW_H = 21;
    private int wlVisible() { return Math.max(1, (contentBottom() - 4 - wlRowsY()) / WL_ROW_H); }

    private void addWinLossRowButtons() {
        wlRows.clear();
        List<PaymentLedger.Entry> all = winLossList();
        int px = fx + 10, pw = fw - 20;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, all.size() - wlVisible())));
        for (int i = 0; i < wlVisible() && scroll + i < all.size(); i++) {
            String name = all.get(scroll + i).name;
            Button b = btn("", px + 4, wlRowsY() + i * WL_ROW_H - 3, pw - 12, () -> { wlPlayer = name; scroll = 0; reveal(); rebuildWidgets(); });
            b.setHeight(WL_ROW_H);
            wlRows.put(b, name);
        }
    }


    /** Esc on a player page goes back to the list instead of closing. */
    @Override
    public void onClose() {
        if (tab == 1 && wlPlayer != null) { wlPlayer = null; scroll = 0; reveal(); rebuildWidgets(); return; }
        super.onClose();
    }

    // ---- Dashboard layout: welcome, 4 cards, feed + balance chart, winners / losers across the bottom ----
    private boolean shortScreen() { return contentBottom() - contentY() < 380; }   // e.g. 1080p at GUI scale 3
    private int bottomH() { return shortScreen() ? 80 : 98; }
    private int welcomeY() { return contentY(); }
    private int cardsY() { return welcomeY() + (shortScreen() ? 42 : 50); }
    private int cardsH() { return shortScreen() ? 74 : 88; }
    private int bottomY() { return contentBottom() - bottomH(); }
    private int[] feedBox() {
        int y = cardsY() + cardsH() + 8, h = Math.max(70, bottomY() - 8 - y);
        int bw = (fw - 28) * 34 / 100;
        return new int[]{fx + 10, y, bw, h};
    }
    private int[] chartBox() {
        int[] f = feedBox();
        int x = f[0] + f[2] + 8;
        return new int[]{x, f[1], fx + fw - 10 - x, f[3]};
    }

    // ---------------- render ----------------
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // solid dark fill over the whole screen, so the game doesn't show anywhere behind the menu
        g.fill(0, 0, width, height, BG);
        // outer frame
        round(g, ox, fy, ow, fh, 6, alpha(BORDER, 0xC0));
        // animated scene behind the content
        Scenes.draw(g, fx, fy + 27, fw - 1, fh - 28, Settings.S.scene, System.currentTimeMillis());
        // header bar, footer strip, sidebar
        g.fill(fx, fy + 1, fx + fw - 1, fy + 26, alpha(FRAME, 0xF4));
        g.fill(fx, fy + 26, fx + fw - 1, fy + 27, BORDER);
        g.fill(fx, fy + fh - 16, fx + fw - 1, fy + fh - 1, alpha(FRAME, 0xD8));
        round(g, ox + 1, fy + 1, side, fh - 2, 5, alpha(0xFF08070D, 0xF6));
        g.fill(ox + side, fy + 1, ox + side + 1, fy + fh - 1, BORDER);
    }

    private int lastMouseX, lastMouseY;
    private int lookMx = Integer.MIN_VALUE, lookMy;   // welcome skin: where it's looking
    private long lookMovedAt = 0;
    private float lookX = Float.NaN, lookY;

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        applyPalette();
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        if (pendingFocus instanceof EditBox eb) { setFocused(eb); pendingFocus = null; }
        super.extractRenderState(g, mouseX, mouseY, delta);
        drawSidebar(g, mouseX, mouseY);
        drawHeader(g, mouseX, mouseY);

        // page slides up into place while the rows uncover one by one (see revealCurtain)
        float p = ease(Math.min(1f, (System.currentTimeMillis() - revealAt) / (float) REVEAL_MS));
        g.pose().pushMatrix();
        g.pose().translate(0f, (1f - p) * 10f);
        switch (tab) {
            case 0 -> drawOverview(g, mouseX, mouseY);
            case 1 -> {
                if (wlPlayer != null) drawPlayerPage(g, mouseX, mouseY);
                else { if (playersView == 0) drawWinLoss(g, mouseX, mouseY); else drawRakeback(g); drawViewSwitch(g, mouseX, mouseY); }
            }
            case 2 -> drawPayments(g, mouseX, mouseY);
            case 3 -> drawAuctions(g, mouseX, mouseY);
            default -> drawStreamer(g, mouseX, mouseY);
        }
        g.pose().popMatrix();
        revealCurtain(g);
        text(g, "Display only. Data is read from your chat, nothing is sent to the server.", fx + 10, fy + fh - 11, DIM, 0.7f, false);
    }

    // ---------------- reveal animation ----------------
    private static final int REVEAL_MS = 520;

    /** Cubic ease-out: fast start, long soft landing. */
    private static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    /** Bands of the page start dark and clear top to bottom, one after another, so rows appear in turn. */
    private void revealCurtain(GuiGraphicsExtractor g) {
        long age = System.currentTimeMillis() - revealAt;
        int top = contentY(), bottom = contentBottom(), band = 20;
        int bands = Math.max(1, (bottom - top + band - 1) / band);
        float stagger = Math.min(35f, 220f / bands);
        if (age > REVEAL_MS + stagger * bands) return;
        for (int i = 0; i < bands; i++) {
            float p = ease((age - i * stagger) / (float) REVEAL_MS);
            if (p >= 1f) continue;
            int y0 = top + i * band, y1 = Math.min(bottom, y0 + band);
            g.fill(fx, y0, fx + fw - 1, y1, alpha(FRAME, (int) ((1f - p) * 235)));
        }
    }

    // ---------------- sidebar ----------------
    private void drawSidebar(GuiGraphicsExtractor g, int mx, int my) {
        int x = ox + 10;
        donutRing(g, x + 6, fy + 16);
        text(g, "Snowy's", x + 18, fy + 9, TEXT, 0.85f, true);
        text(g, "Dashboard", x + 18, fy + 19, DIM, 0.62f, false);

        for (int i = 0; i < tabButtons.size(); i++) sideItem(g, tabButtons.get(i), TAB_LABELS[i], i, i == tab, mx, my);
        if (headerSettings != null) sideItem(g, headerSettings, "Settings", 5, false, mx, my);

        // you, at the bottom
        String me = minecraft != null ? minecraft.getUser().getName() : "you";
        String shown = Settings.S.displayName == null || Settings.S.displayName.isBlank() ? me : Settings.S.displayName;
        int py = fy + fh - 26;
        g.fill(ox + 6, py - 4, ox + side - 6, py - 3, BORDER);
        face(g, me, ox + 10, py + 2, 14);
        String n = shown;
        while (n.length() > 3 && wb(n, 0.8f) > side - 38) n = n.substring(0, n.length() - 2) + ".";
        text(g, n, ox + 30, py + 5, TEXT, 0.8f, true);
    }

    private void sideItem(GuiGraphicsExtractor g, Button b, String label, int icon, boolean on, int mx, int my) {
        int x = b.getX(), y = b.getY(), bw = b.getWidth(), bh = b.getHeight();
        boolean hot = over(b, mx, my);
        if (on) {
            round(g, x, y, bw, bh, 3, mixColor(0xFF08070D, PURPLE, 0.22f));
            g.fill(x, y + 2, x + 2, y + bh - 2, PURPLE);
        } else if (hot) round(g, x, y, bw, bh, 3, mixColor(0xFF08070D, PURPLE, 0.10f));
        int c = on ? TEXT : hot ? TEXT : MUTED;
        sideIcon(g, icon, x + 8, y + bh / 2, on ? PURPLE_LIGHT : c);
        text(g, label, x + 22, y + (bh - 7) / 2, c, 0.8f, on);
    }

    /** Tiny drawn icons (work with any font). */
    private static void sideIcon(GuiGraphicsExtractor g, int i, int x, int cy, int c) {
        int y = cy - 4;
        switch (i) {
            case 0 -> {   // dashboard: 2x2 tiles
                g.fill(x, y, x + 3, y + 3, c); g.fill(x + 5, y, x + 8, y + 3, c);
                g.fill(x, y + 5, x + 3, y + 8, c); g.fill(x + 5, y + 5, x + 8, y + 8, c);
            }
            case 1 -> {   // players: head and shoulders
                g.fill(x + 2, y, x + 6, y + 4, c);
                g.fill(x, y + 5, x + 8, y + 8, c);
            }
            case 2 -> {   // payments: two arrows
                g.fill(x, y + 1, x + 8, y + 2, c); g.fill(x + 6, y, x + 7, y + 3, c);
                g.fill(x, y + 6, x + 8, y + 7, c); g.fill(x + 1, y + 5, x + 2, y + 8, c);
            }
            case 3 -> {   // auctions: gavel
                g.fill(x + 3, y, x + 8, y + 3, c);
                g.fill(x + 1, y + 3, x + 4, y + 6, c);
                g.fill(x, y + 7, x + 8, y + 8, c);
            }
            case 4 -> {   // streamer: monitor
                g.fill(x, y, x + 8, y + 1, c); g.fill(x, y + 5, x + 8, y + 6, c);
                g.fill(x, y, x + 1, y + 6, c); g.fill(x + 7, y, x + 8, y + 6, c);
                g.fill(x + 3, y + 6, x + 5, y + 8, c);
            }
            default -> {   // settings: gear-ish
                g.fill(x + 2, y + 2, x + 6, y + 6, c);
                g.fill(x + 3, y, x + 5, y + 8, c); g.fill(x, y + 3, x + 8, y + 5, c);
                g.fill(x + 3, y + 3, x + 5, y + 5, 0xFF08070D);
            }
        }
    }

    // ---------------- header ----------------
    private void drawHeader(GuiGraphicsExtractor g, int mx, int my) {
        int lx = fx + 12;
        caps(g, TAB_LABELS[tab].toUpperCase(Locale.US), lx, fy + 10, TEXT);
        if (paused) {
            int px = lx + capsW(TAB_LABELS[tab].toUpperCase(Locale.US)) + 10;
            g.fill(px, fy + 11, px + 4, fy + 15, AMBER);
            caps(g, "PAUSED", px + 8, fy + 10, AMBER);
        }

        groupBar(g, rangeButtons);
        for (int i = 0; i < rangeButtons.size(); i++) {
            Button b = rangeButtons.get(i);
            boolean on = i == range;
            cover(g, b, PANEL);
            round(g, b.getX(), b.getY(), b.getWidth(), b.getHeight(), 2,
                    on ? PURPLE : over(b, mx, my) ? mixColor(PANEL, PURPLE, 0.35f) : PANEL);
            String s = b.getMessage().getString();
            text(g, s, b.getX() + (b.getWidth() - wb(s, 0.7f)) / 2, b.getY() + (b.getHeight() - 7) / 2, on ? onAccent() : MUTED, 0.7f, true);
        }

        if (sessionButton != null) {
            Button b = sessionButton;
            boolean active = PaymentLedger.isSessionActive();
            boolean confirming = confirmUntil > System.currentTimeMillis();
            boolean hot = over(b, mx, my);
            int x = b.getX(), y = b.getY(), bw = b.getWidth(), bh = b.getHeight();
            box(g, x, y, bw, bh, hot ? 0xFF2A0F16 : 0xFF1A0C12, confirming ? RED : 0xFF7A2E3A);
            String s = confirming ? "CLICK AGAIN" : active ? "RESET SESSION" : "START SESSION";
            refreshIcon(g, x + 8, y + bh / 2, confirming ? RED : 0xFFFF8A9A);
            text(g, s, x + 17, y + (bh - 7) / 2, confirming ? RED : 0xFFFF8A9A, 0.7f, true);
        }
    }

    /** Small circular arrow. */
    private static void refreshIcon(GuiGraphicsExtractor g, int cx, int cy, int c) {
        for (int a = 40; a < 330; a += 18) {
            double r = Math.toRadians(a);
            int px = cx + (int) Math.round(Math.sin(r) * 3), py = cy - (int) Math.round(Math.cos(r) * 3);
            g.fill(px, py, px + 1, py + 1, c);
        }
        g.fill(cx - 1, cy - 4, cx + 2, cy - 3, c);
        g.fill(cx + 1, cy - 5, cx + 2, cy - 2, c);
    }

    // ================= DASHBOARD =================
    private static long daysToChristmas() {
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate xmas = java.time.LocalDate.of(today.getYear(), 12, 25);
        if (today.isAfter(xmas)) xmas = xmas.plusYears(1);
        return java.time.temporal.ChronoUnit.DAYS.between(today, xmas);
    }

    private void drawOverview(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        String me = minecraft != null ? minecraft.getUser().getName() : "you";
        int wy = welcomeY();
        if (minecraft != null && minecraft.player != null && Settings.S.skinFaces) {
            // big skin, cut off at the chest: the cards drawn after it cover the lower half
            int bx1 = fx + 10, by1 = wy - 10, bx2 = fx + 70, by2 = cardsY() + 60;
            long nowMs = System.currentTimeMillis();
            if (mouseX != lookMx || mouseY != lookMy) { lookMx = mouseX; lookMy = mouseY; lookMovedAt = nowMs; }
            float cxm = (bx1 + bx2) / 2f, cym = (by1 + by2) / 2f, tx, ty;
            if (nowMs - lookMovedAt < 2500) { tx = mouseX; ty = mouseY; }   // you're moving the mouse: watch it
            else {                                                           // idle: look around slowly
                double t = nowMs / 1000.0;
                tx = cxm + (float) (Math.sin(t * 0.6) * 70 + Math.sin(t * 1.7) * 18);
                ty = cym - 30 + (float) (Math.sin(t * 0.45) * 14);
            }
            if (Float.isNaN(lookX)) { lookX = tx; lookY = ty; }
            lookX += (tx - lookX) * 0.08f;   // ease, so the head turns smoothly
            lookY += (ty - lookY) * 0.08f;
            InventoryScreen.extractEntityInInventoryFollowsMouse(g, bx1, by1, bx2, by2, shortScreen() ? 34 : 40, 0.0625f,
                    lookX, lookY, preview());
        } else {
            face(g, me, fx + 16, wy + 4, 30);
        }
        int nx = fx + 78;
        text(g, "Welcome, ", nx, wy + 8, TEXT, 1.6f, true);
        String shown = Settings.S.displayName == null || Settings.S.displayName.isBlank() ? me : Settings.S.displayName;
        glowText(g, shown, nx + wb("Welcome, ", 1.6f), wy + 8, PURPLE_LIGHT, 1.6f);
        long d = daysToChristmas();
        String sub = (d == 0 ? "Merry Christmas! " : d + (d == 1 ? " day" : " days") + " to Christmas. ") + "Have a great time on DonutSMP!";
        text(g, sub, nx, wy + 30, MUTED, 0.75f, false);

        List<PaymentLedger.Payment> pays = rangePayments();
        long net = 0;
        long[] netSeries = new long[pays.size() + 1];
        for (int i = 0; i < pays.size(); i++) {
            net += pays.get(i).paidYou - pays.get(i).youPaid;
            netSeries[i + 1] = net;
        }

        // ---- cards: balance, playtime, profit, volume ----
        int cy = cardsY(), ch = cardsH(), gap = 8, cw = (fw - 20 - gap * 3) / 4;
        int c1 = fx + 10, c2 = c1 + cw + gap, c3 = c2 + cw + gap, c4 = c3 + cw + gap;

        List<long[]> bal = PaymentLedger.balanceSince(since());
        long[] balVals = new long[bal.size()];
        for (int i = 0; i < bal.size(); i++) balVals[i] = bal.get(i)[1];
        long bv = PaymentLedger.balance();
        boolean balDown = balVals.length > 1 && balVals[balVals.length - 1] < balVals[0];
        String balSub = bv < 0 ? "type /bal to load"
                : balVals.length > 1 ? (balDown ? "\u2193 " : "\u2191 ") + signedMoney(balVals[balVals.length - 1] - balVals[0]) + " " + rangeWord()
                : "from /bal and the Balance HUD";
        card(g, c1, cy, cw, ch, "BALANCE (EST.)", bv < 0 ? "$---" : Money.format(bv), balSub, GOLD, TEXT, balDown ? RED : GREEN, balVals);

        int days = switch (range) { case 0, 1 -> 1; case 2 -> 7; default -> 30; };
        long[] play = PaymentLedger.playLastDays(Math.max(7, days));
        long inRange = 0;
        for (int i = Math.max(0, play.length - days); i < play.length; i++) inRange += play[i];
        String playSub = "+" + (inRange / 3_600_000L) + "h " + (inRange % 3_600_000L / 60_000L) + "m " + rangeWord();
        card(g, c2, cy, cw, ch, "PLAYTIME", (PaymentLedger.playTotal() / 3_600_000L) + "h", playSub, BLUE, TEXT, MUTED, play);

        String since0 = pays.isEmpty() ? "no payments yet" : "since " + dayTime(pays.get(0).ts);
        int pc = net >= 0 ? GREEN : RED;
        card(g, c3, cy, cw, ch, RANGE_LABELS[range] + " PROFIT", signedMoney(net), since0, pc, pc, MUTED, netSeries);

        long volume = 0;
        for (PaymentLedger.Entry e : PaymentLedger.entries(false)) volume += e.paidYou;
        card(g, c4, cy, cw, ch, "VOLUME", Money.format(volume), "all time paid to you", 0xFF2DD4BF, TEXT, MUTED, null);

        // ---- payment feed ----
        int[] fb = feedBox();
        box(g, fb[0], fb[1], fb[2], fb[3], PANEL, BORDER);
        topGlow(g, fb[0], fb[1], fb[2], PURPLE);
        caps(g, "PAYMENT FEED", fb[0] + 10, fb[1] + 10, TEXT);
        groupBar(g, filterButtons);
        for (int i = 0; i < filterButtons.size(); i++) pill(g, filterButtons.get(i), i == filter, mouseX, mouseY);
        List<PaymentLedger.Payment> feed = new ArrayList<>();
        for (int i = pays.size() - 1; i >= 0; i--) {
            PaymentLedger.Payment p = pays.get(i);
            boolean in = p.paidYou > 0;
            if (filter == 0 || (filter == 1 && in) || (filter == 2 && !in)) feed.add(p);
        }
        int rowH = 19, ry = fb[1] + 28, rows = Math.max(0, (fb[1] + fb[3] - 4 - ry) / rowH);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, feed.size() - rows)));
        scrollbar(g, fb[0] + fb[2] - 5, ry - 2, fb[1] + fb[3] - 4 - ry, rows, feed.size());
        for (int i = 0; i < Math.min(rows, feed.size() - scroll); i++) {
            PaymentLedger.Payment p = feed.get(scroll + i);
            boolean in = p.paidYou > 0;
            int y = ry + i * rowH;
            if (i > 0) g.fill(fb[0] + 8, y - 3, fb[0] + fb[2] - 10, y - 2, alpha(BORDER, 200));
            text(g, clock(p.ts), fb[0] + 10, y + 4, DIM, 0.62f, false);
            int ix = fb[0] + 10 + w("00:00 pm", 0.62f) + 6;
            face(g, p.name, ix, y + 1, 11);
            String amt = signedMoney(in ? p.paidYou : -p.youPaid);
            int aw = wb(amt, 0.74f);
            String nm = p.name;
            while (nm.length() > 3 && wb(nm, 0.74f) > fb[0] + fb[2] - 18 - aw - (ix + 15)) nm = nm.substring(0, nm.length() - 2) + ".";
            text(g, nm, ix + 15, y, TEXT, 0.74f, true);
            text(g, in ? "paid you" : "you paid", ix + 15, y + 9, DIM, 0.6f, false);
            text(g, amt, fb[0] + fb[2] - 10 - aw, y + 4, in ? GREEN : RED, 0.74f, true);
        }
        if (feed.isEmpty()) text(g, "No payments in this range", fb[0] + 10, ry + 2, DIM, 0.85f, false);

        // ---- balance chart ----
        int[] cb = chartBox();
        box(g, cb[0], cb[1], cb[2], cb[3], PANEL, BORDER);
        topGlow(g, cb[0], cb[1], cb[2], PURPLE);
        int capW = caps(g, "BALANCE", cb[0] + 12, cb[1] + 10, TEXT);
        String span = switch (range) { case 0 -> "this session"; case 1 -> "last 24 hours"; case 2 -> "last 7 days"; case 3 -> "last 30 days"; default -> "all time"; };
        text(g, span, cb[0] + 20 + capW, cb[1] + 11, DIM, 0.66f, false);
        String big = bv < 0 ? "$---" : Money.format(bv);
        text(g, big, cb[0] + cb[2] - 12 - wb(big, 1.4f), cb[1] + 8, TEXT, 1.4f, true);
        int chX = cb[0] + 12, chY = cb[1] + 34, chW = cb[2] - 24, chH = cb[3] - 46;
        if (balVals.length < 2) {
            text(g, bv < 0 ? "Type /bal (or run the Balance HUD) to start the chart" : "The chart fills in as your balance changes",
                    chX, chY + chH / 2 - 4, DIM, 0.8f, false);
        } else {
            areaChart(g, balVals, chX, chY, chW, chH, balDown ? RED : PURPLE);
        }

        // ---- top winners / losers, full width ----
        drawWinnersLosers(g, pays);
    }

    private void drawWinnersLosers(GuiGraphicsExtractor g, List<PaymentLedger.Payment> pays) {
        int x = fx + 10, y = bottomY(), bw = fw - 20, bh = bottomH();
        box(g, x, y, bw, bh, PANEL, BORDER);
        topGlow(g, x, y, bw, PURPLE);
        int capW = caps(g, "TOP WINNERS / LOSERS", x + 12, y + 9, TEXT);
        text(g, rangeWord(), x + 20 + capW, y + 10, DIM, 0.66f, false);

        Map<String, long[]> pp = perPlayer(pays);   // a[0] = your net from them (+ = they lost)
        List<Map.Entry<String, long[]>> up = new ArrayList<>(), down = new ArrayList<>();
        for (Map.Entry<String, long[]> e : pp.entrySet()) {
            if (e.getValue()[0] < 0) up.add(e); else if (e.getValue()[0] > 0) down.add(e);
        }
        up.sort((a, b) -> Long.compare(a.getValue()[0], b.getValue()[0]));                         // won the most first
        down.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));             // lost the most first

        int colW = (bw - 36) / 2, lx = x + 12, rx = x + 12 + colW + 12;
        caps(g, "TOP WINNERS", lx, y + 22, GREEN);
        caps(g, "TOP LOSERS", rx, y + 22, RED);
        int rowH = 18, ry = y + 36, rows = Math.max(1, (y + bh - 2 - ry) / rowH);
        long maxUp = up.isEmpty() ? 1 : Math.max(1, -up.get(0).getValue()[0]);
        long maxDown = down.isEmpty() ? 1 : Math.max(1, down.get(0).getValue()[0]);
        for (int i = 0; i < rows; i++) {
            if (i < up.size()) wlRow(g, lx, ry + i * rowH, colW, i + 1, up.get(i).getKey(), -up.get(i).getValue()[0], maxUp, GREEN);
            if (i < down.size()) wlRow(g, rx, ry + i * rowH, colW, i + 1, down.get(i).getKey(), -down.get(i).getValue()[0], maxDown, RED);
        }
        if (up.isEmpty()) text(g, "Nobody is up on you", lx, ry + 2, DIM, 0.75f, false);
        if (down.isEmpty()) text(g, "Nobody is down to you", rx, ry + 2, DIM, 0.75f, false);
    }

    /** "1  [face] Name   +$1.28B" with a thin bar under it sized to the amount. */
    private void wlRow(GuiGraphicsExtractor g, int x, int y, int w, int rank, String name, long theirNet, long max, int col) {
        text(g, String.valueOf(rank), x, y + 3, DIM, 0.72f, false);
        face(g, name, x + 12, y, 12);
        String amt = signedMoney(theirNet);
        int aw = wb(amt, 0.8f);
        String nm = name;
        while (nm.length() > 3 && wb(nm, 0.8f) > w - 34 - aw) nm = nm.substring(0, nm.length() - 2) + ".";
        text(g, nm, x + 30, y + 2, TEXT, 0.8f, true);
        text(g, amt, x + w - aw, y + 2, col, 0.8f, true);
        int bar = (int) ((w - 12) * Math.min(1.0, Math.abs(theirNet) / (double) max));
        g.fill(x + 12, y + 15, x + w, y + 16, alpha(BORDER, 200));
        g.fill(x + 12, y + 15, x + 12 + Math.max(2, bar), y + 16, col);
    }

    private static String ago(long ts) {
        long m = Math.max(0, (System.currentTimeMillis() - ts) / 60000);
        if (m < 1) return "just now";
        if (m < 60) return m + "m ago";
        long h = m / 60;
        if (h < 24) return h + "h ago";
        return (h / 24) + "d ago";
    }

    private void drawPlayerPage(GuiGraphicsExtractor g, int mx, int my) {
        String name = wlPlayer;
        PaymentLedger.Entry all = PaymentLedger.allTimeFor(name);
        List<PaymentLedger.Payment> pays = PaymentLedger.paymentsFor(name, 0);   // newest first
        List<PaymentLedger.Payment> oldest = new ArrayList<>(pays);
        java.util.Collections.reverse(oldest);

        // back button
        {
            Button b = wlBack;
            boolean hot = over(b, mx, my);
            box(g, b.getX(), b.getY(), b.getWidth(), b.getHeight(), hot ? 0xFF1E1A2E : PANEL, hot ? PURPLE : BORDER);
            int ax = b.getX() + 8, ay = b.getY() + b.getHeight() / 2;
            for (int i = 0; i < 4; i++) {   // little left arrow
                g.fill(ax + i, ay - i, ax + i + 1, ay - i + 1, hot ? TEXT : MUTED);
                g.fill(ax + i, ay + i, ax + i + 1, ay + i + 1, hot ? TEXT : MUTED);
            }
            text(g, "All players", ax + 9, b.getY() + (b.getHeight() - 7) / 2, hot ? TEXT : MUTED, 0.8f, false);
        }

        // header: face, name, first / last bet
        int hx = fx + 10, hy = contentY() + 30;
        face(g, name, hx, hy, 26);
        text(g, name, hx + 34, hy + 2, TEXT, 1.4f, true);
        String sub = pays.isEmpty() ? "No dated payments yet"
                : "first bet " + day(oldest.get(0).ts) + "   -   last bet " + ago(pays.get(0).ts);
        text(g, sub, hx + 34, hy + 18, DIM, 0.72f, false);

        // stat boxes
        long theirNet = all == null ? 0 : all.net();
        long paidYou = all == null ? 0 : all.paidYou, youPaid = all == null ? 0 : all.youPaid;
        int bets = all == null ? pays.size() : all.payments;
        long biggest = 0;
        for (PaymentLedger.Payment p : pays) biggest = Math.max(biggest, p.youPaid);
        int cy = hy + 34, gap = 8, cw = (fw - 20 - gap * 4) / 5;
        int[] cols = {fx + 10, 0, 0, 0, 0};
        for (int i = 1; i < 5; i++) cols[i] = cols[i - 1] + cw + gap;
        statBox(g, cols[0], cy, cw, "THEIR NET", signedMoney(theirNet), theirNet > 0 ? GREEN : theirNet < 0 ? RED : TEXT,
                theirNet > 0 ? "up on you" : theirNet < 0 ? "down to you" : "even");
        statBox(g, cols[1], cy, cw, "PAID YOU", Money.format(paidYou), TEXT, "their bets");
        statBox(g, cols[2], cy, cw, "YOU PAID", Money.format(youPaid), TEXT, "their wins");
        statBox(g, cols[3], cy, cw, "BETS", String.valueOf(bets), TEXT, "payments");
        statBox(g, cols[4], cy, cw, "BIGGEST WIN", Money.format(biggest), biggest > 0 ? GREEN : TEXT, "one payout");

        // chart (left) + history (right)
        int py = cy + 58, ph = contentBottom() - py;
        int lw = (fw - 28) * 58 / 100, rx = fx + 10 + lw + 8, rw = fx + fw - 10 - rx;
        box(g, fx + 10, py, lw, ph, PANEL, BORDER);
        caps(g, "THEIR NET OVER TIME", fx + 20, py + 10, TEXT);
        long[] series = new long[oldest.size() + 1];
        long run = 0;
        for (int i = 0; i < oldest.size(); i++) {
            run += oldest.get(i).youPaid - oldest.get(i).paidYou;   // their side
            series[i + 1] = run;
        }
        String now = signedMoney(run);
        text(g, now, fx + 10 + lw - 12 - wb(now, 1.1f), py + 8, run > 0 ? GREEN : run < 0 ? RED : MUTED, 1.1f, true);
        chart(g, series, fx + 20, py + 28, lw - 30, ph - 38);

        box(g, rx, py, rw, ph, PANEL, BORDER);
        caps(g, "PAYMENTS", rx + 10, py + 10, TEXT);
        if (!pays.isEmpty()) text(g, String.valueOf(pays.size()), rx + 16 + capsW("PAYMENTS"), py + 10, DIM, 0.72f, false);
        int rowH = 18, ry = py + 28, rows = Math.max(0, (py + ph - 6 - ry) / rowH);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, pays.size() - rows)));
        for (int i = 0; i < rows && scroll + i < pays.size(); i++) {
            PaymentLedger.Payment p = pays.get(scroll + i);
            boolean in = p.paidYou > 0;
            int ty = ry + i * rowH;
            if (i % 2 == 0) g.fill(rx + 6, ty - 3, rx + rw - 6, ty + rowH - 3, 0xFF15121F);
            text(g, day(p.ts) + "  " + clock(p.ts), rx + 12, ty + 2, DIM, 0.74f, false);
            text(g, in ? "bet" : "won", rx + rw * 52 / 100, ty + 2, MUTED, 0.74f, false);
            String amt = in ? "+" + Money.format(p.paidYou) : "-" + Money.format(p.youPaid);
            text(g, amt, rx + rw - 12 - wb(amt, 0.8f), ty + 1, in ? GREEN : RED, 0.8f, true);
        }
        if (pays.isEmpty()) text(g, "No dated payments with " + name + " yet", rx + 12, ry + 2, DIM, 0.8f, false);
        scrollbar(g, rx + rw - 4, ry - 3, py + ph - 6 - ry, rows, pays.size());
    }

    private void statBox(GuiGraphicsExtractor g, int x, int y, int bw, String label, String value, int valueColor, String sub) {
        box(g, x, y, bw, 50, PANEL, BORDER);
        caps(g, label, x + 10, y + 8, MUTED);
        text(g, value, x + 10, y + 21, valueColor, 1.15f, true);
        text(g, sub, x + 10, y + 38, DIM, 0.66f, false);
    }

    // ---------------- drawing helpers ----------------
    /** Rounded box: border color first, then the fill inset by 1px. */
    private void box(GuiGraphicsExtractor g, int x, int y, int bw, int bh, int fill, int border) {
        if (fill == PANEL) fill = (0xE6 << 24) | (PANEL & 0xFFFFFF);
        int r = Math.max(1, Math.min(4, Math.min(bw, bh) / 5));
        round(g, x, y, bw, bh, r, border);
        round(g, x + 1, y + 1, bw - 2, bh - 2, Math.max(0, r - 1), fill);
    }

    /** Filled rectangle with rounded corners of radius r. */
    static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        if (r <= 0) { g.fill(x, y, x + w, y + h, color); return; }
        g.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
    }

    private static int alpha(int col, int a) { return (a << 24) | (col & 0xFFFFFF); }

    private static final boolean GLOWS = false;   // flat look: cleaner and lighter to draw

    private void glow(GuiGraphicsExtractor g, int cx, int cy, int rx, int ry, int color, int strength) {
        if (!GLOWS) return;
        int steps = 10;   // light: 10 soft layers (looks the same, far less to draw)
        int per = Math.max(1, Math.round(strength / (float) steps));
        for (int i = steps; i >= 1; i--) {
            float t = i / (float) steps;
            int gw = Math.round(rx * t), gh = Math.round(ry * t);
            int cw = Math.round(gw * 0.71f);
            g.fill(cx - cw, cy - gh, cx + cw, cy + gh, alpha(color, per));
            g.fill(cx - gw, cy - Math.round(gh * 0.71f), cx - cw, cy + Math.round(gh * 0.71f), alpha(color, per));
            g.fill(cx + cw, cy - Math.round(gh * 0.71f), cx + gw, cy + Math.round(gh * 0.71f), alpha(color, per));
        }
    }

    private static boolean over(Button b, int mx, int my) {
        return b != null && mx >= b.getX() && mx < b.getX() + b.getWidth() && my >= b.getY() && my < b.getY() + b.getHeight();
    }

    private void pill(GuiGraphicsExtractor g, Button b, boolean on, int mx, int my) {
        pill(g, b, on, mx, my, 0.76f);
    }

    private void pill(GuiGraphicsExtractor g, Button b, boolean on, int mx, int my, float sc) {
        int x = b.getX(), y = b.getY(), bw = b.getWidth(), bh = b.getHeight();
        boolean hover = over(b, mx, my);
        cover(g, b, PANEL);
        round(g, x, y, bw, bh, 3, on ? PURPLE : hover ? mixColor(PANEL, PURPLE, 0.45f) : mixColor(PANEL, PURPLE, 0.25f));
        String s = b.getMessage().getString();
        text(g, s, x + (bw - wb(s, sc)) / 2, y + (bh - Math.round(9 * sc)) / 2, on ? onAccent() : hover ? TEXT : MUTED, sc, true);
    }

    /** Thin, smooth, wavy line (no fill), soft edges. */
    private void smoothLine(GuiGraphicsExtractor g, long[] vals, int x, int y, int w, int h, int color) {
        if (vals.length < 2) { g.fill(x, y + h - 2, x + w, y + h, alpha(color, 90)); return; }
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (long v : vals) { min = Math.min(min, v); max = Math.max(max, v); }
        if (max == min) { max++; min--; }
        int prev = Integer.MIN_VALUE;
        for (int px = 0; px < w; px++) {
            double t = (double) px * (vals.length - 1) / Math.max(1, w - 1);
            int i0 = (int) t, i1 = Math.min(vals.length - 1, i0 + 1);
            double f = t - i0, e = (1 - Math.cos(Math.PI * f)) / 2;   // rounded waves between points
            double v = vals[i0] + (vals[i1] - vals[i0]) * e;
            int yy = y + (int) Math.round((max - v) / (double) (max - min) * (h - 2));
            int a = prev == Integer.MIN_VALUE ? yy : prev;
            g.fill(x + px, yy + 2, x + px + 1, y + h + 2, alpha(color, 30));             // filled area under the line
            g.fill(x + px, Math.min(a, yy), x + px + 1, Math.max(a, yy) + 2, color);   // solid, 2 px
            prev = yy;
        }
    }

    /** One bar per day, today on the right and brightest. */
    private void bars(GuiGraphicsExtractor g, long[] vals, int x, int y, int bw, int bh, int color) {
        int n = vals.length;
        long max = 1;
        for (long v : vals) max = Math.max(max, v);
        int gap = n > 14 ? 1 : 3;
        float each = (bw - gap * (n - 1)) / (float) n;
        for (int i = 0; i < n; i++) {
            int x0 = x + Math.round(i * (each + gap)), x1 = x + Math.round(i * (each + gap) + each);
            int h = vals[i] <= 0 ? 1 : Math.max(2, Math.round(bh * vals[i] / (float) max));
            boolean today = i == n - 1;
            g.fill(x0, y + bh - h, Math.max(x0 + 1, x1), y + bh, today ? color : alpha(color, vals[i] <= 0 ? 50 : 130));
        }
    }

    private static int mixColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int gg = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (gg << 8) | bl;
    }

    /** Letter-spaced bold text (tabs). */
    private int spacedW(String s, float sc) {
        int t = 0;
        for (char ch : s.toCharArray()) t += wb(String.valueOf(ch), sc) + 1;
        return Math.max(0, t - 1);
    }

    private void spacedText(GuiGraphicsExtractor g, String s, int x, int y, int color, float sc) {
        for (char ch : s.toCharArray()) {
            String c = String.valueOf(ch);
            text(g, c, x, y, color, sc, true);
            x += wb(c, sc) + 1;
        }
    }

    /** Paint over Minecraft's own button underneath, so its white edge never peeks out at rounded corners. */
    private void cover(GuiGraphicsExtractor g, Button b) { cover(g, b, FRAME); }

    private void cover(GuiGraphicsExtractor g, Button b, int color) {
        g.fill(b.getX(), b.getY(), b.getX() + b.getWidth(), b.getY() + b.getHeight(), color);
    }

    /** Rounded bar drawn behind a row of pills. */
    private void groupBar(GuiGraphicsExtractor g, List<Button> btns) {
        if (btns.isEmpty()) return;
        Button a = btns.get(0), z = btns.get(btns.size() - 1);
        box(g, a.getX() - 2, a.getY() - 2, z.getX() + z.getWidth() - a.getX() + 4, a.getHeight() + 4, PANEL, BORDER);
    }

    private void spark(GuiGraphicsExtractor g, long[] vals, int x, int y, int sw, int sh, int color) {
        if (vals.length < 2) {
            g.fill(x, y + sh - 1, x + sw, y + sh, alpha(color, 60));
            return;
        }
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (long v : vals) { min = Math.min(min, v); max = Math.max(max, v); }
        if (max == min) { max++; min--; }
        int last = -1;
        for (int px = 0; px < sw; px += 2) {
            double t = (double) px * (vals.length - 1) / Math.max(1, sw - 1);
            int i0 = (int) t, i1 = Math.min(vals.length - 1, i0 + 1);
            double v = vals[i0] + (vals[i1] - vals[i0]) * (t - i0);
            int yy = y + (int) Math.round((max - v) / (double) (max - min) * (sh - 2)) + 1;
            g.fill(x + px, yy, x + px + 2, y + sh, alpha(color, 34));
            int from = last < 0 ? yy : last;
            g.fill(x + px, Math.min(yy, from), x + px + 2, Math.max(yy, from) + 1, color);
            last = yy;
        }
    }

    private static String clock(long ts) {
        return java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.US)).toLowerCase(Locale.US);
    }

    private static String day(long ts) {
        return java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.US));
    }

    private static String dayTime(long ts) {
        return java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US));
    }

    private static String signedMoney(long v) {
        return (v > 0 ? "+" : v < 0 ? "-" : "") + Money.format(Math.abs(v));
    }

    private String rangeWord() {
        return switch (range) { case 0 -> "this session"; case 1 -> "today"; case 2 -> "this week"; case 3 -> "this month"; default -> "all time"; };
    }

    private void face(GuiGraphicsExtractor g, String name, int x, int y, int size) {
        if (Settings.S.skinFaces) try {
            var conn = minecraft == null ? null : minecraft.getConnection();
            var info = conn == null ? null : conn.getPlayerInfo(name);
            if (info != null) {
                PlayerFaceExtractor.extractRenderState(g, info.getSkin(), x, y, size);
                return;
            }
        } catch (Exception ignored) { }
        // not online: purple circle-ish badge with the first letter, like the picture
        g.fill(x + 1, y, x + size - 1, y + size, PURPLE);
        g.fill(x, y + 1, x + size, y + size - 1, PURPLE);
        String l = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.US);
        float sc = size / 12f;
        int ty = y + Math.round((size - Fonts.capHeight(sc)) / 2f - Fonts.capTop(sc));   // letter centered top-to-bottom
        text(g, l, x + (size - wb(l, sc)) / 2, ty, TEXT, sc, true);
    }

    /** Payments in the current range, oldest first. */
    private List<PaymentLedger.Payment> rangePayments() {
        long since = since(), now = now();
        List<PaymentLedger.Payment> out = new ArrayList<>();
        for (PaymentLedger.Payment p : PaymentLedger.paymentsSince(since)) if (p.ts <= now) out.add(p);
        return out;
    }

    /** Your net per player in the current range (+ = you won from them). */
    private Map<String, long[]> perPlayer(List<PaymentLedger.Payment> pays) {
        Map<String, long[]> m = new LinkedHashMap<>();   // name -> {net, paidYou, youPaid, count}
        for (PaymentLedger.Payment p : pays) {
            long[] a = m.computeIfAbsent(p.name, k -> new long[4]);
            a[0] += p.paidYou - p.youPaid;
            a[1] += p.paidYou;
            a[2] += p.youPaid;
            a[3]++;
        }
        return m;
    }

    /** Little pixel donut logo: golden dough, pink frosting, sprinkles, a hole in the middle. */
    private static void donut(GuiGraphicsExtractor g, int cx, int cy) {
        final int DOUGH = 0xFFD99A4E, DOUGH_DARK = 0xFFB0763A, FROST = 0xFFF472B6, FROST_LIGHT = 0xFFF9A8D4;
        double R = 5.6, HOLE = 1.9;
        for (int dy = -6; dy <= 6; dy++) {
            for (int dx = -6; dx <= 6; dx++) {
                double d = Math.sqrt((dx + 0.5) * (dx + 0.5) + (dy + 0.5) * (dy + 0.5));
                if (d > R || d < HOLE) continue;
                int col;
                boolean frosting = d < R - 1.2 && (dy < 1 || (dy == 1 && (dx + 7) % 3 != 0));   // frosting with little drips
                if (frosting) col = (dx < -1 && dy < -1) ? FROST_LIGHT : FROST;
                else col = dy > 2 ? DOUGH_DARK : DOUGH;
                g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, col);
            }
        }
        // sprinkles on the frosting
        g.fill(cx - 3, cy - 4, cx - 2, cy - 3, 0xFFFFFFFF);
        g.fill(cx + 2, cy - 4, cx + 3, cy - 3, 0xFFFDE047);
        g.fill(cx + 3, cy - 1, cx + 4, cy, 0xFF60A5FA);
        g.fill(cx - 4, cy - 1, cx - 3, cy, 0xFF4ADE80);
        g.fill(cx, cy - 5, cx + 1, cy - 4, 0xFF60A5FA);
    }

    /** Sharp purple donut: thick ring, clear hole, light top-left, shaded bottom-right. No soft pixels. */
    private static void donutRing(GuiGraphicsExtractor g, int cx, int cy) {
        final int MID = PURPLE, LIGHT = PURPLE_LIGHT;
        final int DARK = 0xFF000000 | ((((MID >> 16) & 0xFF) * 6 / 10) << 16) | ((((MID >> 8) & 0xFF) * 6 / 10) << 8) | ((MID & 0xFF) * 6 / 10);
        double R = 8.0, HOLE = 3.2;
        for (int dy = -9; dy <= 9; dy++) {
            for (int dx = -9; dx <= 9; dx++) {
                double px = dx + 0.5, py = dy + 0.5;
                double d = Math.sqrt(px * px + py * py);
                if (d > R || d < HOLE) continue;
                double light = -(px + py) / Math.max(0.001, d);           // + toward the top-left
                double inner = (d - HOLE) / (R - HOLE);                   // 0 at the hole, 1 at the edge
                int col = light > 0.55 && inner > 0.25 ? LIGHT : light < -0.45 ? DARK : MID;
                g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, col);
            }
        }
    }

    /** Text with a soft glow around the letters. */
    private void glowText(GuiGraphicsExtractor g, String s, int x, int y, int color, float scale) {
        // soft light BEHIND the name; the letters themselves stay sharp
        int tw = wb(s, scale), th = Math.round(9 * scale);
        softGlow(g, x + tw / 2, y + th / 2, tw / 2 + 10, th / 2 + 6, PURPLE, 40);
        text(g, s, x, y, color, scale, true);
    }

    /** Soft round glow: stacked rounded shapes, very faint each. */
    private static void softGlow(GuiGraphicsExtractor g, int cx, int cy, int rw, int rh, int color, int strength) {
        int layers = 8, per = Math.max(1, strength / layers);
        for (int i = layers; i >= 1; i--) {
            int w = rw * i / layers, h = rh * i / layers;
            round(g, cx - w, cy - h, w * 2, h * 2, Math.min(Math.min(w, h), 12), alpha(color, per));
        }
    }

    /** Settings gear button. */
    private void gear(GuiGraphicsExtractor g, Button b, int mx, int my) {
        boolean hot = over(b, mx, my);
        cover(g, b);
        box(g, b.getX() - 2, b.getY() - 2, b.getWidth() + 4, b.getHeight() + 4, hot ? 0xFF1E1A2E : PANEL, hot ? PURPLE : BORDER);
        int cx = b.getX() + b.getWidth() / 2, cy = b.getY() + b.getHeight() / 2, c = hot ? TEXT : MUTED;
        for (int k = 0; k < 8; k++) {   // teeth
            double a = k * Math.PI / 4;
            int tx = cx + (int) Math.round(Math.cos(a) * 5), ty = cy + (int) Math.round(Math.sin(a) * 5);
            g.fill(tx - 1, ty - 1, tx + 1, ty + 1, c);
        }
        for (int dy = -4; dy <= 4; dy++) for (int dx = -4; dx <= 4; dx++) {   // ring
            int d2 = dx * dx + dy * dy;
            if (d2 <= 16 && d2 >= 5) g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, c);
        }
    }

    private static String hms(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    /** Stat card: label, big value, a line under it, and the sparkline along the bottom (never under the text). */
    private void card(GuiGraphicsExtractor g, int x, int y, int cw, int ch, String label, String value, String sub,
                      int lineColor, int valueColor, int subColor, long[] series) {
        box(g, x, y, cw, ch, PANEL, BORDER);
        g.fill(x + 1, y, x + cw - 1, y + 2, lineColor);                          // colored top edge
        for (int i = 0; i < 10; i++)                                               // soft glow fading down from it
            g.fill(x + 1, y + 2 + i, x + cw - 1, y + 3 + i, alpha(lineColor, 26 - i * 2));
        caps(g, fitText(label, cw - 20, 0.76f, true), x + 10, y + 10, MUTED);
        text(g, fitText(value, cw - 20, 1.5f, true), x + 10, y + 24, valueColor, 1.5f, true);
        text(g, fitText(sub, cw - 20, 0.7f, false), x + 10, y + 44, subColor, 0.7f, false);
        int sx = x + cw * 40 / 100, sw = x + cw - 10 - sx, sy = y + ch - 24, sh = 16;
        if (series != null && series.length > 1) thinLine(g, series, sx, sy, sw, sh, lineColor);
    }

    /** Faint glow fading down from the top edge of a panel. */
    private static void topGlow(GuiGraphicsExtractor g, int x, int y, int w, int color) {
        g.fill(x + 1, y, x + w - 1, y + 1, alpha(color, 120));
        for (int i = 0; i < 14; i++) g.fill(x + 1, y + 1 + i, x + w - 1, y + 2 + i, alpha(color, 22 - i * 3 / 2));
    }

    /** Thin 1 px line with a faint fill under it (card sparklines). */
    private void thinLine(GuiGraphicsExtractor g, long[] vals, int x, int y, int w, int h, int color) {
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (long v : vals) { min = Math.min(min, v); max = Math.max(max, v); }
        if (max == min) { max++; min--; }
        int prev = Integer.MIN_VALUE;
        for (int px = 0; px < w; px++) {
            double t = (double) px * (vals.length - 1) / Math.max(1, w - 1);
            int i0 = (int) t, i1 = Math.min(vals.length - 1, i0 + 1);
            double f = t - i0, e = (1 - Math.cos(Math.PI * f)) / 2;
            double v = vals[i0] + (vals[i1] - vals[i0]) * e;
            int yy = y + (int) Math.round((max - v) / (double) (max - min) * (h - 1));
            int a = prev == Integer.MIN_VALUE ? yy : prev;
            g.fill(x + px, yy + 1, x + px + 1, y + h, alpha(color, 18));
            g.fill(x + px, Math.min(a, yy), x + px + 1, Math.max(a, yy) + 1, color);
            prev = yy;
        }
    }

    /** Big filled area chart: translucent fill, 1 px line, dashed guide lines. */
    private void areaChart(GuiGraphicsExtractor g, long[] vals, int x, int y, int w, int h, int color) {
        for (int k = 0; k <= 3; k++) {   // dashed guides
            int gy = y + k * (h - 1) / 3;
            for (int dx = 0; dx < w; dx += 6) g.fill(x + dx, gy, x + Math.min(w, dx + 3), gy + 1, alpha(BORDER, 170));
        }
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (long v : vals) { min = Math.min(min, v); max = Math.max(max, v); }
        if (max == min) { max++; min--; }
        int prev = Integer.MIN_VALUE, last = y;
        for (int px = 0; px < w; px++) {
            double t = (double) px * (vals.length - 1) / Math.max(1, w - 1);
            int i0 = (int) t, i1 = Math.min(vals.length - 1, i0 + 1);
            double f = t - i0, e = (1 - Math.cos(Math.PI * f)) / 2;
            double v = vals[i0] + (vals[i1] - vals[i0]) * e;
            int yy = y + 4 + (int) Math.round((max - v) / (double) (max - min) * (h - 8));
            int a = prev == Integer.MIN_VALUE ? yy : prev;
            g.fill(x + px, yy + 1, x + px + 1, y + h, alpha(color, 85));
            g.fill(x + px, Math.min(a, yy), x + px + 1, Math.max(a, yy) + 1, mixColor(color, 0xFFFFFFFF, 0.25f));
            prev = yy;
            last = yy;
        }
        round(g, x + w - 4, last - 3, 7, 7, 3, mixColor(color, 0xFFFFFFFF, 0.25f));   // dot at "now"
    }

    /** Shortens s with a dot so it fits in maxW. */
    private String fitText(String s, int maxW, float scale, boolean bold) {
        while (s.length() > 3 && (bold ? wb(s, scale) : w(s, scale)) > maxW) s = s.substring(0, s.length() - 2) + ".";
        return s;
    }

    private void chart(GuiGraphicsExtractor g, long[] vals, int x, int y, int cw, int ch) {
        if (vals.length < 2) {
            text(g, "No payments in this range yet", x + 4, y + ch / 2 - 4, DIM, 0.85f, false);
            return;
        }
        long min = 0, max = 0;
        for (long v : vals) { min = Math.min(min, v); max = Math.max(max, v); }
        long pad = Math.max(1, (max - min) / 12);
        final long hi = max + pad, lo = min - pad;
        final double span = (double) (hi - lo);
        double zyF = y + (double) hi / span * (ch - 1);
        int zy = (int) Math.round(zyF);
        for (int dx = 0; dx < cw; dx += 8) g.fill(x + dx, zy, x + Math.min(cw, dx + 4), zy + 1, 0xFF3A3358);

        // sharp line: one column at a time, solid 2 px, connected through drops
        int prevI = Integer.MIN_VALUE;
        double lastY = zyF;
        for (int px = 0; px < cw; px++) {
            double t = (double) px * (vals.length - 1) / Math.max(1, cw - 1);
            int i0 = (int) t, i1 = Math.min(vals.length - 1, i0 + 1);
            double v = vals[i0] + (vals[i1] - vals[i0]) * (t - i0);
            double fy = y + (hi - v) / span * (ch - 1);
            int yy = (int) Math.round(fy);
            int col = v >= 0 ? GREEN : RED;
            int cx0 = x + px;
            if (yy < zy) g.fill(cx0, yy + 1, cx0 + 1, zy, alpha(col, 38));        // even fill above zero
            else if (yy > zy) g.fill(cx0, zy + 1, cx0 + 1, yy, alpha(col, 38));   // even fill below zero
            int a = prevI == Integer.MIN_VALUE ? yy : prevI;
            g.fill(cx0, Math.min(a, yy), cx0 + 1, Math.max(a, yy) + 2, col);
            prevI = yy;
            lastY = fy;
        }
        int ex = x + cw - 1, ey = (int) Math.round(lastY);
        int ec = vals[vals.length - 1] >= 0 ? GREEN : RED;
        round(g, ex - 2, ey - 2, 5, 5, 2, ec);   // end dot in the line's own color
    }

    // ================= shared bits for the list pages =================
    private static final int PINK = 0xFFF472B6;

    private int pageTitle(GuiGraphicsExtractor g, String title, String sub) {
        int x = fx + 10, y = contentY() + 4;
        text(g, title, x, y, TEXT, 1.4f, true);
        int maxW = fw - 20 - (viewButtons.isEmpty() ? 0 : fx + fw - viewButtons.get(0).getX() + 2);   // never runs under the switch
        while (sub.length() > 4 && w(sub, 0.72f) > maxW) sub = sub.substring(0, sub.length() - 2) + ".";
        text(g, sub, x, y + 14, MUTED, 0.72f, false);
        return y + 26;
    }

    /** Little stat box like "PLAYERS 175". Returns its width. */
    private int chip(GuiGraphicsExtractor g, int x, int y, String label, String value, int valueColor) {
        int cw = Math.max(capsW(label), wb(value, 0.95f)) + 22;
        box(g, x, y, cw, 36, PANEL, BORDER);
        caps(g, label, x + 11, y + 7, MUTED);
        text(g, value, x + 11, y + 20, valueColor, 0.95f, true);
        return cw;
    }

    private void scrollbar(GuiGraphicsExtractor g, int x, int y, int h, int visible, int total) {
        if (total <= visible) return;
        g.fill(x, y, x + 2, y + h, 0xFF1C1830);
        int bh = Math.max(12, h * visible / total);
        int by = y + (h - bh) * scroll / Math.max(1, total - visible);
        g.fill(x, by, x + 2, by + bh, 0xFF5F5B75);
    }

    private void drawSearchFrame(GuiGraphicsExtractor g, int y) {
        if (searchBox == null) return;
        int sbw = Math.min(170, fw / 4), sbx = fx + fw - 10 - sbw;
        searchField(g, sbx, y + 8, sbw, 20, searchBox, "Search players");
    }

    /** Clean search field: dark fill, purple outline when typing, magnifier, your text and a blinking cursor. */
    private void searchField(GuiGraphicsExtractor g, int x, int y, int bw, int bh, EditBox box, String hint) {
        boolean focused = box != null && box.isFocused();
        String value = box == null ? "" : box.getValue();
        int r = Math.min(6, bh / 2);
        round(g, x, y, bw, bh, r, focused ? PURPLE : BORDER);                 // thin border
        round(g, x + 1, y + 1, bw - 2, bh - 2, r - 1, mixColor(FRAME, PANEL, 0.6f));   // soft dark fill
        int lc = focused ? PURPLE_LIGHT : DIM, lx0 = x + 10, ly0 = y + bh / 2 - 1;
        ring(g, lx0, ly0, 3, 1, 1f, lc, lc);                                  // round lens
        g.fill(lx0 + 2, ly0 + 2, lx0 + 3, ly0 + 3, lc);                       // short handle
        g.fill(lx0 + 3, ly0 + 3, lx0 + 4, ly0 + 4, lc);
        int tx = x + 19, ty = y + (bh - 7) / 2, maxW = bw - 26;
        if (value.isEmpty()) {
            text(g, hint, tx, ty, DIM, 0.66f, false);
        } else {
            String shown = value;
            while (shown.length() > 1 && w(shown, 0.66f) > maxW) shown = shown.substring(1);   // keep the end visible
            text(g, shown, tx, ty, TEXT, 0.66f, false);
            tx += w(shown, 0.66f) + 1;
        }
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) g.fill(tx, ty - 1, tx + 1, ty + 8, TEXT);
    }

    // ================= WIN / LOSS =================
    private void drawWinLoss(GuiGraphicsExtractor g, int mx, int my) {
        List<PaymentLedger.Entry> all = winLossList();   // everyone, all time, won most off you first
        int up = 0, down = 0;
        long upSum = 0, downSum = 0;
        for (PaymentLedger.Entry e : all) {
            long n = e.net();
            if (n > 0) { up++; upSum += n; } else if (n < 0) { down++; downSum -= n; }
        }

        int y = pageTitle(g, "Players",
                "Everyone who has gambled with you. Top = won the most off you, bottom = lost the most.");
        int x = fx + 10;
        x += chip(g, x, y, "PLAYERS", String.valueOf(all.size()), TEXT) + 6;
        x += chip(g, x, y, "UP ON YOU", up + " \u00b7 +" + Money.format(upSum), GREEN) + 6;
        chip(g, x, y, "DOWN ON YOU", down + " \u00b7 -" + Money.format(downSum), RED);
        drawSearchFrame(g, y);

        int py = y + 44, ph = contentBottom() - py, pw = fw - 20, px = fx + 10;
        box(g, px, py, pw, ph, PANEL, BORDER);
        int cNum = px + 10, cName = px + 28, cBets = px + pw * 48 / 100, cWag = px + pw * 62 / 100, cNet = px + pw - 14;
        int hy = py + 8;
        caps(g, "#", cNum, hy, DIM);
        caps(g, "PLAYER", cName + 14, hy, DIM);
        caps(g, "BETS", cBets, hy, DIM);
        caps(g, "WAGERED", cWag, hy, DIM);
        caps(g, "NET (THEIR SIDE)", cNet - capsW("NET (THEIR SIDE)"), hy, DIM);
        g.fill(px + 1, hy + 13, px + pw - 1, hy + 14, BORDER);

        int rowH = WL_ROW_H, ry = hy + 19, visible = Math.max(1, (py + ph - 4 - ry) / rowH);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, all.size() - visible)));
        for (int i = 0; i < visible && scroll + i < all.size(); i++) {
            int idx = scroll + i;
            PaymentLedger.Entry e = all.get(idx);
            int ty = ry + i * rowH;
            boolean hot = mx >= px + 4 && mx < px + pw - 8 && my >= ty - 3 && my < ty + rowH - 3;
            if (hot) g.fill(px + 4, ty - 3, px + pw - 8, ty + rowH - 3, 0xFF221D36);
            else if (i > 0) g.fill(px + 8, ty - 3, px + pw - 10, ty - 2, alpha(BORDER, 200));
            if (hot) g.fill(px + 4, ty - 3, px + 6, ty + rowH - 3, PURPLE);
            text(g, String.valueOf(idx + 1), cNum, ty + 1, DIM, 0.76f, false);
            face(g, e.name, cName, ty - 1, 10);
            text(g, e.name, cName + 14, ty, hot ? PURPLE_LIGHT : TEXT, 0.8f, true);
            text(g, String.valueOf(e.payments), cBets, ty + 1, MUTED, 0.75f, false);
            text(g, Money.format(e.paidYou), cWag, ty + 1, MUTED, 0.75f, false);
            long n = e.net();
            String v = signedMoney(n);
            text(g, v, cNet - wb(v, 0.8f), ty, n > 0 ? GREEN : n < 0 ? RED : MUTED, 0.8f, true);
        }
        if (all.isEmpty()) text(g, "No players yet", cName, ry, DIM, 0.85f, false);
        else text(g, "click a player to see their history", cName + 14 + capsW("PLAYER") + 10, hy, DIM, 0.66f, false);
        scrollbar(g, px + pw - 5, ry - 3, py + ph - ry, visible, all.size());
    }

    // ================= RAKEBACK =================
    private void drawRakeback(GuiGraphicsExtractor g) {
        Settings st = Settings.S;
        List<PaymentLedger.Entry> losers = new ArrayList<>();
        int ready = 0;
        long owe = 0;
        String q = search.trim().toLowerCase(Locale.US);
        for (PaymentLedger.Entry e : PaymentLedger.entries(false)) {   // everyone, all time
            long lost = -e.net();   // what they lost to you
            if (lost > 0 && (q.isEmpty() || e.name.toLowerCase(Locale.US).contains(q))) {
                losers.add(e);
                if (lost >= st.rakeMin) { ready++; owe += lost * st.rakePct / 100; }
            }
        }
        losers.sort(Comparator.comparingLong(PaymentLedger.Entry::net));   // closest to (or over) the target first

        int y = pageTitle(g, "Rakeback", st.rakePct + "% back once a player has lost " + Money.format(st.rakeMin)
                + ". Everyone, closest to " + Money.format(st.rakeMin) + " first.");
        int x = fx + 10;
        x += chip(g, x, y, "PLAYERS", String.valueOf(losers.size()), TEXT) + 6;
        x += chip(g, x, y, "READY", String.valueOf(ready), GREEN) + 6;
        chip(g, x, y, "YOU OWE", Money.format(owe), TEXT);
        drawSearchFrame(g, y);

        int py = y + 46, ph = contentBottom() - py, pw = fw - 20, px = fx + 10;
        int rowH = 24, ry = py, visible = Math.max(1, (py + ph - 4 - ry) / rowH);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, losers.size() - visible)));
        for (int i = 0; i < visible && scroll + i < losers.size(); i++) {
            int idx = scroll + i;
            PaymentLedger.Entry e = losers.get(idx);
            long lost = -e.net();
            boolean isReady = lost >= st.rakeMin;
            int ty = ry + i * rowH, rx = px, rw = pw - 8;
            box(g, rx, ty, rw, rowH - 4, PANEL, BORDER);
            text(g, String.valueOf(idx + 1), rx + 10, ty + 5, DIM, 0.7f, false);
            text(g, e.name, rx + 28, ty + 4, TEXT, 0.74f, true);
            String right = isReady ? "READY  owe " + Money.format(lost * st.rakePct / 100)
                    : Money.format(lost) + " / " + Money.format(st.rakeMin);
            text(g, right, rx + rw - 10 - wb(right, 0.74f), ty + 4, isReady ? GREEN : TEXT, 0.74f, true);
            int bx = rx + 28, be = rx + rw - 10, by = ty + rowH - 9;
            g.fill(bx, by, be, by + 2, alpha(BORDER, 255));
            int fill = (int) ((be - bx) * Math.min(1.0, lost / (double) st.rakeMin));
            for (int k = 0; k < fill; k += 2) {   // rainbow: pink -> white -> green along the whole bar
                float t = k / (float) Math.max(1, be - bx);
                int c = t < 0.5f ? mixColor(0xFFF472B6, 0xFFEDE9FE, t * 2f) : mixColor(0xFFEDE9FE, 0xFF4ADE80, (t - 0.5f) * 2f);
                g.fill(bx + k, by, bx + Math.min(fill, k + 2), by + 2, c);
            }
        }
        if (losers.isEmpty()) text(g, q.isEmpty() ? "Nobody has lost to you yet" : "No players match \"" + search.trim() + "\"",
                px + 10, ry + 4, DIM, 0.85f, false);
        scrollbar(g, px + pw - 3, ry, Math.min(ph - 8, visible * rowH), visible, losers.size());
    }


    // ================= PLAYERS: ALL PLAYERS / RAKEBACK switch =================
    private void drawViewSwitch(GuiGraphicsExtractor g, int mx, int my) {
        groupBar(g, viewButtons);
        for (int i = 0; i < viewButtons.size(); i++) pill(g, viewButtons.get(i), i == playersView, mx, my, 0.7f);
    }

    // ================= PAYMENTS =================
    private static final int PAY_ROW = 20;

    private int[] payPanel() {
        int y = contentY() + 30 + 58;
        return new int[]{fx + 10, y, fw - 20, contentBottom() - y};
    }

    private int paySearchW() { return Math.min(150, Math.max(80, (fw - 20) / 4)); }

    private int paySearchX() {
        int[] p = payPanel();
        int px = p[0] + p[2] - 10;
        for (String s : new String[]{"ALL", "IN", "OUT"}) px -= small(s) + 3;
        return px - 8 - paySearchW();
    }

    private void initPayments() {
        int[] p = payPanel();
        int px = p[0] + p[2] - 10;
        String[] f = {"OUT", "IN", "ALL"};
        for (int i = 0; i < 3; i++) {
            final int idx = 2 - i;
            int pw = small(f[i]);
            filterButtons.add(0, btn(f[i], px - pw, p[1] + 6, pw, () -> { payFilter = idx; scroll = 0; }));
            px -= pw + 3;
        }
        searchBox = new EditBox(font, -4000, -4000, 200, 12, Component.literal("Search"));   // hidden: we draw it ourselves
        searchBox.setMaxLength(32);
        searchBox.setValue(paySearch);
        searchBox.setResponder(v -> { paySearch = v; scroll = 0; });
        addWidget(searchBox);
        btn("", paySearchX(), p[1] + 6, paySearchW(), () -> pendingFocus = searchBox);
        int lw = wb("LOAD MORE", 0.7f) + 20;
        loadMoreBtn = btn("", p[0] + p[2] - 10 - lw, p[1] + p[3] - 21, lw, () -> payShown += 12);
    }

    /** Payments in range, newest first, with the IN / OUT filter and the search applied. */
    private List<PaymentLedger.Payment> payList() {
        List<PaymentLedger.Payment> pays = rangePayments();
        List<PaymentLedger.Payment> out = new ArrayList<>();
        String q = paySearch.trim().toLowerCase(Locale.US);
        for (int i = pays.size() - 1; i >= 0; i--) {
            PaymentLedger.Payment p = pays.get(i);
            boolean in = p.paidYou > 0;
            if (payFilter == 1 && !in || payFilter == 2 && in) continue;
            if (!q.isEmpty() && !p.name.toLowerCase(Locale.US).contains(q)) continue;
            out.add(p);
        }
        return out;
    }

    private void drawPayments(GuiGraphicsExtractor g, int mx, int my) {
        int y = pageTitle(g, "Payments", "Every payment you sent or received.");
        List<PaymentLedger.Payment> pays = rangePayments();
        long recv = 0, sent = 0;
        int nIn = 0, nOut = 0;
        for (PaymentLedger.Payment p : pays) {
            if (p.paidYou > 0) { recv += p.paidYou; nIn++; }
            if (p.youPaid > 0) { sent += p.youPaid; nOut++; }
        }
        long net = recv - sent;
        int count = pays.size();
        int gap = 8, cw = (fw - 20 - gap * 3) / 4, x = fx + 10;
        statBox(g, x, y, cw, "RECEIVED", "+" + Money.format(recv), GREEN, nIn + (nIn == 1 ? " payment" : " payments"));
        statBox(g, x + (cw + gap), y, cw, "SENT", "-" + Money.format(sent), RED, nOut + (nOut == 1 ? " payment" : " payments"));
        statBox(g, x + (cw + gap) * 2, y, cw, "NET", signedMoney(net), net >= 0 ? GREEN : RED, "received minus sent");
        statBox(g, x + (cw + gap) * 3, y, cw, "TRANSACTIONS", String.valueOf(count), TEXT,
                count == 0 ? "none yet" : "avg " + Money.format((recv + sent) / count) + " each");

        int[] p = payPanel();
        box(g, p[0], p[1], p[2], p[3], PANEL, BORDER);
        int capW = caps(g, "HISTORY", p[0] + 12, p[1] + 10, TEXT);
        text(g, rangeWord(), p[0] + 20 + capW, p[1] + 11, DIM, 0.66f, false);
        searchField(g, paySearchX(), p[1] + 6, paySearchW(), 16, searchBox, "Search player");
        groupBar(g, filterButtons);
        for (int i = 0; i < filterButtons.size(); i++) pill(g, filterButtons.get(i), i == payFilter, mx, my);

        int cPlayer = p[0] + 12, cType = p[0] + p[2] * 56 / 100, cAmt = p[0] + p[2] * 80 / 100, cWhen = p[0] + p[2] - 14;
        int hy = p[1] + 32;
        caps(g, "PLAYER", cPlayer, hy, DIM);
        caps(g, "TYPE", cType, hy, DIM);
        caps(g, "AMOUNT", cAmt - capsW("AMOUNT"), hy, DIM);
        caps(g, "WHEN", cWhen - capsW("WHEN"), hy, DIM);
        g.fill(p[0] + 1, hy + 12, p[0] + p[2] - 1, hy + 13, BORDER);

        List<PaymentLedger.Payment> list = payList();
        int total = list.size(), limited = Math.min(total, payShown);
        int ry = hy + 18, footY = p[1] + p[3] - 22, visible = Math.max(1, (footY - 4 - ry) / PAY_ROW);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, limited - visible)));
        for (int i = 0; i < visible && scroll + i < limited; i++) {
            PaymentLedger.Payment pay = list.get(scroll + i);
            boolean in = pay.paidYou > 0;
            int ty = ry + i * PAY_ROW;
            boolean hot = mx >= p[0] + 4 && mx < p[0] + p[2] - 8 && my >= ty - 4 && my < ty + PAY_ROW - 4;
            if (hot) g.fill(p[0] + 4, ty - 4, p[0] + p[2] - 8, ty + PAY_ROW - 4, 0x14FFFFFF);
            else if (i > 0) g.fill(p[0] + 8, ty - 4, p[0] + p[2] - 10, ty - 3, alpha(BORDER, 200));
            face(g, pay.name, cPlayer, ty - 1, 12);
            String nm = pay.name;
            while (nm.length() > 3 && wb(nm, 0.8f) > cType - cPlayer - 26) nm = nm.substring(0, nm.length() - 2) + ".";
            text(g, nm, cPlayer + 18, ty + 1, TEXT, 0.8f, true);
            String type = in ? "RECEIVED" : "SENT";
            int col = in ? GREEN : RED, tw = wb(type, 0.6f) + 10;
            round(g, cType, ty - 1, tw, 12, 2, alpha(col, 140));
            round(g, cType + 1, ty, tw - 2, 10, 2, mixColor(PANEL, col, 0.18f));
            text(g, type, cType + 5, ty + 2, col, 0.6f, true);
            String amt = signedMoney(in ? pay.paidYou : -pay.youPaid);
            text(g, amt, cAmt - wb(amt, 0.8f), ty + 1, col, 0.8f, true);
            String when = ago(pay.ts);
            text(g, when, cWhen - w(when, 0.72f), ty + 2, MUTED, 0.72f, false);
        }
        if (total == 0) text(g, paySearch.isBlank() ? "No payments in this range" : "No payments match \"" + paySearch.trim() + "\"",
                cPlayer, ry + 2, DIM, 0.85f, false);
        scrollbar(g, p[0] + p[2] - 5, ry - 4, footY - ry, visible, limited);

        g.fill(p[0] + 1, footY - 2, p[0] + p[2] - 1, footY - 1, alpha(BORDER, 200));
        String showing = total == 0 ? "Showing 0 of 0"
                : "Showing " + (scroll + 1) + "-" + Math.min(limited, scroll + visible) + " of " + total;
        text(g, showing, p[0] + 12, footY + 6, DIM, 0.7f, false);
        if (loadMoreBtn != null && limited < total) {
            Button b = loadMoreBtn;
            boolean hot = over(b, mx, my);
            box(g, b.getX(), b.getY(), b.getWidth(), b.getHeight(), hot ? mixColor(PANEL, PURPLE, 0.3f) : PANEL, PURPLE);
            text(g, "LOAD MORE", b.getX() + (b.getWidth() - wb("LOAD MORE", 0.7f)) / 2, b.getY() + (b.getHeight() - 7) / 2,
                    PURPLE_LIGHT, 0.7f, true);
        }
    }

    // ================= STREAMER =================
    private static final int K_TOGGLE = 0, K_KEY = 1, K_ACCENT = 2, K_SCENE = 3;
    private static final int S_ROW_H = 36, S_GAP = 6;

    private record SRow(String title, String about, int kind, BooleanSupplier get, Consumer<Boolean> set) {}

    private final List<Button[]> streamButtons = new ArrayList<>();   // per visible row
    private EditBox hexBox;
    private boolean bindingKey = false;

    private List<SRow> streamerRows() {
        Settings s = Settings.S;
        List<SRow> r = new ArrayList<>();
        r.add(new SRow("BALANCE BOX", "Your balance box with your name (Balance HUD mod)", K_TOGGLE,
                () -> Panels.balance, v -> Panels.balance = v));
        r.add(new SRow("PROFIT TRACKER", "The profit panel with the chart, top winners / losers and recent bets (Balance HUD mod)", K_TOGGLE,
                () -> Panels.profit, v -> Panels.profit = v));
        r.add(new SRow("RAKEBACK", s.rakePct + "% back once a player has lost " + Money.format(s.rakeMin)
                + ". Who is close and what you owe (Balance HUD mod)", K_TOGGLE, () -> Panels.rakeback, v -> Panels.rakeback = v));
        r.add(new SRow("PROFIT OF THE DAY", "The small profit box on your screen", K_TOGGLE, () -> s.hudShow, v -> s.hudShow = v));
        r.add(new SRow("WIN STREAK", "Win streak counter (/win, /lose or keybinds)", K_TOGGLE, () -> s.streakShow, v -> s.streakShow = v));
        r.add(new SRow("AUCTION HUD", "The auction in the middle of your screen while it runs", K_TOGGLE,
                () -> s.auctionHud, v -> s.auctionHud = v));
        r.add(new SRow("PAYMENT POPUP", "The alert for big payments you receive", K_TOGGLE, () -> s.popupShow, v -> s.popupShow = v));
        r.add(new SRow("CHAT COLORS", "Recolor payment messages in chat (only on your screen)", K_TOGGLE,
                () -> s.chatColors, v -> s.chatColors = v));
        r.add(new SRow("CHAT FILTER", "Hide small payments from chat. They still count.", K_TOGGLE, () -> s.filterOn, v -> s.filterOn = v));
        r.add(new SRow("PAY CONFIRMATION", "Asks before a big /pay. You still press Enter yourself.", K_TOGGLE,
                () -> s.payAsk, v -> s.payAsk = v));
        r.add(new SRow("STREAMER KEYBIND", "Opens the dashboard straight on this tab. Click, then press a key (Backspace clears).",
                K_KEY, null, null));
        r.add(new SRow("ACCENT COLOR", "Recolors buttons, pills, borders and graph lines. Type a hex color.", K_ACCENT, null, null));
        r.add(new SRow("BACKGROUND", "Animated scene behind the dashboard", K_SCENE, null, null));
        return r;
    }

    private int stTop() { return contentY() + 30; }
    private int stVisible() { return Math.max(1, (contentBottom() - stTop() + S_GAP) / (S_ROW_H + S_GAP)); }

    private static void saveAll() {
        Panels.save();
        Settings.save();
    }

    private void initStreamer() {
        Panels.load();
        List<SRow> rows = streamerRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - stVisible())));
        int x = fx + 10, w = fw - 20;
        for (int i = 0; i < stVisible() && scroll + i < rows.size(); i++) {
            SRow r = rows.get(scroll + i);
            int y = stTop() + i * (S_ROW_H + S_GAP), cy = y + (S_ROW_H - 16) / 2, right = x + w - 12;
            Button[] b;
            switch (r.kind()) {
                case K_TOGGLE -> b = new Button[]{
                        btn("ON", right - 64, cy, 32, () -> { r.set().accept(true); saveAll(); }),
                        btn("OFF", right - 32, cy, 32, () -> { r.set().accept(false); saveAll(); })};
                case K_KEY -> b = new Button[]{btn("", right - 110, cy, 110, () -> bindingKey = !bindingKey)};
                case K_ACCENT -> {
                    hexBox = new EditBox(font, -4000, -4000, 80, 12, Component.literal("Accent"));   // hidden: drawn by us
                    hexBox.setMaxLength(7);
                    hexBox.setValue(Settings.hex(Settings.S.accent));
                    hexBox.setResponder(v -> {
                        String h = v.startsWith("#") ? v.substring(1) : v;
                        if (h.matches("[0-9a-fA-F]{6}")) {
                            Settings.applyAccent(Integer.parseInt(h, 16));
                            Settings.save();
                        }
                    });
                    addWidget(hexBox);
                    final EditBox eb = hexBox;
                    b = new Button[]{btn("", right - 16 - 8 - 80, cy, 80, () -> pendingFocus = eb)};
                }
                default -> b = new Button[]{
                        btn("", right - 160, cy, 16, () -> { Settings.S.scene = Math.floorMod(Settings.S.scene - 1, Scenes.NAMES.length); Settings.save(); }),
                        btn("", right - 16, cy, 16, () -> { Settings.S.scene = Math.floorMod(Settings.S.scene + 1, Scenes.NAMES.length); Settings.save(); })};
            }
            streamButtons.add(b);
        }
    }

    private void drawStreamer(GuiGraphicsExtractor g, int mx, int my) {
        pageTitle(g, "Streamer", "Turn every HUD and module on or off, and set your keybind, accent color and background.");
        List<SRow> rows = streamerRows();
        int x = fx + 10, w = fw - 20;
        for (int i = 0; i < streamButtons.size() && scroll + i < rows.size(); i++) {
            SRow r = rows.get(scroll + i);
            Button[] b = streamButtons.get(i);
            int y = stTop() + i * (S_ROW_H + S_GAP), cy = y + (S_ROW_H - 16) / 2, right = x + w - 12;
            box(g, x, y, w, S_ROW_H, PANEL, BORDER);
            caps(g, r.title(), x + 12, y + 8, TEXT);
            int controlsW = switch (r.kind()) { case K_TOGGLE -> 64; case K_KEY -> 110; case K_ACCENT -> 104; default -> 160; };
            String about = r.about();
            while (about.length() > 4 && w(about, 0.7f) > w - 36 - controlsW) about = about.substring(0, about.length() - 2) + ".";
            text(g, about, x + 12, y + 21, DIM, 0.7f, false);

            switch (r.kind()) {
                case K_TOGGLE -> {
                    boolean on = r.get().getAsBoolean();
                    round(g, right - 65, cy - 1, 66, 18, 2, BORDER);
                    segment(g, b[0], "ON", on, mx, my);
                    segment(g, b[1], "OFF", !on, mx, my);
                }
                case K_KEY -> {
                    KeyMapping km = DashboardClient.streamerKey;
                    String k = bindingKey ? "Press a key..." : km == null ? "-" : km.getTranslatedKeyMessage().getString();
                    if (!bindingKey && km != null && km.isUnbound()) k = "Not set";
                    boolean hot = over(b[0], mx, my);
                    box(g, b[0].getX(), cy, 110, 16, 0xFF0B0A12, bindingKey ? PURPLE : hot ? PURPLE_LIGHT : BORDER);
                    text(g, k, b[0].getX() + (110 - w(k, 0.74f)) / 2, cy + 4, bindingKey ? PURPLE_LIGHT : GOLD, 0.74f, false);
                }
                case K_ACCENT -> {
                    boolean focused = hexBox != null && hexBox.isFocused();
                    int bx = b[0].getX();
                    box(g, bx, cy, 80, 16, 0xFF0B0A12, focused ? PURPLE : BORDER);
                    String v = hexBox == null ? Settings.hex(Settings.S.accent) : hexBox.getValue();
                    text(g, v, bx + 7, cy + 4, TEXT, 0.74f, false);
                    if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
                        int tx = bx + 8 + w(v, 0.74f);
                        g.fill(tx, cy + 3, tx + 1, cy + 12, TEXT);
                    }
                    round(g, right - 16, cy, 16, 16, 2, BORDER);
                    round(g, right - 15, cy + 1, 14, 14, 2, PURPLE);   // live swatch
                }
                default -> {
                    arrowBox(g, b[0], true, mx, my);
                    arrowBox(g, b[1], false, mx, my);
                    String n = Scenes.NAMES[Math.floorMod(Settings.S.scene, Scenes.NAMES.length)];
                    int mid = (b[0].getX() + 16 + b[1].getX()) / 2;
                    text(g, n, mid - wb(n, 0.74f) / 2, cy + 4, TEXT, 0.74f, true);
                }
            }
        }
        scrollbar(g, x + w + 3, stTop(), contentBottom() - stTop(), stVisible(), rows.size());
    }

    /** One half of an ON / OFF switch: solid accent when picked. */
    private void segment(GuiGraphicsExtractor g, Button b, String label, boolean on, int mx, int my) {
        int x = b.getX(), y = b.getY(), bw = b.getWidth(), bh = b.getHeight();
        boolean hot = over(b, mx, my);
        g.fill(x, y, x + bw, y + bh, on ? PURPLE : hot ? mixColor(PANEL, PURPLE, 0.3f) : 0xFF0B0A12);
        text(g, label, x + (bw - wb(label, 0.7f)) / 2, y + (bh - 7) / 2, on ? onAccent() : MUTED, 0.7f, true);
    }

    private void arrowBox(GuiGraphicsExtractor g, Button b, boolean left, int mx, int my) {
        int x = b.getX(), y = b.getY();
        boolean hot = over(b, mx, my);
        box(g, x, y, 16, 16, hot ? mixColor(PANEL, PURPLE, 0.3f) : 0xFF0B0A12, hot ? PURPLE : BORDER);
        int c = hot ? TEXT : MUTED, cx = x + 8, cy = y + 8;
        for (int i = 0; i < 4; i++) {
            int ax = left ? cx - 2 + i : cx + 1 - i;
            g.fill(ax, cy - i, ax + 1, cy + i + 1, c);
        }
    }

    // ================= AUCTIONS =================
    private EditBox itemSearch;
    private static String itemQuery = "";
    private final java.util.Map<Button, Runnable> auctionButtons = new java.util.LinkedHashMap<>();
    private Button startBtn, clearBtn, switchBtn, gridBtn;
    private final List<Button> durationBtns = new ArrayList<>();
    private final List<Button> colorBtns = new ArrayList<>();
    private static final long[] DURATIONS = {15_000, 30_000, 45_000, 60_000, 120_000, 300_000};
    private static final String[] DURATION_LABELS = {"15s", "30s", "45s", "1m", "2m", "5m"};
    private static final int[] TIMER_COLORS = {GOLD, PURPLE, GREEN, RED, 0xFF5B8CFF, 0xFFF472B6, 0xFF3ADFDF, 0xFFF2F0FA};

    /** Every item in the game with its name, built once. */
    private static List<net.minecraft.world.item.ItemStack> allItems;
    private static List<String> allNames;

    private static void loadItems() {
        if (allItems != null) return;
        allItems = new ArrayList<>();
        allNames = new ArrayList<>();
        for (net.minecraft.world.item.Item it : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            net.minecraft.world.item.ItemStack st = new net.minecraft.world.item.ItemStack(it);
            if (st.isEmpty()) continue;
            allItems.add(st);
            allNames.add(st.getHoverName().getString());
        }
    }

    private static String cachedQuery = null;
    private static List<Integer> cachedItems = null;

    private List<Integer> filteredItems() {
        loadItems();
        if (itemQuery.equals(cachedQuery) && cachedItems != null) return cachedItems;
        String q = itemQuery.trim().toLowerCase(Locale.US);
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < allItems.size(); i++)
            if (q.isEmpty() || allNames.get(i).toLowerCase(Locale.US).contains(q)) out.add(i);
        cachedQuery = itemQuery;
        cachedItems = out;
        return out;
    }

    // ---- layout shared by click areas and drawing ----
    private EditBox nameBox, qtyBox, secBox;
    private Button nameClick, qtyClick, secClick, svBtn, hueBtn;
    private static float hue = 300f, sat = 0.55f, val = 0.72f;   // color picker
    private static final int CELL = 26;
    private static final int BID_H = 92;   // TOP BIDDERS panel under the item picker

    private int aTop() { return contentY() + 30; }
    private int aLeftW() { return (fw - 28) * 49 / 100; }
    private int aRingH() {   // whatever height is left after the controls, so the switch never runs past the footer
        int controls = 8 + 28 + 36 + 50 + 28 + 4;
        return Math.max(72, Math.min(170, contentBottom() - aTop() - controls));
    }
    private int aCtrlY() { return aTop() + aRingH() + 8; }
    private int aFieldsY() { return aCtrlY() + 28; }
    private int aColorY() { return aFieldsY() + 36; }
    private int aSwitchY() { return aColorY() + 50; }
    private int aLastY() { return aSwitchY() + 34; }
    private int[] aGrid() {   // x, y, w, h of the item grid
        int rx = fx + 10 + aLeftW() + 8, rw = fx + fw - 10 - rx;
        return new int[]{rx + 10, aTop() + 48, rw - 24, contentBottom() - BID_H - 8 - 18 - (aTop() + 48)};
    }
    private int[] aFields() {   // name x/w, qty x/w, sec x/w
        int lx = fx + 10, lw = aLeftW();
        int nw = lw * 50 / 100, qw = (lw - nw - 12) / 2;
        return new int[]{lx, nw, lx + nw + 6, qw, lx + nw + 12 + qw, lw - nw - 12 - qw};
    }
    private int[] aPicker() {   // square x/y/w/h, hue bar x, hex box x/w
        int lx = fx + 10, lw = aLeftW();
        int sx = lx + 76, hexW = 64, sw = lw - 76 - 10 - 8 - hexW - 6;
        return new int[]{sx, aColorY() + 13, sw, 30, sx + sw + 4, sx + sw + 18, hexW};
    }

    private static int hsv(float h, float s, float v) {
        float c = v * s, hp = (h % 360f) / 60f, x = c * (1 - Math.abs(hp % 2 - 1)), m = v - c;
        float r, g, b;
        if (hp < 1) { r = c; g = x; b = 0; }
        else if (hp < 2) { r = x; g = c; b = 0; }
        else if (hp < 3) { r = 0; g = c; b = x; }
        else if (hp < 4) { r = 0; g = x; b = c; }
        else if (hp < 5) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return 0xFF000000 | (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
    }

    private EditBox hiddenBox(String value, int max, java.util.function.Predicate<String> filter, java.util.function.Consumer<String> onChange) {
        final EditBox b = new EditBox(font, -4000, -4000, 200, 12, Component.literal(""));   // hidden: we draw it ourselves
        b.setMaxLength(max);
        b.setValue(value);
        b.setResponder(v -> {
            if (filter != null && !filter.test(v)) {   // numbers only: strip anything else
                b.setValue(v.replaceAll("[^0-9]", ""));
                return;
            }
            onChange.accept(v);
        });
        addWidget(b);
        return b;
    }

    private void initAuctions() {
        colorBtns.clear();
        int lx = fx + 10, lw = aLeftW();
        startBtn = btn("START AUCTION", lx, aCtrlY(), wb("START AUCTION", 0.76f) + 34, () -> {
            if (Auction.running) Auction.endAt = System.currentTimeMillis();   // end now
            else Auction.start();
        });
        startBtn.setHeight(20);
        clearBtn = btn("Clear", startBtn.getX() + startBtn.getWidth() + 8, aCtrlY(), w("Clear", 0.8f) + 22, () -> {
            Auction.clearItem();
            rebuildWidgets();   // empties the NAME / QUANTITY boxes too
        });
        clearBtn.setHeight(20);

        int[] fl = aFields();
        nameBox = hiddenBox(Auction.shortName() == null ? "" : Auction.shortName(), 40, null, v -> Auction.customName = v);
        qtyBox = hiddenBox(String.valueOf(Auction.quantity), 7, v -> v.matches("\\d*"), v -> {
            try { Auction.quantity = Math.max(1, Integer.parseInt(v)); } catch (Exception e) { Auction.quantity = 1; }
        });
        secBox = hiddenBox(String.valueOf(Auction.durationMs / 1000), 4, v -> v.matches("\\d*"), v -> {
            try { if (!Auction.running) Auction.durationMs = Math.max(5, Math.min(3600, Integer.parseInt(v))) * 1000L; } catch (Exception ignored) { }
        });
        int fy0 = aFieldsY() + 12;
        nameClick = btn("", fl[0], fy0, fl[1], () -> pendingFocus = nameBox); nameClick.setHeight(16);
        qtyClick = btn("", fl[2], fy0, fl[3], () -> pendingFocus = qtyBox); qtyClick.setHeight(16);
        secClick = btn("", fl[4], fy0, fl[5], () -> pendingFocus = secBox); secClick.setHeight(16);

        for (int i = 0; i < TIMER_COLORS.length; i++) {   // 2 rows of 4 swatches
            final int col = TIMER_COLORS[i];
            Button cb = btn("", lx + (i % 4) * 16, aColorY() + 13 + (i / 4) * 16, 12, () -> Auction.color = col);
            cb.setHeight(12);
            colorBtns.add(cb);
        }
        int[] pk = aPicker();
        svBtn = btn("", pk[0], pk[1], pk[2], this::pickSV); svBtn.setHeight(pk[3]);
        hueBtn = btn("", pk[4], pk[1], 8, this::pickHue); hueBtn.setHeight(pk[3]);

        switchBtn = btn("", lx, aSwitchY(), lw, () -> Auction.switchOn = !Auction.switchOn);
        switchBtn.setHeight(28);

        int[] gr = aGrid();
        int rx = fx + 10 + aLeftW() + 8, rw = fx + fw - 10 - rx;
        itemSearch = hiddenBox(itemQuery, 32, null, v -> { itemQuery = v; scroll = 0; });
        Button click = btn("", rx + 10, aTop() + 24, rw - 20, () -> pendingFocus = itemSearch);
        click.setHeight(18);
        gridBtn = addWidget(Button.builder(Component.literal(""), b -> pickItemAtMouse())
                .bounds(gr[0], gr[1], gr[2], gr[3]).build());
    }

    private void pickSV() {
        int[] pk = aPicker();
        sat = Math.max(0f, Math.min(1f, (lastMouseX - pk[0]) / (float) Math.max(1, pk[2] - 1)));
        val = Math.max(0f, Math.min(1f, 1f - (lastMouseY - pk[1]) / (float) Math.max(1, pk[3] - 1)));
        Auction.color = hsv(hue, sat, val);
    }

    private void pickHue() {
        int[] pk = aPicker();
        hue = Math.max(0f, Math.min(359f, (lastMouseY - pk[1]) / (float) Math.max(1, pk[3] - 1) * 360f));
        Auction.color = hsv(hue, sat, val);
    }

    private void pickItemAtMouse() {
        int[] gr = aGrid();
        int cols = Math.max(1, gr[2] / CELL);
        int col = (lastMouseX - gr[0]) / CELL, row = (lastMouseY - gr[1]) / CELL;
        if (col < 0 || col >= cols || row < 0) return;
        List<Integer> items = filteredItems();
        int idx = (scroll + row) * cols + col;
        if (idx >= 0 && idx < items.size() && !Auction.running) {
            Auction.item = allItems.get(items.get(idx)).copy();
            Auction.itemName = allNames.get(items.get(idx));
            Auction.customName = Auction.itemName;   // NAME box fills in; you can retype it
            Auction.ended = false;
            rebuildWidgets();
        }
    }

    /** Plain input box drawn like the reference: label above, dark field, your text and a cursor. */
    private void inputField(GuiGraphicsExtractor g, String label, int x, int y, int w, EditBox box, String hint) {
        caps(g, label, x, y, DIM);
        int by = y + 12, bh = 16;
        boolean focused = box != null && box.isFocused();
        box(g, x, by, w, bh, 0xFF0B0A12, focused ? PURPLE : BORDER);
        String v = box == null ? "" : box.getValue();
        int tx = x + 7, ty = by + (bh - 7) / 2;
        if (v.isEmpty()) text(g, hint, tx, ty, DIM, 0.74f, false);
        else {
            String shown = v;
            while (shown.length() > 1 && w(shown, 0.74f) > w - 14) shown = shown.substring(1);
            text(g, shown, tx, ty, TEXT, 0.74f, false);
            tx += w(shown, 0.74f) + 1;
        }
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) g.fill(tx, ty - 1, tx + 1, ty + 8, TEXT);
    }

    /** Smooth ring: lit part (from the top, clockwise) shows the time left. */
    static void ring(GuiGraphicsExtractor g, int cx, int cy, int r, int thick, float frac, int litColor, int restColor) {
        float ro = r, ri = r - thick;
        int lim = r + 1;
        for (int dy = -lim; dy <= lim; dy++) {
            int runStart = 0, runColor = 0;
            for (int dx = -lim; dx <= lim + 1; dx++) {
                int col = 0;
                float cover = 0f;
                if (dx <= lim) {
                    float d = (float) Math.sqrt((dx + 0.5f) * (dx + 0.5f) + (dy + 0.5f) * (dy + 0.5f));
                    cover = Math.max(0f, Math.min(1f, ro - d + 0.5f)) * Math.max(0f, Math.min(1f, d - ri + 0.5f));
                    if (cover > 0f) {
                        double ang = Math.toDegrees(Math.atan2(dx + 0.5, -(dy + 0.5)));   // 0 at the top, clockwise
                        if (ang < 0) ang += 360;
                        col = (litColor != 0 && ang < frac * 360) ? litColor : restColor;
                    }
                }
                boolean solid = cover >= 0.99f;
                int runKey = solid ? col : 0;
                if (runKey != runColor) {   // close the previous solid run
                    if (runColor != 0) g.fill(cx + runStart, cy + dy, cx + dx, cy + dy + 1, runColor);
                    runStart = dx;
                    runColor = runKey;
                }
                if (!solid && cover > 0f) {   // soft edge pixel
                    int a = (int) (((col >>> 24) & 0xFF) * cover);
                    g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, (a << 24) | (col & 0xFFFFFF));
                }
            }
        }
    }

    private void itemIcon(GuiGraphicsExtractor g, net.minecraft.world.item.ItemStack st, int x, int y, float scale) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.item(st, 0, 0);
        g.pose().popMatrix();
    }

    private void drawAuctions(GuiGraphicsExtractor g, int mx, int my) {
        pageTitle(g, "Auctions", "Pick an item, set the timer and start. Whoever pays you the most before it hits 0 wins.");
        int lx = fx + 10, lw = aLeftW(), top = aTop();
        int acc = Auction.color;

        // ---- ring panel ----
        int rh = aRingH();
        box(g, lx, top, lw, rh, PANEL, BORDER);
        String t = "AUCTIONS";
        caps(g, t, lx + (lw - capsW(t)) / 2, top + 9, acc);
        int ccx = lx + lw / 2, rad = Math.max(12, Math.min(64, (rh - 48) / 2)), ccy = top + 25 + rad;   // title above, status below
        float frac = Auction.running ? Auction.left() / (float) Auction.durationMs : 1f;
        ring(g, ccx, ccy, rad, 6, frac, acc, Auction.running ? alpha(acc, 60) : acc);
        int inner = (rad - 6) * 2 - 10;
        float iconScale = Math.max(1.2f, Math.min(2f, rad / 30f));
        int iconSize = (int) (16 * iconScale);
        int iconY = ccy - rad + 10;
        if (!Auction.item.isEmpty()) {
            itemIcon(g, Auction.item, ccx - iconSize / 2, iconY, iconScale);
            if (Auction.quantity > 1) {   // quantity badge, beside the item
                String q = "x" + Auction.quantity;
                int qw = wb(q, 0.55f) + 8, qx = ccx + iconSize / 2 - 2, qy = iconY + iconSize - 10;
                round(g, qx, qy, qw, 11, 3, acc);
                text(g, q, qx + 4, qy + 2, onAccentFor(acc), 0.55f, true);
            }
        }
        String big = Auction.running ? String.format("%d:%02d", Auction.left() / 60000, (Auction.left() / 1000) % 60)
                : Auction.ended ? (Auction.winner != null ? "SOLD" : "NO BIDS")
                : Auction.item.isEmpty() ? "PICK" : String.format("%d:%02d", Auction.durationMs / 60000, (Auction.durationMs / 1000) % 60);
        float bs = 1.05f;
        while (bs > 0.6f && wb(big, bs) > inner) bs -= 0.05f;
        int bigY = Auction.item.isEmpty() ? ccy - 8 : iconY + iconSize + 4;
        text(g, big, ccx - wb(big, bs) / 2, bigY, Auction.running && Auction.left() <= 10_000 ? RED : TEXT, bs, true);
        String nm = Auction.item.isEmpty() ? "an item" : Auction.displayName();
        while (nm.length() > 3 && w(nm, 0.52f) > inner - 8) nm = nm.substring(0, nm.length() - 2) + ".";
        text(g, nm, ccx - w(nm, 0.52f) / 2, bigY + 15, MUTED, 0.52f, false);
        String status = Auction.running ? "Bidding is open" : Auction.ended && Auction.winner != null
                ? Auction.winner + " won it  -  " + Money.format(Auction.winAmount)
                : Auction.item.isEmpty() ? "Pick an item on the right" : "Ready when you are";
        text(g, status, ccx - wb(status, 0.7f) / 2, top + rh - 13, Auction.ended && Auction.winner != null ? GREEN : MUTED, 0.7f, true);

        // ---- start + note ----
        {
            Button b = startBtn;
            int x = b.getX(), y = b.getY(), bw = b.getWidth(), bh = b.getHeight();
            boolean disabled = !Auction.running && Auction.item.isEmpty();
            int col = Auction.running ? RED : disabled ? 0xFF3A3550 : PURPLE;
            round(g, x, y, bw, bh, 3, over(b, mx, my) && !disabled ? mixColor(col, 0xFFFFFFFF, 0.12f) : col);
            int ix = x + 10, iy = y + bh / 2;
            if (Auction.running) g.fill(ix, iy - 3, ix + 6, iy + 3, TEXT);
            else for (int i = 0; i < 4; i++) g.fill(ix + i, iy - 4 + i, ix + i + 1, iy + 4 - i, TEXT);
            String s0 = Auction.running ? "END NOW" : "START AUCTION";
            text(g, s0, x + 22, y + (bh - 7) / 2, TEXT, 0.76f, true);
        }
        {   // Clear
            Button b = clearBtn;
            boolean hot = over(b, mx, my);
            box(g, b.getX(), b.getY(), b.getWidth(), b.getHeight(), hot ? mixColor(PANEL, PURPLE, 0.3f) : PANEL, hot ? PURPLE : BORDER);
            text(g, "Clear", b.getX() + (b.getWidth() - w("Clear", 0.8f)) / 2, b.getY() + (b.getHeight() - 7) / 2, hot ? TEXT : MUTED, 0.8f, false);
        }
        String note = "Biggest single payment wins (bids don't stack)";
        if (lx + lw - w(note, 0.62f) > clearBtn.getX() + clearBtn.getWidth() + 8)   // only when it doesn't touch Clear
            text(g, note, lx + lw - w(note, 0.62f), aCtrlY() + 7, DIM, 0.62f, false);

        // ---- name / quantity / seconds ----
        round(g, lx - 6, aFieldsY() - 6, lw + 12, aSwitchY() - aFieldsY(), 4, alpha(FRAME, 0xC8));   // dark backing so labels read over the scene
        int[] fl = aFields();
        inputField(g, "NAME", fl[0], aFieldsY(), fl[1], nameBox, "Item name");
        inputField(g, "QUANTITY", fl[2], aFieldsY(), fl[3], qtyBox, "1");
        inputField(g, "SECONDS", fl[4], aFieldsY(), fl[5], secBox, "30");

        // ---- timer color: swatches, picker, hex ----
        caps(g, "TIMER COLOR", lx, aColorY(), DIM);
        for (int i = 0; i < colorBtns.size(); i++) {
            Button b = colorBtns.get(i);
            int x = b.getX(), y = b.getY();
            if (TIMER_COLORS[i] == acc) round(g, x - 1, y - 1, 14, 14, 7, TEXT);
            round(g, x, y, 12, 12, 6, TIMER_COLORS[i]);
        }
        int[] pk = aPicker();
        int step = 4;
        for (int px = 0; px < pk[2]; px += step) {
            for (int py = 0; py < pk[3]; py += 3) {
                float s1 = px / (float) Math.max(1, pk[2] - 1), v1 = 1f - py / (float) Math.max(1, pk[3] - 1);
                g.fill(pk[0] + px, pk[1] + py, pk[0] + Math.min(pk[2], px + step), pk[1] + Math.min(pk[3], py + 3), hsv(hue, s1, v1));
            }
        }
        int kx = pk[0] + Math.round(sat * (pk[2] - 1)), ky = pk[1] + Math.round((1f - val) * (pk[3] - 1));
        ring(g, kx, ky, 3, 1, 1f, 0xFFFFFFFF, 0xFFFFFFFF);
        for (int py = 0; py < pk[3]; py += 2) g.fill(pk[4], pk[1] + py, pk[4] + 8, pk[1] + Math.min(pk[3], py + 2), hsv(py / (float) pk[3] * 360f, 1f, 1f));
        int hy = pk[1] + Math.round(hue / 360f * (pk[3] - 1));
        g.fill(pk[4] - 1, hy - 1, pk[4] + 9, hy + 1, 0xFFFFFFFF);
        round(g, pk[5], pk[1], pk[6], pk[3], 4, acc);
        String hex = Settings.hex(acc & 0xFFFFFF);
        text(g, hex, pk[5] + (pk[6] - w(hex, 0.62f)) / 2, pk[1] + (pk[3] - 7) / 2, onAccentFor(acc), 0.62f, true);

        // ---- auction switch ----
        {
            Button b = switchBtn;
            int x = b.getX(), y = b.getY(), bw = b.getWidth(), bh = b.getHeight();
            box(g, x, y, bw, bh, over(b, mx, my) ? 0xFF181526 : PANEL, BORDER);
            text(g, "Auction switch", x + 10, y + 5, TEXT, 0.76f, true);
            text(g, "On: while an auction runs, money paid to you counts as bids, not bets.", x + 10, y + 16, DIM, 0.6f, false);
            int sw = 24, sh = 12, sx = x + bw - sw - 10, sy = y + (bh - sh) / 2;
            round(g, sx, sy, sw, sh, 6, Auction.switchOn ? PURPLE : 0xFF2A2640);
            round(g, Auction.switchOn ? sx + sw - 10 : sx + 2, sy + 2, 8, 8, 4, Auction.switchOn ? FRAME : 0xFF6B6684);
        }

        // ---- top bidders (left) + last auctions (right) ----
        int ly = aLastY(), lh = contentBottom() - ly;
        if (lh > 24) {
            int half = (lw - 6) / 2, bx2 = lx + half + 6;
            box(g, lx, ly, half, lh, PANEL, BORDER);
            caps(g, "TOP BIDDERS", lx + 10, ly + 8, TEXT);
            List<java.util.Map.Entry<String, Long>> bids = Auction.sortedBids();
            int rowH = 15, ry = ly + 22, rows = Math.max(0, (ly + lh - 4 - ry) / rowH);
            for (int i = 0; i < Math.min(rows, bids.size()); i++) {
                var e = bids.get(i);
                int ty = ry + i * rowH;
                if (i == 0) round(g, lx + 4, ty - 2, half - 8, rowH - 1, 3, alpha(acc, 40));   // the leader
                face(g, e.getKey(), lx + 8, ty, 10);
                String n = e.getKey();
                while (n.length() > 3 && wb(n, 0.62f) > half - 80) n = n.substring(0, n.length() - 2) + ".";
                text(g, n, lx + 22, ty + 1, TEXT, 0.62f, true);
                String amt = Money.format(e.getValue());
                text(g, amt, lx + half - 8 - wb(amt, 0.62f), ty + 1, GREEN, 0.62f, true);
            }
            if (bids.isEmpty()) text(g, Auction.running ? "Waiting for bids..." : "No bids yet", lx + 10, ry, DIM, 0.62f, false);

            box(g, bx2, ly, lw - half - 6, lh, PANEL, BORDER);
            caps(g, "LAST AUCTIONS", bx2 + 10, ly + 8, TEXT);
            int lw2 = lw - half - 6;
            for (int i = 0; i < Math.min(rows, Auction.history.size()); i++) {
                Auction.Past a = Auction.history.get(i);
                int ty = ry + i * rowH;
                String item = (a.qty() > 1 ? a.qty() + "x " : "") + a.name();
                String who = a.winner() == null ? "no bids" : a.winner();
                String line = item + " > " + who;
                while (line.length() > 4 && w(line, 0.58f) > lw2 - 70) line = line.substring(0, line.length() - 2) + ".";
                text(g, line, bx2 + 10, ty + 1, MUTED, 0.58f, false);
                String amt = a.winner() == null ? "-" : Money.format(a.amount());
                text(g, amt, bx2 + lw2 - 8 - wb(amt, 0.6f), ty + 1, a.winner() == null ? DIM : GREEN, 0.6f, true);
            }
            if (Auction.history.isEmpty()) text(g, "Finished auctions show up here", bx2 + 10, ry, DIM, 0.6f, false);
        }

        // ---- pick an item ----
        int rx = fx + 10 + lw + 8, rw = fx + fw - 10 - rx, rpH = contentBottom() - BID_H - 8 - top;
        box(g, rx, top, rw, rpH, PANEL, BORDER);
        caps(g, "PICK AN ITEM", rx + 10, top + 9, TEXT);
        if (!Auction.item.isEmpty()) text(g, Auction.itemName, rx + rw - 10 - wb(Auction.itemName, 0.7f), top + 9, acc, 0.7f, true);
        searchField(g, rx + 10, top + 24, rw - 20, 18, itemSearch, "Search every item and block...");

        int[] gr = aGrid();
        List<Integer> items = filteredItems();
        int cols = Math.max(1, gr[2] / CELL), rows = Math.max(1, gr[3] / CELL);
        int totalRows = (items.size() + cols - 1) / cols;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, totalRows - rows)));
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = (scroll + r) * cols + c;
                if (idx >= items.size()) break;
                int x = gr[0] + c * CELL, y = gr[1] + r * CELL;
                var st = allItems.get(items.get(idx));
                boolean hot = mx >= x && mx < x + CELL && my >= y && my < y + CELL;
                boolean sel = !Auction.item.isEmpty() && Auction.item.getItem() == st.getItem();
                if (sel) box(g, x, y, CELL - 2, CELL - 2, alpha(acc, 60), acc);
                else if (hot) round(g, x, y, CELL - 2, CELL - 2, 3, 0xFF1E1A2E);
                itemIcon(g, st, x + 2, y + 2, 1.25f);
            }
        }
        scrollbar(g, gr[0] + gr[2] + 4, gr[1], gr[3], rows, totalRows);
        text(g, items.size() + " items", rx + 10, top + rpH - 12, DIM, 0.64f, false);

        // ---- TOP BIDDERS: under the item picker ----
        int tby = top + rpH + 8;
        box(g, rx, tby, rw, BID_H, PANEL, BORDER);
        caps(g, "TOP BIDDERS", rx + 10, tby + 8, TEXT);
        List<java.util.Map.Entry<String, Long>> topBids = Auction.sortedBids();
        int bRow = 15, bry = tby + 24, bRows = Math.max(0, (tby + BID_H - 4 - bry) / bRow);
        for (int i = 0; i < Math.min(bRows, topBids.size()); i++) {
            var e = topBids.get(i);
            int ty = bry + i * bRow;
            if (i == 0) round(g, rx + 4, ty - 2, rw - 8, bRow - 1, 3, alpha(acc, 40));   // the leader
            face(g, e.getKey(), rx + 8, ty, 10);
            text(g, e.getKey(), rx + 22, ty + 1, TEXT, 0.64f, true);
            String amt = Money.format(e.getValue());
            text(g, amt, rx + rw - 10 - wb(amt, 0.64f), ty + 1, GREEN, 0.64f, true);
        }
        if (topBids.isEmpty()) text(g, Auction.running ? "Waiting for bids..." : "No bids yet", rx + 10, bry, DIM, 0.64f, false);
    }

    private static int onAccentFor(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 > 170 ? 0xFF111111 : 0xFFFFFFFF;
    }

    private void stat(GuiGraphicsExtractor g, String label, String value, int x, int y, int color) {
        caps(g, label, x, y, MUTED);
        text(g, value, x, y + 13, color, 1.15f, true);
    }
}
