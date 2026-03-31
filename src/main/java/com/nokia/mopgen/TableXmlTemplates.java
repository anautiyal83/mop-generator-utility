package com.nokia.mopgen;

/**
 * Holds per-action XML templates for one CIQ table.
 * Configured under {@code tables.<TableName>.xmlTemplates} in the YAML template.
 *
 * <p>Only the actions you need to override require a template — any action
 * without a template falls back to the configured {@link XmlBuilder} (e.g. the
 * programmatic {@link NetconfSoiXmlBuilder}).
 *
 * <pre>
 * tables:
 *   CRFTargetList:
 *     xmlTemplates:
 *       create:
 *         envelope: |  ...
 *         record:   |  ...
 *         subRecords:
 *           CRFTargetListEntry: |  ...
 *       delete:
 *         envelope: |  ...
 *         record:   |  ...
 *       rollback:
 *         envelope: |  ...
 *         record:   |  ...
 * </pre>
 *
 * @see ActionTemplate for available placeholders and rendering rules
 */
public class TableXmlTemplates {

    private ActionTemplate create;
    private ActionTemplate delete;
    private ActionTemplate modify;
    private ActionTemplate rollback;

    public ActionTemplate getCreate()   { return create; }
    public void setCreate(ActionTemplate v)   { this.create = v; }

    public ActionTemplate getDelete()   { return delete; }
    public void setDelete(ActionTemplate v)   { this.delete = v; }

    public ActionTemplate getModify()   { return modify; }
    public void setModify(ActionTemplate v)   { this.modify = v; }

    public ActionTemplate getRollback() { return rollback; }
    public void setRollback(ActionTemplate v) { this.rollback = v; }

    /** Return the template for the given action name (case-insensitive), or null if not defined. */
    public ActionTemplate forAction(String action) {
        if (action == null) return null;
        switch (action.toUpperCase()) {
            case "CREATE":   return create;
            case "DELETE":   return delete;
            case "MODIFY":   return modify;
            case "ROLLBACK": return rollback;
            default:         return null;
        }
    }
}
