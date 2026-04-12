package com.nokia.mopgen;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads {@link MopConfig} from an externally-provided YAML template file.
 *
 * <h3>Include / reuse mechanism</h3>
 * A template may declare an {@code includes} list at the top level.
 * Each entry is a path (relative to the template file's directory) to another
 * YAML file that is loaded first as the base config.  The current file's keys
 * are then deep-merged on top, so only the keys you want to override need to
 * be repeated.
 *
 * <pre>
 * # MRF_ANNOUNCEMENT_LOADING_MOP-Template.yaml
 * includes:
 *   - "MRF_common_blocks.yaml"   # defines preNodeHealthCheck, rollback, etc.
 *
 * activity:
 *   execution:
 *     - name: "LOAD_ANNOUNCEMENT_FILES"
 *       ...
 * </pre>
 *
 * Included files may themselves include further files (recursive).
 * Returns an all-defaults {@link MopConfig} when {@code path} is null.
 */
public class MopConfigLoader {

    public MopConfig load(String path) throws IOException {
        if (path == null) {
            return new MopConfig();
        }
        Map<String, Object> merged = loadRaw(new File(path).getAbsoluteFile());
        return fromMap(merged);
    }

    // -------------------------------------------------------------------------
    // Raw YAML loading with include resolution
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadRaw(File file) throws IOException {
        Yaml yaml = new Yaml();
        Map<String, Object> raw;
        try (InputStream in = new FileInputStream(file)) {
            raw = (Map<String, Object>) yaml.load(in);
        }
        if (raw == null) raw = new LinkedHashMap<>();

        // Pull out includes before merging so it doesn't end up in the config map
        List<String> includes = (List<String>) raw.remove("includes");

        if (includes != null && !includes.isEmpty()) {
            Map<String, Object> base = new LinkedHashMap<>();
            File dir = file.getParentFile();
            for (String include : includes) {
                File includeFile = new File(dir, include);
                Map<String, Object> included = loadRaw(includeFile.getAbsoluteFile());
                base = deepMerge(base, included);
            }
            // Current template overrides the merged includes
            raw = deepMerge(base, raw);
        }

        return raw;
    }

    /**
     * Deep-merge {@code override} on top of {@code base}.
     * When both values are Maps the merge recurses; otherwise {@code override} wins.
     * Returns a new map; neither input is mutated.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> entry : override.entrySet()) {
            String key = entry.getKey();
            Object overrideVal = entry.getValue();
            Object baseVal = result.get(key);
            if (baseVal instanceof Map && overrideVal instanceof Map) {
                result.put(key, deepMerge(
                        (Map<String, Object>) baseVal,
                        (Map<String, Object>) overrideVal));
            } else {
                result.put(key, overrideVal);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Map → MopConfig
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private MopConfig fromMap(Map<String, Object> raw) {
        MopConfig cfg = new MopConfig();
        if (raw == null) return cfg;

        if (raw.containsKey("mopApprovalFormatType"))
            cfg.setMopApprovalFormatType((String) raw.get("mopApprovalFormatType"));
        if (raw.containsKey("jsonMapping"))
            cfg.setJsonMapping(toStringMap((Map<String, Object>) raw.get("jsonMapping")));
        if (raw.containsKey("preNodeHealthCheck"))
            cfg.setPreNodeHealthCheck(parseActivityList(raw.get("preNodeHealthCheck")));
        if (raw.containsKey("backup"))
            cfg.setBackup(parseActivityList(raw.get("backup")));
        if (raw.containsKey("activity"))
            cfg.setActivity(parseActivitySectionConfig(
                    (Map<String, Object>) raw.get("activity")));
        if (raw.containsKey("postNodeHealthCheck"))
            cfg.setPostNodeHealthCheck(parseActivityList(raw.get("postNodeHealthCheck")));
        if (raw.containsKey("rollback"))
            cfg.setRollback(parseRollbackConfig((Map<String, Object>) raw.get("rollback")));
        if (raw.containsKey("constants"))
            cfg.setConstants(toStringMap((Map<String, Object>) raw.get("constants")));

        return cfg;
    }

    /**
     * Accepts either a YAML list of activity maps or a single activity map.
     * A single map is wrapped in a one-element list for uniform handling.
     */
    @SuppressWarnings("unchecked")
    private List<ActivityConfig> parseActivityList(Object value) {
        List<ActivityConfig> result = new ArrayList<>();
        if (value instanceof List) {
            for (Map<String, Object> item : (List<Map<String, Object>>) value) {
                result.add(parseActivityConfig(item));
            }
        } else if (value instanceof Map) {
            result.add(parseActivityConfig((Map<String, Object>) value));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private ActivityConfig parseActivityConfig(Map<String, Object> raw) {
        ActivityConfig ac = new ActivityConfig();
        if (raw == null) return ac;
        if (raw.containsKey("name"))        ac.setName((String) raw.get("name"));
        if (raw.containsKey("description")) ac.setDescription((String) raw.get("description"));
        if (raw.containsKey("targetNode"))  ac.setTargetNode((String) raw.get("targetNode"));
        if (raw.containsKey("method"))      ac.setMethod((String) raw.get("method"));
        if (raw.containsKey("commands"))    ac.setCommandEntries(parseCommandEntries(raw.get("commands")));
        return ac;
    }

    /**
     * Parse a YAML commands list where each item is either a plain {@code String}
     * or a conditional map with {@code if}/{@code then}/{@code else} keys,
     * or a rich map with {@code cmd}/{@code description}/{@code validation} keys.
     */
    @SuppressWarnings("unchecked")
    private List<CommandEntry> parseCommandEntries(Object raw) {
        List<CommandEntry> result = new ArrayList<>();
        if (!(raw instanceof List)) return result;
        for (Object item : (List<Object>) raw) {
            if (item instanceof String) {
                result.add(CommandEntry.plain((String) item));
            } else if (item instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) item;
                if (map.containsKey("cmd")) {
                    // Rich command: cmd + optional description + optional validation
                    result.add(CommandEntry.rich(
                            (String) map.get("cmd"),
                            (String) map.get("description"),
                            (String) map.get("validation")));
                } else {
                    // Conditional: if/then/else
                    String condition  = (String) map.get("if");
                    List<CommandEntry> thenCmds = map.containsKey("then")
                            ? parseCommandEntries(map.get("then")) : new ArrayList<>();
                    List<CommandEntry> elseCmds = map.containsKey("else")
                            ? parseCommandEntries(map.get("else")) : new ArrayList<>();
                    result.add(CommandEntry.conditional(condition, thenCmds, elseCmds));
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private ActivitySectionConfig parseActivitySectionConfig(Map<String, Object> raw) {
        ActivitySectionConfig ac = new ActivitySectionConfig();
        if (raw == null) return ac;
        if (raw.containsKey("precheck")) {
            List<ActivityConfig> list = new ArrayList<>();
            for (Map<String, Object> item : (List<Map<String, Object>>) raw.get("precheck")) {
                list.add(parseActivityConfig(item));
            }
            ac.setPrecheck(list);
        }
        if (raw.containsKey("execution")) {
            List<ActivityConfig> list = new ArrayList<>();
            for (Map<String, Object> item : (List<Map<String, Object>>) raw.get("execution")) {
                list.add(parseActivityConfig(item));
            }
            ac.setExecution(list);
        }
        if (raw.containsKey("postcheck")) {
            List<ActivityConfig> list = new ArrayList<>();
            for (Map<String, Object> item : (List<Map<String, Object>>) raw.get("postcheck")) {
                list.add(parseActivityConfig(item));
            }
            ac.setPostcheck(list);
        }
        return ac;
    }

    @SuppressWarnings("unchecked")
    private RollbackConfig parseRollbackConfig(Map<String, Object> raw) {
        RollbackConfig rc = new RollbackConfig();
        if (raw == null) return rc;
        if (raw.containsKey("precheck")) {
            List<ActivityConfig> list = new ArrayList<>();
            for (Map<String, Object> item : (List<Map<String, Object>>) raw.get("precheck")) {
                list.add(parseActivityConfig(item));
            }
            rc.setPrecheck(list);
        }
        if (raw.containsKey("execution")) {
            List<ActivityConfig> list = new ArrayList<>();
            for (Map<String, Object> item : (List<Map<String, Object>>) raw.get("execution")) {
                list.add(parseActivityConfig(item));
            }
            rc.setExecution(list);
        }
        if (raw.containsKey("postcheck")) {
            List<ActivityConfig> list = new ArrayList<>();
            for (Map<String, Object> item : (List<Map<String, Object>>) raw.get("postcheck")) {
                list.add(parseActivityConfig(item));
            }
            rc.setPostcheck(list);
        }
        return rc;
    }

    /** Convert a raw YAML map (values may be Object) to a {@code Map<String,String>}. */
    private Map<String, String> toStringMap(Map<String, Object> raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null) return result;
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (e.getValue() != null) {
                result.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        return result;
    }
}
