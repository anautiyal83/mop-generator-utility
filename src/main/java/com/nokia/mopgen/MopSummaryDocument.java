package com.nokia.mopgen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Root data model for the MOP approval summary report.
 *
 * <h3>Design</h3>
 * <p>Fully generic — no hardcoded scope fields (nodeType, crGroup, group, etc.).
 * All document-level context lives in {@link #metadata} as a plain
 * {@code Map<String, String>}, so the same class serves CRGROUP, GROUP, and NODE
 * scopes without any code changes.
 *
 * <h3>Lifecycle</h3>
 * <p>{@link MopGenerator} populates this document during summary generation.
 * Once populated, it is passed to
 * {@link GroupApprovalMopGenerator#generate(MopSummaryDocument, String)} to produce
 * the HTML or DOCX approval report.
 *
 * <h3>Typical metadata keys</h3>
 * <table border="1">
 *   <tr><th>Key</th><th>Example value</th></tr>
 *   <tr><td>nodeType</td><td>MRF</td></tr>
 *   <tr><td>activity</td><td>ANNOUNCEMENT_LOADING</td></tr>
 *   <tr><td>crGroup</td><td>CR001</td></tr>
 *   <tr><td>group</td><td>A</td></tr>
 *   <tr><td>generated</td><td>2026-04-10 14:30</td></tr>
 * </table>
 */
public class MopSummaryDocument {

    /**
     * Document-level metadata displayed in the report header.
     * All key-value pairs are rendered as a metadata table in the approval report.
     */
    private final Map<String, String> metadata = new LinkedHashMap<>();

    /**
     * Ordered list of units (nodes or groups) covered by this document.
     * Each unit produces one collapsible section in the HTML report.
     */
    private final List<MopSummaryUnit> units = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Population helpers
    // -------------------------------------------------------------------------

    /**
     * Adds or replaces a document-level metadata entry.
     *
     * @param key   metadata key (e.g. {@code "crGroup"})
     * @param value metadata value (e.g. {@code "CR001"}); {@code null} values are ignored
     */
    public void addMeta(String key, String value) {
        if (key != null && value != null) metadata.put(key, value);
    }

    /**
     * Appends a unit to the document.
     *
     * @param unit the unit to add; {@code null} is ignored
     */
    public void addUnit(MopSummaryUnit unit) {
        if (unit != null) units.add(unit);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Returns an unmodifiable view of all document-level metadata entries. */
    public Map<String, String> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    /** Returns the mutable units list. */
    public List<MopSummaryUnit> getUnits() { return units; }

    // -------------------------------------------------------------------------
    // Convenience
    // -------------------------------------------------------------------------

    /**
     * Returns the value of a specific metadata key, or {@code defaultValue} if absent.
     */
    public String getMeta(String key, String defaultValue) {
        return metadata.getOrDefault(key, defaultValue);
    }

    /**
     * Builds a display title from nodeType and activity metadata.
     * Falls back gracefully when either key is absent.
     */
    public String buildTitle() {
        String nodeType = metadata.getOrDefault("nodeType", "");
        String activity = metadata.getOrDefault("activity", "");
        if (!nodeType.isEmpty() && !activity.isEmpty()) return nodeType + "_" + activity;
        if (!nodeType.isEmpty()) return nodeType;
        return activity;
    }

    @Override
    public String toString() {
        return "MopSummaryDocument{metadata=" + metadata.keySet()
                + ", units=" + units.size() + "}";
    }
}
