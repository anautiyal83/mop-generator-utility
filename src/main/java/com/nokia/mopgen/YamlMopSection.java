package com.nokia.mopgen;

import java.util.ArrayList;
import java.util.List;

/**
 * One activity or rollback block in a {@link YamlMopGroup}.
 *
 * <p>Maps 1:1 to a {@code ##BLOCK_NAME} section in the legacy text MOP format
 * but carries richer metadata and pre-resolved commands directly in the model.
 */
public class YamlMopSection {

    /** Block display name (e.g. {@code PRE_NODE_HEALTH_CHECK}, {@code ANNOUNCEMENT_FILES_CREATE_ACTIVITY_CONFIGURATION}). */
    public String name;

    /** Human-readable description of this block's purpose. May be {@code null}. */
    public String description;

    /**
     * Target NE identifier for this block (the node on which commands run).
     * May be {@code null} when the target is implicit from the group.
     */
    public String targetNode;

    /**
     * Execution method, e.g. {@code "CLI"} or {@code "NETCONF"}.
     * May be {@code null} for structural / header-only blocks.
     */
    public String method;

    /**
     * Type marker for CIQ-driven configuration blocks:
     * {@code "CREATE"}, {@code "DELETE"}, {@code "MODIFY"}, or {@code "CREATE_ROLLBACK"}.
     * {@code null} for static (non-CIQ) blocks such as health checks and backups.
     */
    public String typeMarker;

    /** Fully resolved, ready-to-execute commands for this block. */
    public List<YamlMopCommand> commands = new ArrayList<>();
}
