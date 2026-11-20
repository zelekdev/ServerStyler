package pl.zelekdev.serverstyler.client.gui;

import pl.zelekdev.serverstyler.config.PresetStore;
import pl.zelekdev.serverstyler.config.ServerStyle;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * The preset column: <b>two separate windows</b> stacked with a visible gap.
 *
 * <ol>
 *   <li><b>Built-in presets</b> &mdash; a grid of ready-made styles. One click
 *       applies the preset to the working style.</li>
 *   <li><b>Your presets</b> &mdash; a name field plus a Save button, and a
 *       paginated list of everything the player has saved. Each row applies that
 *       preset; the small &times; deletes it.</li>
 * </ol>
 *
 * <h3>Why two windows and not one</h3>
 * <p>They answer different questions ("give me a look" vs "keep this look"), so
 * they get their own frame, header and border. The gap between them makes the
 * split obvious at a glance.</p>
 *
 * <h3>Two layouts</h3>
 * <p>At GUI scale 1&ndash;2 there is room for the full column
 * ({@value #FULL_HEIGHT}&nbsp;px). At GUI scale 3 the usable screen is only about
 * 640&times;360 GUI pixels and the editor panel already takes 254 of those 360, so
 * the column switches to a compact layout ({@value #COMPACT_HEIGHT}&nbsp;px): a
 * 4&times;3 built-in grid, one saved row per page and no section captions. That is
 * what makes the presets reachable at every scale instead of silently vanishing.</p>
 *
 * <h3>Placement policy</h3>
 * <p>Never to the left of the editor panel &mdash; that used to overlap the name
 * field. The host asks for a spot <b>right</b> of the editor first, then
 * <b>below</b> it. If neither fits, the column hides itself rather than covering
 * the screen.</p>
 *
 * <h3>Input contract</h3>
 * <p>Like the editor panel, this widget is registered <em>before</em> its children
 * and keeps {@code active = false}, so it never swallows a click: every
 * interaction goes to a real child widget.</p>
 */
public final class PresetPanel extends AbstractWidget {

	/** Column width in GUI-scaled pixels. */
	public static final int WIDTH = 180;

	/** Height of the full column (GUI scale 1&ndash;2). */
	public static final int FULL_HEIGHT = 188;

	/** Height of the compact column (GUI scale 3). */
	public static final int COMPACT_HEIGHT = 105;

	/** Height of the tiny column (GUI scale 3 on a small window). */
	public static final int TINY_HEIGHT = 88;

	/** Gap between the two windows, and between the column and the editor. */
	public static final int GAP = 4;

	private static final int PAD = 8;
	private static final int INNER = WIDTH - PAD * 2;

	private static final int SAVE_BUTTON_W = 44;
	private static final int DELETE_W = 16;
	/** Width of the per-row rename / duplicate buttons. */
	private static final int ROW_ACTION_W = 12;
	/** Gap between two per-row action buttons. */
	private static final int ROW_ACTION_GAP = 2;
	private static final int PAGER_BUTTON_W = 16;

	/** Largest built-in grid either layout can show. */
	private static final int MAX_BUILTIN_CELLS = 12;

	private final ServerStyle style;
	private final Font font;
	private final PresetStore store;
	private final StyleEditorPanel.Anchor anchor;
	private final Runnable onStyleChanged;
	private Consumer<AbstractWidget> widgetSink;

	private final Button[] builtInButtons = new Button[MAX_BUILTIN_CELLS];
	private final List<PresetStore.Preset> builtIns;

	private EditBox nameBox;
	private Button saveButton;

	private final Button[] rowButtons = new Button[3];
	private final Button[] deleteButtons = new Button[3];
	/** Per-row rename buttons, parallel to {@link #rowButtons}. */
	private final Button[] renameButtons = new Button[3];
	/** Per-row duplicate buttons, parallel to {@link #rowButtons}. */
	private final Button[] duplicateButtons = new Button[3];
	private Button prevButton;
	private Button nextButton;

	/** Zero-based index of the visible page of user presets. */
	private int page;
	/** Names currently bound to the visible rows, parallel to {@link #rowButtons}. */
	private final String[] rowNames = new String[3];

	// ---- Active layout metrics (set by applyMode) ---------------------------
	private int mode;
	private boolean compact;
	private int headerH;
	private int biGridY;
	private int biCols;
	private int biRows;
	private int biCellW;
	private int biCellH;
	private int biPitch;
	private int builtinHeight;
	private int userTop;
	private int userSaveY;
	private int userSaveH;
	private int userRowsY;
	private int userRows;
	private int userRowH;
	private int userPitch;
	private int userPagerY;
	private int userPagerH;
	private int height;

	private boolean requestedVisible;
	private boolean dimsValid;

	public PresetPanel(ServerStyle style, Font font, PresetStore store,
					   StyleEditorPanel.Anchor anchor, Runnable onStyleChanged) {
		super(0, 0, WIDTH, FULL_HEIGHT, Component.literal("ServerStyler presets"));
		this.style = style;
		this.font = font;
		this.store = store;
		this.anchor = anchor;
		this.onStyleChanged = onStyleChanged;
		this.builtIns = PresetStore.builtIns();
		this.active = false;
		this.visible = false;
		applyMode(StyleEditorPanel.MODE_FULL);
	}

	/** Creates and registers every child widget. Called once by the host screen. */
	public void addChildren(Consumer<AbstractWidget> sink) {
		this.widgetSink = sink;
		createBuiltInButtons();
		createSaveRow();
		createUserRows();
		createPager();
		refreshUserRows();
		applyVisibility();
	}

	// ------------------------------------------------------------------
	// Layout modes
	// ------------------------------------------------------------------

	/**
	 * Switches between the full, compact and tiny geometries.
	 *
	 * <p>All modes show the same twelve built-ins; only the grid shape, the number
	 * of saved rows and the caption rows differ. Keeping the widget count identical
	 * means switching modes never has to create or destroy children.</p>
	 */
	private void applyMode(int mode) {
		this.mode = mode;
		this.compact = mode != StyleEditorPanel.MODE_FULL;
		if (mode == StyleEditorPanel.MODE_TINY) {
			this.headerH = 10;
			this.biGridY = 12;
			this.biCols = 4;
			this.biRows = 3;
			this.biCellW = (INNER - 12) / 4;
			this.biCellH = 10;
			this.biPitch = 11;
			this.builtinHeight = 45;
			this.userTop = 48;
			this.userSaveY = 56;
			this.userSaveH = 12;
			this.userRowsY = 70;
			this.userRows = 1;
			this.userRowH = 11;
			this.userPitch = 12;
			this.userPagerY = 79;
			this.userPagerH = 9;
			this.height = TINY_HEIGHT;
		} else if (mode == StyleEditorPanel.MODE_COMPACT) {
			this.headerH = 12;
			this.biGridY = 14;
			this.biCols = 4;
			this.biRows = 3;
			this.biCellW = (INNER - 12) / 4;
			this.biCellH = 11;
			this.biPitch = 12;
			this.builtinHeight = 50;
			this.userTop = 54;
			this.userSaveY = 66;
			this.userSaveH = 13;
			this.userRowsY = 81;
			this.userRows = 1;
			this.userRowH = 12;
			this.userPitch = 13;
			this.userPagerY = 95;
			this.userPagerH = 10;
			this.height = COMPACT_HEIGHT;
		} else {
			this.headerH = 13;
			this.biGridY = 25;
			this.biCols = 3;
			this.biRows = 4;
			this.biCellW = (INNER - 8) / 3;
			this.biCellH = 12;
			this.biPitch = 13;
			this.builtinHeight = 79;
			this.userTop = 83;
			this.userSaveY = 108;
			this.userSaveH = 14;
			this.userRowsY = 133;
			this.userRows = 3;
			this.userRowH = 12;
			this.userPitch = 13;
			this.userPagerY = 175;
			this.userPagerH = 10;
			this.height = FULL_HEIGHT;
		}
	}

	/** Height of the column in its current mode. */
	public int height() {
		return this.height;
	}

	// ------------------------------------------------------------------
	// Construction
	// ------------------------------------------------------------------

	private void createBuiltInButtons() {
		for (int i = 0; i < this.builtInButtons.length; i++) {
			final int index = i;
			boolean present = i < this.builtIns.size();
			String label = present ? this.builtIns.get(i).name() : "";
			Button button = Button.builder(Component.literal(label), b -> applyBuiltIn(index))
					.bounds(0, 0, this.biCellW, this.biCellH)
					.build();
			if (present) {
				button.setTooltip(Tooltip.create(Component.literal(
						"Apply the \"" + label + "\" preset to this server")));
			}
			button.visible = present;
			button.active = present;
			this.builtInButtons[i] = button;
			this.widgetSink.accept(button);
		}
	}

	private void createSaveRow() {
		this.nameBox = new EditBox(this.font, 0, 0, INNER - SAVE_BUTTON_W - 4, this.userSaveH,
				Component.literal("Preset name"));
		this.nameBox.setMaxLength(PresetStore.MAX_NAME_LENGTH);
		this.nameBox.setTooltip(Tooltip.create(Component.literal(
				"Name for the style you are about to save")));
		this.widgetSink.accept(this.nameBox);

		this.saveButton = Button.builder(Component.literal("Save"), b -> saveCurrent())
				.bounds(0, 0, SAVE_BUTTON_W, this.userSaveH)
				.build();
		this.saveButton.setTooltip(Tooltip.create(Component.literal(
				"Save the current style under this name.\n"
						+ "Saving an existing name overwrites it.")));
		this.widgetSink.accept(this.saveButton);
	}

	private void createUserRows() {
		for (int i = 0; i < this.rowButtons.length; i++) {
			final int row = i;
			Button apply = Button.builder(Component.literal(""), b -> applyRow(row))
					.bounds(0, 0, rowApplyWidth(), this.userRowH)
					.build();
			this.rowButtons[i] = apply;
			this.widgetSink.accept(apply);

			Button rename = Button.builder(Component.literal("R"), b -> renameRow(row))
					.bounds(0, 0, ROW_ACTION_W, this.userRowH)
					.build();
			rename.setTooltip(Tooltip.create(Component.literal(
					"Rename this preset to the name typed above")));
			this.renameButtons[i] = rename;
			this.widgetSink.accept(rename);

			Button duplicate = Button.builder(Component.literal("D"), b -> duplicateRow(row))
					.bounds(0, 0, ROW_ACTION_W, this.userRowH)
					.build();
			duplicate.setTooltip(Tooltip.create(Component.literal(
					"Save a copy of this preset under the name typed above")));
			this.duplicateButtons[i] = duplicate;
			this.widgetSink.accept(duplicate);

			Button delete = Button.builder(Component.literal("\u00d7"), b -> deleteRow(row))
					.bounds(0, 0, DELETE_W, this.userRowH)
					.build();
			delete.setTooltip(Tooltip.create(Component.literal("Delete this preset")));
			this.deleteButtons[i] = delete;
			this.widgetSink.accept(delete);
		}
	}

	/** Width of the row's apply button once the three action buttons took their share. */
	private static int rowApplyWidth() {
		return INNER - DELETE_W - 4 - (ROW_ACTION_W * 2 + ROW_ACTION_GAP * 2);
	}

	private void createPager() {
		this.prevButton = Button.builder(Component.literal("<"), b -> changePage(-1))
				.bounds(0, 0, PAGER_BUTTON_W, this.userPagerH)
				.build();
		this.prevButton.setTooltip(Tooltip.create(Component.literal("Previous page")));
		this.widgetSink.accept(this.prevButton);

		this.nextButton = Button.builder(Component.literal(">"), b -> changePage(1))
				.bounds(0, 0, PAGER_BUTTON_W, this.userPagerH)
				.build();
		this.nextButton.setTooltip(Tooltip.create(Component.literal("Next page")));
		this.widgetSink.accept(this.nextButton);
	}

	// ------------------------------------------------------------------
	// Actions
	// ------------------------------------------------------------------

	private void applyBuiltIn(int index) {
		if (index < 0 || index >= this.builtIns.size()) {
			return;
		}
		applyPreset(this.builtIns.get(index).style());
	}

	private void applyRow(int row) {
		String name = this.rowNames[row];
		if (name == null) {
			return;
		}
		for (PresetStore.Preset preset : this.store.userPresets()) {
			if (preset.name().equals(name)) {
				applyPreset(preset.style());
				return;
			}
		}
	}

	/**
	 * Merges a preset into the live working style.
	 *
	 * <p>The editor and the host screen both hold a reference to the same
	 * {@link ServerStyle} instance, so the preset is copied <em>into</em> it rather
	 * than replacing the reference &mdash; otherwise the screen would keep
	 * persisting the old object.</p>
	 */
	private void applyPreset(ServerStyle preset) {
		if (preset == null) {
			return;
		}
		this.style.copyFrom(preset);
		if (this.onStyleChanged != null) {
			this.onStyleChanged.run();
		}
	}

	private void saveCurrent() {
		String typed = this.nameBox == null ? "" : this.nameBox.getValue();
		String stored = this.store.save(typed, this.style);
		if (stored == null) {
			return;
		}
		this.store.saveAsync();
		if (this.nameBox != null) {
			this.nameBox.setValue("");
		}
		// Jump to the page holding the freshly saved preset so the user sees it.
		int index = indexOfUserPreset(stored);
		if (index >= 0) {
			this.page = index / this.userRows;
		}
		refreshUserRows();
	}

	private void deleteRow(int row) {
		String name = this.rowNames[row];
		if (name == null) {
			return;
		}
		if (this.store.delete(name)) {
			this.store.saveAsync();
			clampPage();
			refreshUserRows();
		}
	}

	/**
	 * Renames the row's preset to whatever is typed in the name field.
	 *
	 * <p>The name field doubles as the rename input: it is already the one place
	 * the player types a preset name, so a separate dialog would be redundant.</p>
	 */
	private void renameRow(int row) {
		String oldName = this.rowNames[row];
		if (oldName == null) {
			return;
		}
		String typed = this.nameBox == null ? "" : this.nameBox.getValue();
		String renamed = this.store.rename(oldName, typed);
		if (renamed == null) {
			return;
		}
		this.store.saveAsync();
		if (this.nameBox != null) {
			this.nameBox.setValue("");
		}
		int index = indexOfUserPreset(renamed);
		if (index >= 0) {
			this.page = index / this.userRows;
		}
		refreshUserRows();
	}

	/**
	 * Saves a copy of the row's preset under the typed name.
	 *
	 * <p>When the field is empty a free name is generated from the source name, so
	 * the button always does something useful.</p>
	 */
	private void duplicateRow(int row) {
		String sourceName = this.rowNames[row];
		if (sourceName == null) {
			return;
		}
		String typed = this.nameBox == null ? "" : this.nameBox.getValue();
		String target = PresetStore.sanitiseName(typed);
		if (target.isEmpty()) {
			target = this.store.suggestName(sourceName);
		}
		String created = this.store.duplicate(sourceName, target);
		if (created == null) {
			return;
		}
		this.store.saveAsync();
		if (this.nameBox != null) {
			this.nameBox.setValue("");
		}
		int index = indexOfUserPreset(created);
		if (index >= 0) {
			this.page = index / this.userRows;
		}
		refreshUserRows();
	}

	private void changePage(int delta) {
		int pages = pageCount();
		int next = this.page + delta;
		if (next < 0 || next >= pages) {
			return;
		}
		this.page = next;
		refreshUserRows();
	}

	private int indexOfUserPreset(String name) {
		List<PresetStore.Preset> presets = this.store.userPresets();
		for (int i = 0; i < presets.size(); i++) {
			if (presets.get(i).name().equals(name)) {
				return i;
			}
		}
		return -1;
	}

	private int pageCount() {
		int size = this.store.size();
		return Math.max(1, (size + this.userRows - 1) / this.userRows);
	}

	private void clampPage() {
		int pages = pageCount();
		if (this.page >= pages) {
			this.page = pages - 1;
		}
		if (this.page < 0) {
			this.page = 0;
		}
	}

	/** Rebinds the visible rows to the presets on the current page. */
	private void refreshUserRows() {
		clampPage();
		List<PresetStore.Preset> presets = this.store.userPresets();
		int base = this.page * this.userRows;
		for (int i = 0; i < this.rowButtons.length; i++) {
			int index = base + i;
			boolean present = i < this.userRows && index < presets.size();
			String name = present ? presets.get(index).name() : null;
			this.rowNames[i] = name;
			Button apply = this.rowButtons[i];
			Button delete = this.deleteButtons[i];
			Button rename = this.renameButtons[i];
			Button duplicate = this.duplicateButtons[i];
			if (apply != null) {
				apply.setMessage(Component.literal(present ? name : ""));
				apply.setTooltip(present
						? Tooltip.create(Component.literal("Apply \"" + name + "\" to this server"))
						: null);
			}
			if (delete != null) {
				delete.setTooltip(present
						? Tooltip.create(Component.literal("Delete \"" + name + "\""))
						: null);
			}
			if (rename != null) {
				rename.setTooltip(present
						? Tooltip.create(Component.literal("Rename \"" + name + "\" to the name typed above"))
						: null);
			}
			if (duplicate != null) {
				duplicate.setTooltip(present
						? Tooltip.create(Component.literal("Save a copy of \"" + name + "\" under the name typed above"))
						: null);
			}
		}
		applyVisibility();
	}

	// ------------------------------------------------------------------
	// Placement
	// ------------------------------------------------------------------

	/** Live anchor, shared with the editor panel. */
	public StyleEditorPanel.Anchor anchor() {
		return this.anchor;
	}

	/** True while the user wants the column open. */
	public boolean isVisible() {
		return this.requestedVisible;
	}

	public void setVisible(boolean visible) {
		this.requestedVisible = visible;
		this.active = false;
		applyVisibility();
	}

	/**
	 * Height the column would like when stacked under the editor panel, given the
	 * screen height and the editor's current height.
	 *
	 * @return {@link #FULL_HEIGHT}, {@link #COMPACT_HEIGHT}, {@link #TINY_HEIGHT},
	 *         or {@code -1} when even the tiny column cannot fit under the editor
	 */
	public int heightForStack(int editorY, int editorHeight) {
		int screenHeight = this.anchor.screenHeight();
		int margin = StyleEditorPanel.MARGIN;
		// Space actually left between the bottom of the editor and the screen edge.
		int available = screenHeight - margin - (editorY + editorHeight + GAP);
		if (available >= FULL_HEIGHT) {
			return FULL_HEIGHT;
		}
		if (available >= COMPACT_HEIGHT) {
			return COMPACT_HEIGHT;
		}
		if (available >= TINY_HEIGHT) {
			return TINY_HEIGHT;
		}
		return -1;
	}

	/** Layout tier that corresponds to a height returned by {@link #heightForStack}. */
	private static int modeForHeight(int height) {
		if (height == FULL_HEIGHT) {
			return StyleEditorPanel.MODE_FULL;
		}
		if (height == COMPACT_HEIGHT) {
			return StyleEditorPanel.MODE_COMPACT;
		}
		return StyleEditorPanel.MODE_TINY;
	}

	/**
	 * Tries to place the column to the <b>right</b> of the editor panel.
	 *
	 * <p>Uses the roomiest layout that fits vertically. The column is vertically
	 * clamped so it never leaves the screen.</p>
	 *
	 * @return true when the column was placed and is now visible
	 */
	public boolean tryPlaceRight(int editorX, int editorY, int editorHeight) {
		int screenWidth = this.anchor.screenWidth();
		int screenHeight = this.anchor.screenHeight();
		int margin = StyleEditorPanel.MARGIN;

		int x = editorX + StyleEditorPanel.WIDTH + GAP;
		if (x + WIDTH > screenWidth - margin) {
			return false;
		}

		int h = FULL_HEIGHT;
		if (editorY + h > screenHeight - margin) {
			h = COMPACT_HEIGHT;
		}
		if (editorY + h > screenHeight - margin) {
			h = TINY_HEIGHT;
		}
		if (editorY + h > screenHeight - margin) {
			return false;
		}
		int y = Math.max(margin, Math.min(editorY, screenHeight - margin - h));

		applyMode(modeForHeight(h));
		layout(x, y);
		return true;
	}

	/**
	 * Places the column directly <b>below</b> the editor panel.
	 *
	 * @param height one of {@link #FULL_HEIGHT}, {@link #COMPACT_HEIGHT} or
	 *               {@link #TINY_HEIGHT}
	 * @return true when the column was placed and is now visible
	 */
	public boolean placeBelow(int editorX, int editorY, int editorHeight, int height) {
		int screenWidth = this.anchor.screenWidth();
		int screenHeight = this.anchor.screenHeight();
		int margin = StyleEditorPanel.MARGIN;

		int y = editorY + editorHeight + GAP;
		if (editorX + WIDTH > screenWidth - margin || y + height > screenHeight - margin) {
			hideForSpace();
			return false;
		}
		applyMode(modeForHeight(height));
		layout(editorX, y);
		return true;
	}

	/** Hides the column because the host found no room for it. */
	public void hideForSpace() {
		this.dimsValid = false;
		applyVisibility();
	}

	private void layout(int originX, int originY) {
		this.dimsValid = true;
		place(this, originX, originY, WIDTH, this.height);
		int col = originX + PAD;

		for (int i = 0; i < this.builtInButtons.length; i++) {
			int cx = i % this.biCols;
			int cy = i / this.biCols;
			place(this.builtInButtons[i],
					col + cx * (this.biCellW + 4),
					originY + this.biGridY + cy * this.biPitch,
					this.biCellW, this.biCellH);
		}

		place(this.nameBox, col, originY + this.userSaveY,
				INNER - SAVE_BUTTON_W - 4, this.userSaveH);
		place(this.saveButton, col + INNER - SAVE_BUTTON_W, originY + this.userSaveY,
				SAVE_BUTTON_W, this.userSaveH);

		for (int i = 0; i < this.rowButtons.length; i++) {
			int rowY = originY + this.userRowsY + i * this.userPitch;
			place(this.rowButtons[i], col, rowY, rowApplyWidth(), this.userRowH);
			int actionX = col + rowApplyWidth() + ROW_ACTION_GAP;
			place(this.renameButtons[i], actionX, rowY, ROW_ACTION_W, this.userRowH);
			place(this.duplicateButtons[i], actionX + ROW_ACTION_W + ROW_ACTION_GAP, rowY,
					ROW_ACTION_W, this.userRowH);
			place(this.deleteButtons[i], col + INNER - DELETE_W, rowY, DELETE_W, this.userRowH);
		}

		place(this.prevButton, col, originY + this.userPagerY, PAGER_BUTTON_W, this.userPagerH);
		place(this.nextButton, col + INNER - PAGER_BUTTON_W, originY + this.userPagerY,
				PAGER_BUTTON_W, this.userPagerH);

		applyVisibility();
	}

	private static void place(AbstractWidget widget, int x, int y, int width, int height) {
		if (widget == null) {
			return;
		}
		widget.setX(x);
		widget.setY(y);
		widget.setWidth(width);
		widget.setHeight(height);
	}

	private void applyVisibility() {
		boolean shown = this.requestedVisible && this.dimsValid;
		this.visible = shown;
		this.active = false;

		for (int i = 0; i < this.builtInButtons.length; i++) {
			boolean present = i < this.builtIns.size();
			show(this.builtInButtons[i], shown && present);
		}
		show(this.nameBox, shown);
		show(this.saveButton, shown);

		int pages = pageCount();
		for (int i = 0; i < this.rowButtons.length; i++) {
			boolean present = i < this.userRows && this.rowNames[i] != null;
			show(this.rowButtons[i], shown && present);
			show(this.renameButtons[i], shown && present);
			show(this.duplicateButtons[i], shown && present);
			show(this.deleteButtons[i], shown && present);
		}
		show(this.prevButton, shown && pages > 1 && this.page > 0);
		show(this.nextButton, shown && pages > 1 && this.page < pages - 1);
	}

	private static void show(AbstractWidget widget, boolean visible) {
		if (widget == null) {
			return;
		}
		widget.visible = visible;
		widget.active = visible;
	}

	// ------------------------------------------------------------------
	// Rendering
	// ------------------------------------------------------------------

	@Override
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		int left = getX();
		int top = getY();

		drawWindow(graphics, left, top, this.builtinHeight, "BUILT-IN PRESETS");
		drawWindow(graphics, left, top + this.userTop, this.height - this.userTop,
				"YOUR PRESETS");

		if (!this.compact) {
			sectionHeader(graphics, left + PAD, top + 15, "READY-MADE");
			sectionHeader(graphics, left + PAD, top + this.userTop + 15, "SAVE CURRENT");
			sectionHeader(graphics, left + PAD, top + this.userRowsY - 10, "SAVED");
		}

		// Page indicator, centred between the two pager buttons.
		int pages = pageCount();
		String pageText = "Page " + (this.page + 1) + "/" + pages;
		int textWidth = this.font.width(pageText);
		graphics.drawString(this.font, Component.literal(pageText),
				left + (WIDTH - textWidth) / 2, top + this.userPagerY + 1, 0xFFB0B0B0, false);

		if (this.store.size() == 0) {
			graphics.drawString(this.font, Component.literal("Nothing saved yet"),
					left + PAD, top + this.userRowsY + 2, 0xFF707070, false);
		}
	}

	/** Draws one framed window with its own header bar. */
	private void drawWindow(GuiGraphics graphics, int left, int top, int windowHeight,
							String title) {
		int right = left + WIDTH;
		int bottom = top + windowHeight;

		graphics.fill(left, top, right, bottom, 0xF0101010);
		graphics.fill(left, top, right, top + this.headerH, 0xFF2A2A2A);
		graphics.fill(left, top + this.headerH, right, top + this.headerH + 1, 0xFF000000);
		// 1.21.9 removed GuiGraphics.renderOutline, so the frame is four fills.
		graphics.fill(left, top, right, top + 1, 0xFF000000);
		graphics.fill(left, top + windowHeight - 1, right, top + windowHeight, 0xFF000000);
		graphics.fill(left, top, left + 1, top + windowHeight, 0xFF000000);
		graphics.fill(right - 1, top, right, top + windowHeight, 0xFF000000);
		graphics.fill(left + 1, top + 1, right - 1, top + 2, 0xFF5A5A5A);

		graphics.drawString(this.font, Component.literal(title), left + PAD, top + 3,
				0xFFFFFFFF, true);
	}

	private void sectionHeader(GuiGraphics graphics, int x, int y, String label) {
		graphics.drawString(this.font, Component.literal(label), x, y, 0xFF9A9A9A, false);
		int labelWidth = this.font.width(label);
		int lineX = x + labelWidth + 4;
		int lineY = y + 4;
		graphics.fill(lineX, lineY, getX() + WIDTH - PAD, lineY + 1, 0x30FFFFFF);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
		defaultButtonNarrationText(narrationElementOutput);
	}
}
