package com.nokia.mopgen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Root configuration loaded from an externally-provided YAML template file.
 * Naming convention: {@code {NODE_TYPE}_{ACTIVITY}_MOP-Template.yaml}
 *
 * <p>Every shell command and static MOP block is configurable here.
 * Zero code changes are required to support a new node type or activity —
 * only the YAML template file needs to be created/updated.
 *
 * <pre>
 * preNodeHealthCheck:
 *   - name: "PRE_NODE_HEALTH_CHECK"
 *     method: CLI
 *     commands: ["show status"]
 * backup:
 *   - name: "BACKUP"
 *     method: CLI
 *     commands: ["backup create"]
 * activity:
 *   precheck:
 *     - name: "ACTIVITY_PRECHECK"
 *       method: CLI
 *       commands: ["show status"]
 *   execution:
 *     - name: "LOAD_ANNOUNCEMENT_FILES"
 *       description: "Transfer and load announcement files"
 *       targetNode: "MRF_NODE"
 *       method: CLI
 *       commands:
 *         - "cp /tmp/ann.tar /var/opt/swms/clips/..."
 *   postcheck:
 *     - name: "ACTIVITY_POSTCHECK"
 *       method: CLI
 *       commands: ["show alarms active"]
 * postNodeHealthCheck:
 *   - name: "POST_NODE_HEALTH_CHECK"
 *     method: CLI
 *     commands: ["show alarms active"]
 * rollback:
 *   precheck:
 *     - name: "ROLLBACK_PRECHECK"
 *       commands: ["show status"]
 *   execution:
 *     - name: "ROLLBACK_ANNOUNCEMENT_FILES"
 *       method: CLI
 *       commands: ["cp /var/opt/swms/clips/.bkp/ann.tar ..."]
 *   postcheck:
 *     - name: "ROLLBACK_POSTCHECK"
 *       commands: ["show alarms active"]
 * </pre>
 */
public class MopConfig {

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
     * Maps JSON field names in a node object to MOP variable names.
     *
     * <p>Recognised keys:
     * <ul>
     *   <li>{@code nodeNameKey} — JSON field that holds the node identifier
     *       (default: {@code "node"}).  Its value is bound to the {@code NODE} variable.</li>
     *   <li>{@code neIdKey}     — JSON field that holds the NEID / NIAM name
     *       (default: {@code "niamID"}).  Its value is bound to the {@code NEID} variable.</li>
     *   <li>{@code nodeNameKey} — also used as the display key in the approval report chips.</li>
     * </ul>
     *
     * <p>Example (in MOP-Template.yaml):
     * <pre>
     * jsonMapping:
     *   nodeNameKey: node
     *   neIdKey:     niamID
     * </pre>
     */
    private Map<String, String> jsonMapping = new LinkedHashMap<>();

    /**
     * Path to the external HTML template file used for the MOP approval summary report.
     *
     * <p>When set, {@link HtmlGroupApprovalMopGenerator} loads the template, replaces
     * {@code {{TOKEN}}} placeholders with generated content, and writes the result.
     *
     * <p>This field is typically set at runtime via the {@code --mop-summary-template} CLI
     * parameter rather than baked into the YAML template file.
     */
    private String summaryTemplatePath;

    /**
     * Named constants substituted into command strings at generation time.
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
    // blocks can be emitted per section from the YAML template.
    // An empty list means the section is omitted from the output.
    // -------------------------------------------------------------------------

    /** PRE_NODE_HEALTH_CHECK section: one or more activity blocks. */
    private List<ActivityConfig> preNodeHealthCheck = new ArrayList<>();

    /** BACKUP section: one or more activity blocks. */
    private List<ActivityConfig> backup = new ArrayList<>();

    /**
     * Activity section: precheck, execution, and postcheck blocks.
     */
    private ActivitySectionConfig activity = new ActivitySectionConfig();

    /** POST_NODE_HEALTH_CHECK section: one or more activity blocks. */
    private List<ActivityConfig> postNodeHealthCheck = new ArrayList<>();

    /** Rollback section: precheck, execution, and postcheck blocks. */
    private RollbackConfig rollback = new RollbackConfig();

    // -------------------------------------------------------------------------
    // Getters / Setters
    // -------------------------------------------------------------------------

    public String getMopApprovalFormatType() { return mopApprovalFormatType; }
    public void setMopApprovalFormatType(String v) { this.mopApprovalFormatType = v; }

    public Map<String, String> getJsonMapping() { return jsonMapping; }
    public void setJsonMapping(Map<String, String> v) { this.jsonMapping = v; }

    public String getSummaryTemplatePath() { return summaryTemplatePath; }
    public void setSummaryTemplatePath(String v) { this.summaryTemplatePath = v; }

    public Map<String, String> getConstants() { return constants; }
    public void setConstants(Map<String, String> v) { this.constants = v; }

    public List<ActivityConfig> getPreNodeHealthCheck() { return preNodeHealthCheck; }
    public void setPreNodeHealthCheck(List<ActivityConfig> v) { this.preNodeHealthCheck = v; }

    public List<ActivityConfig> getBackup() { return backup; }
    public void setBackup(List<ActivityConfig> v) { this.backup = v; }

    public ActivitySectionConfig getActivity() { return activity; }
    public void setActivity(ActivitySectionConfig v) { this.activity = v; }

    public List<ActivityConfig> getPostNodeHealthCheck() { return postNodeHealthCheck; }
    public void setPostNodeHealthCheck(List<ActivityConfig> v) { this.postNodeHealthCheck = v; }

    public RollbackConfig getRollback() { return rollback; }
    public void setRollback(RollbackConfig v) { this.rollback = v; }
}
