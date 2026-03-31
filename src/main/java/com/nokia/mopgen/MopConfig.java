package com.nokia.mopgen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Root configuration loaded from an externally-provided YAML template file.
 * Naming convention: {@code {NODE_TYPE}_{ACTIVITY}_MOP-Template.yaml}
 *
 * <p>Every XML tag, shell command, and static MOP block is configurable here.
 * Zero code changes are required to support a new node type, sheet, or column —
 * only the YAML template file needs to be created/updated.
 *
 * <pre>
 * defaultNamespace: "http://nokia.com/yang/isbc-sig"
 * storagePath: "/storage"
 * commands:
 *   stageFile: "cat << 'XMLEOF' > {storageFile}"
 *   heredocEnd: "XMLEOF"
 *   applyConfig: "netconfprov --onerror abort {storageFile}"
 * preNodeHealthCheck:
 *   name: "PRE_NODE_HEALTH_CHECK"
 *   method: CLI
 *   commands: ["show status"]
 * backup:
 *   name: "BACKUP"
 *   method: CLI
 *   commands: ["backup create"]
 * activityPrecheck:
 *   name: "ACTIVITY_PRECHECK"
 *   method: CLI
 *   commands: ["show status"]
 * activityPostcheck:
 *   name: "ACTIVITY_POSTCHECK"
 *   method: CLI
 *   commands: ["show alarms active"]
 * postNodeHealthCheck:
 *   name: "POST_NODE_HEALTH_CHECK"
 *   method: CLI
 *   commands: ["show alarms active"]
 * rollback:
 *   precheck:
 *     - name: "ROLLBACK_PRECHECK"
 *       commands: ["show status"]
 *   postcheck:
 *     - name: "ROLLBACK_POSTCHECK"
 *       commands: ["show alarms active"]
 * tables:
 *   CRFTargetList:
 *     namespace: "http://nokia.com/yang/isbc-sig"
 *     xmlElement: "CRFTargetList"
 *     recordElement: "Record"
 *     columnMappings:
 *       "Record.TARGET_LIST_ID": "TARGET_LIST_ID"
 * </pre>
 */
public class MopConfig {

    /**
     * GROUP MOP generation mode.
     * <ul>
     *   <li>{@code NODE} (default) — one MOP per node, named {@code <base>_<node>_MOP.<ext>}</li>
     *   <li>{@code GROUP} — one shared MOP for the whole group, named {@code <base>_<group>_MOP.<ext>}</li>
     * </ul>
     */
    private String mopGenerationMode = "NODE";

    /**
     * Approval document format type.
     * <ul>
     *   <li>{@code TEXT} (default) — use the generated MOP file as-is (no separate approval document)</li>
     *   <li>{@code HTML} — generate HTML approval doc</li>
     *   <li>{@code MSWORD} — generate .docx approval doc</li>
     * </ul>
     */
    private String mopApprovalFormatType = "TEXT";

    /**
     * Controls how many summary documents are generated in GROUP context.
     * <ul>
     *   <li>{@code GROUP} (default) — one summary for the whole group, named {@code <base>_<group>_SUMMARY.<ext>}</li>
     *   <li>{@code NODE} — one summary per node, named {@code <base>_<node>_SUMMARY.<ext>}</li>
     * </ul>
     * Only relevant when {@code mopApprovalFormatType} is HTML or MSWORD.
     */
    private String mopSummaryGenerationMode = "GROUP";

    /**
     * XML builder implementation to use. Selects the strategy for generating XML payloads.
     * Built-in values: {@code "netconf-soi"} (default).
     * Add new values in {@link XmlBuilderFactory}.
     */
    private String xmlBuilderName = "netconf-soi";

    /** Default XML namespace for all table elements (overridable per-table in {@code tables}). */
    private String defaultNamespace = "http://nokia.com/yang/isbc-sig";

    /**
     * Global attributes added to the XML envelope element (e.g. {@code <config>}) by the
     * active {@link XmlBuilder} implementation.
     * Per-table {@code configAttributes} in {@link TableConfig} override individual keys.
     *
     * <pre>
     * configAttributes:
     *   ne-version:  "R24.7"
     *   ne-type:     "SBC-signaling"
     *   soi-version: "1.0"
     * </pre>
     */
    private Map<String, String> configAttributes = new LinkedHashMap<>();

    /** NETCONF base namespace declared as {@code xmlns:xc} on every table element. */
    private String netconfNamespace = "urn:ietf:params:xml:ns:netconf:base:1.0";

    /** Path on the NE where temporary XML files are staged before applying. */
    private String storagePath = "/storage";

    /** Shell command templates used in every XML staging payload. */
    private CommandConfig commands = new CommandConfig();

    /** Per-table XML configuration (namespace, element names, column mappings). */
    private Map<String, TableConfig> tables = new LinkedHashMap<>();

    /**
     * Named constants that are substituted into command strings at MOP generation time.
     * Reference a constant in any command using {@code $KEY} syntax.
     *
     * <pre>
     * constants:
     *   ANN_DIR: "/var/opt/swms/clips/persistent/provisioned/audioclips/RJ/PREPAID"
     *   ANN_FILE: "announcements_v17.tar"
     * </pre>
     *
     * In commands: {@code "ls -la $ANN_DIR"} → {@code "ls -la /var/opt/swms/..."}
     */
    private Map<String, String> constants = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Static MOP activity blocks — each section is a list so multiple named
    // ##ACTIVITY_ blocks can be emitted per section from the YAML template.
    // An empty list means the section is omitted from the MOP.
    // -------------------------------------------------------------------------

    /** PRE_NODE_HEALTH_CHECK section: one or more activity blocks. */
    private List<ActivityConfig> preNodeHealthCheck = new ArrayList<>();

    /** BACKUP section: one or more activity blocks. */
    private List<ActivityConfig> backup = new ArrayList<>();

    /**
     * Activity section: precheck and postcheck surrounding the auto-generated
     * ACTIVITY_CONFIGURATION blocks.
     */
    private ActivitySectionConfig activity = new ActivitySectionConfig();

    /** POST_NODE_HEALTH_CHECK section: one or more activity blocks. */
    private List<ActivityConfig> postNodeHealthCheck = new ArrayList<>();

    /** Rollback section: lists of precheck and postcheck activity blocks. */
    private RollbackConfig rollback = new RollbackConfig();

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Return the {@link TableConfig} for the given table name.
     * If no explicit config exists, returns a default with the global namespace.
     * Never returns null.
     */
    public TableConfig getTableConfig(String tableName) {
        TableConfig tc = tables.get(tableName);
        if (tc == null) {
            tc = new TableConfig();
        }
        if (tc.getNamespace() == null) {
            tc.setNamespace(defaultNamespace);
        }
        // Merge global configAttributes → per-table (table-level keys win)
        if (!configAttributes.isEmpty()) {
            Map<String, String> merged = new LinkedHashMap<>(configAttributes);
            merged.putAll(tc.getConfigAttributes());
            tc.setConfigAttributes(merged);
        }
        return tc;
    }

    // -------------------------------------------------------------------------
    // Getters / Setters
    // -------------------------------------------------------------------------

    public String getMopGenerationMode() { return mopGenerationMode; }
    public void setMopGenerationMode(String v) { this.mopGenerationMode = v; }

    public String getMopApprovalFormatType() { return mopApprovalFormatType; }
    public void setMopApprovalFormatType(String v) { this.mopApprovalFormatType = v; }

    public String getMopSummaryGenerationMode() { return mopSummaryGenerationMode; }
    public void setMopSummaryGenerationMode(String v) { this.mopSummaryGenerationMode = v; }

    public String getXmlBuilderName() { return xmlBuilderName; }
    public void setXmlBuilderName(String v) { this.xmlBuilderName = v; }

    public String getDefaultNamespace() { return defaultNamespace; }
    public void setDefaultNamespace(String v) { this.defaultNamespace = v; }

    public Map<String, String> getConfigAttributes() { return configAttributes; }
    public void setConfigAttributes(Map<String, String> v) { this.configAttributes = v; }

    public String getNetconfNamespace() { return netconfNamespace; }
    public void setNetconfNamespace(String v) { this.netconfNamespace = v; }

    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String v) { this.storagePath = v; }

    public CommandConfig getCommands() { return commands; }
    public void setCommands(CommandConfig v) { this.commands = v; }

    public Map<String, TableConfig> getTables() { return tables; }
    public void setTables(Map<String, TableConfig> v) { this.tables = v; }

    public List<ActivityConfig> getPreNodeHealthCheck() { return preNodeHealthCheck; }
    public void setPreNodeHealthCheck(List<ActivityConfig> v) { this.preNodeHealthCheck = v; }

    public List<ActivityConfig> getBackup() { return backup; }
    public void setBackup(List<ActivityConfig> v) { this.backup = v; }

    public ActivitySectionConfig getActivity() { return activity; }
    public void setActivity(ActivitySectionConfig v) { this.activity = v; }

    public Map<String, String> getConstants() { return constants; }
    public void setConstants(Map<String, String> v) { this.constants = v; }

    public List<ActivityConfig> getPostNodeHealthCheck() { return postNodeHealthCheck; }
    public void setPostNodeHealthCheck(List<ActivityConfig> v) { this.postNodeHealthCheck = v; }

    public RollbackConfig getRollback() { return rollback; }
    public void setRollback(RollbackConfig v) { this.rollback = v; }
}
