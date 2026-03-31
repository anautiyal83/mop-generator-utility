package com.nokia.mopgen;

import java.util.ArrayList;
import java.util.List;

/**
 * Configures a single named activity block in the MOP.
 * Used for static sections: PRE_NODE_HEALTH_CHECK, BACKUP,
 * POST_NODE_HEALTH_CHECK, ROLLBACK_PRECHECK, ROLLBACK_POSTCHECK.
 *
 * <p>Commands may be plain strings or conditional blocks:
 * <pre>
 * - name: "LOAD_FILES"
 *   method: CLI
 *   commands:
 *     - "# always run"
 *     - if: "${VERSION} == 13"
 *       then:
 *         - "cp /tmp/$ANN_FILE $ANN_DIR"
 *       else:
 *         - "tar -xf /tmp/$ANN_FILE -C $ANN_DIR"
 * </pre>
 */
public class ActivityConfig {

    /** Activity name suffix used in ##ACTIVITY_ header and action line. */
    private String name;

    /** Human-readable description emitted as a ## Description comment in the MOP. */
    private String description;

    /**
     * Identifier of the network element or node on which this activity block
     * should be executed.  Emitted as {@code ## TargetNode: <value>} in the
     * activity header so the execution framework knows which NE to connect to.
     * Optional — omit when all activities run on the same node.
     */
    private String targetNode;

    /** Execution method: CLI or API. */
    private String method = "CLI";

    /**
     * Ordered list of command entries for the payload.
     * Each entry is either a plain command string or a conditional if/then/else block.
     * See {@link CommandEntry}.
     */
    private List<CommandEntry> commandEntries = new ArrayList<>();

    // -------------------------------------------------------------------------

    public String getName()   { return name; }
    public void setName(String v)   { this.name = v; }

    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }

    public String getTargetNode() { return targetNode; }
    public void setTargetNode(String v) { this.targetNode = v; }

    public String getMethod() { return method; }
    public void setMethod(String v) { this.method = v; }

    public List<CommandEntry> getCommandEntries() { return commandEntries; }
    public void setCommandEntries(List<CommandEntry> v) { this.commandEntries = v; }

    /**
     * Convenience: returns the text of top-level plain (non-conditional) entries.
     * Useful for simple assertions and logging; does not expand conditionals.
     */
    public List<String> getCommands() {
        List<String> result = new ArrayList<>();
        for (CommandEntry e : commandEntries) {
            if (e.isPlain()) result.add(e.getText());
        }
        return result;
    }
}
