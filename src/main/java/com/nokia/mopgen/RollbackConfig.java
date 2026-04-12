package com.nokia.mopgen;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups the rollback section activities: precheck, execution, postcheck.
 *
 * <pre>
 * rollback:
 *   precheck:
 *     - name: "ROLLBACK_PRECHECK"
 *       commands: ["show status"]
 *   execution:
 *     - name: "ROLLBACK_ANNOUNCEMENT_FILES"
 *       description: "Restore announcement files from backup"
 *       targetNode: "MRF_NODE"
 *       method: CLI
 *       commands:
 *         - "cp /var/opt/swms/clips/.bkp/ann.tar /var/opt/swms/clips/..."
 *   postcheck:
 *     - name: "ROLLBACK_POSTCHECK"
 *       commands: ["show alarms active"]
 * </pre>
 */
public class RollbackConfig {

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
