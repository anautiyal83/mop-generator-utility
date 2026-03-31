package com.nokia.mopgen;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups the activity section blocks: precheck, configuration/execution, postcheck.
 *
 * <p>Two mutually exclusive configuration modes are supported:
 *
 * <p><b>CIQ-driven (XML-based, e.g. SBC):</b> use {@code configuration} + {@code tables}.
 * One ACTIVITY_CONFIGURATION block is auto-generated per table/action from CIQ data.
 * <pre>
 * activity:
 *   configuration:
 *     - CRFTargetList
 *   configurationTargetNode: "OAM_NODE"
 * </pre>
 *
 * <p><b>Static CLI (e.g. MRF, DPA):</b> use {@code execution}.
 * One block is emitted per entry in the list, exactly as defined.
 * <pre>
 * activity:
 *   execution:
 *     - name: "LOAD_ANNOUNCEMENT_FILES"
 *       description: "Transfer and load announcement files to MRF"
 *       targetNode: "MRF_NODE"
 *       method: CLI
 *       commands:
 *         - "cp /tmp/ann.tar /var/opt/swms/clips/..."
 * </pre>
 *
 * {@code configuration} is an ordered list of table names to include in
 * ACTIVITY_CONFIGURATION blocks.  When empty or absent, all CIQ tables
 * are used (backward-compatible default).
 */
public class ActivitySectionConfig {

    private List<ActivityConfig>  precheck      = new ArrayList<>();
    private List<String>          configuration = new ArrayList<>();
    /** Static CLI execution blocks — used for non-CIQ activities (e.g. MRF, DPA). */
    private List<ActivityConfig>  execution     = new ArrayList<>();
    private List<ActivityConfig>  postcheck     = new ArrayList<>();

    /**
     * Auto-generated per-table precheck blocks.
     * When enabled, one block is inserted per configured table containing
     * a download command and per-record existence-check commands.
     */
    private TablePrecheckConfig tablePrecheck = new TablePrecheckConfig();

    /**
     * Auto-generated per-table postcheck blocks.
     * When enabled, one block is inserted per configured table after all
     * ACTIVITY_CONFIGURATION blocks, containing a download command and
     * per-record verification commands based on action/sub-action.
     */
    private TablePostcheckConfig tablePostcheck = new TablePostcheckConfig();

    /**
     * Target node for auto-generated ACTIVITY_CONFIGURATION blocks.
     * Emitted as {@code ## TargetNode: <value>} in each configuration activity header.
     * Optional — omit when not needed.
     */
    private String configurationTargetNode;

    public List<ActivityConfig> getPrecheck()  { return precheck; }
    public void setPrecheck(List<ActivityConfig> v)  { this.precheck = v; }

    public List<String> getConfiguration() { return configuration; }
    public void setConfiguration(List<String> v) { this.configuration = v; }

    public List<ActivityConfig> getExecution() { return execution; }
    public void setExecution(List<ActivityConfig> v) { this.execution = v; }

    public List<ActivityConfig> getPostcheck() { return postcheck; }
    public void setPostcheck(List<ActivityConfig> v) { this.postcheck = v; }

    public String getConfigurationTargetNode() { return configurationTargetNode; }
    public void setConfigurationTargetNode(String v) { this.configurationTargetNode = v; }

    public TablePrecheckConfig getTablePrecheck() { return tablePrecheck; }
    public void setTablePrecheck(TablePrecheckConfig v) { this.tablePrecheck = v; }

    public TablePostcheckConfig getTablePostcheck() { return tablePostcheck; }
    public void setTablePostcheck(TablePostcheckConfig v) { this.tablePostcheck = v; }
}
