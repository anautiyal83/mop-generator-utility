package com.nokia.mopgen;

import java.io.IOException;

/**
 * CLI entry point for the MOP Generator Utility.
 *
 * <p>Reads a unified JSON file produced by ciq-processor and generates an
 * approval summary document (HTML or DOCX) driven by the YAML MOP template.
 *
 * <pre>
 *   java -jar mop-generator-utility.jar \
 *     --json-dir                &lt;/path/to/json&gt;                           \
 *     --json-file               &lt;all-nodes.json&gt;                          \
 *     --json-output-config-file &lt;MRF_ANNOUNCEMENT_LOADING_json-output.yaml&gt; \
 *     --node-type               &lt;MRF&gt;                                     \
 *     --activity                &lt;ANNOUNCEMENT_LOADING&gt;                    \
 *     --mop-template            &lt;MRF_ANNOUNCEMENT_LOADING_MOP-Template.yaml&gt; \
 *     --output-dir              &lt;/output/mop&gt;                             \
 *     [--crgroup                &lt;CR-001&gt;]
 *
 * </pre>
 *
 * <p>{@code --json-dir} is optional — when provided it is combined with {@code --json-file}
 * inside {@link MopGenerator#generateSummary} to form the full path.
 * {@code --crgroup} is optional — omit when the CIQ has no group column and
 * all nodes should be included in the summary.
 *
 * <p>Exit code: 0 = success, 1 = error.
 */
public class MopGeneratorMain {

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        String jsonFile             = null;
        String jsonDir              = null;
        String jsonOutputConfigFile = null;
        String nodeType             = null;
        String activity             = null;
        String crGroup              = null;
        String templateFile         = null;
        String summaryTemplate      = null;
        String outputDir            = null;
        String mopFileName          = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--json-file":               if (i + 1 < args.length) jsonFile             = args[++i]; break;
                case "--json-dir":                if (i + 1 < args.length) jsonDir              = args[++i]; break;
                case "--json-output-config-file": if (i + 1 < args.length) jsonOutputConfigFile = args[++i]; break;
                case "--node-type":               if (i + 1 < args.length) nodeType             = args[++i]; break;
                case "--activity":                if (i + 1 < args.length) activity             = args[++i]; break;
                case "--crgroup":                 if (i + 1 < args.length) crGroup              = args[++i]; break;
                case "--mop-template":            if (i + 1 < args.length) templateFile         = args[++i]; break;
                case "--mop-summary-template":    if (i + 1 < args.length) summaryTemplate      = args[++i]; break;
                case "--output-dir":              if (i + 1 < args.length) outputDir            = args[++i]; break;
                case "--mop-summary-file-name":   if (i + 1 < args.length) mopFileName          = args[++i]; break;
                case "--help": case "-h": printUsage(); return 0;
                default:
                    System.err.println("Error: Unknown argument: " + args[i]);
                    printUsage();
                    return 1;
            }
        }

        if (nodeType == null || activity == null || outputDir == null) {
            System.err.println("Error: --node-type, --activity, and --output-dir are required.");
            printUsage();
            return 1;
        }
        if (jsonFile == null) {
            System.err.println("Error: --json-file is required.");
            printUsage();
            return 1;
        }
        if (jsonOutputConfigFile == null) {
            System.err.println("Error: --json-output-config-file is required.");
            printUsage();
            return 1;
        }

        try {
            new MopGenerator().generateSummary(
                    jsonDir, jsonFile, jsonOutputConfigFile,
                    templateFile, summaryTemplate, crGroup,
                    nodeType, activity, outputDir, mopFileName);

            System.out.println("Approval summary generated in: " + outputDir);
            return 0;

        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar mop-generator-utility.jar [options]");
        System.out.println();
        System.out.println("Required:");
        System.out.println("  --json-file                   <name>  JSON file name (combined with --json-dir if provided)");
        System.out.println("  --json-output-config-file     <file>  Matching *_json-output.yaml");
        System.out.println("  --node-type                   <type>  Node type, e.g. MRF");
        System.out.println("  --activity                    <name>  Activity name, e.g. ANNOUNCEMENT_LOADING");
        System.out.println("  --output-dir                  <dir>   Directory where output files will be written");
        System.out.println();
        System.out.println("Optional:");
        System.out.println("  --json-dir                 <dir>   Directory containing the JSON file");
        System.out.println("  --crgroup                  <id>    CR scope filter (omit if CIQ has no group column)");
        System.out.println("  --mop-template             <file>  MOP template YAML file");
        System.out.println("  --mop-summary-template     <file>  External HTML approval summary template");
        System.out.println("  --mop-summary-file-name    <name>  Output file base name");
        System.out.println();
        System.out.println("Exit code: 0=success, 1=error");
    }
}
