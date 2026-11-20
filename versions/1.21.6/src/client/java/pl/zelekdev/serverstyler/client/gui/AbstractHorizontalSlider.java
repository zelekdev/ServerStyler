package pl.zelekdev.serverstyler.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Base class for the panel's horizontal sliders (1.21.4 API).
 *
 * <p>Horizontal, like every vanilla slider, so the control reads the same way as
 * the rest of the game's UI. {@code value} is {@code 0} at the left end of the
 * track and {@code 1} at the right.</p>
 *
 * <p>Subclasses paint only the track ({@link #drawTrack}) and the knob
 * ({@link #drawHandle}); geometry, input, clamping and the caption live here so
 * the sliders cannot drift apart visually.</p>
 */
public abstract class AbstractHorizontalSlider extends AbstractWidget {

	/** Inset of the track from the widget bounds, leaving room for the knob. */
	protected static final int KNOB_INSET = 4;
	/** Half-width of the knob, in GUI-scaled pixels. */
	protected static final int KNOB_HALF_WIDTH = 3;
	/** Half-height of the knob, in GUI-scaled pixels. */
	protected static final int KNOB_HALF_HEIGHT = 5;

	private final Font font;
	protected double value;

	/** Called when a press starts a gesture; no-op until the panel wires it. */
	private Runnable gestureStart = () -> {
	};
	/** Called when the press that started a gesture is released. */
	private Runnable gestureEnd = () -> {
	};
	public AbstractHorizontalSlider(Font font, int x, int y, int width, int height, Component message,
									double value) {
		super(x, y, width, height, message);
		this.font = font;
		this.value = Mth.clamp(value, 0.0, 1.0);
	}

	/**
	 * Wires the gesture callbacks.
	 *
	 * <p>A slider drag fires dozens of value changes per second. The panel uses
	 * these hooks to collapse the whole press-drag-release into a single undo
	 * entry, so undo returns to the value the slider had when it was grabbed
	 * rather than to the previous pixel of the drag.</p>
	 */
	public void setGestureHooks(Runnable start, Runnable end) {
		this.gestureStart = start == null ? () -> {
		} : start;
		this.gestureEnd = end == null ? () -> {
		} : end;
	}
	public double getValue() {
		return value;
	}

	public void setValue(double newValue) {
		double clamped = Mth.clamp(newValue, 0.0, 1.0);
		if (clamped != this.value) {
			this.value = clamped;
			applyValue();
		}
		updateMessage();
	}

	protected int trackLeft() {
		return getX() + KNOB_INSET;
	}

	protected int trackRight() {
		return getX() + getWidth() - KNOB_INSET;
	}

	/**
	 * Inner track rectangle: 4 px tall, vertically centred, leaving a dark border
	 * above and below so the colour reads as a filled groove.
	 */
	protected int trackTop() {
		return getY() + getHeight() / 2 - 2;
	}

	protected int trackBottom() {
		return getY() + getHeight() / 2 + 2;
	}

	/** X (in screen space) of the knob centre for the current value. */
	protected int knobX() {
		int left = trackLeft();
		int right = trackRight();
		return left + (int) Math.round(this.value * (right - left));
	}

	private void setValueFromMouse(double mouseX) {
		int left = trackLeft();
		int right = trackRight();
		double span = right - left;
		if (span <= 0.0) {
			return;
		}
		setValue((mouseX - left) / span);
	}

	@Override
	public void onClick(double mouseX, double mouseY) {
		setValueFromMouse(mouseX);
	}

	@Override
	protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
		setValueFromMouse(mouseX);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!this.visible || !this.active) {
			return false;
		}
		if (isValidClickButton(button) && isMouseOver(mouseX, mouseY)) {
			this.gestureStart.run();
			onClick(mouseX, mouseY);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		// Ends the gesture opened in mouseClicked(), so the panel can push exactly
		// one undo entry for the whole press-drag-release.
		this.gestureEnd.run();
		return false;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		// Deliberately NOT gated on isMouseOver: this widget is only 16 px tall, so a
		// horizontal drag would die the moment the pointer drifted a few pixels above
		// or below the track. Vanilla's own sliders keep dragging until released, and
		// the screen only routes drags here while this widget holds focus.
		if (isValidClickButton(button)) {
			onDrag(mouseX, mouseY, dragX, dragY);
			return true;
		}
		return false;
	}

	@Override
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		drawFrame(graphics);
		drawTrack(graphics);
		// Knob before caption: the caption carries the value readout ("#RRGGBB" /
		// "100%"), and the knob is a solid white bar that must never paint over it.
		// Drawing the knob first puts it on a lower layer than the text.
		drawHandle(graphics, isHoveredOrFocused());
		drawCaption(graphics);
	}

	/**
	 * The shared chrome: a black well with a subtle light top edge, matching the
	 * inset look of vanilla's text fields rather than a floating rectangle.
	 */
	protected void drawFrame(GuiGraphics graphics) {
		int left = getX();
		int top = getY();
		int right = getX() + getWidth();
		int bottom = getY() + getHeight();
		graphics.fill(left, top, right, bottom, 0xFF1A1A1A);
		graphics.fill(left, top, right, top + 1, 0xFF000000);
		graphics.fill(left, bottom - 1, right, bottom, 0xFF3A3A3A);
	}

	/** Paints the colour/gradient track between {@link #trackLeft()} and {@link #trackRight()}. */
	protected abstract void drawTrack(GuiGraphics graphics);

	/**
	 * Paints the draggable knob at {@link #knobX()}: a light rounded bar with a
	 * dark outline, so it stays visible over both a black field and a bright hue
	 * ramp.
	 */
	protected void drawHandle(GuiGraphics graphics, boolean highlighted) {
		int cx = knobX();
		int top = getY() + getHeight() / 2 - KNOB_HALF_HEIGHT;
		int bottom = getY() + getHeight() / 2 + KNOB_HALF_HEIGHT;
		int left = cx - KNOB_HALF_WIDTH;
		int right = cx + KNOB_HALF_WIDTH;

		graphics.fill(left - 1, top - 1, right + 1, bottom + 1, 0xFF000000);
		graphics.fill(left, top, right, bottom, highlighted ? 0xFFFFFFFF : 0xFFD8D8D8);
		graphics.fill(left, top, right, top + 1, 0xFFFFFFFF);
	}

	/**
	 * Paints the value caption centred on the slider with a shadow, so it stays
	 * legible over a bright hue ramp.
	 */
	protected void drawCaption(GuiGraphics graphics) {
		Component message = getMessage();
		if (message == null) {
			return;
		}
		String text = message.getString();
		if (text.isEmpty()) {
			return;
		}
		int width = this.font.width(text);
		int x = getX() + (getWidth() - width) / 2;
		int y = getY() + (getHeight() - 8) / 2;
		graphics.drawString(this.font, message, x, y, 0xFFFFFFFF, true);
	}

	/** Called whenever {@link #value} changes. */
	protected abstract void applyValue();

	/** Called to refresh the caption. */
	protected abstract void updateMessage();

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
		defaultButtonNarrationText(narrationElementOutput);
	}
}