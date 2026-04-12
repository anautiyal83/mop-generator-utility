package com.nokia.mopgen;

import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Generates a DOCX (MS Word) MOP approval summary from a {@link MopSummaryDocument}.
 *
 * <p>Each unit (node) in the document is rendered as a labelled heading section.
 * Activity and rollback sections are separated by a visual divider.
 */
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

    // =========================================================================
    // Primary entry point
    // =========================================================================

    @Override
    public void generate(MopSummaryDocument summaryDoc, String outputPath) throws IOException {

        String today = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());
        summaryDoc.addMeta("generated", today);

        String nodeNameKey = config.getJsonMapping().getOrDefault("nodeNameKey", "node");
        String neIdKey     = config.getJsonMapping().getOrDefault("neIdKey",     "niamID");

        XWPFDocument doc = new XWPFDocument();

        // Title
        addHeading1(doc, "Approval MOP: " + summaryDoc.buildTitle(), NOKIA_BLUE);

        // Approval banner
        XWPFParagraph banner = doc.createParagraph();
        shadeParagraph(banner, BANNER_BG);
        XWPFRun bannerRun = banner.createRun();
        bannerRun.setBold(true);
        bannerRun.setColor(BANNER_FG);
        bannerRun.setText("\u26A0 APPROVAL COPY \u2014 For review and sign-off only. Do NOT use for direct execution.");

        // Metadata table
        Map<String, String> meta = summaryDoc.getMetadata();
        XWPFTable metaTable = doc.createTable(meta.size(), 2);
        setTableBorder(metaTable);
        int rowIdx = 0;
        for (Map.Entry<String, String> e : meta.entrySet()) {
            setCell(metaTable.getRow(rowIdx).getCell(0), capitalise(e.getKey()), true,  NOKIA_BLUE + "10");
            setCell(metaTable.getRow(rowIdx).getCell(1), e.getValue(),           false, null);
            rowIdx++;
        }

        // Nodes heading + table
        addHeading2(doc, "Nodes covered \u2014 MOP will be executed on each node", SECTION_BLUE);
        List<MopSummaryUnit> units = summaryDoc.getUnits();
        XWPFTable nodesTable = doc.createTable(units.size() + 1, 3);
        setTableBorder(nodesTable);
        setCell(nodesTable.getRow(0).getCell(0), "#",        true, NOKIA_BLUE); setRunColor(nodesTable.getRow(0).getCell(0), "FFFFFF");
        setCell(nodesTable.getRow(0).getCell(1), "Node",     true, NOKIA_BLUE); setRunColor(nodesTable.getRow(0).getCell(1), "FFFFFF");
        setCell(nodesTable.getRow(0).getCell(2), "NEID",     true, NOKIA_BLUE); setRunColor(nodesTable.getRow(0).getCell(2), "FFFFFF");
        for (int i = 0; i < units.size(); i++) {
            MopSummaryUnit u = units.get(i);
            String node = u.getInfo(nodeNameKey, firstValue(u.getUnitInfo()));
            String neid = u.getInfo(neIdKey, "");
            setCell(nodesTable.getRow(i + 1).getCell(0), String.valueOf(i + 1), false, null);
            setCell(nodesTable.getRow(i + 1).getCell(1), node,                  false, null);
            setCell(nodesTable.getRow(i + 1).getCell(2), neid,                  false, null);
        }

        // Sections per unit
        for (MopSummaryUnit unit : units) {
            String nodeName = unit.getInfo(nodeNameKey, firstValue(unit.getUnitInfo()));
            String neId     = unit.getInfo(neIdKey, "");
            String unitLabel = neId.isEmpty() ? nodeName : nodeName + " \u2014 " + neId;

            // Additional unitInfo as supplementary info
            StringBuilder extraInfo = new StringBuilder();
            for (Map.Entry<String, String> e : unit.getUnitInfo().entrySet()) {
                if (e.getKey().equals(nodeNameKey) || e.getKey().equals(neIdKey)) continue;
                if (extraInfo.length() > 0) extraInfo.append("  |  ");
                extraInfo.append(capitalise(e.getKey())).append(": ").append(e.getValue());
            }

            addHeading1(doc, "Node: " + unitLabel, NOKIA_BLUE);
            if (extraInfo.length() > 0) {
                XWPFParagraph ep = doc.createParagraph();
                XWPFRun er = ep.createRun();
                er.setItalic(true);
                er.setColor("555555");
                er.setText(extraInfo.toString());
            }

            // Activity sections
            if (!unit.getActivity().isEmpty()) {
                addHeading2(doc, "\u25B6 Activity", SECTION_BLUE);
                int num = 1;
                for (MopSummarySection s : unit.getActivity()) renderDocxSection(doc, s, num++, false);
            }

            // Rollback sections
            if (!unit.getRollback().isEmpty()) {
                XWPFParagraph divider = doc.createParagraph();
                addBorder(divider, ROLLBACK_RED);
                addHeading2(doc, "\u21B0 Rollback", ROLLBACK_RED);
                int num = 1;
                for (MopSummarySection s : unit.getRollback()) renderDocxSection(doc, s, num++, true);
            }
        }

        // Footer
        XWPFParagraph footer = doc.createParagraph();
        addBorder(footer, "CCCCCC");
        XWPFRun footerRun = footer.createRun();
        footerRun.setFontSize(8);
        footerRun.setColor("888888");
        footerRun.setText("Generated by MOP Generator Utility  |  " + today
                + "  |  APPROVAL COPY \u2014 Not for direct execution");

        File out = new File(outputPath);
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(out)) { doc.write(fos); }
        doc.close();
        log.info("Approval DOCX written: {}", outputPath);
    }

    // =========================================================================
    // Section rendering
    // =========================================================================

    private void renderDocxSection(XWPFDocument doc, MopSummarySection s,
                                    int num, boolean rollback) {
        String color = rollback ? ROLLBACK_RED : SECTION_BLUE;

        XWPFParagraph secHead = doc.createParagraph();
        secHead.setStyle("Heading3");
        XWPFRun sr = secHead.createRun();
        sr.setText(num + ". " + s.getName());
        sr.setColor(color);

        // Meta line
        XWPFParagraph metaLine = doc.createParagraph();
        if (s.getTargetNode() != null) addInlineBold(metaLine, "Target: ", s.getTargetNode() + "   ");
        if (s.getMethod()     != null) addInlineBold(metaLine, "Method: ", s.getMethod()     + "   ");
        if (s.getTypeMarker() != null) addInlineBold(metaLine, "Type: ",   s.typeLabel());

        // Description
        if (s.getDescription() != null && !s.getDescription().isEmpty()) {
            XWPFParagraph dp = doc.createParagraph();
            XWPFRun dr = dp.createRun();
            dr.setItalic(true);
            dr.setColor("555555");
            dr.setText(s.getDescription());
        }

        // Commands
        if (!s.getCommands().isEmpty()) {
            for (MopSummaryCommand c : s.getCommands()) {
                String cmd = c.getText() != null ? c.getText() : "";
                XWPFParagraph cp = doc.createParagraph();
                cp.setSpacingBefore(0);
                cp.setSpacingAfter(0);
                shadeParagraph(cp, CODE_BG);
                XWPFRun cr = cp.createRun();
                cr.setFontFamily("Courier New");
                cr.setFontSize(9);
                cr.setColor(CODE_FG);
                cr.setText(cmd.isEmpty() ? " " : cmd);
            }
            doc.createParagraph(); // spacer
        }
    }

    // =========================================================================
    // POI helpers
    // =========================================================================

    private static void addHeading1(XWPFDocument doc, String text, String color) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Heading1");
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setColor(color);
    }

    private static void addHeading2(XWPFDocument doc, String text, String color) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Heading2");
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setColor(color);
    }

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
        CTTblBorders borders = tblPr.isSetTblBorders()
                ? tblPr.getTblBorders() : tblPr.addNewTblBorders();
        for (CTBorder b : new CTBorder[]{
                borders.addNewTop(), borders.addNewBottom(),
                borders.addNewLeft(), borders.addNewRight(),
                borders.addNewInsideH(), borders.addNewInsideV()}) {
            b.setVal(STBorder.SINGLE);
            b.setSz(BigInteger.valueOf(4));
            b.setColor("CCCCCC");
        }
    }

    private static void setCell(XWPFTableCell cell, String text, boolean bold, String bgColor) {
        if (bgColor != null) {
            CTTcPr tcPr = cell.getCTTc().isSetTcPr()
                    ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
            CTShd shd = tcPr.isSetShd() ? tcPr.getShd() : tcPr.addNewShd();
            shd.setVal(STShd.CLEAR);
            shd.setFill(bgColor);
        }
        XWPFParagraph p = cell.getParagraphs().isEmpty()
                ? cell.addParagraph() : cell.getParagraphs().get(0);
        XWPFRun run = p.getRuns().isEmpty() ? p.createRun() : p.getRuns().get(0);
        run.setBold(bold);
        run.setText(text);
    }

    private static void setRunColor(XWPFTableCell cell, String color) {
        for (XWPFParagraph p : cell.getParagraphs())
            for (XWPFRun r : p.getRuns()) r.setColor(color);
    }

    private static void addInlineBold(XWPFParagraph p, String label, String value) {
        XWPFRun lr = p.createRun();
        lr.setBold(true);
        lr.setColor("555555");
        lr.setText(label);
        XWPFRun vr = p.createRun();
        vr.setText(value);
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String firstValue(Map<String, String> map) {
        return map.isEmpty() ? "NODE" : map.values().iterator().next();
    }
}
