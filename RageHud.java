package com.ragemod.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.Random;

/** Sinir bari, kirmizi ekran ve sagdaki RAGE metresi. */
public final class RageHud {
    private RageHud() {}

    public static void render(DrawContext ctx, float tickDelta) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.player == null || c.world == null || c.options.hudHidden) return;
        int w = ctx.getScaledWindowWidth();
        int h = ctx.getScaledWindowHeight();
        float t = c.world.getTime() + tickDelta;

        if (ClientRageState.active) {
            drawRedScreen(ctx, w, h, t);
            drawMeter(ctx, c.textRenderer, w, h, t);
            drawControls(ctx, c.textRenderer, h);
        } else {
            drawBar(ctx, c.textRenderer, w, h, t);
        }
    }

    // ---------------- normal mod: kucuk sinir bari (hotbar'in saginda)
    private static void drawBar(DrawContext ctx, TextRenderer tr, int w, int h, float t) {
        int bw = 70, bh = 8;
        int x = w / 2 + 96;
        int y = h - 14;
        float pct = Math.min(1f, ClientRageState.bar / 100f);
        boolean full = pct >= 1f;

        ctx.fill(x - 2, y - 2, x + bw + 2, y + bh + 2, 0xFF1A0612);
        ctx.fill(x - 1, y - 1, x + bw + 1, y + bh + 1, full && ((int) (t / 4) % 2 == 0) ? 0xFFFF3030 : 0xFF4B2A8C);
        ctx.fill(x, y, x + bw, y + bh, 0xFF22101A);
        int fw = (int) (bw * pct);
        if (fw > 0) ctx.fillGradient(x, y, x + fw, y + bh, 0xFFFF5040, 0xFF8A0000);
        for (int i = 1; i < 5; i++) ctx.fill(x + bw * i / 5, y, x + bw * i / 5 + 1, y + 2, 0xFF6FD3FF);

        String label = ClientRageState.petRage ? "İNTİKAM" : "RAGE";
        int color = ClientRageState.petRage ? 0xFFB00000 : 0xFFFF4040;
        ctx.drawTextWithShadow(tr, label, x, y - 11, color);
        if (full) {
            String hint = "[R]";
            int hx = x + bw - tr.getWidth(hint);
            ctx.drawTextWithShadow(tr, hint, hx, y - 11, ((int) (t / 5) % 2 == 0) ? 0xFFFFFFFF : 0xFFFF3030);
        }
    }

    // ---------------- rage: kirmizi ekran
    private static void drawRedScreen(DrawContext ctx, int w, int h, float t) {
        int pulse = (int) (28 + 14 * Math.sin(t * 0.15));
        ctx.fill(0, 0, w, h, (pulse << 24) | 0xFF0000);
        ctx.fillGradient(0, 0, w, h / 4, 0x99AA0000, 0x00AA0000);
        ctx.fillGradient(0, h * 3 / 4, w, h, 0x00AA0000, 0x99AA0000);
        for (int i = 0; i < 24; i++) {
            int a = (int) (0x80 * (1f - i / 24f));
            int col = (a << 24) | 0xAA0000;
            ctx.fill(i * 2, 0, i * 2 + 2, h, col);
            ctx.fill(w - i * 2 - 2, 0, w - i * 2, h, col);
        }
    }

    // ---------------- rage: sagdaki dikey metre (referans cizime gore)
    private static void drawMeter(DrawContext ctx, TextRenderer tr, int w, int h, float t) {
        int mw = 22, mh = 120;
        int mx = w - mw - 18;
        int my = h / 2 - mh / 2 + 10;
        Random rnd = new Random((long) (t / 2));

        // mavi simsekler
        for (int side = 0; side < 2; side++) {
            int bx = side == 0 ? mx - 6 : mx + mw + 3;
            int yy = my + rnd.nextInt(10);
            while (yy < my + mh) {
                int len = 4 + rnd.nextInt(6);
                int off = rnd.nextInt(5) - 2;
                if (rnd.nextFloat() < 0.75f) ctx.fill(bx + off, yy, bx + off + 2, yy + len, 0xCC6FD3FF);
                yy += len + rnd.nextInt(5);
            }
        }

        // cerceve
        ctx.fill(mx - 2, my - 2, mx + mw + 2, my + mh + 2, 0xFF120818);
        ctx.fillGradient(mx, my, mx + mw, my + mh, 0xFF3A2AA8, 0xFF7A1FB0);
        int ix = mx + 4, iy = my + 4, iw = mw - 8, ih = mh - 8;
        ctx.fill(ix, iy, ix + iw, iy + ih, 0xFF1A0A10);

        // dolum (asagidan yukari)
        float pct = Math.max(0f, Math.min(1f, ClientRageState.meter / 100f));
        int fh = (int) (ih * pct);
        if (fh > 0) ctx.fillGradient(ix, iy + ih - fh, ix + iw, iy + ih, 0xFFFF3A3A, 0xFFB00000);
        if (fh > 2) ctx.fill(ix, iy + ih - fh, ix + iw, iy + ih - fh + 2, 0xFF6FD3FF);

        // olcek cizgileri
        for (int i = 1; i < 6; i++) {
            int ty = iy + ih * i / 6;
            ctx.fill(ix, ty, ix + 5, ty + 1, 0xFF6FD3FF);
        }

        // RAGE yazisi (titreyen)
        String title = "RAGE";
        float s = 1.8f;
        int jx = rnd.nextInt(3) - 1, jy = rnd.nextInt(3) - 1;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(mx + mw / 2f + jx, my - 18 + jy, 0);
        ctx.getMatrices().scale(s, s, 1f);
        ctx.drawText(tr, title, -tr.getWidth(title) / 2, 0, 0xFFFF2020, true);
        ctx.getMatrices().pop();

        // kilit suresi / yuzde
        String info;
        int infoCol;
        if (ClientRageState.freezeTicks > 0) {
            int sec = ClientRageState.freezeTicks / 20;
            info = String.format("%d:%02d", sec / 60, sec % 60);
            infoCol = 0xFF6FD3FF;
        } else {
            info = (int) ClientRageState.meter + "%";
            infoCol = 0xFFFF6060;
        }
        ctx.drawTextWithShadow(tr, info, mx + mw / 2 - tr.getWidth(info) / 2, my + mh + 5, infoCol);
    }

    // ---------------- rage: sol taraftaki kontrol listesi
    private static void drawControls(DrawContext ctx, TextRenderer tr, int h) {
        int x = 10;
        int y = h / 2 - 40;
        int cd = ClientRageState.screamCooldown;
        String scream = "SCREAM/R" + (cd > 0 ? "  " + (cd / 20 + 1) + "s" : "");
        ctx.drawTextWithShadow(tr, scream, x, y, cd > 0 ? 0xFF888888 : 0xFFFFFFFF);
        ctx.drawTextWithShadow(tr, "GRAB/M2" + (ClientRageState.grabbing ? "  [TUTUYOR]" : ""), x, y + 11, 0xFFFFFFFF);
        ctx.drawTextWithShadow(tr, "KICK/F", x, y + 22, 0xFFFFFFFF);
        ctx.drawTextWithShadow(tr, "DASH/G", x, y + 33, 0xFFFFFFFF);
        ctx.drawTextWithShadow(tr, "SHIFT+SPACE: SÜPER ZIPLA", x, y + 44, 0xFFDDDDDD);
        ctx.drawTextWithShadow(tr, "HAVADA CTRL: YERE ÇAK", x, y + 55, 0xFFDDDDDD);
        ctx.drawTextWithShadow(tr, "P: SAKİNLEŞ", x, y + 66, 0xFFAAAAAA);
    }
}
