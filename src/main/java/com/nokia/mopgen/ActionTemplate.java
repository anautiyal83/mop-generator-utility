package com.nokia.mopgen;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * XML template for one action (CREATE, DELETE, MODIFY, or ROLLBACK).
 *
 * <p>Three levels correspond to the XML structure:
 * <pre>
 * envelope  →  outer XML (config + table wrapper), contains ${records}
 * record    →  one per CIQ record group, contains ${subrecords.SubTableName}
 * subRecords → one entry per sub-table name, one instance per sub-table row
 * </pre>
 *
 * <p>Available placeholders in <b>envelope</b>:
 * <ul>
 *   <li>{@code ${ne}}               — NE identifier from NIAM mapping</li>
 *   <li>{@code ${namespace}}         — table XML namespace</li>
 *   <li>{@code ${netconfNamespace}}  — NETCONF base namespace</li>
 *   <li>{@code ${<configAttrKey>}}   — any key from configAttributes (e.g. {@code ${ne-version}})</li>
 *   <li>{@code ${records}}           — substituted with all rendered record blocks</li>
 * </ul>
 *
 * <p>Available placeholders in <b>record</b>:
 * <ul>
 *   <li>All envelope placeholders (except {@code ${records}})</li>
 *   <li>{@code ${Record.FIELD}}      — depth-1 field value (e.g. {@code ${Record.TARGET_LIST_ID}})</li>
 *   <li>{@code ${subrecords.Name}}   — substituted with rendered sub-records for sub-table Name</li>
 * </ul>
 *
 * <p>Available placeholders in <b>subRecords</b> entries:
 * <ul>
 *   <li>All envelope + record placeholders</li>
 *   <li>{@code ${Record.SubTable.FIELD}} — depth-2 field value</li>
 * </ul>
 *
 * <p>Any placeholder that resolves to null or blank is substituted with an empty string.
 *
 * <h3>YAML example</h3>
 * <pre>
 * tables:
 *   CRFTargetList:
 *     xmlTemplates:
 *       create:
 *         envelope: |
 *           &lt;?xml version="1.0" encoding="UTF-8"?&gt;
 *           &lt;config ne="${ne}" ne-version="${ne-version}"&gt;
 *             &lt;CRFTargetList xmlns="${namespace}" xmlns:xc="${netconfNamespace}"&gt;
 *           ${records}
 *             &lt;/CRFTargetList&gt;
 *           &lt;/config&gt;
 *         record: |
 *             &lt;Record xc:operation="create"&gt;
 *               &lt;TARGET_LIST_ID&gt;${Record.TARGET_LIST_ID}&lt;/TARGET_LIST_ID&gt;
 *               &lt;DESCRIPTION&gt;${Record.DESCRIPTION}&lt;/DESCRIPTION&gt;
 *           ${subrecords.CRFTargetListEntry}
 *             &lt;/Record&gt;
 *         subRecords:
 *           CRFTargetListEntry: |
 *               &lt;CRFTargetListEntry&gt;
 *                 &lt;TARGET_ID&gt;${Record.CRFTargetListEntry.TARGET_ID}&lt;/TARGET_ID&gt;
 *               &lt;/CRFTargetListEntry&gt;
 * </pre>
 */
public class ActionTemplate {

    /** Outer XML wrapping all records. Must contain {@code ${records}} placeholder. */
    private String envelope;

    /** Single record block. Rendered once per CIQ record group. */
    private String record;

    /**
     * Per sub-table templates. Key = sub-table name (e.g. {@code "CRFTargetListEntry"}).
     * Each entry is rendered once per sub-table row and concatenated into
     * {@code ${subrecords.Name}} inside the record template.
     */
    private Map<String, String> subRecords = new LinkedHashMap<>();

    public String getEnvelope() { return envelope; }
    public void setEnvelope(String envelope) { this.envelope = envelope; }

    public String getRecord() { return record; }
    public void setRecord(String record) { this.record = record; }

    public Map<String, String> getSubRecords() { return subRecords; }
    public void setSubRecords(Map<String, String> subRecords) {
        this.subRecords = subRecords != null ? subRecords : new LinkedHashMap<>();
    }
}
