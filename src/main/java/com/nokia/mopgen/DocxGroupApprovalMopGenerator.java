package com.nokia.mopgen;

import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nokia.mopgen.CRGroupIndex;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class DocxGroupApprovalMopGenerator implements GroupApprovalMopGenerator {

    private static final Logger log = LoggerFactory.getLogger(DocxGroupApprovalMopGenerator.class);
    private final MopConfig config;

    // Brand colours
    private static final String NOKIA_BLUE   = "003087";
    private static final String SECTION_BLUE = "005A9C";
    private static final String ROLLBACK_RED = "C0392B";
    private static final String CODE_BG      = "1E1E1E";
    private static final String CODE_FG      = "D4D4D4";
    private static final String BANNER_BG    = "FFF3CD";
    private static final String BANNER_FG    = "856404";

    public DocxGroupApprovalMopGenerator(MopConfig config) { this.config = config; }

    @Override
    public void generate(String mopFilePath, GroupIndex groupIndex, String outputPath) throws IOException {
        String mopContent = new String(Files.readAllBytes(new File(mopFilePath).toPath()), StandardCharsets.UTF_8);
        List<MopSection> sections = GroupApprovalMopGenerator.parseMopFile(mopContent);

        String nodeType  = groupIndex.getNodeType();
        String activity  = groupIndex.getActivity();
        String group     = groupIndex.getGroup();
        List<String> nodes = groupIndex.getNodes();
        String mopFileName = new File(mopFilePath).getName();
        String today = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());

        XWPFDocument doc = new XWPFDocument();

        // Title
        XWPFParagraph title = doc.createParagraph();
        title.setStyle("Heading1");
        XWPFRun titleRun = title.createRun();
        titleRun.setText("Approval MOP: " + nodeType + "_" + activity + " \u2014 Group " + group);
        titleRun.setColor(NOKIA_BLUE);

        // Approval banner
        XWPFParagraph banner = doc.createParagraph();
        shadeParagraph(banner, BANNER_BG);
        XWPFRun bannerRun = banner.createRun();
        bannerRun.setBold(true);
        bannerRun.setColor(BANNER_FG);
        bannerRun.setText("\u26A0 APPROVAL COPY \u2014 For review and sign-off only. Do NOT use for direct execution.");

        // Metadata table
        XWPFTable metaTable = doc.createTable(5, 2);
        setTableBorder(metaTable);
        String[][] meta = {
            {"Node Type", nodeType}, {"Activity", activity},
            {"Group", group}, {"MOP File", mopFileName}, {"Generated", today}
        };
        for (int i = 0; i < meta.length; i++) {
            setCell(metaTable.getRow(i).getCell(0), meta[i][0], true, NOKIA_BLUE + "10");
            setCell(metaTable.getRow(i).getCell(1), meta[i][1], false, null);
        }

        // Nodes heading
        XWPFParagraph nodesHeading = doc.createParagraph();
        nodesHeading.setStyle("Heading2");
        XWPFRun nodesHeadingRun = nodesHeading.createRun();
        nodesHeadingRun.setText("Nodes in Group " + group + " \u2014 MOP will be executed on each node");
        nodesHeadingRun.setColor(SECTION_BLUE);

        // Nodes table
        XWPFTable nodesTable = doc.createTable(nodes.size() + 1, 2);
        setTableBorder(nodesTable);
        // Header row
        setCell(nodesTable.getRow(0).getCell(0), "#",         true, NOKIA_BLUE);
        setCell(nodesTable.getRow(0).getCell(1), "Node Name", true, NOKIA_BLUE);
        setRunColor(nodesTable.getRow(0).getCell(0), "FFFFFF");
        setRunColor(nodesTable.getRow(0).getCell(1), "FFFFFF");
        for (int i = 0; i < nodes.size(); i++) {
            setCell(nodesTable.getRow(i + 1).getCell(0), String.valueOf(i + 1), false, null);
            setCell(nodesTable.getRow(i + 1).getCell(1), nodes.get(i),          false, null);
        }

        // MOP Sections
        int fwdNum = 1; int rbNum = 1;
        boolean rollbackHeaderWritten = false;

        for (MopSection s : sections) {
            if (s.rollback && !rollbackHeaderWritten) {
                // Rollback divider
                XWPFParagraph divider = doc.createParagraph();
                addBorder(divider, ROLLBACK_RED);
                XWPFParagraph rbHead = doc.createParagraph();
                rbHead.setStyle("Heading1");
                XWPFRun rbRun = rbHead.createRun();
                rbRun.setText("\u21B0 ROLLBACK");
                rbRun.setColor(ROLLBACK_RED);
                rollbackHeaderWritten = true;
            }

            int num = s.rollback ? rbNum++ : fwdNum++;
            String color = s.rollback ? ROLLBACK_RED : SECTION_BLUE;

            XWPFParagraph secHead = doc.createParagraph();
            secHead.setStyle("Heading2");
            XWPFRun secRun = secHead.createRun();
            secRun.setText(num + ". " + s.name);
            secRun.setColor(color);

            // Meta line
            XWPFParagraph metaLine = doc.createParagraph();
            if (s.targetNode != null) addInlineBold(metaLine, "Target: ", s.targetNode + "   ");
            if (s.method     != null) addInlineBold(metaLine, "Method: ", s.method + "   ");
            addInlineBold(metaLine, "Type: ", s.typeLabel());

            // Description
            if (s.description != null && !s.description.isEmpty()) {
                XWPFParagraph desc = doc.createParagraph();
                XWPFRun dRun = desc.createRun();
                dRun.setItalic(true);
                dRun.setColor("555555");
                dRun.setText(s.description);
            }

            // Commands — dark code block
            if (!s.commands.isEmpty()) {
                for (MopSection.CommandLine cl : s.commands) {
                    String cmd = cl.text != null ? cl.text : "";
                    XWPFParagraph codeLine = doc.createParagraph();
                    codeLine.setSpacingBefore(0);
                    codeLine.setSpacingAfter(0);
                    shadeParagraph(codeLine, CODE_BG);
                    XWPFRun codeRun = codeLine.createRun();
                    codeRun.setFontFamily("Courier New");
                    codeRun.setFontSize(9);
                    codeRun.setColor(CODE_FG);
                    codeRun.setText(cmd.isEmpty() ? " " : cmd);
                }
                // small spacer after code block
                doc.createParagraph();
            }
        }

        // Footer
        XWPFParagraph footer = doc.createParagraph();
        addBorder(footer, "CCCCCC");
        XWPFRun footerRun = footer.createRun();
        footerRun.setFontSize(8);
        footerRun.setColor("888888");
        footerRun.setText("Generated by MOP Generator Utility  |  " + today + "  |  APPROVAL COPY \u2014 Not for direct execution");

        File outFile = new File(outputPath);
        if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            doc.write(fos);
        }
        doc.close();
        log.info("Approval DOCX written: {}", outputPath);
    }

    // -------------------------------------------------------------------------
    // POI helpers
    // -------------------------------------------------------------------------

    private static void shadeParagraph(XWPFParagraph p, String hexColor) {
        CTPPr pPr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        CTShd shd = pPr.isSetShd() ? pPr.getShd() : pPr.addNewShd();
        shd.setVal(STShd.CLEAR);
        shd.setFill(hexColor);
    }

    private static void addBorder(XWPFParagraph p, String hexColor) {
        CTPPr pPr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        CTPBdr pBdr = pPr.isSetPBdr() ? pPr.getPBdr() : pPr.addNewPBdr();
        CTBorder top = pBdr.addNewTop();
        top.setVal(STBorder.SINGLE);
        top.setSz(BigInteger.valueOf(6));
        top.setColor(hexColor);
    }

    private static void setTableBorder(XWPFTable table) {
        CTTblPr tblPr = table.getCTTbl().getTblPr();
        CTTblBorders borders = tblPr.isSetTblBorders() ? tblPr.getTblBorders() : tblPr.addNewTblBorders();
        for (CTBorder b : new CTBorder[]{borders.addNewTop(), borders.addNewBottom(),
                                         borders.addNewLeft(), borders.addNewRight(),
                                         borders.addNewInsideH(), borders.addNewInsideV()}) {
            b.setVal(STBorder.SINGLE);
            b.setSz(BigInteger.valueOf(4));
            b.setColor("CCCCCC");
        }
    }

    private static void setCell(XWPFTableCell cell, String text, boolean bold, String bgColor) {
        if (bgColor != null) {
            CTTcPr tcPr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
            CTShd shd = tcPr.isSetShd() ? tcPr.getShd() : tcPr.addNewShd();
            shd.setVal(STShd.CLEAR);
            shd.setFill(bgColor);
        }
        XWPFParagraph p = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
        XWPFRun run = p.getRuns().isEmpty() ? p.createRun() : p.getRuns().get(0);
        run.setBold(bold);
        run.setText(text);
    }

    private static void setRunColor(XWPFTableCell cell, String color) {
        for (XWPFParagraph p : cell.getParagraphs())
            for (XWPFRun r : p.getRuns()) r.setColor(color);
    }

    private static void addInlineBold(XWPFParagraph p, String label, String value) {
        XWPFRun labelRun = p.createRun();
        labelRun.setBold(true);
        labelRun.setColor("555555");
        labelRun.setText(label);
        XWPFRun valRun = p.createRun();
        valRun.setText(value);
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

        XWPFDocument doc = new XWPFDocument();

        // Title
        XWPFParagraph title = doc.createParagraph();
        title.setStyle("Heading1");
        XWPFRun titleRun = title.createRun();
        titleRun.setText("Approval Summary: " + nodeType + "_" + activity + " \u2014 " + crGroup);
        titleRun.setColor(NOKIA_BLUE);

        // Approval banner
        XWPFParagraph banner = doc.createParagraph();
        shadeParagraph(banner, BANNER_BG);
        XWPFRun bannerRun = banner.createRun();
        bannerRun.setBold(true);
        bannerRun.setColor(BANNER_FG);
        bannerRun.setText("\u26A0 APPROVAL COPY \u2014 For review and sign-off only. Do NOT use for direct execution.");

        // Meta table
        XWPFTable meta = doc.createTable(4, 2);
        setTableBorder(meta);
        setCell(meta.getRow(0).getCell(0), "Node Type", true,  NOKIA_BLUE + "10"); setCell(meta.getRow(0).getCell(1), nodeType, false, null);
        setCell(meta.getRow(1).getCell(0), "Activity",  true,  NOKIA_BLUE + "10"); setCell(meta.getRow(1).getCell(1), activity, false, null);
        setCell(meta.getRow(2).getCell(0), "CRGROUP",   true,  NOKIA_BLUE + "10"); setCell(meta.getRow(2).getCell(1), crGroup,  false, null);
        setCell(meta.getRow(3).getCell(0), "Generated", true,  NOKIA_BLUE + "10"); setCell(meta.getRow(3).getCell(1), today,    false, null);

        // Node list
        XWPFParagraph nodeHeading = doc.createParagraph();
        nodeHeading.setStyle("Heading2");
        XWPFRun nodeHeadingRun = nodeHeading.createRun();
        nodeHeadingRun.setText("Nodes in " + crGroup);
        nodeHeadingRun.setColor(SECTION_BLUE);

        for (CRGroupIndex.GroupEntry ge : crGroupIndex.getGroups()) {
            XWPFParagraph groupLine = doc.createParagraph();
            XWPFRun groupLabel = groupLine.createRun();
            groupLabel.setBold(true);
            groupLabel.setColor(NOKIA_BLUE);
            groupLabel.setText("Group " + ge.getGroup() + ": ");
            XWPFRun nodeList = groupLine.createRun();
            nodeList.setText(String.join(", ", ge.getNodes()));
        }

        // MOP sections per GROUP
        for (CRGroupIndex.GroupEntry ge : crGroupIndex.getGroups()) {
            String groupName   = ge.getGroup();
            String mopFilePath = groupMopPaths.get(groupName);

            XWPFParagraph groupHeading = doc.createParagraph();
            groupHeading.setStyle("Heading2");
            XWPFRun groupHeadingRun = groupHeading.createRun();
            groupHeadingRun.setText("Group " + groupName + " \u2014 " + String.join(", ", ge.getNodes()));
            groupHeadingRun.setColor(NOKIA_BLUE);

            if (mopFilePath == null || !new File(mopFilePath).exists()) {
                XWPFParagraph warn = doc.createParagraph();
                XWPFRun warnRun = warn.createRun();
                warnRun.setColor(ROLLBACK_RED);
                warnRun.setText("MOP file not found: " + (mopFilePath != null ? mopFilePath : "(null)"));
                continue;
            }

            String mopContent = new String(Files.readAllBytes(new File(mopFilePath).toPath()), StandardCharsets.UTF_8);
            List<MopSection> sections = GroupApprovalMopGenerator.parseMopFile(mopContent);

            boolean rollbackHeaderWritten = false;
            for (MopSection s : sections) {
                if (s.rollback && !rollbackHeaderWritten) {
                    XWPFParagraph rbHeader = doc.createParagraph();
                    rbHeader.setStyle("Heading3");
                    XWPFRun rbRun = rbHeader.createRun();
                    rbRun.setText("\u2702 ROLLBACK \u2014 Group " + groupName);
                    rbRun.setColor(ROLLBACK_RED);
                    rollbackHeaderWritten = true;
                }

                XWPFParagraph sh = doc.createParagraph();
                sh.setStyle("Heading3");
                XWPFRun sr = sh.createRun();
                sr.setText(s.name);
                sr.setColor(s.rollback ? ROLLBACK_RED : SECTION_BLUE);

                if (s.description != null) {
                    XWPFParagraph dp = doc.createParagraph();
                    XWPFRun dr = dp.createRun();
                    dr.setItalic(true);
                    dr.setColor("666666");
                    dr.setText(s.description);
                }

                if (!s.commands.isEmpty()) {
                    StringBuilder cmdText = new StringBuilder();
                    for (MopSection.CommandLine cl : s.commands) {
                        if (cmdText.length() > 0) cmdText.append("\n");
                        cmdText.append(cl.text != null ? cl.text : "");
                    }
                    XWPFParagraph cp = doc.createParagraph();
                    shadeParagraph(cp, CODE_BG);
                    XWPFRun cr = cp.createRun();
                    cr.setFontFamily("Courier New");
                    cr.setFontSize(9);
                    cr.setColor(CODE_FG);
                    cr.setText(cmdText.toString());
                }
            }
        }

        File out = new File(outputPath);
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(out)) {
            doc.write(fos);
        }
        log.info("CRGROUP approval DOCX written: {}", outputPath);
    }
}
