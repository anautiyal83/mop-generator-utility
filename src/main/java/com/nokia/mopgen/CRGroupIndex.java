package com.nokia.mopgen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON model for a CRGROUP folder produced by ciq-processor in CRGROUP mode.
 *
 * <p>One {@code CRGroupIndex} file is written per unique CRGROUP value (e.g. "CR-001").
 * It records every GROUP that contributes nodes to that CR, together with the
 * NIAM sub-mapping for each node.
 */
public class CRGroupIndex {

    private String nodeType;
    private String activity;
    private String crGroup;
    private List<GroupEntry> groups = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Nested: one entry per GROUP within this CRGROUP
    // -------------------------------------------------------------------------

    public static class GroupEntry {

        private String group;
        private List<String> nodes = new ArrayList<>();
        private Map<String, String> niamMapping = new LinkedHashMap<>();

        public String getGroup()                           { return group; }
        public void   setGroup(String group)               { this.group = group; }

        public List<String> getNodes()                     { return nodes; }
        public void         setNodes(List<String> nodes)   { this.nodes = nodes; }

        public Map<String, String> getNiamMapping()                       { return niamMapping; }
        public void                setNiamMapping(Map<String, String> m)  { this.niamMapping = m; }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String getNodeType()                        { return nodeType; }
    public void   setNodeType(String nodeType)         { this.nodeType = nodeType; }

    public String getActivity()                        { return activity; }
    public void   setActivity(String activity)         { this.activity = activity; }

    public String getCrGroup()                         { return crGroup; }
    public void   setCrGroup(String crGroup)           { this.crGroup = crGroup; }

    public List<GroupEntry> getGroups()                { return groups; }
    public void             setGroups(List<GroupEntry> groups) { this.groups = groups; }

    /** Returns all nodes across every GroupEntry in this CRGROUP. */
    public List<String> getAllNodes() {
        List<String> all = new ArrayList<>();
        for (GroupEntry ge : groups) all.addAll(ge.getNodes());
        return all;
    }
}
