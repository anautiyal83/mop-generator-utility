package com.nokia.mopgen;

import com.nokia.mopgen.CRGroupIndex;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates a human-readable approval document for a GROUP or CRGROUP MOP.
 * Use {@link #forConfig(MopConfig)} to get the right implementation.
 * When mopApprovalFormatType=TEXT, no approval document is generated (returns null).
 */
public interface GroupApprovalMopGenerator {

    /**
     * Generate an approval summary for a single GROUP MOP.
     * Used by the legacy GROUP-folder mode ({@code --group}).
     */
    void generate(String mopFilePath, GroupIndex groupIndex, String outputPath) throws IOException;

    /**
     * Generate a CR-level approval summary spanning one or more GROUP MOPs.
     *
     * <p>The summary shows every GROUP that participates in the CRGROUP, the nodes in
     * each GROUP, and the MOP sections parsed from each GROUP's generated MOP file.
     *
     * @param crGroup       CRGROUP identifier, e.g. {@code "CR-001"}
     * @param crGroupIndex  index with group entries and node lists for this CRGROUP
     * @param groupMopPaths GROUP name → path to the generated MOP file for that group
     * @param outputPath    destination path for the summary document
     */
    void generateForCRGroup(String crGroup,
                             CRGroupIndex crGroupIndex,
                             Map<String, String> groupMopPaths,
                             String outputPath) throws IOException;

    /** Returns null for TEXT mode (no separate approval doc needed). */
    static GroupApprovalMopGenerator forConfig(MopConfig config) {
        String fmt = config.getMopApprovalFormatType();
        if ("HTML".equalsIgnoreCase(fmt)) {
            return new HtmlGroupApprovalMopGenerator(config);
        }
        if ("MSWORD".equalsIgnoreCase(fmt)) {
            return new DocxGroupApprovalMopGenerator(config);
        }
        return null; // TEXT mode
    }

    /** Parse a generated MOP file into ordered MopSections. */
    static List<MopSection> parseMopFile(String mopContent) {
        List<MopSection> sections = new ArrayList<>();
        MopSection current = null;
        boolean inPayload = false;
        List<MopSection.CommandLine> payloadLines = new ArrayList<>();
        boolean inRollback = false;

        for (String raw : mopContent.split("\n")) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            String trimmed = line.trim();

            if (trimmed.startsWith("##") && trimmed.length() > 2
                    && trimmed.charAt(2) != '#' && trimmed.charAt(2) != ' ') {
                // Block header line
                if (current != null) {
                    current.commands = new ArrayList<>(payloadLines);
                    sections.add(current);
                    payloadLines.clear();
                }
                current = new MopSection();
                current.name = trimmed.substring(2);
                current.rollback = inRollback;
                inPayload = false;
            } else if (trimmed.equals("## ROLLBACK")) {
                inRollback = true;
            } else if (current != null) {
                if (trimmed.startsWith("## Description:")) {
                    current.description = trimmed.substring("## Description:".length()).trim();
                } else if (trimmed.startsWith("## TargetNode:")) {
                    current.targetNode = trimmed.substring("## TargetNode:".length()).trim();
                } else if (trimmed.startsWith("$") && !inPayload) {
                    current.typeMarker = trimmed.substring(1);
                } else if (trimmed.matches("ACTIVITY_EXECUTION_METHOD_\\d+=.*")) {
                    current.method = trimmed.split("=", 2)[1];
                } else if (trimmed.matches("ACTIVITY_EXECUTION_PAYLOAD_\\d+=\\{")) {
                    inPayload = true;
                } else if (inPayload && trimmed.equals("}")) {
                    inPayload = false;
                } else if (inPayload) {
                    payloadLines.add(new MopSection.CommandLine(line));
                }
            }
        }
        if (current != null) {
            current.commands = new ArrayList<>(payloadLines);
            sections.add(current);
        }
        return sections;
    }

    /**
     * Load per-command metadata from the sidecar {@code <mopFilePath>.meta.json} file.
     * Returns an empty map if the file does not exist or cannot be read.
     */
    static Map<String, List<MopSection.CommandLine>> loadMetadata(String mopFilePath) {
        File metaFile = new File(mopFilePath + ".meta.json");
        if (!metaFile.exists()) return Collections.emptyMap();
        try {
            Map<String, List<Map<String, String>>> raw =
                    new ObjectMapper().readValue(metaFile,
                            new TypeReference<Map<String, List<Map<String, String>>>>() {});
            Map<String, List<MopSection.CommandLine>> result = new LinkedHashMap<>();
            for (Map.Entry<String, List<Map<String, String>>> e : raw.entrySet()) {
                List<MopSection.CommandLine> lines = new ArrayList<>();
                for (Map<String, String> m : e.getValue()) {
                    lines.add(new MopSection.CommandLine(
                            m.get("text"), m.get("description"), m.get("validation")));
                }
                result.put(e.getKey(), lines);
            }
            return result;
        } catch (IOException ex) {
            return Collections.emptyMap();
        }
    }

    /**
     * Merge per-command metadata from a sidecar into already-parsed sections.
     * For each section whose name matches a key in {@code meta}, the section's
     * command list is replaced with the enriched list from the sidecar
     * (provided the command counts match).
     */
    static void mergeMetadata(List<MopSection> sections,
                               Map<String, List<MopSection.CommandLine>> meta) {
        if (meta.isEmpty()) return;
        for (MopSection s : sections) {
            List<MopSection.CommandLine> enriched = meta.get(s.name);
            if (enriched != null && enriched.size() == s.commands.size()) {
                s.commands = enriched;
            }
        }
    }
}
