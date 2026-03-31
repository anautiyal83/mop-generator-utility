package com.nokia.mopgen;

import com.nokia.ciq.reader.model.CiqRow;
import com.nokia.ciq.reader.model.CiqSheet;

import java.util.List;

/**
 * Strategy interface for building XML payloads from CIQ data.
 *
 * <p>Implement this interface to support a new XML format or vendor without
 * touching any other framework code. Register the implementation name in
 * {@link XmlBuilderFactory} and select it via the YAML template:
 *
 * <pre>
 * xmlBuilder: "netconf-soi"   # built-in Nokia SOI NETCONF
 * # xmlBuilder: "my-custom"  # your own registered implementation
 * </pre>
 *
 * <p>The framework calls {@link #buildXml} for each table/action combination
 * and {@link #buildRollbackXml} for every table that had CREATE rows.
 */
public interface XmlBuilder {

    /**
     * Build an XML payload for a batch of CIQ rows from a single table.
     *
     * @param sheet       the CIQ sheet (provides the ordered column list)
     * @param neId        network element identifier (e.g. from NIAM mapping)
     * @param tableName   CIQ table name (used as fallback element name)
     * @param tableConfig per-table XML configuration from the YAML template
     * @param rows        rows to include — all have the same Action value
     * @param action      "CREATE", "DELETE", or "MODIFY"
     * @return complete XML string to embed in the MOP heredoc
     */
    String buildXml(CiqSheet sheet, String neId, String tableName,
                    TableConfig tableConfig, List<CiqRow> rows, String action);

    /**
     * Build a rollback XML payload that undoes previously created records.
     * Typically generates DELETE operations keyed on the primary key only.
     *
     * @param sheet       the CIQ sheet
     * @param neId        network element identifier
     * @param tableName   CIQ table name
     * @param tableConfig per-table XML configuration
     * @param rows        the original CREATE rows to roll back
     * @return complete XML string for the rollback heredoc
     */
    String buildRollbackXml(CiqSheet sheet, String neId, String tableName,
                            TableConfig tableConfig, List<CiqRow> rows);
}
