package dev.profit.dashboard;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Animated scenery drawn behind the dashboard. Only flat fills, so it works with any font or resource pack.
 * The accent color is separate (Streamer tab > Accent color); these only change the background.
 */
public final class Scenes {
    private Scenes() {}

    public static final String[] NAMES = {
            "Winter Pine & Lights", "Sunset Palms", "Dusk", "Sakura", "Maple", "Snowpine", "Savanna", "Moonbloom"};

    /** Stable pseudo-random number in [0, 1) for item i. */
    private static double rnd(int i, int salt) {
        double v = Math.sin(i * 12.9898 + salt * 78.233) * 43758.5453;
        return v - Math.floor(v);
    }

    private static int mix(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int alpha(int c, int a) { return (Math.max(0, Math.min(255, a)) << 24) | (c & 0xFFFFFF); }

    public static void draw(GuiGraphicsExtractor g, int x, int y, int w, int h, int idx, long now) {
        if (w <= 0 || h <= 0) return;
        double t = (now % 3_600_000L) / 1000.0;   // seconds, wraps every hour
        switch (Math.floorMod(idx, NAMES.length)) {
            case 0 -> winterPine(g, x, y, w, h, t);
            case 1 -> sunsetPalms(g, x, y, w, h, t);
            case 2 -> dusk(g, x, y, w, h, t);
            case 3 -> sakura(g, x, y, w, h, t);
            case 4 -> maple(g, x, y, w, h, t);
            case 5 -> snowpine(g, x, y, w, h, t);
            case 6 -> savanna(g, x, y, w, h, t);
            default -> moonbloom(g, x, y, w, h, t);
        }
    }

    // ================= scenes =================

    private static void winterPine(GuiGraphicsExtractor g, int x, int y, int w, int h, double t) {
        sky(g, x, y, w, h, 0x1A1240, 0x3B2C80);
        stars(g, x, y, w, h * 2 / 3, 40, t, 0xFFFFFF);
        sleigh(g, x, y, w, h, t);
        int ground = y + h * 82 / 100;
        hills(g, x, ground - h / 14, w, y + h, 0xFF8E89C4, h / 30, 0.012, 1);
        pineRow(g, x, w, ground, h * 30 / 100, 9, 0xFF2A2160, 0xFF2A2160, 2, t);
        pineRow(g, x, w, ground + h / 30, h * 38 / 100, 7, 0xFF4A4188, 0xFF4A4188, 3, t);
        g.fill(x, ground + h / 12, x + w, y + h, 0xFFB8B4DC);   // snow ground
        pineRow(g, x, w, y + h, h * 48 / 100, 5, 0xFFCFCBE6, 0xFFF4F2FA, 4, t);   // snowy front trees
        treeLights(g, x, w, y + h, h * 48 / 100, 5, 4, t);
        snow(g, x, y, w, h, 90, t, 1);
        stringLights(g, x, y, w, t);
    }

    private static void sunsetPalms(GuiGraphicsExtractor g, int x, int y, int w, int h, double t) {
        int horizon = y + h * 62 / 100;
        sky(g, x, y, w, horizon - y, 0x2A1450, 0xE89070);
        int sunR = Math.max(10, h / 9), sunX = x + w / 2;
        disc(g, sunX, horizon, sunR + 6, 0x40FFC890);
        disc(g, sunX, horizon, sunR, 0xFFFFD08A);
        sky(g, x, horizon, w, y + h - horizon, 0x4A2A5A, 0x1A0F2A);   // water
        for (int i = 0; i < 14; i++) {   // shimmering reflection lines under the sun
            int ly = horizon + 3 + i * Math.max(2, (y + h - horizon) / 18);
            if (ly >= y + h) break;
            double s = 0.6 + 0.4 * Math.sin(t * 2.2 + i * 1.3);
            int lw = (int) ((sunR * 2.2 - i * sunR * 0.09) * s);
            if (lw > 2) g.fill(sunX - lw / 2, ly, sunX + lw / 2, ly + 1, alpha(0xFFD9A0, 200 - i * 12));
        }
        g.fill(x, horizon, x + w, horizon + 1, 0x60FFD8B0);
        birds(g, x, y, w, h * 45 / 100, 5, t);
        // islands with palms on both sides
        islandWithPalms(g, x + w / 7, horizon + h / 18, w / 5, h, 0xFF1A0F2A, 3, t);
        islandWithPalms(g, x + w * 6 / 7, horizon + h / 22, w / 6, h, 0xFF1A0F2A, 2, t + 1);
    }

    private static void dusk(GuiGraphicsExtractor g, int x, int y, int w, int h, double t) {
        sky(g, x, y, w, h, 0x0E1230, 0x7A3E5E);
        stars(g, x, y, w, h / 2, 60, t, 0xFFFFFF);
        disc(g, x + w * 3 / 4, y + h * 55 / 100, Math.max(8, h / 14), 0xFFF2B28A);
        birds(g, x, y, w, h * 50 / 100, 4, t);
        hills(g, x, y + h * 62 / 100, w, y + h, 0xFF3A2348, h / 12, 0.008, 1);
        hills(g, x, y + h * 72 / 100, w, y + h, 0xFF26173A, h / 14, 0.011, 2);
        hills(g, x, y + h * 84 / 100, w, y + h, 0xFF140C22, h / 18, 0.015, 3);
    }

    private static void sakura(GuiGraphicsExtractor g, int x, int y, int w, int h, double t) {
        sky(g, x, y, w, h, 0x24142E, 0x8A4672);
        disc(g, x + w / 5, y + h / 4, Math.max(8, h / 16), 0xFFF8D8E4);
        hills(g, x, y + h * 74 / 100, w, y + h, 0xFF3A1A3A, h / 16, 0.01, 1);
        g.fill(x, y + h * 88 / 100, x + w, y + h, 0xFF1E0E20);
        for (int i = 0; i < 5; i++) {
            int tx = x + (int) (w * (0.08 + i * 0.22 + rnd(i, 3) * 0.06));
            blossomTree(g, tx, y + h * 90 / 100, (int) (h * (0.32 + rnd(i, 4) * 0.12)), 0xFF1E0E20, 0xFFE89AC0, i);
        }
        petals(g, x, y, w, h, 60, t, 0xFFF4B6D2, 0.6);
    }

    private static void maple(GuiGraphicsExtractor g, int x, int y, int w, int h, double t) {
        sky(g, x, y, w, h, 0x24100E, 0x9A4A1E);
        disc(g, x + w * 7 / 10, y + h * 60 / 100, Math.max(10, h / 10), 0xFFF2A65A);
        hills(g, x, y + h * 70 / 100, w, y + h, 0xFF4A1E14, h / 14, 0.009, 1);
        g.fill(x, y + h * 88 / 100, x + w, y + h, 0xFF200C08);
        int[] cols = {0xFFB8321E, 0xFFD8642A, 0xFF8E2418};
        for (int i = 0; i < 6; i++) {
            int tx = x + (int) (w * (0.06 + i * 0.18 + rnd(i, 7) * 0.05));
            roundTree(g, tx, y + h * 90 / 100, (int) (h * (0.26 + rnd(i, 8) * 0.12)), 0xFF200C08, cols[i % 3]);
        }
        petals(g, x, y, w, h, 45, t, 0xFFE0582A, 0.9);
    }

    private static void snowpine(GuiGraphicsExtractor g, int x, int y, int w, int h, double t) {
        sky(g, x, y, w, h, 0x0A1430, 0x3A5A8A);
        stars(g, x, y, w, h / 2, 50, t, 0xDDE8FF);
        disc(g, x + w * 4 / 5, y + h / 5, Math.max(8, h / 15), 0xFFE8F0FF);
        hills(g, x, y + h * 70 / 100, w, y + h, 0xFF6A84B0, h / 16, 0.01, 1);
        pineRow(g, x, w, y + h * 86 / 100, h * 34 / 100, 8, 0xFF1A2A48, 0xFFD8E4F4, 5, t);
        g.fill(x, y + h * 86 / 100, x + w, y + h, 0xFFC8D6EC);
        pineRow(g, x, w, y + h, h * 44 / 100, 5, 0xFF0E1A30, 0xFFE8F0FA, 6, t);
        snow(g, x, y, w, h, 110, t, 2);
    }

    private static void savanna(GuiGraphicsExtractor g, int x, int y, int w, int h, double t) {
        int horizon = y + h * 78 / 100;
        sky(g, x, y, w, horizon - y, 0x2A1020, 0xD86A2A);
        int r = Math.max(14, h / 6);
        disc(g, x + w / 2, horizon - r / 3, r + 8, 0x30FFC070);
        disc(g, x + w / 2, horizon - r / 3, r, 0xFFFFB050);
        birds(g, x, y, w, h * 45 / 100, 6, t);
        g.fill(x, horizon, x + w, y + h, 0xFF2A1208);
        for (int i = 0; i < 4; i++) {
            int tx = x + (int) (w * (0.12 + i * 0.26 + rnd(i, 11) * 0.06));
            acacia(g, tx, horizon + 1, (int) (h * (0.18 + rnd(i, 12) * 0.1)), 0xFF2A1208);
        }
        for (int gx = 0; gx < w; gx += 3) {   // grass tufts swaying
            int gh = 2 + (int) (rnd(gx, 13) * 4);
            int sway = (int) Math.round(Math.sin(t * 1.5 + gx * 0.05));
            g.fill(x + gx + sway, horizon - gh, x + gx + sway + 1, horizon, 0xFF2A1208);
        }
    }

    private static void moonbloom(GuiGraphicsExtractor g, int x, int y, int w, int h, double t) {
        sky(g, x, y, w, h, 0x08061C, 0x2A1A50);
        stars(g, x, y, w, h * 2 / 3, 80, t, 0xFFFFFF);
        int mr = Math.max(14, h / 7), mx = x + w * 3 / 4, my = y + h / 4;
        disc(g, mx, my, mr + 10, 0x18C8B8FF);
        disc(g, mx, my, mr + 5, 0x28C8B8FF);
        disc(g, mx, my, mr, 0xFFEDE6FF);
        disc(g, mx + mr / 3, my - mr / 4, mr / 4, 0xFFD8CFF0);   // crater
        hills(g, x, y + h * 80 / 100, w, y + h, 0xFF120C26, h / 18, 0.012, 1);
        for (int i = 0; i < 22; i++) {   // flowers: thin stems with glowing blooms
            int fx = x + (int) (rnd(i, 21) * w), base = y + h - (int) (rnd(i, 22) * h / 14);
            int fh = 8 + (int) (rnd(i, 23) * h / 10);
            int sway = (int) Math.round(Math.sin(t * 1.2 + i) * 1.5);
            g.fill(fx, base - fh, fx + 1, base, 0xFF120C26);
            double pulse = 0.6 + 0.4 * Math.sin(t * 2 + i * 0.7);
            int bc = i % 3 == 0 ? 0xC8A0FF : i % 3 == 1 ? 0xF0A0E0 : 0x9AD8FF;
            disc(g, fx + sway, base - fh, 3, alpha(bc, (int) (90 * pulse)));
            g.fill(fx + sway - 1, base - fh - 1, fx + sway + 2, base - fh + 2, alpha(bc, 230));
        }
        for (int i = 0; i < 26; i++) {   // fireflies wandering
            double px = x + (rnd(i, 31) * w + Math.sin(t * 0.4 + i) * 30) % w;
            double py = y + h * 0.5 + rnd(i, 32) * h * 0.45 + Math.cos(t * 0.6 + i * 1.7) * 10;
            double blink = 0.5 + 0.5 * Math.sin(t * 3 + i * 2.1);
            int ix = (int) px, iy = (int) py;
            g.fill(ix - 1, iy - 1, ix + 2, iy + 2, alpha(0xE8FF8A, (int) (60 * blink)));
            g.fill(ix, iy, ix + 1, iy + 1, alpha(0xF4FFC0, (int) (255 * blink)));
        }
    }

    // ================= pieces =================

    private static void sky(GuiGraphicsExtractor g, int x, int y, int w, int h, int top, int bottom) {
        for (int i = 0; i < h; i += 3)
            g.fill(x, y + i, x + w, y + Math.min(h, i + 3), mix(top, bottom, i / (double) Math.max(1, h)));
    }

    private static void disc(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int half = (int) Math.round(Math.sqrt(Math.max(0, r * r - dy * dy)));
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    private static void stars(GuiGraphicsExtractor g, int x, int y, int w, int h, int n, double t, int color) {
        for (int i = 0; i < n; i++) {
            int sx = x + (int) (rnd(i, 1) * w), sy = y + (int) (rnd(i, 2) * h);
            double tw = 0.5 + 0.5 * Math.sin(t * (1 + rnd(i, 5) * 2) + i);
            g.fill(sx, sy, sx + 1, sy + 1, alpha(color, (int) (60 + 160 * tw)));
        }
    }

    /** Ridge line from a few sine waves, filled down to the bottom. */
    private static void hills(GuiGraphicsExtractor g, int x, int base, int w, int bottom, int color, int amp, double freq, int seed) {
        for (int px = 0; px < w; px += 2) {
            double v = Math.sin(px * freq + seed * 1.7) * 0.6 + Math.sin(px * freq * 2.3 + seed) * 0.4;
            int top = base - (int) (v * amp);
            g.fill(x + px, top, x + px + 2, bottom, color);
        }
    }

    /** A row of pine trees standing on baseY. */
    private static void pineRow(GuiGraphicsExtractor g, int x, int w, int baseY, int height, int count, int color, int snow, int seed, double t) {
        for (int i = 0; i < count; i++) {
            double jitter = (rnd(i, seed) - 0.5) * 0.5;
            int cx = x + (int) (w * ((i + 0.5 + jitter) / count));
            int th = (int) (height * (0.75 + rnd(i, seed + 1) * 0.25));
            pine(g, cx, baseY, th, color, snow);
        }
    }

    /** Pine: three stacked tiers; the snow color covers the left (lit) part of each tier. */
    private static void pine(GuiGraphicsExtractor g, int cx, int baseY, int th, int color, int snow) {
        int top = baseY - th, trunkH = Math.max(2, th / 10);
        g.fill(cx - Math.max(1, th / 30), baseY - trunkH, cx + Math.max(1, th / 30) + 1, baseY, color);
        int bodyH = th - trunkH;
        for (int tier = 0; tier < 3; tier++) {
            int ty = top + tier * bodyH / 4, tH = bodyH / 2;
            int maxHalf = (int) (th * (0.16 + tier * 0.06));
            for (int r = 0; r < tH; r += 2) {
                int half = Math.max(1, maxHalf * r / Math.max(1, tH));
                int yy = ty + r;
                if (snow == color) g.fill(cx - half, yy, cx + half + 1, yy + 2, color);
                else {
                    g.fill(cx - half, yy, cx + half + 1, yy + 2, color);
                    g.fill(cx - half, yy, cx + half / 3, yy + 2, snow);   // lit snowy side
                }
            }
        }
    }

    /** Little colored bulbs dotted on the front trees. */
    private static void treeLights(GuiGraphicsExtractor g, int x, int w, int baseY, int height, int count, int seed, double t) {
        int[] cols = {0xFFFF5A6E, 0xFF4ADE80, 0xFF5B8CFF, 0xFFFFD25A};
        for (int i = 0; i < count; i++) {
            double jitter = (rnd(i, seed) - 0.5) * 0.5;
            int cx = x + (int) (w * ((i + 0.5 + jitter) / count));
            int th = (int) (height * (0.75 + rnd(i, seed + 1) * 0.25));
            for (int k = 0; k < 8; k++) {
                double fy = 0.2 + k * 0.09;
                int by = baseY - th + (int) (th * fy);
                int half = (int) (th * 0.2 * fy);
                int bx = cx + (int) ((rnd(i * 8 + k, 9) - 0.5) * 2 * half);
                boolean on = Math.sin(t * 2 + i * 3 + k) > -0.3;
                int c = cols[(i + k) % cols.length];
                if (on) g.fill(bx - 2, by - 2, bx + 3, by + 3, alpha(c, 60));
                g.fill(bx - 1, by - 1, bx + 2, by + 2, on ? c : alpha(c, 120));
            }
        }
    }

    /** Wire along the top edge with colored bulbs that twinkle. */
    private static void stringLights(GuiGraphicsExtractor g, int x, int y, int w, double t) {
        int[] cols = {0xFFFF5A6E, 0xFF4ADE80, 0xFF5B8CFF, 0xFFFFD25A, 0xFFC08AFF};
        int span = 64;
        for (int px = 0; px < w; px += 2) {
            double f = (px % span) / (double) span;
            int sag = (int) (Math.sin(f * Math.PI) * 3);
            g.fill(x + px, y + 1 + sag, x + px + 2, y + 2 + sag, 0xFF2A2440);
        }
        for (int i = 0; i * 16 < w; i++) {
            int bx = x + i * 16 + 8;
            double f = ((i * 16 + 8) % span) / (double) span;
            int by = y + 2 + (int) (Math.sin(f * Math.PI) * 3);
            double tw = 0.55 + 0.45 * Math.sin(t * 2.5 + i * 1.9);
            int c = cols[i % cols.length];
            g.fill(bx - 3, by - 1, bx + 4, by + 6, alpha(c, (int) (55 * tw)));   // glow
            g.fill(bx - 1, by, bx + 2, by + 4, alpha(c, (int) (140 + 115 * tw)));
            g.fill(bx - 1, by - 1, bx + 2, by, 0xFF3A3450);   // cap
        }
    }

    private static void snow(GuiGraphicsExtractor g, int x, int y, int w, int h, int n, double t, int seed) {
        for (int i = 0; i < n; i++) {
            double speed = 8 + rnd(i, seed + 40) * 14;
            double sx = rnd(i, seed + 41) * w + Math.sin(t * 0.8 + i) * 8;
            double sy = (rnd(i, seed + 42) * h + t * speed) % h;
            int sz = rnd(i, seed + 43) > 0.7 ? 2 : 1;
            int px = x + (int) ((sx % w + w) % w), py = y + (int) sy;
            g.fill(px, py, px + sz, py + sz, alpha(0xFFFFFF, 140 + (int) (rnd(i, seed + 44) * 110)));
        }
    }

    /** Petals or leaves: drift down and sideways, tumbling (width flips). */
    private static void petals(GuiGraphicsExtractor g, int x, int y, int w, int h, int n, double t, int color, double drift) {
        for (int i = 0; i < n; i++) {
            double speed = 10 + rnd(i, 51) * 12;
            double sy = (rnd(i, 52) * h + t * speed) % h;
            double sx = rnd(i, 53) * w + t * speed * drift + Math.sin(t + i) * 10;
            int px = x + (int) ((sx % w + w) % w), py = y + (int) sy;
            boolean flat = Math.sin(t * 3 + i) > 0;
            int c = i % 3 == 0 ? mix(color, 0xFFFFFFFF, 0.25) : color;
            g.fill(px, py, px + (flat ? 3 : 1), py + (flat ? 1 : 2), alpha(c, 220));
        }
    }

    private static void birds(GuiGraphicsExtractor g, int x, int y, int w, int h, int n, double t) {
        for (int i = 0; i < n; i++) {
            double speed = 12 + rnd(i, 61) * 10;
            int bx = x + (int) ((rnd(i, 62) * w + t * speed) % (w + 40)) - 20;
            int by = y + (int) (h * (0.25 + rnd(i, 63) * 0.6) + Math.sin(t * 1.5 + i) * 3);
            boolean up = Math.sin(t * 8 + i * 2) > 0;   // wings flap
            int c = 0xE0140A20;
            g.fill(bx - 3, by + (up ? -1 : 1), bx - 1, by + (up ? 0 : 2), c);
            g.fill(bx - 1, by, bx + 1, by + 1, c);
            g.fill(bx + 1, by + (up ? -1 : 1), bx + 3, by + (up ? 0 : 2), c);
        }
    }

    /** Santa's sleigh with reindeer crossing the sky every 40 seconds. */
    private static void sleigh(GuiGraphicsExtractor g, int x, int y, int w, int h, double t) {
        double cycle = 40, p = (t % cycle) / 14.0;   // 14 s to cross, then a break
        if (p > 1) return;
        int c = 0xFF140E2A;
        int sx = x + w + 60 - (int) (p * (w + 140)), sy = y + h / 8 + (int) (Math.sin(p * Math.PI * 2) * 6);
        // sleigh: body, curled front, runner
        g.fill(sx + 22, sy - 4, sx + 38, sy + 3, c);
        g.fill(sx + 20, sy - 1, sx + 23, sy + 3, c);
        g.fill(sx + 34, sy - 8, sx + 38, sy - 4, c);
        g.fill(sx + 20, sy + 5, sx + 40, sy + 6, c);
        g.fill(sx + 27, sy - 9, sx + 32, sy - 4, c);   // santa
        g.fill(sx + 28, sy - 12, sx + 31, sy - 9, c);
        // reindeer ahead (to the left), legs stepping
        for (int k = 0; k < 3; k++) {
            int rx = sx - k * 16, ry = sy - 2 + (int) (Math.sin(t * 6 + k) * 1.5);
            g.fill(rx + 2, ry - 3, rx + 12, ry + 1, c);              // body
            g.fill(rx, ry - 7, rx + 3, ry - 2, c);                   // neck and head
            g.fill(rx - 1, ry - 10, rx, ry - 7, c);                  // antlers
            g.fill(rx + 2, ry - 10, rx + 3, ry - 7, c);
            boolean step = Math.sin(t * 10 + k) > 0;
            g.fill(rx + 3, ry + 1, rx + 4, ry + (step ? 4 : 3), c);
            g.fill(rx + 10, ry + 1, rx + 11, ry + (step ? 3 : 4), c);
            g.fill(rx + 12, ry - 2, rx + 20, ry - 1, alpha(0x140E2A, 160));   // reins
        }
    }

    private static void islandWithPalms(GuiGraphicsExtractor g, int cx, int baseY, int iw, int h, int color, int palms, double t) {
        for (int dy = 0; dy < 10; dy++) {   // low mound
            int half = iw / 2 - dy * dy * iw / 260;
            if (half <= 0) break;
            g.fill(cx - half, baseY - dy, cx + half, baseY - dy + 1, color);
        }
        g.fill(cx - iw / 2, baseY, cx + iw / 2, baseY + 2, color);
        for (int i = 0; i < palms; i++) {
            int px = cx + (int) ((i - (palms - 1) / 2.0) * iw / (palms + 0.5));
            palm(g, px, baseY - 6, (int) (h * (0.22 + rnd(i, 71) * 0.1)), color, i % 2 == 0 ? 1 : -1, t + i);
        }
    }

    /** Curved trunk and drooping fronds that sway a little. */
    private static void palm(GuiGraphicsExtractor g, int bx, int baseY, int ph, int color, int lean, double t) {
        int topX = bx, topY = baseY - ph;
        for (int k = 0; k <= ph; k += 2) {
            double f = k / (double) ph;
            int tx = bx + (int) (lean * Math.pow(f, 2) * ph * 0.25);
            int thick = f < 0.5 ? 2 : 1;
            g.fill(tx - thick, baseY - k, tx + thick, baseY - k + 2, color);
            topX = tx;
            topY = baseY - k;
        }
        double sway = Math.sin(t * 1.3) * 0.08;
        for (int fr = 0; fr < 7; fr++) {
            double ang = -Math.PI / 2 + (fr - 3) * 0.55 + sway;
            int len = (int) (ph * 0.42);
            for (int s = 0; s < len; s += 2) {
                double f = s / (double) len;
                int fx = topX + (int) (Math.cos(ang) * s);
                int fy = topY + (int) (Math.sin(ang) * s + f * f * len * 0.7);   // droop
                g.fill(fx - 1, fy, fx + 2, fy + 2, color);
            }
        }
    }

    private static void blossomTree(GuiGraphicsExtractor g, int cx, int baseY, int th, int trunk, int bloom, int seed) {
        g.fill(cx - 2, baseY - th / 2, cx + 2, baseY, trunk);
        g.fill(cx - th / 6, baseY - th * 5 / 8, cx - th / 6 + 2, baseY - th / 2, trunk);
        for (int b = 0; b < 7; b++) {
            int bx = cx + (int) ((rnd(seed * 7 + b, 81) - 0.5) * th * 0.7);
            int by = baseY - th / 2 - (int) (rnd(seed * 7 + b, 82) * th * 0.45);
            disc(g, bx, by, Math.max(4, th / 7), alpha(bloom, 200));
        }
    }

    private static void roundTree(GuiGraphicsExtractor g, int cx, int baseY, int th, int trunk, int crown) {
        g.fill(cx - 2, baseY - th / 2, cx + 2, baseY, trunk);
        disc(g, cx, baseY - th * 2 / 3, Math.max(5, th / 3), crown);
        disc(g, cx - th / 5, baseY - th / 2, Math.max(4, th / 4), mix(crown, 0xFF000000, 0.15));
        disc(g, cx + th / 5, baseY - th / 2, Math.max(4, th / 4), mix(crown, 0xFF000000, 0.25));
    }

    /** Acacia: thin trunk splitting in two, flat wide crown. */
    private static void acacia(GuiGraphicsExtractor g, int cx, int baseY, int th, int color) {
        for (int k = 0; k < th; k += 2) {
            int off = (int) (k > th / 2 ? (k - th / 2) * 0.5 : 0);
            g.fill(cx - off - 1, baseY - k - 2, cx - off + 1, baseY - k, color);
            g.fill(cx + off - 1, baseY - k - 2, cx + off + 1, baseY - k, color);
        }
        int cy = baseY - th, half = th;
        for (int dy = -th / 6; dy <= th / 10; dy++) {
            int w = (int) (half * Math.sqrt(Math.max(0, 1 - Math.pow(dy / (th / 6.0), 2))));
            g.fill(cx - w, cy + dy, cx + w, cy + dy + 1, color);
        }
    }
}
