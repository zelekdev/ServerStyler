package pl.zelekdev.serverstyler.client.gui;

import pl.zelekdev.serverstyler.i18n.I18n;
import pl.zelekdev.serverstyler.i18n.Keys;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.IntSupplier;

/**
 * Horizontal opacity slider with a checkerboard transparency preview
 * (26.1+ extract API).
 *
 * <p>The track is a left-to-right fade of the active colour over a grey
 * checkerboard, so the chosen alpha is readable at a glance. This is the same
 * visual language as an image editor's alpha channel, which players recognise
 * immediately.</p>
 */
public final class HorizontalAlphaSlider extends AbstractHorizontalSlider {

	/** Checkerboard cell size; 4 px reads clearly at every GUI scale. */
	private static final int CHECKER_CELL = 4;

	private final IntSupplier baseRgb;
	private final DoubleConsumer onChanged;

	public HorizontalAlphaSlider(Font font, int x, int y, int width, int height, double opacity,
								 IntSupplier baseRgb, DoubleConsumer onChanged) {
		super(font, x, y, width, height, Component.literal(I18n.tr(Keys.SLIDER_OPACITY)), opacity);
		this.baseRgb = baseRgb;
		this.onChanged = onChanged;
		updateMessage();
	}

	public float getAlpha() {
		return (float) this.value;
	}

	@Override
	protected void drawTrack(GuiGraphicsExtractor graphics) {
		int left = trackLeft();
		int right = trackRight();
		int top = trackTop();
		int bottom = trackBottom();

		drawChecker(graphics, left, top, right, bottom);

		int rgb = (this.baseRgb == null ? 0xFFFFFF : this.baseRgb.getAsInt()) & 0xFFFFFF;
		// Left end fully transparent, right end fully opaque: the ramp itself shows
		// exactly what the opacity value does to the colour.
		graphics.fillGradient(left, top, right, bottom, rgb & 0x00FFFFFF, 0xFF000000 | rgb);
		graphics.fill(left, top, right, top + 1, 0x40000000);
		graphics.fill(left, bottom - 1, right, bottom, 0x40000000);
	}

	private void drawChecker(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
		for (int y = top; y < bottom; y += CHECKER_CELL) {
			for (int x = left; x < right; x += CHECKER_CELL) {
				boolean dark = (((x - left) / CHECKER_CELL) + ((y - top) / CHECKER_CELL)) % 2 == 0;
				graphics.fill(x, y, Math.min(x + CHECKER_CELL, right), Math.min(y + CHECKER_CELL, bottom),
						dark ? 0xFF565656 : 0xFF8A8A8A);
			}
		}
	}

	@Override
	protected void applyValue() {
		if (this.onChanged != null) {
			this.onChanged.accept(this.value);
		}
	}

	@Override
	protected void updateMessage() {
		setMessage(Component.literal(String.format(Locale.ROOT, "%d%%", Math.round(this.value * 100.0))));
	}
}