package com.nokia.mopgen;

import com.nokia.ciq.reader.model.CiqRow;
import com.nokia.ciq.reader.model.CiqSheet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Template-driven {@link XmlBuilder} implementation.
 *
 * <p>For each table and action, checks whether an {@link ActionTemplate} is defined
 * under {@code tables.<Name>.xmlTemplates} in the YAML. If one is found, renders
 * the XML by substituting {@code ${placeholder}} tokens with CIQ values.
 * If no template is found, delegates to the wrapped fallback builder (typically
 * {@link NetconfSoiXmlBuilder}).
 *
 * <h3>Rendering order</h3>
 * <ol>
 *   <li>Build envelope variables: {@code ${ne}}, {@code ${namespace}},
 *       {@code ${netconfNamespace}}, all {@code configAttributes} entries.</li>
 *   <li>For each record group: substitute depth-1 field values, render sub-table
 *       entries into {@code ${subrecords.Name}}, then render the record template.</li>
 *   <li>Concatenate all rendered records and substitute {@code ${records}} in
 *       the envelope.</li>
 * </ol>
 *
 * <h3>CREATE grouping</h3>
 * CREATE rows are grouped by the first non-null depth-1 column value (the primary key).
 * Depth-1 field values are taken from the first row in each group; depth-2 sub-table
 * entries are rendered once per row.
 *
 * <h3>DELETE / MODIFY / ROLLBACK</h3>
 * Each CIQ row is rendered independently — no grouping.
 *
 * <h3>Blank values</h3>
 * Any placeholder that resolves to null or blank is substituted with an empty string.
 *
 * @see ActionTemplate for placeholder reference
 */
public class TemplateXmlBuilder implements XmlBuilder {

    private final MopConfig config;
    private final XmlBuilder fallback;

    /**
     * @param config   full MOP config (for namespace, netconfNamespace, configAttributes)
     * @param fallback builder to delegate to when no template is defined for a table/action
     */
    public TemplateXmlBuilder(MopConfig config, XmlBuilder fallback) {
        this.config   = config;
        this.fallback = fallback;
    }

    // -------------------------------------------------------------------------
    // XmlBuilder interface
    // -------------------------------------------------------------------------

    @Override
    public String buildXml(CiqSheet sheet, String neId, String tableName,
                           TableConfig tableConfig, List<CiqRow> rows, String action) {
        ActionTemplate template = resolveTemplate(tableConfig, action);
        if (template == null) {
            return fallback.buildXml(sheet, neId, tableName, tableConfig, rows, action);
        }

        Map<String, String> envVars = buildEnvVars(neId, tableConfig);
        StringBuilder records = new StringBuilder();

        switch (action.toUpperCase()) {
            case "CREATE":
                for (List<CiqRow> group : groupById(sheet, rows).values()) {
                    records.append(renderRecord(template, envVars, sheet, group));
                }
                break;
            default:
                // DELETE, MODIFY: one rendered block per row, no grouping
                for (CiqRow row : rows) {
                    List<CiqRow> single = new ArrayList<>();
                    single.add(row);
                    records.append(renderRecord(template, envVars, sheet, single));
                }
                break;
        }

        Map<String, String> envelopeVars = new LinkedHashMap<>(envVars);
        envelopeVars.put("records", records.toString());
        return substitute(template.getEnvelope(), envelopeVars);
    }

    @Override
    public String buildRollbackXml(CiqSheet sheet, String neId, String tableName,
                                   TableConfig tableConfig, List<CiqRow> rows) {
        ActionTemplate template = resolveTemplate(tableConfig, "ROLLBACK");
        if (template == null) {
            return fallback.buildRollbackXml(sheet, neId, tableName, tableConfig, rows);
        }

        Map<String, String> envVars = buildEnvVars(neId, tableConfig);
        StringBuilder records = new StringBuilder();
        for (CiqRow row : rows) {
            List<CiqRow> single = new ArrayList<>();
            single.add(row);
            records.append(renderRecord(template, envVars, sheet, single));
        }

        Map<String, String> envelopeVars = new LinkedHashMap<>(envVars);
        envelopeVars.put("records", records.toString());
        return substitute(template.getEnvelope(), envelopeVars);
    }

    // -------------------------------------------------------------------------
    // Template rendering
    // -------------------------------------------------------------------------

    /**
     * Render one record block (depth-1 fields + sub-table entries) from a row group.
     * Depth-1 values are taken from the first non-null occurrence across the group.
     * Sub-table entries are rendered once per row that has data for that sub-table.
     */
    private String renderRecord(ActionTemplate template, Map<String, String> envVars,
                                CiqSheet sheet, List<CiqRow> group) {
        Map<String, String> vars = new LinkedHashMap<>(envVars);

        // Depth-1 fields: first non-null across the group
        for (String col : sheet.getColumns()) {
            if (!col.startsWith("Record.")) continue;
            if (col.substring("Record.".length()).contains(".")) continue;  // skip depth-2
            String value = firstNonNull(col, group);
            vars.put(col, value != null ? value : "");
        }

        // Sub-table entries: render each sub-table's template per row, concat
        if (template.getSubRecords() != null) {
            for (Map.Entry<String, String> subEntry : template.getSubRecords().entrySet()) {
                String subTableName = subEntry.getKey();
                String subTemplate  = subEntry.getValue();
                String prefix       = "Record." + subTableName + ".";
                StringBuilder subRendered = new StringBuilder();

                for (CiqRow row : group) {
                    // Skip rows that have no data for this sub-table
                    boolean hasData = false;
                    for (Map.Entry<String, String> cell : row.getData().entrySet()) {
                        if (cell.getKey().startsWith(prefix) && cell.getValue() != null) {
                            hasData = true;
                            break;
                        }
                    }
                    if (!hasData) continue;

                    Map<String, String> subVars = new LinkedHashMap<>(vars);
                    for (Map.Entry<String, String> cell : row.getData().entrySet()) {
                        subVars.put(cell.getKey(), cell.getValue() != null ? cell.getValue() : "");
                    }
                    subRendered.append(substitute(subTemplate, subVars));
                }
                vars.put("subrecords." + subTableName, subRendered.toString());
            }
        }

        return substitute(template.getRecord(), vars);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Build the base variable map from NE id, namespace, configAttributes. */
    private Map<String, String> buildEnvVars(String neId, TableConfig tableConfig) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("ne", neId != null ? neId : "");
        vars.put("namespace", tableConfig.getNamespace() != null ? tableConfig.getNamespace() : "");
        vars.put("netconfNamespace",
                config.getNetconfNamespace() != null ? config.getNetconfNamespace() : "");
        for (Map.Entry<String, String> attr : tableConfig.getConfigAttributes().entrySet()) {
            vars.put(attr.getKey(), attr.getValue() != null ? attr.getValue() : "");
        }
        return vars;
    }

    /** Replace all {@code ${key}} tokens in the template with their values. */
    private String substitute(String template, Map<String, String> vars) {
        if (template == null) return "";
        String result = template;
        for (Map.Entry<String, String> var : vars.entrySet()) {
            result = result.replace("${" + var.getKey() + "}", var.getValue());
        }
        return result;
    }

    /**
     * Group CREATE rows by primary key (first non-null depth-1 column value).
     * Rows with the same key belong to the same parent record (e.g., one
     * CRFTargetList entry with multiple CRFTargetListEntry sub-rows).
     */
    private Map<String, List<CiqRow>> groupById(CiqSheet sheet, List<CiqRow> rows) {
        Map<String, List<CiqRow>> grouped = new LinkedHashMap<>();
        for (CiqRow row : rows) {
            String key = null;
            for (String col : sheet.getColumns()) {
                if (!col.startsWith("Record.")) continue;
                if (col.substring("Record.".length()).contains(".")) continue;
                String v = row.getData().get(col);
                if (v != null) { key = v; break; }
            }
            if (key == null) key = "__row_" + row.getRowNumber();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private String firstNonNull(String column, List<CiqRow> rows) {
        for (CiqRow row : rows) {
            String v = row.getData().get(column);
            if (v != null) return v;
        }
        return null;
    }

    private ActionTemplate resolveTemplate(TableConfig tableConfig, String action) {
        TableXmlTemplates templates = tableConfig.getXmlTemplates();
        if (templates == null) return null;
        return templates.forAction(action);
    }
}
