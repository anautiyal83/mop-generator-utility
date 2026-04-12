package com.nokia.mopgen;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups the activity section blocks: precheck, execution, postcheck.
 *
 * <pre>
 * activity:
 *   precheck:
 *     - name: "ACTIVITY_PRECHECK"
 *       method: CLI
 *       commands: ["show status"]
 *   execution:
 *     - name: "LOAD_ANNOUNCEMENT_FILES"
 *       description: "Transfer and load announcement files to MRF"
 *       targetNode: "MRF_NODE"
 *       method: CLI
 *       commands:
 *         - "cp /tmp/ann.tar /var/opt/swms/clips/..."
 *   postcheck:
 *     - name: "ACTIVITY_POSTCHECK"
 *       method: CLI
 *       commands: ["show alarms active"]
 * </pre>
 */
public class ActivitySectionConfig {

    private List<ActivityConfig> precheck  = new ArrayList<>();
    private List<ActivityConfig> execution = new ArrayList<>();
    private List<ActivityConfig> postcheck = new ArrayList<>();

    public List<ActivityConfig> getPrecheck()  { return precheck; }
    public void setPrecheck(List<ActivityConfig> v)  { this.precheck = v; }

    public List<ActivityConfig> getExecution() { return execution; }
    public void setExecution(List<ActivityConfig> v) { this.execution = v; }

    public List<ActivityConfig> getPostcheck() { return postcheck; }
    public void setPostcheck(List<ActivityConfig> v) { this.postcheck = v; }
}
