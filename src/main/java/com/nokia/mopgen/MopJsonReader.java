package com.nokia.mopgen;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a unified JSON file produced by ciq-processor and extracts per-node data
 * using the {@code *_json-output.yaml} config as a structural guide.
 *
 * <h3>JSON structure</h3>
 * <p>The unified JSON contains data for every CR / group / node in a single file.
 * The nesting depth is determined by the {@code data} section of the json-output config:
 *
 * <pre>
 * {                                    &lt;─ root object
 *   "nodeType": "MRF",                 &lt;─ root scalar
 *   "crs": [                           &lt;─ outer _each level  (CRs)
 *     {
 *       "crGroup": "CR001",            &lt;─ CR-level scalar
 *       "nodes": [                     &lt;─ inner _each level  (nodes)
 *         {
 *           "node":   "MRF1",          &lt;─ node-level scalar → nodeInfo
 *           "niamID": "mrf1-neid",     &lt;─ node-level scalar → nodeInfo
 *           "tableData": [             &lt;─ table array        → configData
 *             { "INPUT_FILE": "...", "Action": "CREATE" }
 *           ]
 *         }
 *       ]
 *     }
 *   ]
 * }
 * </pre>
 *
 * <h3>Scope filtering</h3>
 * <p>The caller supplies a {@code scopeFilter} map built from CLI parameters
 * (e.g. {@code {crGroup: "CR001"}} for {@code --crgroup CR001}).
 * The reader walks the config tree level by level:
 * <ul>
 *   <li>At each {@code _each} level it checks whether any filter key matches a scalar
 *       field in the JSON array's elements.</li>
 *   <li>If yes → <em>filter level</em>: narrow to the matching element, remove the
 *       consumed key, recurse deeper.</li>
 *   <li>If no → <em>nodes level</em>: collect every element as a {@link NodeData}.</li>
 * </ul>
 *
 * <p>This handles arbitrary nesting depths and is not tied to a fixed CRGROUP → GROUP →
 * NODE hierarchy.
 */
public class MopJsonReader {

    private static final Logger log = LoggerFactory.getLogger(MopJsonReader.class);

    private final ObjectMapper mapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    /**
     * Reads the unified JSON file and returns one {@link NodeData} per node that
     * matches the supplied scope filter.
     *
     * @param jsonFilePath      path to the unified JSON file
     *                          (e.g. {@code MRF_ANNOUNCEMENT_LOADING.json})
     * @param jsonOutputConfig  raw map loaded from {@code *_json-output.yaml}
     * @param scopeFilter       filter to apply at the appropriate nesting level;
     *                          e.g. {@code {crGroup: "CR001"}} narrows to that CR.
     *                          Pass an empty map to collect all nodes.
     * @return list of {@link NodeData}, one per node; never {@code null}
     * @throws IOException if the JSON file cannot be read or is malformed
     */
    public List<NodeData> read(String jsonFilePath,
                                Map<String, Object> jsonOutputConfig,
                                Map<String, String> scopeFilter) throws IOException {

        File file = new File(jsonFilePath);
        if (!file.exists()) {
            throw new IOException("JSON file not found: " + jsonFilePath);
        }

        Map<String, Object> json = mapper.readValue(file,
                new TypeReference<Map<String, Object>>() {});

        Object dataObj = jsonOutputConfig.get("data");
        if (!(dataObj instanceof Map)) {
            throw new IOException("json-output config must contain a 'data' map section");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> dataConfig = (Map<String, Object>) dataObj;

        Map<String, String> remainingFilter =
                scopeFilter == null ? Collections.emptyMap() : new LinkedHashMap<>(scopeFilter);

        List<NodeData> result = walk(json, dataConfig, remainingFilter);
        log.info("Extracted {} node(s) from {} with filter {}",
                result.size(), file.getName(), scopeFilter);
        return result;
    }

    // -------------------------------------------------------------------------
    // Recursive walker
    // -------------------------------------------------------------------------

    /**
     * Walks the config tree and the corresponding JSON object simultaneously.
     *
     * <p>At each level the method looks for the first key whose config value is a map
     * containing {@code _each} (indicating an array in the JSON).  It then decides:
     * <ul>
     *   <li><b>Filter level</b> — a key in {@code remainingFilter} matches a scalar
     *       field found in the JSON array's elements.  The matching element is selected
     *       and the walk continues recursively inside it.</li>
     *   <li><b>Nodes level</b> — no filter key matches at this level.  Every array
     *       element is collected as a {@link NodeData} and returned.</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private List<NodeData> walk(Map<String, Object> jsonObject,
                                 Map<String, Object> configLevel,
                                 Map<String, String> remainingFilter) {

        for (Map.Entry<String, Object> entry : configLevel.entrySet()) {
            String key = entry.getKey();
            if ("_each".equals(key)) continue;

            Object configValue = entry.getValue();
            if (!(configValue instanceof Map)) continue;

            Map<String, Object> childConfig = (Map<String, Object>) configValue;
            if (!childConfig.containsKey("_each")) continue;

            // Found an _each level — get the corresponding JSON array
            Object jsonValue = jsonObject.get(key);
            if (!(jsonValue instanceof List)) {
                log.debug("Expected JSON array for key '{}' but found {}; skipping",
                        key, jsonValue == null ? "null" : jsonValue.getClass().getSimpleName());
                continue;
            }
            List<Object> jsonArray = (List<Object>) jsonValue;

            // Determine whether the scope filter applies at this level
            String filterKey = findFilterKeyAtLevel(jsonArray, remainingFilter);

            if (filterKey != null) {
                // ── FILTER LEVEL ──────────────────────────────────────────────
                String filterValue = remainingFilter.get(filterKey);
                Map<String, String> newFilter = new LinkedHashMap<>(remainingFilter);
                newFilter.remove(filterKey);

                // Collect ALL elements that match the filter value
                List<Object> matched = new ArrayList<>();
                for (Object element : jsonArray) {
                    if (!(element instanceof Map)) continue;
                    Map<String, Object> elementMap = (Map<String, Object>) element;
                    Object elemVal = elementMap.get(filterKey);
                    if (elemVal != null && filterValue.equals(String.valueOf(elemVal))) {
                        matched.add(element);
                    }
                }

                if (matched.isEmpty()) {
                    log.warn("No element matched filter {}='{}' in array '{}'",
                            filterKey, filterValue, key);
                    return Collections.emptyList();
                }

                // Decide: are matched elements the leaf nodes, or scope containers?
                // If childConfig has no deeper structural _each level (a child _each
                // whose items themselves contain another _each), the matched elements
                // ARE the nodes → extract directly.
                // Otherwise they are scope containers → recurse into each.
                if (!hasDeepStructuralLevel(childConfig)) {
                    log.debug("Filter level '{}': {} match(es) for {}={} — extracting as nodes",
                            key, matched.size(), filterKey, filterValue);
                    return extractNodes(matched, childConfig);
                }

                log.debug("Filter level '{}': {} match(es) for {}={} — recursing deeper",
                        key, matched.size(), filterKey, filterValue);
                List<NodeData> result = new ArrayList<>();
                for (Object element : matched) {
                    result.addAll(walk((Map<String, Object>) element, childConfig, newFilter));
                }
                return result;

            } else {
                // ── NODES LEVEL ───────────────────────────────────────────────
                log.debug("Nodes level '{}': collecting {} element(s)", key, jsonArray.size());
                return extractNodes(jsonArray, childConfig);
            }
        }

        return Collections.emptyList();
    }

    // -------------------------------------------------------------------------
    // Node extraction
    // -------------------------------------------------------------------------

    /**
     * Extracts one {@link NodeData} per element in the JSON array.
     *
     * <p>For each element:
     * <ul>
     *   <li>String / number values → {@link NodeData#addNodeInfo(String, String)}</li>
     *   <li>List / Map values      → {@link NodeData#addConfigData(String, Object)}</li>
     * </ul>
     *
     * <p>The config level is used to determine which keys are expected so that only
     * declared fields are extracted (unknown extra fields in the JSON are ignored).
     */
    @SuppressWarnings("unchecked")
    private List<NodeData> extractNodes(List<Object> jsonArray,
                                         Map<String, Object> configLevel) {
        List<NodeData> result = new ArrayList<>();

        for (Object element : jsonArray) {
            if (!(element instanceof Map)) continue;
            Map<String, Object> elementMap = (Map<String, Object>) element;
            NodeData nodeData = new NodeData();

            for (Map.Entry<String, Object> configEntry : configLevel.entrySet()) {
                String configKey = configEntry.getKey();
                if ("_each".equals(configKey)) continue;

                Object jsonVal = elementMap.get(configKey);
                if (jsonVal == null) continue;

                if (jsonVal instanceof List || jsonVal instanceof Map) {
                    // Complex value → configData (tables or nested objects)
                    nodeData.addConfigData(configKey, jsonVal);
                } else {
                    // Scalar value → nodeInfo
                    nodeData.addNodeInfo(configKey, String.valueOf(jsonVal));
                }
            }

            result.add(nodeData);
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Filter-key detection
    // -------------------------------------------------------------------------

    /**
     * Determines whether any key in {@code filter} appears as a scalar field in the
     * first element of {@code jsonArray}.
     *
     * @return the matching filter key, or {@code null} if none match
     */
    @SuppressWarnings("unchecked")
    private String findFilterKeyAtLevel(List<Object> jsonArray,
                                         Map<String, String> filter) {
        if (filter.isEmpty() || jsonArray.isEmpty()) return null;

        Object first = jsonArray.get(0);
        if (!(first instanceof Map)) return null;

        Map<String, Object> firstMap = (Map<String, Object>) first;
        for (String filterKey : filter.keySet()) {
            Object val = firstMap.get(filterKey);
            // Only match on scalar (String/Number) fields — ignore arrays/objects
            if (val != null && !(val instanceof List) && !(val instanceof Map)) {
                return filterKey;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Structural depth check
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when {@code configLevel} contains a child config entry
     * whose value is a Map with {@code _each} AND that child's own entries include
     * another Map with {@code _each}.
     *
     * <p>This distinguishes a <em>scope-container</em> level (e.g. CR → nodes, where
     * the node config itself has a nested table {@code _each}) from a <em>leaf-node</em>
     * level (e.g. a flat nodes array whose child config only has table-level {@code _each}
     * entries with no further nesting).
     *
     * <p>Used by {@link #walk} to decide whether filter-matched elements should be
     * extracted directly as {@link NodeData} objects or recursed into.
     */
    @SuppressWarnings("unchecked")
    private boolean hasDeepStructuralLevel(Map<String, Object> configLevel) {
        for (Map.Entry<String, Object> entry : configLevel.entrySet()) {
            if ("_each".equals(entry.getKey())) continue;
            if (!(entry.getValue() instanceof Map)) continue;

            Map<String, Object> childConfig = (Map<String, Object>) entry.getValue();
            if (!childConfig.containsKey("_each")) continue;

            // This child has _each — check if any of ITS children also have _each
            for (Map.Entry<String, Object> grandEntry : childConfig.entrySet()) {
                if ("_each".equals(grandEntry.getKey())) continue;
                if (!(grandEntry.getValue() instanceof Map)) continue;

                Map<String, Object> grandChildConfig = (Map<String, Object>) grandEntry.getValue();
                if (grandChildConfig.containsKey("_each")) return true;
            }
        }
        return false;
    }
}
