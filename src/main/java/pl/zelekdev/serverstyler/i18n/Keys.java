package pl.zelekdev.serverstyler.i18n;

/**
 * Every translation key used by the mod.
 *
 * <p>Centralised so a typo is a compile error rather than a silently missing
 * string, and so the bundled JSON files can be checked against one list.</p>
 *
 * <p>Keys are grouped by the screen that uses them. Values that are pure symbols
 * ({@code "+"}, {@code "-"}, {@code "B"}, {@code "L"}) are still keys: a
 * language may legitimately want a different glyph, and keeping them here means
 * the JSON files are a complete description of the UI.</p>
 */
public final class Keys {

	private Keys() {
	}

	// ------------------------------------------------------------------
	// Style editor panel
	// ------------------------------------------------------------------

	public static final String PANEL_TITLE = "panel.title";

	public static final String HEX_LABEL = "hex.label";
	public static final String HEX_TOOLTIP = "hex.tooltip";

	public static final String MODE_LABEL = "mode.label";
	public static final String MODE_TOOLTIP = "mode.tooltip";
	public static final String MODE_GRADIENT = "mode.gradient";
	public static final String MODE_SOLID = "mode.solid";

	public static final String BOLD_LABEL = "bold.label";
	public static final String BOLD_TOOLTIP = "bold.tooltip";
	public static final String ITALIC_LABEL = "italic.label";
	public static final String ITALIC_TOOLTIP = "italic.tooltip";
	public static final String MINICAPS_TOOLTIP = "minicaps.tooltip";

	public static final String ENABLED_ON = "enabled.on";
	public static final String ENABLED_OFF = "enabled.off";
	public static final String ENABLED_TOOLTIP = "enabled.tooltip";

	public static final String STOP_ADD_LABEL = "stop.add.label";
	public static final String STOP_ADD_TOOLTIP = "stop.add.tooltip";
	public static final String STOP_REMOVE_LABEL = "stop.remove.label";
	public static final String STOP_REMOVE_TOOLTIP = "stop.remove.tooltip";

	public static final String ALIGN_LEFT_LABEL = "align.left.label";
	public static final String ALIGN_LEFT_TOOLTIP = "align.left.tooltip";
	public static final String ALIGN_CENTER_LABEL = "align.center.label";
	public static final String ALIGN_CENTER_TOOLTIP = "align.center.tooltip";
	public static final String ALIGN_RIGHT_LABEL = "align.right.label";
	public static final String ALIGN_RIGHT_TOOLTIP = "align.right.tooltip";

	public static final String UNDO_LABEL = "undo.label";
	public static final String UNDO_TOOLTIP = "undo.tooltip";
	public static final String REDO_LABEL = "redo.label";
	public static final String REDO_TOOLTIP = "redo.tooltip";
	public static final String RESET_LABEL = "reset.label";
	public static final String RESET_TOOLTIP = "reset.tooltip";

	public static final String SECTION_COLOUR = "section.colour";
	public static final String SECTION_STYLE = "section.style";
	public static final String SECTION_LAYOUT = "section.layout";

	public static final String PREVIEW_GRADIENT = "preview.gradient";
	public static final String PREVIEW_SOLID = "preview.solid";

	public static final String SLIDER_HUE = "slider.hue";
	public static final String SLIDER_OPACITY = "slider.opacity";

	// ------------------------------------------------------------------
	// Preset panel
	// ------------------------------------------------------------------

	public static final String PRESETS_TITLE = "presets.title";
	public static final String PRESETS_APPLY_TOOLTIP = "presets.apply.tooltip";
	public static final String PRESETS_NAME_LABEL = "presets.name.label";
	public static final String PRESETS_NAME_TOOLTIP = "presets.name.tooltip";
	public static final String PRESETS_SAVE_LABEL = "presets.save.label";
	public static final String PRESETS_SAVE_TOOLTIP = "presets.save.tooltip";
	public static final String PRESETS_RENAME_LABEL = "presets.rename.label";
	public static final String PRESETS_RENAME_TOOLTIP = "presets.rename.tooltip";
	public static final String PRESETS_DUPLICATE_LABEL = "presets.duplicate.label";
	public static final String PRESETS_DUPLICATE_TOOLTIP = "presets.duplicate.tooltip";
	public static final String PRESETS_DELETE_LABEL = "presets.delete.label";
	public static final String PRESETS_DELETE_TOOLTIP = "presets.delete.tooltip";
	public static final String PRESETS_PREV_LABEL = "presets.prev.label";
	public static final String PRESETS_PREV_TOOLTIP = "presets.prev.tooltip";
	public static final String PRESETS_NEXT_LABEL = "presets.next.label";
	public static final String PRESETS_NEXT_TOOLTIP = "presets.next.tooltip";

	public static final String PRESETS_ROW_APPLY_TOOLTIP = "presets.row.apply.tooltip";
	public static final String PRESETS_ROW_DELETE_TOOLTIP = "presets.row.delete.tooltip";
	public static final String PRESETS_ROW_RENAME_TOOLTIP = "presets.row.rename.tooltip";
	public static final String PRESETS_ROW_DUPLICATE_TOOLTIP = "presets.row.duplicate.tooltip";

	public static final String PRESETS_WINDOW_BUILTIN = "presets.window.builtin";
	public static final String PRESETS_WINDOW_USER = "presets.window.user";
	public static final String PRESETS_SECTION_READY = "presets.section.ready";
	public static final String PRESETS_SECTION_SAVE = "presets.section.save";
	public static final String PRESETS_SECTION_SAVED = "presets.section.saved";
	public static final String PRESETS_PAGE = "presets.page";
	public static final String PRESETS_EMPTY = "presets.empty";

	// ------------------------------------------------------------------
	// Trigger button
	// ------------------------------------------------------------------

	public static final String TRIGGER_TOOLTIP = "trigger.tooltip";

	// ------------------------------------------------------------------
	// Config screen (ModMenu)
	// ------------------------------------------------------------------

	public static final String CONFIG_TITLE = "config.title";
	public static final String CONFIG_LANGUAGE = "config.language";
	public static final String CONFIG_LANGUAGE_TOOLTIP = "config.language.tooltip";
	public static final String CONFIG_DONE = "config.done";
	public static final String CONFIG_HINT_FILES = "config.hint.files";
	public static final String CONFIG_HINT_MANUAL = "config.hint.manual";
}
