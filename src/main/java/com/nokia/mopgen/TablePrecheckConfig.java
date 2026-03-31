package com.nokia.mopgen;

/**
 * Configuration for auto-generated per-table precheck activity blocks.
 *
 * <p>When enabled, one {@code TABLE_PRECHECK} activity block is inserted per table
 * (after the static precheck blocks, before the configuration blocks).
 * Each block contains:
 * <ol>
 *   <li>A download command that reads the current table configuration from the NE.</li>
 *   <li>For every CREATE row: a check command verifying the record does NOT already exist.</li>
 *   <li>For every DELETE/MODIFY row: a check command verifying the record DOES already exist.</li>
 * </ol>
 *
 * <p>All command strings are templates. The following placeholders are substituted:
 * <ul>
 *   <li>{@code {tableName}}   — CIQ table name (e.g. {@code CRFTargetList})</li>
 *   <li>{@code {storagePath}} — global storage path from {@code MopConfig.storagePath}</li>
 *   <li>{@code {keyTag}}      — XML tag of the primary key field (first depth-1 Record.* column)</li>
 *   <li>{@code {keyValue}}    — value of the primary key for this record</li>
 *   <li>{@code {action}}      — action type: CREATE, DELETE, or MODIFY</li>
 * </ul>
 *
 * <pre>
 * activity:
 *   tablePrecheck:
 *     enabled: true
 *     targetNode: "OAM_NODE"
 *     downloadCommand: >
 *       /opt/sbc/CurrRel/scripts/netconfprov/netconfprov --plane signaling
 *       --user root --pemfile /root/.ssh/id_rsa
 *       --table {tableName} --read > {storagePath}/{tableName}_current.xml
 *     createCheckCommand: >
 *       ! grep -q "&lt;{keyTag}&gt;{keyValue}&lt;/{keyTag}&gt;"
 *       {storagePath}/{tableName}_current.xml
 *       || echo "PRECHECK FAIL: {tableName} {keyTag}={keyValue} already exists (CREATE conflict)"
 *     existsCheckCommand: >
 *       grep -q "&lt;{keyTag}&gt;{keyValue}&lt;/{keyTag}&gt;"
 *       {storagePath}/{tableName}_current.xml
 *       || echo "PRECHECK FAIL: {tableName} {keyTag}={keyValue} not found (required for {action})"
 * </pre>
 */
public class TablePrecheckConfig {

    /** Whether to generate per-table precheck blocks. Default: false. */
    private boolean enabled = false;

    /** Target node identifier emitted as {@code ## TargetNode:} in each block header. */
    private String targetNode;

    /**
     * Command template to download the current table configuration from the NE.
     * Placeholders: {@code {tableName}}, {@code {storagePath}}.
     */
    private String downloadCommand;

    /**
     * Command template to verify a record does NOT exist.
     * Used for every distinct primary key value found in CREATE rows.
     * Placeholders: {@code {tableName}}, {@code {storagePath}}, {@code {keyTag}}, {@code {keyValue}}.
     */
    private String createCheckCommand;

    /**
     * Command template to verify a record DOES exist.
     * Used for every distinct primary key value found in DELETE and MODIFY rows.
     * Placeholders: {@code {tableName}}, {@code {storagePath}},
     *               {@code {keyTag}}, {@code {keyValue}}, {@code {action}}.
     */
    private String existsCheckCommand;

    // -------------------------------------------------------------------------

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }

    public String getTargetNode() { return targetNode; }
    public void setTargetNode(String v) { this.targetNode = v; }

    public String getDownloadCommand() { return downloadCommand; }
    public void setDownloadCommand(String v) { this.downloadCommand = v; }

    public String getCreateCheckCommand() { return createCheckCommand; }
    public void setCreateCheckCommand(String v) { this.createCheckCommand = v; }

    public String getExistsCheckCommand() { return existsCheckCommand; }
    public void setExistsCheckCommand(String v) { this.existsCheckCommand = v; }
}
