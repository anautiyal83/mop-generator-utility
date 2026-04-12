package com.nokia.mopgen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds data extracted from one unit (node/group) in the unified JSON produced by ciq-processor.
 *
 * <p>Two distinct categories of data are stored:
 * <ol>
 *   <li><b>nodeInfo</b>  — scalar fields that identify and describe the node:
 *       node name, NEID, crGroup, email, etc.  All values are plain strings.</li>
 *   <li><b>configData</b> — structured configuration data whose shape is determined
 *       by the {@code *_json-output.yaml} template.  Values are generic {@code Object}
 *       instances that preserve the original JSON tree:
 *       <ul>
 *         <li>{@code List<Map<String, Object>>} — a flat table (array of row objects)</li>
 *         <li>{@code Map<String, Object>}        — a nested configuration object</li>
 *         <li>{@code String}                     — an additional scalar encountered
 *             while walking the config tree</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>The class is populated by {@link MopJsonReader} during JSON extraction and consumed
 * by {@link MopGenerator} during MOP generation.
 */
public class NodeData {

    /** Scalar fields from the node element — node name, NEID, crGroup, email, etc. */
    private final Map<String, String> nodeInfo = new LinkedHashMap<>();

    /**
     * Structured configuration data from the node element.
     *
     * <p>The concrete type stored under each key depends on the JSON tree shape:
     * <ul>
     *   <li>{@code List<Map<String, Object>>} — a table / array of row objects</li>
     *   <li>{@code Map<String, Object>}        — a nested config object</li>
     *   <li>{@code String}                     — a scalar that was placed here because
     *       its config-level sibling had {@code _each} (i.e. the enclosing object is
     *       a table-row template)</li>
     * </ul>
     */
    private final Map<String, Object> configData = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Population helpers (called by MopJsonReader)
    // -------------------------------------------------------------------------

    /** Adds a scalar node-info entry (e.g. {@code node → "MRF1"}). */
    public void addNodeInfo(String key, String value) {
        nodeInfo.put(key, value);
    }

    /**
     * Adds a config-data entry.  The value may be a {@code List}, a {@code Map},
     * or a {@code String} — whatever shape was present in the JSON.
     */
    public void addConfigData(String key, Object value) {
        configData.put(key, value);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Returns an unmodifiable view of all scalar node-info entries. */
    public Map<String, String> getNodeInfo() {
        return Collections.unmodifiableMap(nodeInfo);
    }

    /**
     * Returns an unmodifiable view of all config-data entries.
     * Values may be {@code List}, {@code Map}, or {@code String}.
     */
    public Map<String, Object> getConfigData() {
        return Collections.unmodifiableMap(configData);
    }

    // -------------------------------------------------------------------------
    // Convenience accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the config-data value for {@code key} as a list of row maps
     * (i.e. treats it as a table).
     *
     * <p>Returns an empty list when the key is absent or the stored value is
     * not a {@code List}.
     *
     * @param key the config-data key (e.g. {@code "tableData"})
     * @return the table rows, never {@code null}
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getRows(String key) {
        Object val = configData.get(key);
        if (val instanceof List) {
            return (List<Map<String, Object>>) val;
        }
        return Collections.emptyList();
    }

    /**
     * Returns all config-data entries whose value is a {@code List} (i.e. tables / arrays).
     * Insertion order is preserved.
     *
     * @return map of table name → list of row objects
     */
    @SuppressWarnings("unchecked")
    public Map<String, List<Map<String, Object>>> getAllTables() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : configData.entrySet()) {
            if (e.getValue() instanceof List) {
                result.put(e.getKey(), (List<Map<String, Object>>) e.getValue());
            }
        }
        return result;
    }

    /**
     * Returns the config-data value for {@code key} as a nested config object.
     *
     * <p>Returns an empty map when the key is absent or the stored value is not a {@code Map}.
     *
     * @param key the config-data key (e.g. {@code "featureConfig"})
     * @return the nested object, never {@code null}
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getNestedObject(String key) {
        Object val = configData.get(key);
        if (val instanceof Map) {
            return (Map<String, Object>) val;
        }
        return Collections.emptyMap();
    }

    /**
     * Convenience: returns all table names (config-data keys whose value is a {@code List}).
     */
    public List<String> getTableNames() {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Object> e : configData.entrySet()) {
            if (e.getValue() instanceof List) names.add(e.getKey());
        }
        return names;
    }

    @Override
    public String toString() {
        return "NodeData{nodeInfo=" + nodeInfo.keySet()
                + ", configData=" + configData.keySet() + "}";
    }
}
