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
 * # SBC_FIXED_LINE_CONFIGURATION_MOP-Template.yaml
 * includes:
 *   - "SBC_common_blocks.yaml"   # defines preNodeHealthCheck, rollback, etc.
 *
 * defaultNamespace: "http://nokia.com/yang/isbc-sig"
 * backup:
 *   name: "BACKUP"
 *   commands: ["backup create FIXED_LINE"]
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

        if (raw.containsKey("mopGenerationMode"))
            cfg.setMopGenerationMode((String) raw.get("mopGenerationMode"));
        if (raw.containsKey("mopApprovalFormatType"))
            cfg.setMopApprovalFormatType((String) raw.get("mopApprovalFormatType"));
        if (raw.containsKey("mopSummaryGenerationMode"))
            cfg.setMopSummaryGenerationMode((String) raw.get("mopSummaryGenerationMode"));
        if (raw.containsKey("xmlBuilder"))
            cfg.setXmlBuilderName((String) raw.get("xmlBuilder"));
        if (raw.containsKey("configAttributes"))
            cfg.setConfigAttributes(toStringMap((Map<String, Object>) raw.get("configAttributes")));
        if (raw.containsKey("defaultNamespace"))
            cfg.setDefaultNamespace((String) raw.get("defaultNamespace"));
        if (raw.containsKey("netconfNamespace"))
            cfg.setNetconfNamespace((String) raw.get("netconfNamespace"));
        if (raw.containsKey("storagePath"))
            cfg.setStoragePath((String) raw.get("storagePath"));
        if (raw.containsKey("commands"))
            cfg.setCommands(parseCommandConfig((Map<String, Object>) raw.get("commands")));
        if (raw.containsKey("tables"))
            cfg.setTables(parseTableConfigs((Map<String, Object>) raw.get("tables")));
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

    @SuppressWarnings("unchecked")
    private CommandConfig parseCommandConfig(Map<String, Object> raw) {
        CommandConfig cc = new CommandConfig();
        if (raw == null) return cc;
        if (raw.containsKey("stageFile"))   cc.setStageFile((String) raw.get("stageFile"));
        if (raw.containsKey("heredocEnd"))  cc.setHeredocEnd((String) raw.get("heredocEnd"));
        if (raw.containsKey("applyConfig")) cc.setApplyConfig((String) raw.get("applyConfig"));
        return cc;
    }

    @SuppressWarnings("unchecked")
    private Map<String, TableConfig> parseTableConfigs(Map<String, Object> raw) {
        Map<String, TableConfig> result = new LinkedHashMap<>();
        if (raw == null) return result;
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            result.put(entry.getKey(),
                    parseTableConfig((Map<String, Object>) entry.getValue()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private TableConfig parseTableConfig(Map<String, Object> raw) {
        TableConfig tc = new TableConfig();
        if (raw == null) return tc;
        if (raw.containsKey("namespace"))      tc.setNamespace((String) raw.get("namespace"));
        if (raw.containsKey("xmlElement"))     tc.setXmlElement((String) raw.get("xmlElement"));
        if (raw.containsKey("recordElement"))  tc.setRecordElement((String) raw.get("recordElement"));
        if (raw.containsKey("columnMappings"))
            tc.setColumnMappings((Map<String, String>) raw.get("columnMappings"));
        if (raw.containsKey("configAttributes"))
            tc.setConfigAttributes(toStringMap((Map<String, Object>) raw.get("configAttributes")));
        if (raw.containsKey("xmlTemplates"))
            tc.setXmlTemplates(parseTableXmlTemplates(
                    (Map<String, Object>) raw.get("xmlTemplates")));
        return tc;
    }

    @SuppressWarnings("unchecked")
    private TableXmlTemplates parseTableXmlTemplates(Map<String, Object> raw) {
        TableXmlTemplates t = new TableXmlTemplates();
        if (raw == null) return t;
        if (raw.containsKey("create"))
            t.setCreate(parseActionTemplate((Map<String, Object>) raw.get("create")));
        if (raw.containsKey("delete"))
            t.setDelete(parseActionTemplate((Map<String, Object>) raw.get("delete")));
        if (raw.containsKey("modify"))
            t.setModify(parseActionTemplate((Map<String, Object>) raw.get("modify")));
        if (raw.containsKey("rollback"))
            t.setRollback(parseActionTemplate((Map<String, Object>) raw.get("rollback")));
        return t;
    }

    @SuppressWarnings("unchecked")
    private ActionTemplate parseActionTemplate(Map<String, Object> raw) {
        ActionTemplate t = new ActionTemplate();
        if (raw == null) return t;
        if (raw.containsKey("envelope"))   t.setEnvelope((String) raw.get("envelope"));
        if (raw.containsKey("record"))     t.setRecord((String) raw.get("record"));
        if (raw.containsKey("subRecords"))
            t.setSubRecords((Map<String, String>) raw.get("subRecords"));
        return t;
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
     * or a conditional map with {@code if}/{@code then}/{@code else} keys.
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
        if (raw.containsKey("configuration")) {
            ac.setConfiguration((List<String>) raw.get("configuration"));
        }
        if (raw.containsKey("configurationTargetNode")) {
            ac.setConfigurationTargetNode((String) raw.get("configurationTargetNode"));
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
        if (raw.containsKey("tablePrecheck")) {
            ac.setTablePrecheck(parseTablePrecheckConfig(
                    (Map<String, Object>) raw.get("tablePrecheck")));
        }
        if (raw.containsKey("tablePostcheck")) {
            ac.setTablePostcheck(parseTablePostcheckConfig(
                    (Map<String, Object>) raw.get("tablePostcheck")));
        }
        return ac;
    }

    @SuppressWarnings("unchecked")
    private TablePrecheckConfig parseTablePrecheckConfig(Map<String, Object> raw) {
        TablePrecheckConfig tpc = new TablePrecheckConfig();
        if (raw == null) return tpc;
        if (raw.containsKey("enabled"))            tpc.setEnabled((Boolean) raw.get("enabled"));
        if (raw.containsKey("targetNode"))         tpc.setTargetNode((String) raw.get("targetNode"));
        if (raw.containsKey("downloadCommand"))    tpc.setDownloadCommand((String) raw.get("downloadCommand"));
        if (raw.containsKey("createCheckCommand")) tpc.setCreateCheckCommand((String) raw.get("createCheckCommand"));
        if (raw.containsKey("existsCheckCommand")) tpc.setExistsCheckCommand((String) raw.get("existsCheckCommand"));
        return tpc;
    }

    @SuppressWarnings("unchecked")
    private TablePostcheckConfig parseTablePostcheckConfig(Map<String, Object> raw) {
        TablePostcheckConfig tpc = new TablePostcheckConfig();
        if (raw == null) return tpc;
        if (raw.containsKey("enabled"))              tpc.setEnabled((Boolean) raw.get("enabled"));
        if (raw.containsKey("targetNode"))           tpc.setTargetNode((String) raw.get("targetNode"));
        if (raw.containsKey("downloadCommand"))      tpc.setDownloadCommand((String) raw.get("downloadCommand"));
        if (raw.containsKey("createCheckCommand"))   tpc.setCreateCheckCommand((String) raw.get("createCheckCommand"));
        if (raw.containsKey("deleteCheckCommand"))   tpc.setDeleteCheckCommand((String) raw.get("deleteCheckCommand"));
        if (raw.containsKey("modFieldCheckCommand")) tpc.setModFieldCheckCommand((String) raw.get("modFieldCheckCommand"));
        if (raw.containsKey("subAddCheckCommand"))   tpc.setSubAddCheckCommand((String) raw.get("subAddCheckCommand"));
        if (raw.containsKey("subDelCheckCommand"))   tpc.setSubDelCheckCommand((String) raw.get("subDelCheckCommand"));
        return tpc;
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
        if (raw.containsKey("configuration")) {
            rc.setConfiguration((List<String>) raw.get("configuration"));
        }
        if (raw.containsKey("configurationTargetNode")) {
            rc.setConfigurationTargetNode((String) raw.get("configurationTargetNode"));
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
        if (raw.containsKey("tablePrecheck")) {
            rc.setTablePrecheck(parseTablePrecheckConfig(
                    (Map<String, Object>) raw.get("tablePrecheck")));
        }
        if (raw.containsKey("tablePostcheck")) {
            rc.setTablePostcheck(parseTablePostcheckConfig(
                    (Map<String, Object>) raw.get("tablePostcheck")));
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
