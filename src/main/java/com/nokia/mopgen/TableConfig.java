package com.nokia.mopgen;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-table XML configuration.
 * Controls the namespace, XML element name, and every column-to-tag mapping
 * for one CIQ sheet. All fields are optional — defaults apply if not specified.
 *
 * <pre>
 * tables:
 *   CRFTargetList:
 *     namespace: "http://nokia.com/yang/isbc-sig"   # optional
 *     xmlElement: "CRFTargetList"                   # optional, defaults to table name
 *     recordElement: "Record"                        # optional, defaults to "Record"
 *     columnMappings:                                # optional per-column overrides
 *       "Record.TARGET_LIST_ID": "TARGET_LIST_ID"
 *       "Record.CRFTargetListEntry": "CRFTargetListEntry"  # sub-table element name
 *       "Record.CRFTargetListEntry.TG_ID": "TG_ID"
 * </pre>
 *
 * <p>Auto-derive rules (when no mapping is configured):
 * <ul>
 *   <li>{@code Record.FIELD} → XML tag {@code FIELD}</li>
 *   <li>{@code Record.SubTable.FIELD} → sub-table element {@code SubTable}, field tag {@code FIELD}</li>
 * </ul>
 */
public class TableConfig {

    /** XML namespace for this table element (null = use defaultNamespace). */
    private String namespace;

    /** XML element name for the table (null = use table name as-is). */
    private String xmlElement;

    /** XML element name for each data record (defaults to "Record"). */
    private String recordElement = "Record";

    /**
     * Per-table attributes added to the XML envelope element (e.g. {@code <config>}).
     * These override the global {@code configAttributes} from {@link MopConfig}
     * for this specific table.
     *
     * <pre>
     * tables:
     *   CRFTargetList:
     *     configAttributes:
     *       ne-type: "SBC-signaling"  # overrides global for this table only
     * </pre>
     */
    private Map<String, String> configAttributes = new LinkedHashMap<>();

    /**
     * Column-to-XML-tag mappings.
     * Keys are full CIQ column paths (e.g. {@code "Record.TARGET_LIST_ID"}).
     * Values are the XML tag/element name to use.
     * Sub-table element names are keyed as {@code "Record.SubTable"}
     * (without the field suffix).
     */
    private Map<String, String> columnMappings = new LinkedHashMap<>();

    /**
     * Optional XML templates for this table, keyed by action (create/delete/modify/rollback).
     * When set, {@link TemplateXmlBuilder} uses these templates instead of programmatic XML
     * generation. Actions without a template fall back to the configured {@link XmlBuilder}.
     */
    private TableXmlTemplates xmlTemplates;

    // -------------------------------------------------------------------------

    /**
     * Resolve the XML tag name for a depth-1 CIQ column (e.g. {@code "Record.TARGET_LIST_ID"}).
     * Checks explicit mapping first; falls back to stripping the {@code "Record."} prefix.
     */
    public String resolveFieldTag(String ciqColumn) {
        if (columnMappings.containsKey(ciqColumn)) {
            return columnMappings.get(ciqColumn);
        }
        // Auto-derive: "Record.FIELD" → "FIELD"
        int dot = ciqColumn.indexOf('.');
        return dot >= 0 ? ciqColumn.substring(dot + 1) : ciqColumn;
    }

    /**
     * Resolve the XML element name for a sub-table.
     * Key in mappings is {@code "Record.SubTableName"}.
     * Falls back to {@code subTableName} itself.
     */
    public String resolveSubTableElement(String subTableName) {
        String key = "Record." + subTableName;
        return columnMappings.getOrDefault(key, subTableName);
    }

    /**
     * Resolve the XML tag name for a depth-2 CIQ column
     * (e.g. {@code "Record.CRFTargetListEntry.TG_ID"}).
     * Falls back to the field name (last segment).
     */
    public String resolveSubFieldTag(String ciqColumn) {
        if (columnMappings.containsKey(ciqColumn)) {
            return columnMappings.get(ciqColumn);
        }
        // Auto-derive: last segment after final "."
        int lastDot = ciqColumn.lastIndexOf('.');
        return lastDot >= 0 ? ciqColumn.substring(lastDot + 1) : ciqColumn;
    }

    public String getNamespace()    { return namespace; }
    public void setNamespace(String v)    { this.namespace = v; }

    public String getXmlElement()   { return xmlElement; }
    public void setXmlElement(String v)   { this.xmlElement = v; }

    public String getRecordElement() { return recordElement != null ? recordElement : "Record"; }
    public void setRecordElement(String v) { this.recordElement = v; }

    public Map<String, String> getColumnMappings() { return columnMappings; }
    public void setColumnMappings(Map<String, String> v) { this.columnMappings = v; }

    public Map<String, String> getConfigAttributes() { return configAttributes; }
    public void setConfigAttributes(Map<String, String> v) {
        this.configAttributes = v != null ? v : new LinkedHashMap<>();
    }

    public TableXmlTemplates getXmlTemplates() { return xmlTemplates; }
    public void setXmlTemplates(TableXmlTemplates v) { this.xmlTemplates = v; }
}
