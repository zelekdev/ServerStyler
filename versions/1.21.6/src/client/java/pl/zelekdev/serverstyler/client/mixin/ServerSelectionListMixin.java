package pl.zelekdev.serverstyler.client.mixin;

import pl.zelekdev.serverstyler.client.ServerStylerClient;
import pl.zelekdev.serverstyler.config.ConfigManager;
import pl.zelekdev.serverstyler.config.ServerStyle;
import pl.zelekdev.serverstyler.render.RenderCache;
import pl.zelekdev.serverstyler.render.TextStylingEngine;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import java.util.function.ToIntFunction;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Reinjects the styled server name into {@code ServerSelectionList}'s online
 * server entries for Minecraft 1.21.4 mappings.
 *
 * <p>Vanilla draws the name via a single
 * {@code GuiGraphics.drawString(Font, String, int, int, int)} call inside
 * {@code OnlineServerEntry#render}. We redirect exactly that call and, when the
 * entry's server has an enabled {@link ServerStyle}, draw the per-character
 * gradient component instead.</p>
 *
 * <h3>Why there is no {@code @Shadow} on inherited members</h3>
 * <p>Mixin resolves {@code @Shadow} methods and fields against the
 * <em>target class's own</em> declared members only. {@code getContentRight()},
 * {@code getRowLeft()}, {@code getRowWidth()} and the {@code list} field are all
 * inherited from {@code AbstractSelectionList.Entry} / {@code ObjectSelectionList.Entry},
 * so shadowing them throws {@code InvalidMixinException} and the <b>entire mixin is
 * discarded</b> &mdash; which is exactly why styled names never appeared in the
 * multiplayer list. The only member shadowed here,
 * {@link #serverData}, is declared directly on {@code OnlineServerEntry}.</p>
 *
 * <p>Instead of reaching for inherited geometry, the name's usable span is derived
 * from two constants that both versions hard-code in {@code ServerSelectionList}:
 * the row width ({@code 305}, a {@code sipush} literal in {@code getRowWidth()})
 * and the name's horizontal offset inside the row ({@code 32}px icon + {@code 3}px
 * padding). Vanilla starts the name at {@code rowLeft + 35}; the row ends at
 * {@code rowLeft + 305}; therefore the space available from the name's start to the
 * row's right edge is the constant {@code 305 - 35}.</p>
 *
 * <h3>Scope is deliberately minimal</h3>
 * <ul>
 *   <li>The MOTD lines are drawn through the {@code FormattedCharSequence}
 *       overload &mdash; a different descriptor, never intercepted.</li>
 *   <li>The player-count status text uses the {@code Component} overload &mdash;
 *       also untouched.</li>
 *   <li>Ping sprites, favicons, hover tooltips and click handling live in the rest
 *       of {@code render} and run exactly as before.</li>
 * </ul>
 */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
public abstract class ServerSelectionListMixin {

	/** Vanilla's row width, hard-coded as {@code sipush 305} in {@code getRowWidth()}. */
	@Unique
	private static final int SERVERSTYLER_ROW_WIDTH = 305;

	/** {@code 32}px favicon + {@code 3}px padding: where vanilla starts the name. */
	@Unique
	private static final int SERVERSTYLER_NAME_OFFSET = 32 + 3;

	/** Pixels the ping icon cluster occupies at the row's right edge. */
	@Unique
	private static final int SERVERSTYLER_ICON_CLUSTER = 20;

	/**
	 * Hard cap on how many characters a styled name may occupy on the list.
	 *
	 * <p>Pixels alone are not enough: at ~6px per glyph the row can technically
	 * swallow 40+ characters, which looks nothing like a server name and crowds the
	 * player count. The cap is applied before measuring, so even the widest glyphs
	 * stay short.</p>
	 */
	@Unique
	private static final int SERVERSTYLER_NAME_CHAR_LIMIT = 20;

	/** Breathing room kept between the name and the player-count cluster. */
	@Unique
	private static final int SERVERSTYLER_RIGHT_MARGIN = 8;

	/**
	 * The entry's server record.
	 *
	 * <p>Declared directly on {@code OnlineServerEntry} as
	 * {@code private final ServerData serverData}, so this shadow resolves. 1.21.4
	 * exposes no {@code getServerData()} accessor here.</p>
	 */
	@Shadow
	@Final
	private ServerData serverData;

	@Redirect(
			method = "render",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"))
	private void serverstyler$drawStyledName(GuiGraphics graphics, Font font, String text,
											 int x, int y, int color) {
		if (text == null || text.isEmpty()) {
			graphics.drawString(font, text, x, y, color);
			return;
		}
		ServerStyle style = serverstyler$style();
		if (style == null) {
			graphics.drawString(font, text, x, y, color);
			return;
		}

		// Recompute the runway every frame: it depends on the row's runtime left
		// edge (which moves with the window size / GUI scale) and on the width of
		// this server's player-count text (which changes as pings resolve).
		int span = serverstyler$availableWidth(font, x);

		// Mini Caps first, then fit: truncation and alignment must measure the
		// glyphs that actually get drawn, and small capitals have different
		// advances from their Latin sources.
		String source = TextStylingEngine.applyMiniCaps(text, style);

		// Cap the character count and fit the remaining pixels in one call: any
		// truncation (by characters or by pixels) is marked with an ellipsis, so a
		// shortened name is never mistaken for the full one.
		String visible = TextStylingEngine.fitToRow(
				source, SERVERSTYLER_NAME_CHAR_LIMIT, span, serverstyler$measure(font, style));
		if (visible.isEmpty()) {
			// Not even the ellipsis fits: draw nothing rather than overflow the row.
			return;
		}

		Component styled = serverstyler$styledName(visible, style);
		int drawX = TextStylingEngine.alignedX(x, span, font.width(styled), style.getAlignment());
		graphics.drawString(font, styled, drawX, y, color);
	}

	/**
	 * Width measurer that accounts for the extra pixel bold adds to every glyph.
	 *
	 * <p>{@code Font.width(String)} always measures with {@link Style#EMPTY}, i.e.
	 * as if the text were regular weight, while a bold glyph's advance is
	 * {@code getAdvance() + 1.0F} (see {@code GlyphInfo.getAdvance(boolean)}). A
	 * bold name is therefore one pixel per character wider than the measured value,
	 * which is exactly how a "fitting" name still bled past the row.</p>
	 */
	@Unique
	private ToIntFunction<String> serverstyler$measure(Font font, ServerStyle style) {
		boolean bold = style.isBold();
		return candidate -> font.width(candidate)
				+ (bold ? candidate.codePointCount(0, candidate.length()) : 0);
	}

	/**
	 * Pixels available to the name: from its vanilla start position to just before
	 * the status cluster at the row's right edge.
	 *
	 * <p>Never a cached screen constant &mdash; {@code baseX} is the live draw
	 * position vanilla passed in, so this follows window resizes and GUI-scale
	 * changes automatically.</p>
	 */
	@Unique
	private int serverstyler$availableWidth(Font font, int baseX) {
		int rowRight = baseX - SERVERSTYLER_NAME_OFFSET + SERVERSTYLER_ROW_WIDTH;
		return Math.max(0, rowRight - serverstyler$reservedRightWidth(font)
				- SERVERSTYLER_RIGHT_MARGIN - baseX);
	}

	/**
	 * Pixels at the row's right edge owned by vanilla's status text + ping icon.
	 */
	@Unique
	private int serverstyler$reservedRightWidth(Font font) {
		ServerData data = this.serverData;
		if (data == null || data.status == null) {
			return SERVERSTYLER_ICON_CLUSTER;
		}
		return SERVERSTYLER_ICON_CLUSTER + font.width(data.status);
	}

	/** The effective (enabled) style for this entry, or {@code null}. */
	@Unique
	private ServerStyle serverstyler$style() {
		ServerData data = this.serverData;
		if (data == null || data.ip == null) {
			return null;
		}
		ConfigManager config = ServerStylerClient.config();
		return config == null ? null : config.getEffectiveFor(data.ip, data.name);
	}

	/**
	 * Builds the styled component for the already-trimmed {@code visible} text.
	 *
	 * <p>{@link RenderCache} is keyed on {@code (server id, text)}, and the text passed here
	 * is the post-truncation string, so a name that shrinks at a narrow window and
	 * grows again on resize still hits the cache correctly instead of reusing a
	 * stale, over-long component.</p>
	 */
	@Unique
	private Component serverstyler$styledName(String visible, ServerStyle style) {
		ServerData data = this.serverData;
		if (data == null || data.ip == null) {
			return Component.literal(visible);
		}
		RenderCache cache = ServerStylerClient.renderCache();
		return cache != null
				? cache.styled(data.ip, data.name, visible)
				: TextStylingEngine.buildComponent(visible, style);
	}
}