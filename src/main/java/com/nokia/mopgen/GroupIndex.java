package com.nokia.mopgen;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-side mirror of {@code com.nokia.ciq.processor.model.GroupIndex}.
 *
 * <p>Deserialised from the {@code *_index_<group>.json} file written by
 * {@code ciq-processor} GROUP-mode segregation.  The JSON contract (field names) must
 * match the ciq-processor version.  Unknown fields (e.g. legacy {@code niamMapping})
 * are ignored to maintain forward/backward compatibility.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GroupIndex {

    private String nodeType;
    private String activity;
    private String group;
    private List<String> nodes        = new ArrayList<>();
    private List<String> tables       = new ArrayList<>();

    public String getNodeType()                       { return nodeType; }
    public void   setNodeType(String nodeType)        { this.nodeType = nodeType; }

    public String getActivity()                       { return activity; }
    public void   setActivity(String activity)        { this.activity = activity; }

    public String getGroup()                          { return group; }
    public void   setGroup(String group)              { this.group = group; }

    public List<String> getNodes()                    { return nodes; }
    public void         setNodes(List<String> nodes)  { this.nodes = nodes; }

    public List<String> getTables()                     { return tables; }
    public void         setTables(List<String> tables)  { this.tables = tables; }
}
