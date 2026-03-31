package com.nokia.mopgen;

/**
 * Configuration for auto-generated per-table postcheck activity blocks.
 *
 * <p>When enabled, one {@code TABLE_POSTCHECK} activity block is inserted per table
 * after all ACTIVITY_CONFIGURATION blocks and before the static ACTIVITY_POSTCHECK.
 * Each block contains:
 * <ol>
 *   <li>A download command that re-reads the table configuration from the NE.</li>
 *   <li>Per-record verification commands based on the action type.</li>
 * </ol>
 *
 * <h3>Verification rules by action</h3>
 * <ul>
 *   <li><b>CREATE</b> — record must now exist in the current config.</li>
 *   <li><b>DELETE</b> — record must no longer exist in the current config.</li>
 *   <li><b>MODIFY / SubAction=MOD</b> — each CIQ field value must match current config.</li>
 *   <li><b>MODIFY / SubAction=ADD</b> — sub-record key must now exist.</li>
 *   <li><b>MODIFY / SubAction=DEL</b> — sub-record key must no longer exist.</li>
 * </ul>
 *
 * <h3>Placeholders</h3>
 * <ul>
 *   <li>{@code {tableName}}   — CIQ table name (e.g. {@code CRFTargetList})</li>
 *   <li>{@code {storagePath}} — global storage path from {@code MopConfig.storagePath}</li>
 *   <li>{@code {keyTag}}      — XML tag of the primary key field</li>
 *   <li>{@code {keyValue}}    — primary key value for the record (or sub-record key for ADD/DEL)</li>
 *   <li>{@code {fieldTag}}    — XML tag of the specific field being verified (MOD only)</li>
 *   <li>{@code {fieldValue}}  — expected field value (MOD only)</li>
 *   <li>{@code {action}}      — action type: CREATE, DELETE, or MODIFY</li>
 *   <li>{@code {subAction}}   — sub-action: ADD, DEL, or MOD (MODIFY rows only)</li>
 * </ul>
 *
 * <pre>
 * activity:
 *   tablePostcheck:
 *     enabled: true
 *     targetNode: "OAM_NODE"
 *     downloadCommand: "...netconfprov --table {tableName} --read > {storagePath}/{tableName}_post.xml"
 *     createCheckCommand:   "grep -q ..."    # record must exist
 *     deleteCheckCommand:   "! grep -q ..."  # record must not exist
 *     modFieldCheckCommand: "grep -q ..."    # specific field value must match
 *     subAddCheckCommand:   "grep -q ..."    # sub-record key must exist
 *     subDelCheckCommand:   "! grep -q ..."  # sub-record key must not exist
 * </pre>
 */
public class TablePostcheckConfig {

    /** Whether to generate per-table postcheck blocks. Default: false. */
    private boolean enabled = false;

    /** Target node identifier emitted as {@code ## TargetNode:} in each block header. */
    private String targetNode;

    /**
     * Command template to re-download table configuration after applying changes.
     * Placeholders: {@code {tableName}}, {@code {storagePath}}.
     */
    private String downloadCommand;

    /**
     * Command template to verify a new record now exists (CREATE postcheck).
     * Placeholders: {@code {tableName}}, {@code {storagePath}}, {@code {keyTag}}, {@code {keyValue}}.
     */
    private String createCheckCommand;

    /**
     * Command template to verify a record no longer exists (DELETE postcheck).
     * Placeholders: {@code {tableName}}, {@code {storagePath}}, {@code {keyTag}}, {@code {keyValue}}.
     */
    private String deleteCheckCommand;

    /**
     * Command template to verify one field value matches CIQ data (MODIFY/MOD postcheck).
     * Generated once per non-null field in the modified row.
     * Placeholders: {@code {tableName}}, {@code {storagePath}},
     *               {@code {keyTag}}, {@code {keyValue}},
     *               {@code {fieldTag}}, {@code {fieldValue}}.
     */
    private String modFieldCheckCommand;

    /**
     * Command template to verify a sub-record was added (MODIFY/SubAction=ADD postcheck).
     * {@code {keyTag}}/{@code {keyValue}} hold the sub-record's first field tag and value.
     * Placeholders: {@code {tableName}}, {@code {storagePath}}, {@code {keyTag}}, {@code {keyValue}}.
     */
    private String subAddCheckCommand;

    /**
     * Command template to verify a sub-record was deleted (MODIFY/SubAction=DEL postcheck).
     * {@code {keyTag}}/{@code {keyValue}} hold the sub-record's first field tag and value.
     * Placeholders: {@code {tableName}}, {@code {storagePath}}, {@code {keyTag}}, {@code {keyValue}}.
     */
    private String subDelCheckCommand;

    // -------------------------------------------------------------------------

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }

    public String getTargetNode() { return targetNode; }
    public void setTargetNode(String v) { this.targetNode = v; }

    public String getDownloadCommand() { return downloadCommand; }
    public void setDownloadCommand(String v) { this.downloadCommand = v; }

    public String getCreateCheckCommand() { return createCheckCommand; }
    public void setCreateCheckCommand(String v) { this.createCheckCommand = v; }

    public String getDeleteCheckCommand() { return deleteCheckCommand; }
    public void setDeleteCheckCommand(String v) { this.deleteCheckCommand = v; }

    public String getModFieldCheckCommand() { return modFieldCheckCommand; }
    public void setModFieldCheckCommand(String v) { this.modFieldCheckCommand = v; }

    public String getSubAddCheckCommand() { return subAddCheckCommand; }
    public void setSubAddCheckCommand(String v) { this.subAddCheckCommand = v; }

    public String getSubDelCheckCommand() { return subDelCheckCommand; }
    public void setSubDelCheckCommand(String v) { this.subDelCheckCommand = v; }
}
