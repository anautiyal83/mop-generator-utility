package com.nokia.mopgen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One GROUP entry inside a {@link YamlMopDocument}.
 *
 * <p>A group represents a set of nodes that receive identical configuration.
 * The YAML MOP file lists both the node names (for human review) and fully-resolved
 * command blocks so the NCE executor can look up a target node, find its group,
 * and execute the group's sections without further resolution.
 */
public class YamlMopGroup {

    /** GROUP identifier (e.g. {@code "A"}, {@code "B"}). */
    public String group;

    /** Names of all nodes in this group (e.g. {@code ["MRF1", "MRF2"]}). */
    public List<String> nodes = new ArrayList<>();

    /**
     * NIAM (NE identifier) mapping: {@code nodeName → neId}.
     * May be empty when the node name is used directly as the NE identifier.
     */
    public Map<String, String> niamMapping = new LinkedHashMap<>();

    /** Activity (forward) blocks in execution order. */
    public List<YamlMopSection> activity = new ArrayList<>();

    /** Rollback blocks in execution order. */
    public List<YamlMopSection> rollback = new ArrayList<>();
}
