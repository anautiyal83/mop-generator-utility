package com.nokia.mopgen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates an HTML MOP approval summary from a {@link MopSummaryDocument}.
 *
 * <h3>Template syntax — identical to the CIQ validation-report template</h3>
 *
 * <h4>Scalar tokens</h4>
 * <p>{@code {{key}}} is replaced with the corresponding value anywhere in the template.
 *
 * <h4>Loop / conditional sections</h4>
 * <p>{@code {{#sectionName}}...{{/sectionName}}} — the inner block is expanded once per
 * iteration (loops) or kept / removed (conditionals).
 *
 * <h3>Token and section reference</h3>
 *
 * <h4>Document-level scalar tokens (replaced once, anywhere)</h4>
 * <pre>
 * {{TITLE}}        — computed page title
 * {{nodeType}}     — e.g. MRF
 * {{activity}}     — e.g. ANNOUNCEMENT_LOADING
 * {{crGroup}}      — e.g. CR001
 * {{group}}        — e.g. A
 * {{generated}}    — timestamp
 * </pre>
 *
 * <h4>Loops</h4>
 * <pre>
 * {{#nodeChips}}                        — repeats per node (for overview chips)
 *   {{nodeInfo.&lt;key&gt;}}              — any nodeInfo field
 * {{/nodeChips}}
 *
 * {{#nodes}}                            — repeats per node (full detail block)
 *   {{nodeInfo.&lt;key&gt;}}              — any nodeInfo field (e.g. node, niamID)
 *   {{NODE_ID}}                         — sanitised HTML id
 *
 *   {{#nodeExtraInfo}}                  — repeats per extra nodeInfo field
 *     {{extraInfo.key}}
 *     {{extraInfo.value}}
 *   {{/nodeExtraInfo}}
 *
 *   {{#if_hasActivitySections}}...{{/if_hasActivitySections}}
 *   {{#activitySections}}               — repeats per activity section
 *     {{section.number}}
 *     {{section.name}}
 *     {{section.description}}
 *     {{section.targetNode}}
 *     {{section.method}}
 *     {{section.typeMarker}}
 *     {{section.typeLabel}}
 *     {{section.typeBadgeClass}}
 *     {{#if_sectionDescription}}...{{/if_sectionDescription}}
 *     {{#if_sectionTargetNode}}...{{/if_sectionTargetNode}}
 *     {{#if_sectionMethod}}...{{/if_sectionMethod}}
 *     {{#if_sectionTypeLabel}}...{{/if_sectionTypeLabel}}
 *     {{#commandsTable}}                — present only when commands have metadata
 *       {{#commandRow}}
 *         {{cmd.number}}  {{cmd.text}}  {{cmd.description}}
 *         {{cmd.validation}}  {{cmd.commentClass}}
 *       {{/commandRow}}
 *     {{/commandsTable}}
 *     {{#commandsBlock}}                — present only when commands are plain text
 *       {{#commandLine}}
 *         {{cmd.text}}
 *       {{/commandLine}}
 *     {{/commandsBlock}}
 *   {{/activitySections}}
 *
 *   {{#if_hasRollbackSections}}...{{/if_hasRollbackSections}}
 *   {{#rollbackSections}}               — same tokens as activitySections
 *     ...
 *   {{/rollbackSections}}
 * {{/nodes}}
 * </pre>
 */
public class HtmlGroupApprovalMopGenerator implements GroupApprovalMopGenerator {

    private static final Logger log = LoggerFactory.getLogger(HtmlGroupApprovalMopGenerator.class);

    private final MopConfig config;

    public HtmlGroupApprovalMopGenerator(MopConfig config) {
        this.config = config;
    }

    // =========================================================================
    // Primary entry point
    // =========================================================================

    @Override
    public void generate(MopSummaryDocument summaryDoc, String outputPath) throws IOException {

        String today = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());
        summaryDoc.addMeta("generated", today);

        String nodeNameKey = config.getJsonMapping().getOrDefault("nodeNameKey", "node");
        String neIdKey     = config.getJsonMapping().getOrDefault("neIdKey",     "niamID");
        List<MopSummaryUnit> units = summaryDoc.getUnits();

        String html = loadTemplate();

        // ── Step 1: conditional blocks ───────────────────────────────────────
        // (none at document level currently; kept for symmetry with validation report)

        // ── Step 2: loop / section expansion ────────────────────────────────

        // {{#nodeChips}} — one chip per node
        html = expandSection(html, "nodeChips", inner -> {
            StringBuilder sb = new StringBuilder();
            for (MopSummaryUnit unit : units) {
                sb.append(applyNodeInfoTokens(inner, unit.getUnitInfo()));
            }
            return sb.toString();
        });

        // {{#nodes}} — full detail block per node
        html = expandSection(html, "nodes", nodeTemplate -> {
            StringBuilder sb = new StringBuilder();
            for (MopSummaryUnit unit : units) {
                String nodeName = unit.getInfo(nodeNameKey, firstValue(unit.getUnitInfo()));
                String unitId   = sanitiseId(nodeName);

                String block = nodeTemplate;

                // Conditionals for phase presence
                block = applyConditional(block, "if_hasActivitySections", !unit.getActivity().isEmpty());
                block = applyConditional(block, "if_hasRollbackSections", !unit.getRollback().isEmpty());

                // {{#nodeExtraInfo}} — extra scalar fields (excludes nodeName and neId keys)
                final Map<String, String> extraInfo = getExtraInfo(unit.getUnitInfo(), nodeNameKey, neIdKey);
                block = expandSection(block, "nodeExtraInfo", extraTemplate -> {
                    StringBuilder eSb = new StringBuilder();
                    for (Map.Entry<String, String> e : extraInfo.entrySet()) {
                        eSb.append(extraTemplate
                                .replace("{{extraInfo.key}}",   esc(e.getKey()))
                                .replace("{{extraInfo.value}}", esc(e.getValue())));
                    }
                    return eSb.toString();
                });

                // {{#activitySections}}
                block = expandSection(block, "activitySections",
                        sectionTemplate -> expandSections(sectionTemplate, unit.getActivity()));

                // {{#rollbackSections}}
                block = expandSection(block, "rollbackSections",
                        sectionTemplate -> expandSections(sectionTemplate, unit.getRollback()));

                // Node-level scalar tokens
                block = applyNodeInfoTokens(block, unit.getUnitInfo());
                block = block.replace("{{NODE_ID}}", unitId);

                sb.append(block);
            }
            return sb.toString();
        });

        // ── Step 3: document-level scalar substitutions ──────────────────────
        html = html.replace("{{TITLE}}", esc(buildTitle(summaryDoc)));
        for (Map.Entry<String, String> e : summaryDoc.getMetadata().entrySet()) {
            html = html.replace("{{" + e.getKey() + "}}", esc(e.getValue()));
        }

        // ── Step 4: remove any tokens that were never substituted ────────────
        html = html.replaceAll("\\{\\{[A-Za-z][A-Za-z0-9_.]*\\}\\}", "");

        writeFile(outputPath, html);
        log.info("Approval HTML written: {}", outputPath);
    }

    // =========================================================================
    // Section expansion
    // =========================================================================

    @FunctionalInterface
    private interface SectionExpander {
        String expand(String inner);
    }

    /**
     * Finds {@code {{#tag}}...{{/tag}}}, passes the inner content to {@code expander},
     * and replaces the whole block with the result.
     * Returns {@code template} unchanged when the markers are absent.
     */
    private static String expandSection(String template, String tag, SectionExpander expander) {
        String inner = extractSection(template, tag);
        if (inner == null) return template;
        return replaceSection(template, tag, expander.expand(inner));
    }

    /**
     * Extracts the content between {@code {{#tag}}} and {@code {{/tag}}}.
     * Returns {@code null} when not found.
     */
    private static String extractSection(String template, String tag) {
        String open  = "{{#" + tag + "}}";
        String close = "{{/" + tag + "}}";
        int start = template.indexOf(open);
        if (start < 0) return null;
        int contentStart = start + open.length();
        int end = template.indexOf(close, contentStart);
        if (end < 0) return null;
        return template.substring(contentStart, end);
    }

    /**
     * Replaces the {@code {{#tag}}...{{/tag}}} block with {@code replacement}.
     */
    private static String replaceSection(String template, String tag, String replacement) {
        String open  = "{{#" + tag + "}}";
        String close = "{{/" + tag + "}}";
        int start = template.indexOf(open);
        if (start < 0) return template;
        int end = template.indexOf(close, start + open.length());
        if (end < 0) return template;
        return template.substring(0, start)
                + replacement
                + template.substring(end + close.length());
    }

    /**
     * Conditional block: keeps inner content when {@code show} is true, removes it otherwise.
     * Uses the same {@code {{#tag}}...{{/tag}}} syntax as loops.
     */
    private static String applyConditional(String template, String tag, boolean show) {
        String inner = extractSection(template, tag);
        if (inner == null) return template;
        return replaceSection(template, tag, show ? inner : "");
    }

    // =========================================================================
    // Sections loop expansion
    // =========================================================================

    private String expandSections(String sectionTemplate, List<MopSummarySection> sections) {
        StringBuilder sb = new StringBuilder();
        int num = 1;
        for (MopSummarySection s : sections) {
            String block = sectionTemplate;

            // Per-section conditionals
            block = applyConditional(block, "if_sectionDescription",
                    s.getDescription() != null && !s.getDescription().isEmpty());
            block = applyConditional(block, "if_sectionTargetNode",
                    s.getTargetNode() != null && !s.getTargetNode().isEmpty());
            block = applyConditional(block, "if_sectionMethod",
                    s.getMethod() != null && !s.getMethod().isEmpty());
            String typeLabel = effectiveTypeLabel(s);
            block = applyConditional(block, "if_sectionTypeLabel", !typeLabel.isEmpty());

            // {{#commandsTable}} — shown when commands carry description/validation metadata
            if (s.hasCommandMetadata()) {
                final List<MopSummaryCommand> cmds = s.getCommands();
                block = expandSection(block, "commandsTable", tableTemplate ->
                        expandSection(tableTemplate, "commandRow", rowTemplate -> {
                            StringBuilder cSb = new StringBuilder();
                            int cmdNum = 1;
                            for (MopSummaryCommand c : cmds) {
                                cSb.append(applyCommandTokens(rowTemplate, c, cmdNum++));
                            }
                            return cSb.toString();
                        }));
                block = removeSection(block, "commandsBlock");
            } else {
                // {{#commandsBlock}} — plain-text pre block
                final List<MopSummaryCommand> cmds = s.getCommands();
                block = removeSection(block, "commandsTable");
                block = expandSection(block, "commandsBlock", blockTemplate ->
                        expandSection(blockTemplate, "commandLine", lineTemplate -> {
                            StringBuilder cSb = new StringBuilder();
                            int cmdNum = 1;
                            for (MopSummaryCommand c : cmds) {
                                cSb.append(applyCommandTokens(lineTemplate, c, cmdNum++));
                            }
                            return cSb.toString();
                        }));
            }

            block = applySectionTokens(block, s, num++, typeLabel);
            sb.append(block);
        }
        return sb.toString();
    }

    /** Removes a {@code {{#tag}}...{{/tag}}} block entirely. */
    private static String removeSection(String template, String tag) {
        String inner = extractSection(template, tag);
        if (inner == null) return template;
        return replaceSection(template, tag, "");
    }

    // =========================================================================
    // Token application helpers
    // =========================================================================

    private static String applyNodeInfoTokens(String template, Map<String, String> nodeInfo) {
        for (Map.Entry<String, String> e : nodeInfo.entrySet()) {
            template = template.replace("{{nodeInfo." + e.getKey() + "}}", esc(e.getValue()));
        }
        return template;
    }

    private static String applySectionTokens(String block, MopSummarySection s,
                                              int num, String typeLabel) {
        return block
                .replace("{{section.number}}",         String.valueOf(num))
                .replace("{{section.name}}",            esc(s.getName()        != null ? s.getName()        : ""))
                .replace("{{section.description}}",     esc(s.getDescription() != null ? s.getDescription() : ""))
                .replace("{{section.targetNode}}",      esc(s.getTargetNode()  != null ? s.getTargetNode()  : ""))
                .replace("{{section.method}}",          esc(s.getMethod()      != null ? s.getMethod()      : ""))
                .replace("{{section.typeMarker}}",      esc(s.getTypeMarker()  != null ? s.getTypeMarker()  : ""))
                .replace("{{section.typeLabel}}",       esc(typeLabel))
                .replace("{{section.typeBadgeClass}}", effectiveBadgeClass(s));
    }

    private static String applyCommandTokens(String template, MopSummaryCommand c, int num) {
        String text         = c.getText() != null ? c.getText() : "";
        String commentClass = text.trim().startsWith("#") ? "cmd-comment" : "";
        return template
                .replace("{{cmd.number}}",       String.valueOf(num))
                .replace("{{cmd.text}}",          esc(text))
                .replace("{{cmd.description}}",   esc(c.getDescription()))
                .replace("{{cmd.validation}}",    esc(c.getValidation()))
                .replace("{{cmd.commentClass}}", commentClass);
    }

    // =========================================================================
    // Badge / label helpers
    // =========================================================================

    private static String effectiveTypeLabel(MopSummarySection s) {
        String label = s.typeLabel();
        if (label.isEmpty() && s.isRollback()) return "ROLLBACK";
        return label;
    }

    private static String effectiveBadgeClass(MopSummarySection s) {
        String tm = s.getTypeMarker();
        if (tm != null) {
            if (tm.contains("ROLLBACK")) return "badge-rollback";
            if ("CREATE".equals(tm))     return "badge-create";
            if ("DELETE".equals(tm))     return "badge-delete";
            return "badge-modify";
        }
        return s.isRollback() ? "badge-rollback" : "";
    }

    // =========================================================================
    // Node info helpers
    // =========================================================================

    private static Map<String, String> getExtraInfo(Map<String, String> unitInfo,
                                                     String nodeNameKey, String neIdKey) {
        Map<String, String> extra = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : unitInfo.entrySet()) {
            if (!e.getKey().equals(nodeNameKey) && !e.getKey().equals(neIdKey)) {
                extra.put(e.getKey(), e.getValue());
            }
        }
        return extra;
    }

    // =========================================================================
    // Template loading
    // =========================================================================

    private String loadTemplate() throws IOException {
        String templatePath = config.getSummaryTemplatePath();
        if (templatePath != null && !templatePath.trim().isEmpty()) {
            File f = new File(templatePath.trim());
            if (f.exists()) {
                log.debug("Using external summary template: {}", f.getAbsolutePath());
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            }
            log.warn("Summary template not found at '{}'; falling back to classpath template", templatePath);
        }
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("mop-summary-template.html")) {
            if (is != null) return readStream(is);
        }
        throw new IOException("Summary template not found. " +
                "Ensure 'mop-summary-template.html' is on the classpath or set --mop-summary-template.");
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private static String buildTitle(MopSummaryDocument doc) {
        StringBuilder sb = new StringBuilder("Approval MOP: ").append(doc.buildTitle());
        String crGroup = doc.getMeta("crGroup", null);
        String group   = doc.getMeta("group",   null);
        if (crGroup != null) sb.append(" \u2014 ").append(crGroup);
        else if (group != null) sb.append(" \u2014 Group ").append(group);
        return sb.toString();
    }

    private static String firstValue(Map<String, String> map) {
        return map.isEmpty() ? "NODE" : map.values().iterator().next();
    }

    private static String sanitiseId(String s) {
        return s == null ? "node" : s.replaceAll("[^A-Za-z0-9]", "_");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String readStream(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
        return buf.toString(StandardCharsets.UTF_8.name());
    }

    private static void writeFile(String outputPath, String content) throws IOException {
        File out = new File(outputPath);
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }
}
