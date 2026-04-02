package com.nokia.mopgen;

import com.nokia.mopgen.CRGroupIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class HtmlGroupApprovalMopGenerator implements GroupApprovalMopGenerator {

    private static final Logger log = LoggerFactory.getLogger(HtmlGroupApprovalMopGenerator.class);
    private final MopConfig config;

    public HtmlGroupApprovalMopGenerator(MopConfig config) { this.config = config; }

    @Override
    public void generate(String mopFilePath, GroupIndex groupIndex, String outputPath) throws IOException {
        String mopContent = new String(Files.readAllBytes(new java.io.File(mopFilePath).toPath()), StandardCharsets.UTF_8);
        List<MopSection> sections = GroupApprovalMopGenerator.parseMopFile(mopContent);
        GroupApprovalMopGenerator.mergeMetadata(sections, GroupApprovalMopGenerator.loadMetadata(mopFilePath));

        String nodeType = groupIndex.getNodeType();
        String activity = groupIndex.getActivity();
        String group    = groupIndex.getGroup();
        List<String> nodes = groupIndex.getNodes();
        String mopFileName = new java.io.File(mopFilePath).getName();
        String today = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"UTF-8\">\n");
        html.append("<title>Approval MOP: ").append(esc(nodeType)).append("_").append(esc(activity))
            .append(" \u2014 Group ").append(esc(group)).append("</title>\n");
        html.append(CSS);
        html.append("</head>\n<body>\n");

        // Page header
        html.append("<div class=\"page-header\">\n");
        html.append("<h1>Approval MOP: ").append(esc(nodeType)).append("_").append(esc(activity))
            .append(" \u2014 Group ").append(esc(group)).append("</h1>\n");
        html.append("<table class=\"meta-table\">\n");
        row(html, "Node Type", nodeType);
        row(html, "Activity", activity);
        row(html, "Group", group);
        row(html, "MOP File", mopFileName);
        row(html, "Generated", today);
        html.append("</table>\n</div>\n");

        // Banner
        html.append("<div class=\"approval-banner\">&#9888;&nbsp; APPROVAL COPY")
            .append(" \u2014 For review and sign-off only. Do NOT use for direct execution.</div>\n");

        // Node list
        html.append("<div class=\"node-list\"><strong>Nodes in Group ")
            .append(esc(group)).append(" \u2014 MOP will be executed on each node:</strong><br><br>\n");
        for (String n : nodes) {
            html.append("<span class=\"node-chip\">").append(esc(n)).append("</span>\n");
        }
        html.append("</div>\n");

        // TOC
        html.append("<div class=\"toc\"><h3>Contents</h3><ol>\n");
        html.append("<li><a href=\"#act\"><strong>Activity</strong></a><ol>\n");
        int num = 1;
        for (MopSection s : sections) {
            if (!s.rollback) {
                html.append("<li><a href=\"#s").append(num).append("\">").append(esc(s.name)).append("</a></li>\n");
                num++;
            }
        }
        html.append("</ol></li>\n");
        html.append("<li><a href=\"#rb\"><strong>Rollback</strong></a><ol>\n");
        for (MopSection s : sections) {
            if (s.rollback) {
                html.append("<li><a href=\"#s").append(num).append("\">").append(esc(s.name)).append("</a></li>\n");
                num++;
            }
        }
        html.append("</ol></li>\n");
        html.append("</ol></div>\n");

        // Sections
        int fwdNum = 1; int rbNum = 1;
        boolean activityHeaderWritten = false;
        boolean rollbackHeaderWritten = false;
        for (MopSection s : sections) {
            if (!s.rollback && !activityHeaderWritten) {
                html.append("<h2 class=\"phase-heading\" id=\"act\">&#9654; Activity</h2>\n");
                activityHeaderWritten = true;
            }
            if (s.rollback && !rollbackHeaderWritten) {
                html.append("<hr class=\"rollback-divider\" id=\"rb\">\n")
                    .append("<h2 class=\"rollback-heading\">&#9100; Rollback</h2>\n");
                rollbackHeaderWritten = true;
            }
            String id = s.rollback ? "s" + (sections.size() + rbNum++) : "s" + fwdNum++;
            html.append("<section id=\"").append(id).append("\"")
                .append(s.rollback ? " class=\"rollback-section\"" : "").append(">\n");
            html.append("<h3").append(s.rollback ? " class=\"rollback\"" : "").append(">");
            html.append("<span class=\"section-number").append(s.rollback ? " rollback" : "").append("\">")
                .append(s.rollback ? rbNum - 1 : fwdNum - 1).append("</span> ").append(esc(s.name)).append("</h3>\n");
            html.append("<div class=\"section-meta\">");
            if (s.targetNode != null) html.append("<span>Target: <strong>").append(esc(s.targetNode)).append("</strong></span> ");
            if (s.method     != null) html.append("<span><span class=\"badge badge-cli\">").append(esc(s.method)).append("</span></span> ");
            if (s.typeMarker != null) {
                String tl = s.typeLabel();
                String bc = s.typeMarker.contains("ROLLBACK") ? "badge-rollback" :
                            "CREATE".equals(s.typeMarker) ? "badge-create" :
                            "DELETE".equals(s.typeMarker) ? "badge-delete" : "badge-modify";
                html.append("<span><span class=\"badge ").append(bc).append("\">").append(esc(tl)).append("</span></span>");
            } else if (s.rollback) {
                html.append("<span><span class=\"badge badge-rollback\">ROLLBACK</span></span>");
            }
            html.append("</div>\n");
            if (s.description != null && !s.description.isEmpty()) {
                html.append("<p class=\"section-description\">").append(esc(s.description)).append("</p>\n");
            }
            renderCommands(html, s);
            html.append("</section>\n");
        }

        html.append("<footer>Generated by MOP Generator Utility &nbsp;|&nbsp; ")
            .append(today).append(" &nbsp;|&nbsp; APPROVAL COPY \u2014 Not for direct execution</footer>\n");
        html.append("</body>\n</html>\n");

        File out = new File(outputPath);
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write(html.toString());
        }
        log.info("Approval HTML written: {}", outputPath);
    }

    // -------------------------------------------------------------------------
    // CRGROUP summary (spans multiple GROUP MOPs)
    // -------------------------------------------------------------------------

    @Override
    public void generateForCRGroup(String crGroup,
                                    CRGroupIndex crGroupIndex,
                                    Map<String, String> groupMopPaths,
                                    String outputPath) throws IOException {

        String nodeType = crGroupIndex.getNodeType();
        String activity = crGroupIndex.getActivity();
        String today    = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"UTF-8\">\n");
        html.append("<title>Approval Summary: ").append(esc(nodeType)).append("_").append(esc(activity))
            .append(" \u2014 ").append(esc(crGroup)).append("</title>\n");
        html.append(CSS);
        html.append("</head>\n<body>\n");

        // Page header
        html.append("<div class=\"page-header\">\n");
        html.append("<h1>Approval Summary: ").append(esc(nodeType)).append("_").append(esc(activity))
            .append(" \u2014 ").append(esc(crGroup)).append("</h1>\n");
        html.append("<table class=\"meta-table\">\n");
        row(html, "Node Type", nodeType);
        row(html, "Activity",  activity);
        row(html, "CRGROUP",   crGroup);
        row(html, "Generated", today);
        html.append("</table>\n</div>\n");

        // Banner
        html.append("<div class=\"approval-banner\">&#9888;&nbsp; APPROVAL COPY")
            .append(" \u2014 For review and sign-off only. Do NOT use for direct execution.</div>\n");

        // Node list grouped by GROUP
        html.append("<div class=\"node-list\"><strong>Nodes in ").append(esc(crGroup))
            .append(" \u2014 MOP will be executed on each node:</strong><br><br>\n");
        for (CRGroupIndex.GroupEntry ge : crGroupIndex.getGroups()) {
            html.append("<span class=\"group-label\">Group ").append(esc(ge.getGroup())).append(":</span> ");
            for (String n : ge.getNodes()) {
                html.append("<span class=\"node-chip\">").append(esc(n)).append("</span> ");
            }
            html.append("<br>\n");
        }
        html.append("</div>\n");

        // One section block per GROUP
        for (CRGroupIndex.GroupEntry ge : crGroupIndex.getGroups()) {
            String groupName   = ge.getGroup();
            String mopFilePath = groupMopPaths.get(groupName);

            html.append("<div class=\"group-section\">\n");
            html.append("<h2 class=\"group-heading\">Group ").append(esc(groupName)).append("</h2>\n");
            html.append("<div class=\"group-nodes\">");
            html.append("Applies to: ");
            for (String n : ge.getNodes()) {
                html.append("<span class=\"node-chip\">").append(esc(n)).append("</span> ");
            }
            html.append("</div>\n");

            if (mopFilePath == null || !new File(mopFilePath).exists()) {
                html.append("<p class=\"warn\">MOP file not found: ")
                    .append(esc(mopFilePath != null ? mopFilePath : "(null)")).append("</p>\n");
            } else {
                String mopContent = new String(Files.readAllBytes(new File(mopFilePath).toPath()), StandardCharsets.UTF_8);
                List<MopSection> sections = GroupApprovalMopGenerator.parseMopFile(mopContent);
                GroupApprovalMopGenerator.mergeMetadata(sections, GroupApprovalMopGenerator.loadMetadata(mopFilePath));
                writeSections(html, sections, groupName);
            }
            html.append("</div>\n");
        }

        html.append("<footer>Generated by MOP Generator Utility &nbsp;|&nbsp; ")
            .append(today).append(" &nbsp;|&nbsp; APPROVAL COPY \u2014 Not for direct execution</footer>\n");
        html.append("</body>\n</html>\n");

        File out = new File(outputPath);
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write(html.toString());
        }
        log.info("CRGROUP approval HTML written: {}", outputPath);
    }

    /** Render MOP sections (forward + rollback) into the HTML buffer. */
    private void writeSections(StringBuilder html, List<MopSection> sections, String groupName) {
        String gid = groupName.replaceAll("[^A-Za-z0-9]", "_");
        boolean activityHeaderWritten = false;
        boolean rollbackHeaderWritten = false;
        int fwdNum = 1; int rbNum = 1;
        for (MopSection s : sections) {
            if (!s.rollback && !activityHeaderWritten) {
                html.append("<h3 class=\"phase-heading\" id=\"act_").append(gid)
                    .append("\">&#9654; Activity</h3>\n");
                activityHeaderWritten = true;
            }
            if (s.rollback && !rollbackHeaderWritten) {
                html.append("<hr class=\"rollback-divider\">\n")
                    .append("<h3 class=\"rollback-heading\" id=\"rb_").append(gid)
                    .append("\">&#9100; Rollback</h3>\n");
                rollbackHeaderWritten = true;
            }
            String id = "g" + gid + (s.rollback ? "_rb" + rbNum++ : "_fw" + fwdNum++);
            html.append("<section id=\"").append(id).append("\"")
                .append(s.rollback ? " class=\"rollback-section\"" : "").append(">\n");
            html.append("<h4").append(s.rollback ? " class=\"rollback\"" : "").append(">")
                .append("<span class=\"section-number").append(s.rollback ? " rollback" : "").append("\">")
                .append(s.rollback ? rbNum - 1 : fwdNum - 1).append("</span> ")
                .append(esc(s.name)).append("</h4>\n");
            html.append("<div class=\"section-meta\">");
            if (s.targetNode != null)
                html.append("<span>Target: <strong>").append(esc(s.targetNode)).append("</strong></span> ");
            if (s.method != null)
                html.append("<span><span class=\"badge badge-cli\">").append(esc(s.method)).append("</span></span> ");
            if (s.typeMarker != null) {
                String tl = s.typeLabel();
                String bc = s.typeMarker.contains("ROLLBACK") ? "badge-rollback" :
                            "CREATE".equals(s.typeMarker) ? "badge-create" :
                            "DELETE".equals(s.typeMarker) ? "badge-delete" : "badge-modify";
                html.append("<span><span class=\"badge ").append(bc).append("\">")
                    .append(esc(tl)).append("</span></span>");
            } else if (s.rollback) {
                html.append("<span><span class=\"badge badge-rollback\">ROLLBACK</span></span>");
            }
            html.append("</div>\n");
            if (s.description != null && !s.description.isEmpty()) {
                html.append("<p class=\"section-description\">").append(esc(s.description)).append("</p>\n");
            }
            renderCommands(html, s);
            html.append("</section>\n");
        }
    }

    /**
     * Render the commands of a section.
     * If any command carries description or validation metadata → table layout.
     * Otherwise → plain {@code <pre>} block.
     */
    private static void renderCommands(StringBuilder html, MopSection s) {
        if (s.commands.isEmpty()) return;
        if (s.hasCommandMetadata()) {
            html.append("<table class=\"cmd-table\">\n");
            html.append("<thead><tr>")
                .append("<th>#</th><th>Command</th>")
                .append("<th>Description</th><th>Validation</th>")
                .append("</tr></thead>\n<tbody>\n");
            int i = 1;
            for (MopSection.CommandLine cl : s.commands) {
                String rowClass = cl.text != null && cl.text.trim().startsWith("#")
                        ? " class=\"cmd-comment\"" : "";
                html.append("<tr").append(rowClass).append(">");
                html.append("<td class=\"cmd-num\">").append(i++).append("</td>");
                html.append("<td><code>").append(esc(cl.text)).append("</code></td>");
                html.append("<td>").append(cl.description != null ? esc(cl.description) : "").append("</td>");
                html.append("<td>").append(cl.validation  != null ? esc(cl.validation)  : "").append("</td>");
                html.append("</tr>\n");
            }
            html.append("</tbody></table>\n");
        } else {
            html.append("<pre>");
            for (MopSection.CommandLine cl : s.commands) html.append(esc(cl.text)).append("\n");
            html.append("</pre>\n");
        }
    }

    private static void row(StringBuilder sb, String label, String value) {
        sb.append("<tr><td>").append(esc(label)).append("</td><td>").append(esc(value)).append("</td></tr>\n");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final String CSS =
        "<style>\n"
        + "body{font-family:Arial,sans-serif;margin:2.5em;color:#222;background:#fff;}\n"
        + "body::before{content:'APPROVAL COPY';position:fixed;top:40%;left:50%;"
        + "transform:translate(-50%,-50%) rotate(-30deg);font-size:6em;font-weight:bold;"
        + "color:rgba(220,0,0,0.07);white-space:nowrap;pointer-events:none;z-index:0;}\n"
        + ".page-header{border-bottom:3px solid #003087;padding-bottom:1em;margin-bottom:1.5em;}\n"
        + ".page-header h1{color:#003087;margin:0 0 0.3em 0;font-size:1.6em;}\n"
        + ".meta-table{border-collapse:collapse;width:100%;margin-top:0.5em;}\n"
        + ".meta-table td{padding:4px 12px 4px 0;font-size:0.95em;}\n"
        + ".meta-table td:first-child{font-weight:bold;color:#555;width:160px;}\n"
        + ".approval-banner{background:#fff3cd;border:2px solid #ffc107;padding:0.8em 1.2em;"
        + "border-radius:4px;font-weight:bold;font-size:1.1em;color:#856404;margin-bottom:1.5em;}\n"
        + ".node-list{background:#e8f0fe;border-left:4px solid #003087;padding:0.7em 1em;"
        + "margin-bottom:2em;border-radius:0 4px 4px 0;}\n"
        + ".node-list strong{color:#003087;}\n"
        + ".node-chip{display:inline-block;background:#003087;color:white;border-radius:12px;"
        + "padding:2px 10px;margin:2px 4px;font-size:0.9em;}\n"
        + ".toc{background:#f8f9fa;border:1px solid #dee2e6;padding:1em 1.5em;margin-bottom:2em;border-radius:4px;}\n"
        + ".toc h3{margin:0 0 0.5em 0;color:#003087;font-size:1em;}\n"
        + ".toc ol{margin:0;padding-left:1.5em;}\n"
        + ".toc li{margin:3px 0;font-size:0.92em;}\n"
        + ".toc a{color:#005a9c;text-decoration:none;}\n"
        + ".toc a:hover{text-decoration:underline;}\n"
        + ".phase-heading{color:#003087;font-size:1.15em;font-weight:bold;margin:1.5em 0 0.5em 0;"
        + "border-bottom:2px solid #003087;padding-bottom:0.3em;}\n"
        + "h2.rollback-heading{color:#c0392b;font-size:1.15em;font-weight:bold;margin:1em 0 0.5em 0;"
        + "border-bottom:2px solid #c0392b;padding-bottom:0.3em;}\n"
        + "h3.phase-heading{color:#003087;font-size:1.05em;font-weight:bold;margin:1.2em 0 0.4em 0;"
        + "border-bottom:1px solid #c8d8f0;padding-bottom:0.2em;}\n"
        + "h3.rollback-heading{color:#c0392b;font-size:1.05em;font-weight:bold;margin:0.8em 0 0.4em 0;"
        + "border-bottom:1px solid #f5c6cb;padding-bottom:0.2em;}\n"
        + "section{border-left:4px solid #005a9c;padding:0.5em 0 0.5em 1.2em;margin:0.8em 0 0.8em 1em;}\n"
        + "section.rollback-section{border-left-color:#c0392b;}\n"
        + "h3{color:#005a9c;margin:0 0 0.4em 0;font-size:1.05em;}\n"
        + "h3.rollback{color:#c0392b;}\n"
        + "h4{color:#005a9c;margin:0 0 0.3em 0;font-size:0.98em;}\n"
        + "h4.rollback{color:#c0392b;}\n"
        + ".section-meta{font-size:0.88em;color:#666;margin-bottom:0.4em;}\n"
        + ".section-meta span{margin-right:1.2em;}\n"
        + ".section-description{font-size:0.92em;color:#444;font-style:italic;margin:0.2em 0 0.5em 0;}\n"
        + ".badge{display:inline-block;padding:1px 8px;border-radius:10px;font-size:0.8em;font-weight:bold;vertical-align:middle;}\n"
        + ".badge-cli{background:#d1ecf1;color:#0c5460;}\n"
        + ".badge-create{background:#d4edda;color:#155724;}\n"
        + ".badge-delete{background:#f8d7da;color:#721c24;}\n"
        + ".badge-modify{background:#fff3cd;color:#856404;}\n"
        + ".badge-rollback{background:#f8d7da;color:#721c24;}\n"
        + "pre{background:#1e1e1e;color:#d4d4d4;padding:0.9em 1em;border-radius:4px;"
        + "overflow-x:auto;font-size:0.88em;line-height:1.5;margin:0.5em 0 0 0;}\n"
        + ".cmd-table{width:100%;border-collapse:collapse;font-size:0.87em;margin:0.5em 0 0 0;}\n"
        + ".cmd-table thead tr{background:#003087;color:white;}\n"
        + ".cmd-table th{padding:6px 10px;text-align:left;font-weight:600;white-space:nowrap;}\n"
        + ".cmd-table td{padding:5px 10px;border-bottom:1px solid #e0e0e0;vertical-align:top;}\n"
        + ".cmd-table tbody tr:nth-child(even){background:#f8f9fa;}\n"
        + ".cmd-table tbody tr:hover{background:#e8f0fe;}\n"
        + ".cmd-table code{background:#1e1e1e;color:#d4d4d4;padding:2px 6px;border-radius:3px;"
        + "font-size:0.95em;white-space:pre-wrap;word-break:break-all;}\n"
        + ".cmd-table .cmd-num{color:#888;text-align:center;width:2em;}\n"
        + ".cmd-table tr.cmd-comment td{color:#888;font-style:italic;}\n"
        + ".cmd-table tr.cmd-comment code{background:#2d2d2d;color:#888;}\n"
        + ".rollback-divider{border:none;border-top:3px dashed #c0392b;margin:2.5em 0 1.5em 0;}\n"
        + ".section-number{display:inline-block;background:#003087;color:white;border-radius:50%;"
        + "width:1.6em;height:1.6em;text-align:center;line-height:1.6em;font-size:0.85em;margin-right:0.5em;}\n"
        + ".section-number.rollback{background:#c0392b;}\n"
        + "footer{margin-top:3em;border-top:1px solid #ccc;padding-top:1em;font-size:0.82em;color:#888;}\n"
        + ".group-section{border:1px solid #c8d8f0;border-radius:6px;margin:2em 0;padding:0 1.2em 1em 1.2em;}\n"
        + ".group-heading{color:#003087;font-size:1.2em;border-bottom:2px solid #003087;padding-bottom:0.3em;}\n"
        + ".group-nodes{font-size:0.9em;color:#555;margin-bottom:1em;}\n"
        + ".group-label{font-weight:bold;color:#003087;margin-right:0.4em;}\n"
        + ".warn{color:#c0392b;font-weight:bold;}\n"
        + "</style>\n";
}
