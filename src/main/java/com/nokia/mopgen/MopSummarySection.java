package com.nokia.mopgen;

import java.util.ArrayList;
import java.util.List;

/**
 * One named MOP block within a {@link MopSummaryUnit}.
 *
 * <p>Sections are stored in two separate ordered lists inside their parent unit:
 * {@link MopSummaryUnit#getActivity()} and {@link MopSummaryUnit#getRollback()},
 * so the {@link #rollback} flag is set accordingly when a section is converted
 * from the YAML MOP model.
 */
public class MopSummarySection {

    /** Block name (e.g. {@code "PRE_NODE_HEALTH_CHECK"}, {@code "ANNOUNCEMENT_FILES_CREATE_ACTIVITY_CONFIGURATION"}). */
    private String name;

    /** Human-readable description of the section's purpose. */
    private String description;

    /** Target node label shown in the approval report (e.g. {@code "MRF_NODE"}). */
    private String targetNode;

    /** Execution method (e.g. {@code "CLI"}, {@code "NETCONF"}). */
    private String method;

    /**
     * Type marker used to determine the badge colour in the report.
     * Typical values: {@code "CREATE"}, {@code "DELETE"}, {@code "MODIFY"},
     * {@code "CREATE_ROLLBACK"}, or {@code null} for static sections.
     */
    private String typeMarker;

    /**
     * {@code true} when this section belongs to the rollback phase.
     * Set automatically during conversion from {@link YamlMopSection}.
     */
    private boolean rollback;

    /** Ordered list of command lines for this section. */
    private List<MopSummaryCommand> commands = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Convenience
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when any command in this section carries description
     * or validation metadata (triggers a table layout in the HTML report).
     */
    public boolean hasCommandMetadata() {
        for (MopSummaryCommand c : commands) {
            if (c.hasMetadata()) return true;
        }
        return false;
    }

    /**
     * Returns a display label for the {@link #typeMarker} suitable for rendering
     * in a badge.
     */
    public String typeLabel() {
        if (typeMarker == null) return "";
        switch (typeMarker) {
            case "CREATE":          return "CREATE";
            case "DELETE":          return "DELETE";
            case "CREATE_ROLLBACK": return "ROLLBACK";
            default:                return typeMarker;
        }
    }

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    public String getName()              { return name; }
    public void   setName(String v)       { this.name = v; }

    public String getDescription()              { return description; }
    public void   setDescription(String v)       { this.description = v; }

    public String getTargetNode()              { return targetNode; }
    public void   setTargetNode(String v)       { this.targetNode = v; }

    public String getMethod()              { return method; }
    public void   setMethod(String v)       { this.method = v; }

    public String getTypeMarker()              { return typeMarker; }
    public void   setTypeMarker(String v)       { this.typeMarker = v; }

    public boolean isRollback()            { return rollback; }
    public void    setRollback(boolean v)   { this.rollback = v; }

    public List<MopSummaryCommand> getCommands()                        { return commands; }
    public void                    setCommands(List<MopSummaryCommand> v) { this.commands = v; }
}
