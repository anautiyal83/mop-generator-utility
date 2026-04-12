package com.nokia.mopgen;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class MopGeneratorTest {

    private static final String TEMPLATE = "src/main/resources/SBC_FIXED_LINE_CONFIGURATION_MOP-Template.yaml";

    // -------------------------------------------------------------------------
    // Config loading
    // -------------------------------------------------------------------------

    @Test
    public void testIncludesMechanism() throws Exception {
        Assume.assumeTrue("SBC template not found — skipping includes test", new File(TEMPLATE).exists());
        MopConfig config = new MopConfigLoader().load(TEMPLATE);
        assertFalse("preNodeHealthCheck should be loaded via includes",
                config.getPreNodeHealthCheck().isEmpty());
        assertEquals("PRE_NODE_HEALTH_CHECK", config.getPreNodeHealthCheck().get(0).getName());

        assertFalse("backup should be present", config.getBackup().isEmpty());
        assertTrue("backup command should contain 'netconfprov'",
                config.getBackup().get(0).getCommands().get(0).contains("netconfprov"));

        assertNotNull("rollback should be loaded via includes", config.getRollback());
        assertFalse("rollback.precheck should not be empty",
                config.getRollback().getPrecheck().isEmpty());
    }

    // -------------------------------------------------------------------------
    // JSON file mode
    //
    // Parameters (pass as -D JVM system properties):
    //
    //   mop.json.file            path to the unified JSON file (required)
    //   mop.json.output.config   path to the *_json-output.yaml file (required)
    //   mop.crgroup              CR scope filter, e.g. CR-001 (optional — omit if CIQ has no group)
    //   mop.node.type            node type, e.g. MRF  (default: MRF)
    //   mop.activity             activity name       (default: ANNOUNCEMENT_LOADING)
    //   mop.template             MOP template YAML   (default: MRF_ANNOUNCEMENT_LOADING_MOP-Template.yaml)
    //   mop.output.dir           output directory    (default: target/mrf-mop-output-json)
    //
    // Example Maven invocation:
    //   mvn test -Dtest=MopGeneratorTest#testJsonFileMopGeneration
    //            -Dmop.json.file=target/mrf-mop-json/MRF_ANNOUNCEMENT_LOADING_ALL.json
    //            -Dmop.json.output.config=src/main/resources/MRF_ANNOUNCEMENT_LOADING_json-output.yaml
    //            -Dmop.crgroup=CR1
    // -------------------------------------------------------------------------

    private static final String MRF_NODE_TYPE = "MRF";
    private static final String MRF_ACTIVITY  = "ANNOUNCEMENT_LOADING";
    private static final String MRF_TEMPLATE  = "src/main/resources/MRF_ANNOUNCEMENT_LOADING_MOP-Template.yaml";

    @Test
    public void testJsonFileMopGeneration() throws Exception {
        String jsonFile       = System.getProperty("mop.json.file");
        String jsonConfigFile = System.getProperty("mop.json.output.config");

        Assume.assumeNotNull("Skipping: set -Dmop.json.file and -Dmop.json.output.config to run this test",
                jsonFile, jsonConfigFile);
        Assume.assumeTrue("JSON file not found: " + jsonFile, new File(jsonFile).exists());
        Assume.assumeTrue("JSON output config not found: " + jsonConfigFile, new File(jsonConfigFile).exists());

        String nodeType   = System.getProperty("mop.node.type", MRF_NODE_TYPE);
        String activity   = System.getProperty("mop.activity",  MRF_ACTIVITY);
        String crGroup    = System.getProperty("mop.crgroup");   // optional
        String templateFile = System.getProperty("mop.template", MRF_TEMPLATE);
        String outputDir  = System.getProperty("mop.output.dir", "target/mrf-mop-output-json");

        new File(outputDir).mkdirs();

        // Build CLI args and invoke via MopGeneratorMain
        java.util.List<String> argList = new java.util.ArrayList<>();
        argList.add("--json-file");               argList.add(jsonFile);
        argList.add("--json-output-config-file"); argList.add(jsonConfigFile);
        argList.add("--node-type");               argList.add(nodeType);
        argList.add("--activity");                argList.add(activity);
        argList.add("--mop-template");            argList.add(templateFile);
        argList.add("--output-dir");              argList.add(outputDir);
        if (crGroup != null && !crGroup.isEmpty()) {
            argList.add("--crgroup");
            argList.add(crGroup);
        }

        int exitCode = MopGeneratorMain.run(argList.toArray(new String[0]));
        assertEquals("MopGeneratorMain should exit 0", 0, exitCode);

        // Verify approval summary was written
        MopConfig config = new MopConfigLoader().load(templateFile);
        String ext = "MSWORD".equalsIgnoreCase(config.getMopApprovalFormatType()) ? "docx" : "html";
        File[] summaryFiles = new File(outputDir).listFiles(
                f -> f.getName().endsWith("_SUMMARY." + ext));
        assertNotNull("Output dir is empty", summaryFiles);
        assertTrue("No approval summary generated", summaryFiles.length > 0);
        assertTrue("Approval summary is empty", summaryFiles[0].length() > 0);

        if ("html".equals(ext)) {
            String html = new String(Files.readAllBytes(summaryFiles[0].toPath()), StandardCharsets.UTF_8);
            assertTrue("Summary must contain APPROVAL COPY", html.contains("APPROVAL COPY"));
            assertTrue("Summary must contain node type", html.contains(nodeType));
        }

        System.out.println("=== JSON File Mode ===");
        System.out.println("JSON file  : " + jsonFile);
        System.out.println("CR scope   : " + (crGroup != null ? crGroup : "(none — all nodes)"));
        System.out.println("Summary    : " + summaryFiles[0].getAbsolutePath()
                + " (" + summaryFiles[0].length() + " bytes)");
        System.out.println("Output dir : " + outputDir);
    }
}
