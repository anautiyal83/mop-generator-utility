package com.nokia.mopgen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One unit (node or group) in a {@link MopSummaryDocument}.
 *
 * <h3>Design</h3>
 * <p>{@code unitInfo} is a generic {@code Map<String, String>} that mirrors
 * {@link NodeData#getNodeInfo()} exactly — no hardcoded field names.
 * Typical keys: {@code node}, {@code niamID}, {@code crGroup}, {@code group}, {@code email}.
 *
 * <p>The {@link #activity} and {@link #rollback} lists hold the ordered MOP sections
 * for the forward and rollback phases respectively.
 *
 * <h3>HTML rendering</h3>
 * <p>Each unit is rendered as a collapsible {@code <details>/<summary>} block in the
 * approval summary report.  The {@code summary} line is built from {@code unitInfo}
 * entries whose keys are configured in {@link MopConfig#getJsonMapping()}.
 */
public class MopSummaryUnit {

    /**
     * Generic scalar info about this unit.
     *
     * <p>Same content as {@link NodeData#getNodeInfo()} — populated directly from it
     * in the JSON path.
     *
     * <p>Typical keys: {@code node}, {@code niamID}.
     */
    private final Map<String, String> unitInfo = new LinkedHashMap<>();

    /** Forward (activity) MOP sections in execution order. */
    private final List<MopSummarySection> activity = new ArrayList<>();

    /** Rollback MOP sections in rollback execution order. */
    private final List<MopSummarySection> rollback = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Population helpers
    // -------------------------------------------------------------------------

    /** Adds or replaces a scalar unit-info entry. */
    public void addUnitInfo(String key, String value) {
        if (key != null && value != null) unitInfo.put(key, value);
    }

    /** Bulk-adds all entries from the given map. */
    public void addAllUnitInfo(Map<String, String> entries) {
        if (entries != null) unitInfo.putAll(entries);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Returns an unmodifiable view of all unit-info entries. */
    public Map<String, String> getUnitInfo() {
        return Collections.unmodifiableMap(unitInfo);
    }

    /** Returns the activity sections list (mutable — for population by {@link MopGenerator}). */
    public List<MopSummarySection> getActivity() { return activity; }

    /** Returns the rollback sections list (mutable — for population by {@link MopGenerator}). */
    public List<MopSummarySection> getRollback() { return rollback; }

    // -------------------------------------------------------------------------
    // Convenience
    // -------------------------------------------------------------------------

    /**
     * Returns the value of a specific unit-info key, or {@code defaultValue} if absent.
     */
    public String getInfo(String key, String defaultValue) {
        return unitInfo.getOrDefault(key, defaultValue);
    }

    /**
     * Returns {@code true} when there are activity or rollback sections.
     */
    public boolean hasSections() {
        return !activity.isEmpty() || !rollback.isEmpty();
    }

    @Override
    public String toString() {
        return "MopSummaryUnit{unitInfo=" + unitInfo + "}";
    }
}
