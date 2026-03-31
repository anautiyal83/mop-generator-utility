package com.nokia.mopgen;

/**
 * Shell command templates used in every XML staging payload.
 * {@code {storageFile}} is replaced at generation time with the actual file path.
 *
 * Override all three fields in the MOP-Template YAML to target a different
 * NE type or provisioning tool — zero code changes required.
 */
public class CommandConfig {

    /** Opens the heredoc and redirects to the storage file on the NE. */
    private String stageFile = "cat << 'XMLEOF' > {storageFile}";

    /** Heredoc end marker — must match the marker used in stageFile. */
    private String heredocEnd = "XMLEOF";

    /** Applies the staged XML file to the NE. */
    private String applyConfig = "netconfprov --onerror abort {storageFile}";

    // -------------------------------------------------------------------------

    public String resolveStageFile(String storageFile) {
        return stageFile.replace("{storageFile}", storageFile);
    }

    public String resolveApplyConfig(String storageFile) {
        return applyConfig.replace("{storageFile}", storageFile);
    }

    public String getStageFile()  { return stageFile; }
    public void setStageFile(String v)  { this.stageFile = v; }

    public String getHeredocEnd() { return heredocEnd; }
    public void setHeredocEnd(String v) { this.heredocEnd = v; }

    public String getApplyConfig() { return applyConfig; }
    public void setApplyConfig(String v) { this.applyConfig = v; }
}
