package com.nokia.mopgen;

import com.nokia.ciq.reader.ExcelCiqReader;
import com.nokia.mopgen.CRGroupIndex;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class MopGeneratorTest {

    private static final String CIQ_FILE      = "C:/Users/DELL/Desktop/SBC-Usecase-96FixedLineconfig/96_Fixed line configuration in SBC_CIQ.xlsx";
    /** Pre-built JSON output from ciq-reader — used directly if available. */
    private static final String JSON_DIR_PREBUILT = "D:/Nokia/workspace/ciq-reader/json-out";
    private static final String JSON_DIR_GENERATED = "target/ciq-json";
    private static final String TEMPLATE    = "src/main/resources/SBC_FIXED_LINE_CONFIGURATION_MOP-Template.yaml";
    private static final String OUTPUT_DIR  = "target/mop-output";
    private static final String OUTPUT_FILE = OUTPUT_DIR + "/SBC_FIXED_LINE_CONFIGURATION.mop";
    private static final String NODE_TYPE   = "SBC";
    private static final String ACTIVITY    = "FIXED_LINE_CONFIGURATION";

    /** Resolved at class load: prefer pre-built JSON, fall back to CIQ-generated. */
    private static String jsonDir;

    @BeforeClass
    public static void resolveJsonDir() throws Exception {
        File prebuilt = new File(JSON_DIR_PREBUILT,
                NODE_TYPE + "_" + ACTIVITY + "_index.json");
        if (prebuilt.exists()) {
            jsonDir = JSON_DIR_PREBUILT;
        } else {
            // Fall back: generate from CIQ file
            org.junit.Assume.assumeTrue("Neither pre-built JSON nor CIQ file found",
                    new File(CIQ_FILE).exists());
            new File(JSON_DIR_GENERATED).mkdirs();
            new ExcelCiqReader().read(CIQ_FILE, JSON_DIR_GENERATED, NODE_TYPE, ACTIVITY);
            jsonDir = JSON_DIR_GENERATED;
        }
    }

    @Test
    public void testGenerate() throws Exception {
        new File(OUTPUT_DIR).mkdirs();

        MopConfig config = new MopConfigLoader().load(TEMPLATE);
        new MopGenerator(config).generate(jsonDir, NODE_TYPE, ACTIVITY, null, OUTPUT_FILE);

        File mop = new File(OUTPUT_FILE);
        assertTrue("MOP file not created", mop.exists());
        assertTrue("MOP file is empty", mop.length() > 0);

        String content = new String(Files.readAllBytes(mop.toPath()), StandardCharsets.UTF_8);

        // --- Header ---
        assertTrue("Missing MOP header",
                content.contains("## MOP         : SBC_FIXED_LINE_CONFIGURATION"));

        // --- Static blocks (inherited from SBC_common_blocks.yaml) ---
        assertTrue("Missing PRE_NODE_HEALTH_CHECK",
                content.contains("##PRE_NODE_HEALTH_CHECK"));
        assertTrue("Missing BACKUP",
                content.contains("##BACKUP"));
        assertTrue("Missing ACTIVITY_PRECHECK",
                content.contains("##ACTIVITY_PRECHECK"));
        assertTrue("Missing ACTIVITY_POSTCHECK",
                content.contains("##ACTIVITY_POSTCHECK"));
        assertTrue("Missing POST_NODE_HEALTH_CHECK",
                content.contains("##POST_NODE_HEALTH_CHECK"));

        // --- ACTIVITY_CONFIGURATION blocks (auto-generated from CIQ) ---
        assertTrue("No ACTIVITY_CONFIGURATION block found",
                content.contains("_ACTIVITY_CONFIGURATION"));
        assertTrue("No $CREATE type",
                content.contains("$CREATE"));

        // --- XML payload ---
        assertTrue("Missing heredoc start",     content.contains("cat << 'XMLEOF'"));
        assertTrue("Missing heredoc end",        content.contains("XMLEOF"));
        assertTrue("Missing netconfprov",        content.contains("netconfprov"));
        assertTrue("Missing XML declaration",    content.contains("<?xml version=\"1.0\""));
        assertTrue("Missing config element",     content.contains("<config ne="));
        assertTrue("Missing xc:operation",       content.contains("xc:operation="));

        // --- Rollback section ---
        assertTrue("Missing ROLLBACK section separator",
                content.contains("## ROLLBACK"));
        assertTrue("Missing ROLLBACK_PRECHECK",
                content.contains("##ROLLBACK_PRECHECK"));
        assertTrue("Missing ROLLBACK_CONFIGURATION",
                content.contains("_ROLLBACK_CONFIGURATION"));
        assertTrue("Missing $CREATE_ROLLBACK",
                content.contains("$CREATE_ROLLBACK"));
        assertTrue("Missing ROLLBACK_POSTCHECK",
                content.contains("##ROLLBACK_POSTCHECK"));

        // --- Section order ---
        int posPre      = content.indexOf("##PRE_NODE_HEALTH_CHECK");
        int posBackup   = content.indexOf("##BACKUP");
        int posPrecheck = content.indexOf("##ACTIVITY_PRECHECK");
        int posConfig   = content.indexOf("_ACTIVITY_CONFIGURATION");
        int posRollback = content.indexOf("## ROLLBACK");
        assertTrue("PRE_NODE_HEALTH_CHECK must come before BACKUP", posPre < posBackup);
        assertTrue("BACKUP must come before ACTIVITY_PRECHECK", posBackup < posPrecheck);
        assertTrue("ACTIVITY_PRECHECK must come before ACTIVITY_CONFIGURATION", posPrecheck < posConfig);
        assertTrue("ACTIVITY_CONFIGURATION must come before ROLLBACK section", posConfig < posRollback);

        System.out.println("\n=== MOP Generated: " + OUTPUT_FILE + " ===");
        System.out.println("File size: " + mop.length() + " bytes");

        String[] lines = content.split("\n");
        int preview = Math.min(80, lines.length);
        for (int i = 0; i < preview; i++) System.out.println(lines[i]);
        if (lines.length > preview) System.out.println("... (" + (lines.length - preview) + " more lines)");
    }

    @Test
    public void testIncludesMechanism() throws IOException {
        // Verify that includes are resolved: preNodeHealthCheck should come from SBC_common_blocks.yaml
        MopConfig config = new MopConfigLoader().load(TEMPLATE);
        assertFalse("preNodeHealthCheck should be loaded via includes",
                config.getPreNodeHealthCheck().isEmpty());
        assertEquals("PRE_NODE_HEALTH_CHECK", config.getPreNodeHealthCheck().get(0).getName());

        // backup override in template should win over included version
        assertFalse("backup should be present", config.getBackup().isEmpty());
        assertTrue("backup command should contain 'netconfprov'",
                config.getBackup().get(0).getCommands().get(0).contains("netconfprov"));

        // rollback from common blocks
        assertNotNull("rollback should be loaded via includes", config.getRollback());
        assertFalse("rollback.precheck should not be empty",
                config.getRollback().getPrecheck().isEmpty());
    }

    @Test
    public void testDefaultConfigUsedWhenNoTemplate() throws Exception {
        String out = OUTPUT_DIR + "/SBC_FIXED_LINE_CONFIGURATION_defaults.mop";
        new File(OUTPUT_DIR).mkdirs();
        new MopGenerator(new MopConfig()).generate(jsonDir, NODE_TYPE, ACTIVITY, null, out);
        assertTrue("MOP file missing", new File(out).exists());
    }

    @Test
    public void testCliMain() {
        // Detect first host sub-folder in jsonDir for NODE mode CLI test
        File[] subdirs = new File(jsonDir).listFiles(File::isDirectory);
        Assume.assumeTrue("No node sub-folders found in " + jsonDir,
                subdirs != null && subdirs.length > 0);
        String hostName = subdirs[0].getName();

        int rc = MopGeneratorMain.run(new String[]{
                "--json-dir",      jsonDir,
                "--node-type",     NODE_TYPE,
                "--activity",      ACTIVITY,
                "--host",          hostName,
                "--template",      TEMPLATE,
                "--output-dir",    OUTPUT_DIR,
                "--mop-file-name", "SBC_FIXED_LINE_CONFIGURATION_cli"
        });
        assertEquals("Expected exit 0", 0, rc);
    }

    // -------------------------------------------------------------------------
    // MRF CRGROUP mode
    // -------------------------------------------------------------------------

    private static final String MRF_MOP_JSON_DIR = "target/mrf-mop-json";
    private static final String MRF_NODE_TYPE    = "MRF";
    private static final String MRF_ACTIVITY     = "ANNOUNCEMENT_LOADING";
    private static final String MRF_TEMPLATE     = "src/main/resources/MRF_ANNOUNCEMENT_LOADING_MOP-Template.yaml";
    private static final String MRF_OUTPUT_DIR   = "target/mrf-mop-output";

    /**
     * Reads a CRGROUP folder (e.g. {@code target/mrf-mop-json/CR-001/}) produced by
     * ciq-processor and verifies CRGROUP mode:
     * <ul>
     *   <li>One shared MOP per GROUP is generated ({@code mopGenerationMode=GROUP})</li>
     *   <li>The MOP header lists all nodes for that GROUP</li>
     *   <li>One CR-level approval HTML is generated covering all GROUPs in the CR</li>
     * </ul>
     */
    @Test
    public void testMrfCRGroupMopGeneration() throws Exception {
        // Require at least one CRGROUP folder from ciq-processor
        File mopJsonDir = new File(MRF_MOP_JSON_DIR);
        Assume.assumeTrue("MRF mop-json dir not found — run testMrfValidation first",
                mopJsonDir.exists() && mopJsonDir.isDirectory());

        // Find first CRGROUP sub-folder (contains a CRGroupIndex JSON)
        File[] crFolders = mopJsonDir.listFiles(File::isDirectory);
        Assume.assumeTrue("No CRGROUP sub-folders found in " + MRF_MOP_JSON_DIR,
                crFolders != null && crFolders.length > 0);

        String crGroup   = crFolders[0].getName();
        String crGroupDir = crFolders[0].getAbsolutePath();

        // Require a CRGroupIndex JSON in that folder
        Assume.assumeTrue("CRGroupIndex JSON not found in " + crGroupDir,
                new CRGroupIndexLoader().isCRGroupFolder(crGroupDir, MRF_NODE_TYPE, MRF_ACTIVITY, crGroup));

        new File(MRF_OUTPUT_DIR).mkdirs();

        MopConfig config = new MopConfigLoader().load(MRF_TEMPLATE);
        assertEquals("Template must have mopGenerationMode=GROUP", "GROUP", config.getMopGenerationMode());

        String mopBase = MRF_NODE_TYPE + "_" + MRF_ACTIVITY;
        new MopGenerator(config).generateCRGroupMops(
                crGroupDir, MRF_NODE_TYPE, MRF_ACTIVITY, crGroup, MRF_OUTPUT_DIR, "mop", mopBase);

        // Load CRGroupIndex to know expected groups and nodes
        CRGroupIndex crGroupIndex =
                new CRGroupIndexLoader().load(crGroupDir, MRF_NODE_TYPE, MRF_ACTIVITY, crGroup);

        assertFalse("CRGroupIndex must have groups", crGroupIndex.getGroups().isEmpty());
        assertFalse("CRGroupIndex must have nodes",  crGroupIndex.getAllNodes().isEmpty());

        // Verify one MOP per GROUP
        for (CRGroupIndex.GroupEntry ge : crGroupIndex.getGroups()) {
            File groupMop = new File(MRF_OUTPUT_DIR, mopBase + "_" + ge.getGroup() + "_MOP.mop");
            assertTrue("GROUP MOP not created: " + groupMop.getName(), groupMop.exists());
            assertTrue("GROUP MOP is empty: " + groupMop.getName(), groupMop.length() > 0);

            String mopContent = new String(Files.readAllBytes(groupMop.toPath()), StandardCharsets.UTF_8);
            for (String nodeName : ge.getNodes()) {
                assertTrue("MOP header must list node " + nodeName, mopContent.contains(nodeName));
            }
        }

        // Verify CR-level approval summary
        String approvalExt = "MSWORD".equalsIgnoreCase(config.getMopApprovalFormatType()) ? "docx" : "html";
        if (!"TEXT".equalsIgnoreCase(config.getMopApprovalFormatType())) {
            File summary = new File(MRF_OUTPUT_DIR, mopBase + "_" + crGroup + "_SUMMARY." + approvalExt);
            assertTrue("CRGROUP approval summary not generated", summary.exists());
            if ("html".equals(approvalExt)) {
                String html = new String(Files.readAllBytes(summary.toPath()), StandardCharsets.UTF_8);
                assertTrue("Summary must contain CRGROUP name", html.contains(crGroup));
                assertTrue("Summary must contain APPROVAL COPY", html.contains("APPROVAL COPY"));
                assertTrue("Summary must list first node",
                        html.contains(crGroupIndex.getAllNodes().get(0)));
            }
        }

        System.out.println("=== MRF CRGROUP MOP Generation ===");
        System.out.println("CRGROUP: " + crGroup);
        System.out.println("Groups: " + crGroupIndex.getGroups().stream()
                .map(ge -> ge.getGroup() + "=" + ge.getNodes())
                .collect(java.util.stream.Collectors.joining(", ")));
        System.out.println("Output dir: " + MRF_OUTPUT_DIR);
    }
}
