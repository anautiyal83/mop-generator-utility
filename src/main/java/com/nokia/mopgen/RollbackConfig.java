package com.nokia.mopgen;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups the rollback section activities.
 *
 * <p>Two mutually exclusive rollback modes are supported:
 *
 * <p><b>CIQ-driven (XML-based, e.g. SBC):</b> use {@code configuration}.
 * One ROLLBACK_CONFIGURATION block is auto-generated per table that had CREATE rows.
 * <pre>
 * rollback:
 *   configuration:
 *     - CRFTargetList
 *   configurationTargetNode: "OAM_NODE"
 * </pre>
 *
 * <p><b>Static CLI (e.g. MRF, DPA):</b> use {@code execution}.
 * One block is emitted per entry in the list, exactly as defined.
 * <pre>
 * rollback:
 *   execution:
 *     - name: "ROLLBACK_ANNOUNCEMENT_FILES"
 *       description: "Restore announcement files from backup"
 *       targetNode: "MRF_NODE"
 *       method: CLI
 *       commands:
 *         - "cp /var/opt/swms/clips/.bkp/ann.tar /var/opt/swms/clips/..."
 * </pre>
 *
 * {@code configuration} is an ordered list of table names to include in
 * ROLLBACK_CONFIGURATION blocks.  When empty or absent, all tables that
 * had CREATE actions are rolled back (backward-compatible default).
 */
public class RollbackConfig {

    private List<ActivityConfig> precheck      = new ArrayList<>();
    private List<String>         configuration = new ArrayList<>();
    /** Static CLI rollback execution blocks — used for non-CIQ activities (e.g. MRF, DPA). */
    private List<ActivityConfig> execution     = new ArrayList<>();
    private List<ActivityConfig> postcheck     = new ArrayList<>();

    /**
     * Auto-generated per-table precheck blocks before ROLLBACK_CONFIGURATION.
     * Verifies that CREATE records still exist on the NE before attempting to delete them.
     * Uses {@link TablePrecheckConfig#getExistsCheckCommand()} for each CREATE row.
     */
    private TablePrecheckConfig tablePrecheck = new TablePrecheckConfig();

    /**
     * Auto-generated per-table postcheck blocks after ROLLBACK_CONFIGURATION.
     * Verifies that CREATE records have been successfully deleted from the NE.
     * Uses {@link TablePostcheckConfig#getDeleteCheckCommand()} for each CREATE row.
     */
    private TablePostcheckConfig tablePostcheck = new TablePostcheckConfig();

    /**
     * Target node for auto-generated ROLLBACK_CONFIGURATION blocks.
     * Emitted as {@code ## TargetNode: <value>} in each rollback configuration activity header.
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
