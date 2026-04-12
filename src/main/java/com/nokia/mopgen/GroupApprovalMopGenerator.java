package com.nokia.mopgen;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Generates a human-readable approval document from a {@link MopSummaryDocument}.
 *
 * <p>The primary entry point is {@link #generate(MopSummaryDocument, String)}, which
 * accepts the generic summary model and writes the output file.  Implementations
 * produce different formats (HTML, DOCX).
 *
 * <p>Use {@link #forConfig(MopConfig)} to obtain the correct implementation.
 * Returns {@code null} for TEXT mode (no separate approval document needed).
 */
public interface GroupApprovalMopGenerator {

    /**
     * Generates an approval summary document from a {@link MopSummaryDocument}.
     *
     * <p>This is the primary, generic entry point.  The document contains all
     * scope metadata ({@link MopSummaryDocument#getMetadata()}) and one unit per node
     * ({@link MopSummaryDocument#getUnits()}), each with its ordered MOP sections.
     *
     * @param summaryDoc the fully populated summary document
     * @param outputPath destination file path (extension determined by the implementation)
     * @throws IOException if the file cannot be written
     */
    void generate(MopSummaryDocument summaryDoc, String outputPath) throws IOException;

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Returns the correct {@link GroupApprovalMopGenerator} implementation for the
     * configured approval format, or {@code null} for TEXT mode.
     *
     * @param config the MOP config carrying {@code mopApprovalFormatType}
     * @return an implementation, or {@code null} when no separate document is needed
     */
    static GroupApprovalMopGenerator forConfig(MopConfig config) {
        String fmt = config.getMopApprovalFormatType();
        if ("HTML".equalsIgnoreCase(fmt))   return new HtmlGroupApprovalMopGenerator(config);
        if ("MSWORD".equalsIgnoreCase(fmt)) return new DocxGroupApprovalMopGenerator(config);
        return null; // TEXT mode
    }

    // -------------------------------------------------------------------------
    // Shared conversion helpers (used by MopGenerator to build MopSummaryDocument)
    // -------------------------------------------------------------------------

    /**
     * Converts a {@link YamlMopSection} to a {@link MopSummarySection}.
     *
     * @param ys       the source YAML section
     * @param rollback {@code true} if the section belongs to the rollback phase
     * @return the equivalent summary section
     */
    static MopSummarySection toSummarySection(YamlMopSection ys, boolean rollback) {
        MopSummarySection s = new MopSummarySection();
        s.setName(ys.name);
        s.setDescription(ys.description);
        s.setTargetNode(ys.targetNode);
        s.setMethod(ys.method);
        s.setTypeMarker(ys.typeMarker);
        s.setRollback(rollback);
        for (YamlMopCommand c : ys.commands) {
            s.getCommands().add(new MopSummaryCommand(c.text, c.description, c.validation));
        }
        return s;
    }

    /**
     * Converts a {@link YamlMopGroup} to a {@link MopSummaryUnit} using the provided
     * unit-info map (which mirrors {@link NodeData#getNodeInfo()}).
     *
     * @param yamlGroup the source YAML group
     * @param unitInfo  scalar node/group identifiers for this unit
     * @return the equivalent summary unit
     */
    static MopSummaryUnit toSummaryUnit(YamlMopGroup yamlGroup, Map<String, String> unitInfo) {
        MopSummaryUnit unit = new MopSummaryUnit();
        unit.addAllUnitInfo(unitInfo);
        for (YamlMopSection ys : yamlGroup.activity) {
            unit.getActivity().add(toSummarySection(ys, false));
        }
        for (YamlMopSection ys : yamlGroup.rollback) {
            unit.getRollback().add(toSummarySection(ys, true));
        }
        return unit;
    }

}
