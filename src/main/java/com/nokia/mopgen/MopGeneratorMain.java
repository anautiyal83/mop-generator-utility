package com.nokia.mopgen;

import com.nokia.ciq.reader.util.FileNamingUtil;

import java.io.File;
import java.io.IOException;

/**
 * CLI entry point for the MOP Generator Utility.
 *
 * <p>The scope parameter must match the JSON folder structure written by ciq-processor.
 * The correct parameter depends on what {@code groupByColumnName} was set to in the
 * validation-rules YAML used during ciq-processor execution:
 *
 * <pre>
 *   ciq-processor groupByColumnName  →  mop-generator parameter
 *   ─────────────────────────────────────────────────────────────
 *   CRGROUP                          →  --crgroup  &lt;CR-001&gt;
 *   GROUP                            →  --group    &lt;A&gt;
 *   NODE  (or SBC default)           →  --host     &lt;SBC-1&gt;
 * </pre>
 *
 * <h3>CRGROUP mode</h3>
 * <pre>
 *   java -jar mop-generator-utility.jar \
 *     --json-dir      &lt;mop-json&gt;                          \
 *     --node-type     &lt;MRF&gt;                               \
 *     --activity      &lt;ANNOUNCEMENT_LOADING&gt;             \
 *     --crgroup       &lt;CR-001&gt;                            \
 *     --template      &lt;MRF_ANNOUNCEMENT_LOADING_MOP-Template.yaml&gt; \
 *     --output-dir    &lt;/output/mop&gt;                       \
 *     --mop-file-name &lt;MRF_ANNOUNCEMENT_LOADING&gt;
 * </pre>
 * Reads {@code mop-json/CR-001/} (CRGROUP sub-folder produced by ciq-processor).
 * Generates one MOP per GROUP (or per node) in the CR, plus one CR-level approval summary.
 *
 * <h3>GROUP mode</h3>
 * <pre>
 *   java -jar mop-generator-utility.jar \
 *     --json-dir      &lt;mop-json&gt;                          \
 *     --node-type     &lt;MRF&gt;                               \
 *     --activity      &lt;ANNOUNCEMENT_LOADING&gt;             \
 *     --group         &lt;A&gt;                                 \
 *     --template      &lt;MRF_ANNOUNCEMENT_LOADING_MOP-Template.yaml&gt; \
 *     --output-dir    &lt;/output/mop&gt;                       \
 *     --mop-file-name &lt;MRF_ANNOUNCEMENT_LOADING&gt;
 * </pre>
 * Reads {@code mop-json/A/} (GROUP sub-folder produced by ciq-processor with groupByColumnName=GROUP).
 *
 * <h3>NODE mode</h3>
 * <pre>
 *   java -jar mop-generator-utility.jar \
 *     --json-dir      &lt;mop-json/SBC-1_CR1&gt;               \
 *     --node-type     &lt;SBC&gt;                               \
 *     --activity      &lt;FIXED_LINE_CONFIGURATION&gt;          \
 *     --host          &lt;SBC-1&gt;                             \
 *     --template      &lt;SBC_FIXED_LINE_CONFIGURATION_MOP-Template.yaml&gt; \
 *     --output-dir    &lt;/output/mop&gt;                       \
 *     --mop-file-name &lt;SBC-1_CR1.mop&gt;
 * </pre>
 *
 * <p>Exit code: 0 = success, 1 = error.
 */
public class MopGeneratorMain {

    /** JSON folder structure detected from json-dir contents. */
    private enum JsonDirMode { CRGROUP, GROUP, NODE, EMPTY }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        String jsonDir       = null;
        String nodeType      = null;
        String activity      = null;
        String host          = null;   // --host  : NODE mode  (maps to childOrder internally)
        String group         = null;   // --group : GROUP mode
        String crGroup       = null;   // --crgroup: CRGROUP mode
        String templateFile  = null;
        String outputDir     = null;
        String mopFileName   = null;
        String mopExtension  = "mop";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--json-dir":      if (i + 1 < args.length) jsonDir      = args[++i]; break;
                case "--node-type":     if (i + 1 < args.length) nodeType     = args[++i]; break;
                case "--activity":      if (i + 1 < args.length) activity     = args[++i]; break;
                case "--host":          if (i + 1 < args.length) host         = args[++i]; break;
                case "--child-order":   if (i + 1 < args.length) host         = args[++i]; break; // alias
                case "--group":         if (i + 1 < args.length) group        = args[++i]; break;
                case "--crgroup":       if (i + 1 < args.length) crGroup      = args[++i]; break;
                case "--template":      if (i + 1 < args.length) templateFile = args[++i]; break;
                case "--output-dir":    if (i + 1 < args.length) outputDir    = args[++i]; break;
                case "--mop-file-name": if (i + 1 < args.length) mopFileName  = args[++i]; break;
                case "--mop-extension": if (i + 1 < args.length) mopExtension = args[++i]; break;
                case "--help": case "-h": printUsage(); return 0;
                default:
                    System.err.println("Error: Unknown argument: " + args[i]);
                    printUsage();
                    return 1;
            }
        }

        if (jsonDir == null || nodeType == null || activity == null || outputDir == null) {
            System.err.println("Error: --json-dir, --node-type, --activity, and --output-dir are required.");
            printUsage();
            return 1;
        }

        // Exactly one scope parameter must be provided
        int scopeCount = (crGroup != null ? 1 : 0) + (group != null ? 1 : 0) + (host != null ? 1 : 0);
        if (scopeCount == 0) {
            System.err.println("Error: One of --crgroup, --group, or --host is required.");
            printUsage();
            return 1;
        }
        if (scopeCount > 1) {
            System.err.println("Error: Only one of --crgroup, --group, or --host may be specified.");
            printUsage();
            return 1;
        }

        // Determine intended mode from provided parameter
        String intendedMode = crGroup != null ? "CRGROUP" : group != null ? "GROUP" : "NODE";

        // Validate json-dir structure matches intended mode
        JsonDirMode detected = detectJsonDirMode(jsonDir, nodeType, activity);
        String mismatch = validateMode(intendedMode, detected, jsonDir, nodeType, activity,
                                       crGroup, group, host);
        if (mismatch != null) {
            System.err.println(mismatch);
            return 1;
        }

        try {
            MopConfig config = new MopConfigLoader().load(templateFile);

            String mopFileNameBase = mopFileName == null ? nodeType + "_" + activity
                    : (mopFileName.contains(".")
                            ? mopFileName.substring(0, mopFileName.lastIndexOf('.'))
                            : mopFileName);

            if (crGroup != null) {
                String crGroupDir = jsonDir + File.separator + crGroup;
                new MopGenerator(config).generateCRGroupMops(
                        crGroupDir, nodeType, activity, crGroup, outputDir, mopExtension, mopFileNameBase);
                System.out.println("CRGROUP mode MOPs + summary generated in: " + outputDir);

            } else if (group != null) {
                if (mopFileName == null) {
                    System.err.println("Error: --mop-file-name is required for GROUP mode.");
                    return 1;
                }
                String groupDir = jsonDir + File.separator + group;
                new MopGenerator(config).generateGroupMops(
                        groupDir, nodeType, activity, group, outputDir, mopExtension, mopFileNameBase);
                System.out.println("GROUP mode MOPs generated in: " + outputDir);

            } else {
                // NODE / host mode — resolves jsonDir/host/ (mirrors CRGROUP and GROUP pattern)
                // Naming: <base>_<host>_MOP.<ext>  (consistent with GROUP and CRGROUP modes)
                String hostDir    = jsonDir + File.separator + host;
                String outputFile = outputDir + File.separator + mopFileNameBase + "_" + host + "_MOP." + mopExtension;
                new MopGenerator(config).generate(hostDir, nodeType, activity, host, outputFile);
                System.out.println("MOP generated: " + outputFile);
            }
            return 0;

        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    // -------------------------------------------------------------------------
    // JSON dir structure detection
    // -------------------------------------------------------------------------

    /**
     * Inspects {@code jsonDir} to determine the folder structure written by ciq-processor.
     * Checks sub-folders for CRGroupIndex / GroupIndex files.
     */
    static JsonDirMode detectJsonDirMode(String jsonDir, String nodeType, String activity) {
        File dir = new File(jsonDir);
        if (!dir.exists() || !dir.isDirectory()) return JsonDirMode.EMPTY;

        File[] subdirs = dir.listFiles(File::isDirectory);
        if (subdirs != null) {
            for (File sub : subdirs) {
                String name = sub.getName();
                // Check CRGROUP first (CRGroupIndex has crGroup field)
                if (new CRGroupIndexLoader().isCRGroupFolder(sub.getAbsolutePath(), nodeType, activity, name)) {
                    return JsonDirMode.CRGROUP;
                }
                // Check GROUP (GroupIndex has group field)
                if (new GroupIndexLoader().isGroupFolder(sub.getAbsolutePath(), nodeType, activity, name)) {
                    return JsonDirMode.GROUP;
                }
            }
            // If subdirs exist but none are CRGROUP or GROUP → NODE mode
            // (ciq-processor NODE mode writes one sub-folder per child-order)
            for (File sub : subdirs) {
                File[] jsonFiles = sub.listFiles(f -> f.isFile() && f.getName().endsWith(".json"));
                if (jsonFiles != null && jsonFiles.length > 0) return JsonDirMode.NODE;
            }
        }

        // Flat JSON files directly in jsonDir (legacy / global mode)
        File[] jsonFiles = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".json"));
        if (jsonFiles != null && jsonFiles.length > 0) return JsonDirMode.NODE;

        return JsonDirMode.EMPTY;
    }

    /**
     * Validates that the user-supplied scope parameter matches the detected JSON folder structure.
     *
     * @return an error message string if there is a mismatch, or {@code null} if everything is valid
     */
    private static String validateMode(String intendedMode, JsonDirMode detected,
                                       String jsonDir, String nodeType, String activity,
                                       String crGroup, String group, String host) {
        if (detected == JsonDirMode.EMPTY) {
            return "Error: No recognisable JSON data found in: " + jsonDir
                    + "\n       Run ciq-processor first to generate the JSON output.";
        }

        if (intendedMode.equals(detected.name())) return null;   // match — all good

        // Mismatch — build a helpful error
        StringBuilder sb = new StringBuilder();
        sb.append("Error: Parameter mismatch — ");

        switch (intendedMode) {
            case "CRGROUP":
                sb.append("--crgroup ").append(crGroup).append(" was supplied, ");
                break;
            case "GROUP":
                sb.append("--group ").append(group).append(" was supplied, ");
                break;
            case "NODE":
                sb.append("--host ").append(host).append(" was supplied, ");
                break;
        }

        sb.append("but the JSON in '").append(jsonDir).append("' was generated in ")
          .append(detected.name()).append(" mode.");
        sb.append("\n");

        switch (detected) {
            case CRGROUP:
                String detectedCRGroup = findFirstSubdirName(jsonDir, nodeType, activity, JsonDirMode.CRGROUP);
                sb.append("  The JSON directory contains CRGROUP sub-folders (e.g. '")
                  .append(detectedCRGroup != null ? detectedCRGroup : "CR-001").append("/').");
                sb.append("\n  Use: --crgroup <crgroup-id>  (ciq-processor was run with groupByColumnName: CRGROUP)");
                break;
            case GROUP:
                String detectedGroup = findFirstSubdirName(jsonDir, nodeType, activity, JsonDirMode.GROUP);
                sb.append("  The JSON directory contains GROUP sub-folders (e.g. '")
                  .append(detectedGroup != null ? detectedGroup : "A").append("/').");
                sb.append("\n  Use: --group <group-name>  (ciq-processor was run with groupByColumnName: GROUP)");
                break;
            case NODE:
                sb.append("  The JSON directory contains flat node-based JSON files.");
                sb.append("\n  Use: --host <node-name>  (ciq-processor was run with groupByColumnName: NODE)");
                break;
        }

        return sb.toString();
    }

    /** Returns the name of the first sub-folder matching the given mode, or null. */
    private static String findFirstSubdirName(String jsonDir, String nodeType, String activity,
                                               JsonDirMode mode) {
        File[] subdirs = new File(jsonDir).listFiles(File::isDirectory);
        if (subdirs == null) return null;
        for (File sub : subdirs) {
            String name = sub.getName();
            if (mode == JsonDirMode.CRGROUP
                    && new CRGroupIndexLoader().isCRGroupFolder(sub.getAbsolutePath(), nodeType, activity, name)) {
                return name;
            }
            if (mode == JsonDirMode.GROUP
                    && new GroupIndexLoader().isGroupFolder(sub.getAbsolutePath(), nodeType, activity, name)) {
                return name;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Usage
    // -------------------------------------------------------------------------

    private static void printUsage() {
        System.out.println("Usage: java -jar mop-generator-utility.jar [options]");
        System.out.println();
        System.out.println("Required (all modes):");
        System.out.println("  --json-dir      <dir>    JSON input directory from ciq-processor");
        System.out.println("  --node-type     <type>   Node type, e.g. SBC or MRF");
        System.out.println("  --activity      <name>   Activity name");
        System.out.println("  --output-dir    <dir>    Directory where MOP files will be written");
        System.out.println();
        System.out.println("Scope (exactly one required — must match ciq-processor groupByColumnName):");
        System.out.println("  --crgroup <id>           CRGROUP mode  (groupByColumnName: CRGROUP)");
        System.out.println("                           Reads <json-dir>/<crgroup>/");
        System.out.println("                           Generates MOPs per GROUP (or node) + approval summary");
        System.out.println("  --group   <name>         GROUP mode    (groupByColumnName: GROUP)");
        System.out.println("                           Reads <json-dir>/<group>/");
        System.out.println("                           Generates MOPs for all nodes in the group");
        System.out.println("  --host    <node>         NODE mode     (groupByColumnName: NODE)");
        System.out.println("                           Reads <json-dir>/ directly");
        System.out.println("                           Output: <base>_<node>_MOP.<ext>");
        System.out.println();
        System.out.println("Optional:");
        System.out.println("  --template      <file>   MOP template YAML file");
        System.out.println("  --mop-file-name <name>   Output file base name");
        System.out.println("  --mop-extension <ext>    File extension for MOPs (default: mop)");
        System.out.println("  --child-order   <order>  Alias for --host (legacy)");
        System.out.println();
        System.out.println("Exit code: 0=success, 1=error");
    }
}
