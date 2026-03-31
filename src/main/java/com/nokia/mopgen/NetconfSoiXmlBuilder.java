package com.nokia.mopgen;

import com.nokia.ciq.reader.model.CiqRow;
import com.nokia.ciq.reader.model.CiqSheet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nokia SOI NETCONF XML builder.
 *
 * <p>Produces XML in the Nokia SOI NETCONF-over-CLI format:
 * <pre>
 * {@literal <?xml version="1.0" encoding="UTF-8"?>}
 * {@literal <config ne="<neId>">}
 * {@literal   <TableElement xmlns="<ns>" xmlns:xc="<netconfNs>">}
 * {@literal     <Record xc:operation="create">}
 * {@literal       <FIELD>value</FIELD>}
 * {@literal     </Record>}
 * {@literal   </TableElement>}
 * {@literal </config>}
 * </pre>
 *
 * <p>Select this builder in the YAML template with:
 * <pre>
 * xmlBuilder: "netconf-soi"
 * </pre>
 *
 * <p>CIQ column naming conventions assumed by this builder:
 * <ul>
 *   <li>{@code Record.FIELD} — depth-1 field inside the record element</li>
 *   <li>{@code Record.SubTable.FIELD} — depth-2 field inside a sub-table element</li>
 * </ul>
 *
 * <p>Action → xc:operation mapping:
 * <ul>
 *   <li>CREATE → {@code xc:operation="create"} on Record</li>
 *   <li>DELETE → {@code xc:operation="delete"} on Record (primary key only)</li>
 *   <li>MODIFY, ActionKey=Record → {@code xc:operation="merge"} on Record</li>
 *   <li>MODIFY, ActionKey=Record.SubTable, SubAction=ADD → {@code xc:operation="create"} on SubTable</li>
 *   <li>MODIFY, ActionKey=Record.SubTable, SubAction=DEL → {@code xc:operation="delete"} on SubTable</li>
 *   <li>MODIFY, ActionKey=Record.SubTable, SubAction=MOD → {@code xc:operation="merge"} on SubTable</li>
 * </ul>
 */
public class NetconfSoiXmlBuilder implements XmlBuilder {

    private final String netconfNs;

    /**
     * @param netconfNamespace NETCONF base namespace URI declared as {@code xmlns:xc}
     *                         on every table element. Typically
     *                         {@code "urn:ietf:params:xml:ns:netconf:base:1.0"}.
     */
    public NetconfSoiXmlBuilder(String netconfNamespace) {
        this.netconfNs = netconfNamespace;
    }

    // -------------------------------------------------------------------------
    // XmlBuilder interface
    // -------------------------------------------------------------------------

    @Override
    public String buildXml(CiqSheet sheet, String neId, String tableName,
                           TableConfig tableConfig, List<CiqRow> rows, String action) {
        String xmlElement = tableConfig.getXmlElement() != null ? tableConfig.getXmlElement() : tableName;
        String namespace  = tableConfig.getNamespace();
        String recordElem = tableConfig.getRecordElement();

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<config ne=\"").append(escapeXml(neId)).append("\"");
        for (Map.Entry<String, String> attr : tableConfig.getConfigAttributes().entrySet()) {
            sb.append(" ").append(attr.getKey()).append("=\"")
              .append(escapeXml(attr.getValue())).append("\"");
        }
        sb.append(">\n");
        sb.append("  <").append(xmlElement)
          .append(" xmlns=\"").append(namespace).append("\"")
          .append("\n             xmlns:xc=\"").append(netconfNs).append("\">\n");

        switch (action.toUpperCase()) {
            case "CREATE": writeCreateRecords(sb, sheet, rows, tableConfig, recordElem); break;
            case "DELETE": writeDeleteRecords(sb, sheet, rows, tableConfig, recordElem); break;
            case "MODIFY": writeModifyRecords(sb, sheet, rows, tableConfig, recordElem); break;
            default: throw new IllegalArgumentException("Unknown action: " + action);
        }

        sb.append("  </").append(xmlElement).append(">\n");
        sb.append("</config>\n");
        return sb.toString();
    }

    @Override
    public String buildRollbackXml(CiqSheet sheet, String neId, String tableName,
                                   TableConfig tableConfig, List<CiqRow> rows) {
        String xmlElement = tableConfig.getXmlElement() != null ? tableConfig.getXmlElement() : tableName;
        String namespace  = tableConfig.getNamespace();
        String recordElem = tableConfig.getRecordElement();
        Map<String, List<CiqRow>> byId = groupById(rows);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<config ne=\"").append(escapeXml(neId)).append("\"");
        for (Map.Entry<String, String> attr : tableConfig.getConfigAttributes().entrySet()) {
            sb.append(" ").append(attr.getKey()).append("=\"")
              .append(escapeXml(attr.getValue())).append("\"");
        }
        sb.append(">\n");
        sb.append("  <").append(xmlElement)
          .append(" xmlns=\"").append(namespace).append("\"")
          .append("\n             xmlns:xc=\"").append(netconfNs).append("\">\n");

        for (List<CiqRow> group : byId.values()) {
            sb.append("    <").append(recordElem).append(" xc:operation=\"delete\">\n");
            for (String col : sheet.getColumns()) {
                if (!col.startsWith("Record.")) continue;
                String suffix = col.substring("Record.".length());
                if (suffix.contains(".")) continue;          // skip depth-2
                String value = firstNonNull(col, group);
                if (value != null) {
                    String tag = tableConfig.resolveFieldTag(col);
                    sb.append("      <").append(tag).append(">")
                      .append(escapeXml(value))
                      .append("</").append(tag).append(">\n");
                    break;   // primary key only
                }
            }
            sb.append("    </").append(recordElem).append(">\n");
        }

        sb.append("  </").append(xmlElement).append(">\n");
        sb.append("</config>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Private — per-action writers
    // -------------------------------------------------------------------------

    private void writeCreateRecords(StringBuilder sb, CiqSheet sheet, List<CiqRow> rows,
                                    TableConfig tableConfig, String recordElem) {
        for (List<CiqRow> group : groupById(rows).values()) {
            sb.append("    <").append(recordElem).append(" xc:operation=\"create\">\n");
            writeRecordFields(sb, sheet, group, tableConfig);
            sb.append("    </").append(recordElem).append(">\n");
        }
    }

    private void writeDeleteRecords(StringBuilder sb, CiqSheet sheet, List<CiqRow> rows,
                                    TableConfig tableConfig, String recordElem) {
        for (CiqRow row : rows) {
            sb.append("    <").append(recordElem).append(" xc:operation=\"delete\">\n");
            for (String col : sheet.getColumns()) {
                if (!col.startsWith("Record.")) continue;
                String suffix = col.substring("Record.".length());
                if (suffix.contains(".")) continue;    // skip depth-2
                String value = row.getData().get(col);
                if (value != null) {
                    String tag = tableConfig.resolveFieldTag(col);
                    sb.append("      <").append(tag).append(">")
                      .append(escapeXml(value))
                      .append("</").append(tag).append(">\n");
                }
            }
            sb.append("    </").append(recordElem).append(">\n");
        }
    }

    private void writeModifyRecords(StringBuilder sb, CiqSheet sheet, List<CiqRow> rows,
                                    TableConfig tableConfig, String recordElem) {
        for (CiqRow row : rows) {
            String actionKey = row.getData().get("ActionKey");
            String subAction = row.getData().get("SubAction");
            boolean directRecordOp = (actionKey == null || "Record".equals(actionKey));

            sb.append("    <").append(recordElem);
            if (directRecordOp) sb.append(" xc:operation=\"merge\"");
            sb.append(">\n");

            String targetSubTable = null;
            if (actionKey != null && actionKey.startsWith("Record.")) {
                targetSubTable = actionKey.substring("Record.".length());
            }

            Set<String> writtenSubTables = new LinkedHashSet<>();

            for (String col : sheet.getColumns()) {
                if (!col.startsWith("Record.")) continue;
                String suffix = col.substring("Record.".length());
                int dot = suffix.indexOf('.');

                if (dot < 0) {
                    // depth-1 field
                    String value = row.getData().get(col);
                    if (value != null) {
                        String tag = tableConfig.resolveFieldTag(col);
                        sb.append("      <").append(tag).append(">")
                          .append(escapeXml(value))
                          .append("</").append(tag).append(">\n");
                    }
                } else {
                    // depth-2 field — only emit the targeted sub-table
                    String subTable = suffix.substring(0, dot);
                    if (!subTable.equals(targetSubTable)) continue;
                    if (writtenSubTables.contains(subTable)) continue;
                    writtenSubTables.add(subTable);

                    String subElem = tableConfig.resolveSubTableElement(subTable);
                    String xcOp = subActionToOperation(subAction);
                    sb.append("      <").append(subElem)
                      .append(" xc:operation=\"").append(xcOp).append("\">\n");

                    for (String stCol : sheet.getColumns()) {
                        if (!stCol.startsWith("Record." + subTable + ".")) continue;
                        String stValue = row.getData().get(stCol);
                        if (stValue != null) {
                            String stTag = tableConfig.resolveSubFieldTag(stCol);
                            sb.append("        <").append(stTag).append(">")
                              .append(escapeXml(stValue))
                              .append("</").append(stTag).append(">\n");
                        }
                    }
                    sb.append("      </").append(subElem).append(">\n");
                }
            }

            sb.append("    </").append(recordElem).append(">\n");
        }
    }

    /**
     * Write all fields for a group of CREATE rows (same parent ID).
     * Depth-1 fields are emitted once (first non-null across the group).
     * Depth-2 sub-table groups emit one element per row that has values.
     */
    private void writeRecordFields(StringBuilder sb, CiqSheet sheet, List<CiqRow> group,
                                   TableConfig tableConfig) {
        Set<String> writtenSubTables = new LinkedHashSet<>();

        for (String col : sheet.getColumns()) {
            if (!col.startsWith("Record.")) continue;
            String suffix = col.substring("Record.".length());
            int dot = suffix.indexOf('.');

            if (dot < 0) {
                // depth-1: emit once using first non-null value
                String value = firstNonNull(col, group);
                if (value != null) {
                    String tag = tableConfig.resolveFieldTag(col);
                    sb.append("      <").append(tag).append(">")
                      .append(escapeXml(value))
                      .append("</").append(tag).append(">\n");
                }
            } else {
                // depth-2: emit one sub-table element per row (when first column encountered)
                String subTable = suffix.substring(0, dot);
                if (writtenSubTables.contains(subTable)) continue;
                writtenSubTables.add(subTable);

                String subElem = tableConfig.resolveSubTableElement(subTable);

                List<String> subTableCols = new ArrayList<>();
                for (String c : sheet.getColumns()) {
                    if (c.startsWith("Record." + subTable + ".")) subTableCols.add(c);
                }

                for (CiqRow row : group) {
                    boolean hasAny = false;
                    for (String stCol : subTableCols) {
                        if (row.getData().get(stCol) != null) { hasAny = true; break; }
                    }
                    if (!hasAny) continue;

                    sb.append("      <").append(subElem).append(">\n");
                    for (String stCol : subTableCols) {
                        String stValue = row.getData().get(stCol);
                        if (stValue != null) {
                            String stTag = tableConfig.resolveSubFieldTag(stCol);
                            sb.append("        <").append(stTag).append(">")
                              .append(escapeXml(stValue))
                              .append("</").append(stTag).append(">\n");
                        }
                    }
                    sb.append("      </").append(subElem).append(">\n");
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Map<String, List<CiqRow>> groupById(List<CiqRow> rows) {
        Map<String, List<CiqRow>> byId = new LinkedHashMap<>();
        for (CiqRow row : rows) {
            String id = row.getData().get("ID");
            if (id == null) id = "__row_" + row.getRowNumber();
            byId.computeIfAbsent(id, k -> new ArrayList<>()).add(row);
        }
        return byId;
    }

    private String firstNonNull(String column, List<CiqRow> rows) {
        for (CiqRow row : rows) {
            String value = row.getData().get(column);
            if (value != null) return value;
        }
        return null;
    }

    private String subActionToOperation(String subAction) {
        if ("ADD".equals(subAction)) return "create";
        if ("DEL".equals(subAction)) return "delete";
        return "merge";   // MOD or default
    }

    private String escapeXml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;");
    }
}
