package com.nokia.mopgen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates an approval summary document from a unified CIQ JSON file.
 *
 * <p>The YAML template drives what activity/rollback sections appear in the
 * summary (pre-health-check, backup, configuration, execution, rollback, etc.)
 * and provides the commands with descriptions and validations.
 *
 * <h3>Output produced</h3>
 * <pre>
 * &lt;base&gt;[_&lt;crGroup&gt;]_SUMMARY.html   — HTML approval summary (default)
 * &lt;base&gt;[_&lt;crGroup&gt;]_SUMMARY.docx   — DOCX approval summary (if mopApprovalFormatType: MSWORD)
 * </pre>
 */
public class MopGenerator {

    private static final Logger log = LoggerFactory.getLogger(MopGenerator.class);

    /**
     * Matches {@code ${VAR}} or {@code ${VAR | func}} or {@code ${VAR | func:arg}}.
     * Group 1 = full content between the braces.
     */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    private MopConfig config;

    /**
     * Variable context for the MOP currently being generated.
     * Holds columns whose value is identical across all CIQ rows (group-level variables).
     * Referenced in commands using the {@code ${KEY}} syntax.
     */
    private final Map<String, String> variableContext = new LinkedHashMap<>();

    /**
     * All CIQ rows collected during the pre-scan phase.
     * Used to expand commands that contain per-row {@code ${KEY}} references
     * (i.e. columns whose value differs across rows, such as INPUT_FILE or MRF_DESTINATION_PATH).
     */
    private final List<Map<String, String>> ciqRows = new ArrayList<>();

    /**
     * Resolved commands collected per block name during generation.
     * Keyed by actionName; values are the fully-resolved command lines with optional
     * description/validation metadata from the YAML template.
     * Read by {@link #addYamlSection} to populate {@link YamlMopSection#commands}.
     */
    private final Map<String, List<MopSummaryCommand>> sectionMetadata = new LinkedHashMap<>();

    /** The {@link YamlMopGroup} being assembled during the current generation pass. */
    private YamlMopGroup currentYamlGroup;

    /**
     * When {@code true}, {@link #addYamlSection} appends to
     * {@link YamlMopGroup#rollback} instead of {@link YamlMopGroup#activity}.
     */
    private boolean inYamlRollback;

    public MopGenerator() {
    }

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Generate an approval summary from a unified JSON file produced by ciq-processor.
     *
     * <p>Loads the YAML MOP template from {@code templateFile}, resolves the JSON path
     * from {@code jsonDir} + {@code jsonFile}, filters nodes by {@code crGroup} when
     * provided, and writes the approval summary to {@code outputDir}.
     *
     * @param jsonDir               optional directory containing the JSON file (may be null or empty)
     * @param jsonFile              JSON file name; combined with {@code jsonDir} when provided
     * @param jsonOutputConfigFile  path to the {@code *_json-output.yaml} config
     * @param templateFile          path to the MOP template YAML (may be null for defaults)
     * @param summaryTemplate       optional path to an external HTML summary template
     * @param crGroup               optional CR group filter; null or empty = include all nodes
     * @param nodeType              e.g. "MRF"
     * @param activity              e.g. "ANNOUNCEMENT_LOADING"
     * @param outputDir             directory where the summary file will be written
     * @param mopFileName           output file base name (null = derived from nodeType + activity)
     */
    public void generateSummary(String jsonDir, String jsonFile, String jsonOutputConfigFile,
                                 String templateFile, String summaryTemplate, String crGroup,
                                 String nodeType, String activity,
                                 String outputDir, String mopFileName) throws IOException {

        config = new MopConfigLoader().load(templateFile);
        if (summaryTemplate != null) config.setSummaryTemplatePath(summaryTemplate);

        String mopFileNameBase = (mopFileName != null)
                ? (mopFileName.contains(".") ? mopFileName.substring(0, mopFileName.lastIndexOf('.')) : mopFileName)
                : nodeType + "_" + activity;

        Map<String, String> scopeFilter = new LinkedHashMap<>();
        if (crGroup != null && !crGroup.isEmpty()) scopeFilter.put("crGroup", crGroup);

        String jsonFilePath = (jsonDir != null && !jsonDir.isEmpty())
                ? jsonDir + java.io.File.separator + jsonFile
                : jsonFile;

        String nodeNameKey = config.getJsonMapping().getOrDefault("nodeNameKey", "node");
        String neIdKey     = config.getJsonMapping().getOrDefault("neIdKey",     "niamID");

        log.info("=== MOP Summary Generator ===");
        log.info("JSON file: {}", jsonFilePath);
        log.info("JSON output config: {}", jsonOutputConfigFile);
        log.info("Scope filter: {}, Node type: {}, Activity: {}", scopeFilter, nodeType, activity);

        String today = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());

        // Read node data from the unified JSON
        Map<String, Object> jsonOutputConfig = new JsonOutputConfigLoader().load(jsonOutputConfigFile);
        List<NodeData> nodeDataList = new MopJsonReader().read(jsonFilePath, jsonOutputConfig, scopeFilter);
        log.info("Loaded {} node(s) from JSON file", nodeDataList.size());

        // Build the approval summary document
        MopSummaryDocument summaryDoc = new MopSummaryDocument();
        summaryDoc.addMeta("nodeType",  nodeType);
        summaryDoc.addMeta("activity",  activity);
        for (Map.Entry<String, String> e : scopeFilter.entrySet()) summaryDoc.addMeta(e.getKey(), e.getValue());
        summaryDoc.addMeta("generated", today);

        // One MopSummaryUnit per node — sections driven by the YAML template
        for (NodeData nodeData : nodeDataList) {
            String nodeName = nodeData.getNodeInfo().getOrDefault(nodeNameKey, "NODE");
            String neId     = nodeData.getNodeInfo().getOrDefault(neIdKey, nodeName);
            log.info("  Processing node {} (neId={})", nodeName, neId);

            YamlMopGroup yamlGroup = buildYamlGroupFromNodeData(nodeData, nodeType, activity, nodeName, neId);
            if (yamlGroup != null) {
                summaryDoc.addUnit(GroupApprovalMopGenerator.toSummaryUnit(
                        yamlGroup, new LinkedHashMap<>(nodeData.getNodeInfo())));
            }
        }

        // Write the approval summary
        GroupApprovalMopGenerator approvalGen = GroupApprovalMopGenerator.forConfig(config);
        if (approvalGen != null) {
            String ext         = "MSWORD".equalsIgnoreCase(config.getMopApprovalFormatType()) ? "docx" : "html";
            String scopeValue  = scopeFilter.getOrDefault("crGroup", scopeFilter.getOrDefault("group", ""));
            String suffix      = scopeValue.isEmpty() ? "" : "_" + scopeValue;
            String summaryPath = outputDir + "/" + mopFileNameBase + suffix + "_SUMMARY." + ext;
            approvalGen.generate(summaryDoc, summaryPath);
            log.info("Summary written: {}", summaryPath);
        }
    }

    // =========================================================================
    // JSON NodeData inner generation
    // =========================================================================

    /**
     * Build a {@link YamlMopGroup} from a generic {@link NodeData} unit loaded from a
     * unified JSON file.  Uses the activity/rollback pipeline defined in the YAML template.
     */
    private YamlMopGroup buildYamlGroupFromNodeData(
            NodeData nodeData, String nodeType, String activity,
            String nodeName, String neId) throws IOException {

        log.info("  Building YAML for node {} (neId={})", nodeName, neId);

        // Initialise state
        variableContext.clear();
        ciqRows.clear();
        sectionMetadata.clear();
        variableContext.put("NODE", nodeName);
        variableContext.put("NEID", neId);
        variableContext.putAll(nodeData.getNodeInfo());

        currentYamlGroup = new YamlMopGroup();
        currentYamlGroup.group = nodeName;
        currentYamlGroup.nodes.add(nodeName);
        currentYamlGroup.niamMapping.put(nodeName, neId);
        inYamlRollback = false;

        // Pre-scan all tables for variable context
        for (List<Map<String, Object>> rows : nodeData.getAllConfigData().values()) {
            if (!rows.isEmpty()) enrichVariableContextFromGenericRows(rows);
        }
        log.debug("Variable context for {}: {}", nodeName, variableContext.keySet());

        // Activity sections
        for (ActivityConfig ac : config.getPreNodeHealthCheck())      buildStaticActivity(ac);
        for (ActivityConfig ac : config.getBackup())                   buildStaticActivity(ac);
        for (ActivityConfig ac : config.getActivity().getPrecheck())   buildStaticActivity(ac);
        for (ActivityConfig ac : config.getActivity().getExecution())  buildStaticActivity(ac);
        for (ActivityConfig ac : config.getActivity().getPostcheck())  buildStaticActivity(ac);
        for (ActivityConfig ac : config.getPostNodeHealthCheck())      buildStaticActivity(ac);

        // Rollback sections
        inYamlRollback = true;
        for (ActivityConfig ac : config.getRollback().getPrecheck())   buildStaticActivity(ac);
        for (ActivityConfig ac : config.getRollback().getExecution())  buildStaticActivity(ac);
        for (ActivityConfig ac : config.getRollback().getPostcheck())  buildStaticActivity(ac);

        YamlMopGroup built = currentYamlGroup;
        currentYamlGroup = null;
        return built;
    }

    // =========================================================================
    // Activity builder
    // =========================================================================

    /**
     * Resolve and collect a static (non-CIQ) activity block.
     * Conditional entries are evaluated using the current variable context.
     */
    private void buildStaticActivity(ActivityConfig ac) {
        if (ac == null || ac.getName() == null || ac.getCommandEntries().isEmpty()) return;
        List<CommandEntry> entries = expandEntries(ac.getCommandEntries());
        if (entries.isEmpty()) return;
        collectBlockActionEntries(ac.getName(), entries);
        addYamlSection(ac.getName(), ac.getName(),
                ac.getDescription(), ac.getTargetNode(), ac.getMethod(), null);
    }

    // =========================================================================
    // Command collection helpers
    // =========================================================================

    /**
     * Resolve a list of {@link CommandEntry} objects (with optional description/validation)
     * and store them in {@link #sectionMetadata}.
     */
    private void collectBlockActionEntries(String actionName, List<CommandEntry> entries) {
        List<MopSummaryCommand> metaLines =
                sectionMetadata.computeIfAbsent(actionName, k -> new ArrayList<>());
        for (CommandEntry entry : entries) {
            String resolved = resolveVariables(resolveConstants(entry.getText()));
            String desc     = entry.getDescription() != null
                    ? resolveVariables(resolveConstants(entry.getDescription())) : null;
            String validate = entry.getValidation() != null
                    ? resolveVariables(resolveConstants(entry.getValidation())) : null;
            if (!ciqRows.isEmpty() && hasVariablePattern(resolved)) {
                for (Map<String, String> row : ciqRows) {
                    metaLines.add(new MopSummaryCommand(
                            resolveRowVariables(resolved, row), desc, validate));
                }
            } else {
                metaLines.add(new MopSummaryCommand(resolved, desc, validate));
            }
        }
    }

    // =========================================================================
    // YAML section builder
    // =========================================================================

    /**
     * Append a new {@link YamlMopSection} to {@link #currentYamlGroup}.
     * No-op when {@code currentYamlGroup} is {@code null}.
     */
    private void addYamlSection(String sectionName, String metaKey, String description,
                                 String targetNode, String method, String typeMarker) {
        if (currentYamlGroup == null) return;
        YamlMopSection s = new YamlMopSection();
        s.name        = sectionName;
        s.description = description;
        s.targetNode  = targetNode;
        s.method      = method;
        s.typeMarker  = typeMarker;
        List<MopSummaryCommand> meta = sectionMetadata.get(metaKey);
        if (meta != null) {
            for (MopSummaryCommand cl : meta) {
                s.commands.add(new YamlMopCommand(cl.getText(), cl.getDescription(), cl.getValidation()));
            }
        }
        if (inYamlRollback) {
            currentYamlGroup.rollback.add(s);
        } else {
            currentYamlGroup.activity.add(s);
        }
    }

    // =========================================================================
    // Variable resolution
    // =========================================================================

    private String resolveConstants(String cmd) {
        Map<String, String> constants = config.getConstants();
        if (constants == null || constants.isEmpty() || cmd == null) return cmd;
        List<String> keys = new ArrayList<>(constants.keySet());
        keys.sort((a, b) -> b.length() - a.length());
        for (String key : keys) cmd = cmd.replace("$" + key, constants.get(key));
        return cmd;
    }

    private String resolveVariables(String cmd) {
        if (cmd == null || variableContext.isEmpty()) return cmd;
        return applyVariables(cmd, variableContext);
    }

    private String resolveRowVariables(String cmd, Map<String, String> row) {
        if (cmd == null) return cmd;
        return applyVariables(cmd, row);
    }

    private String applyVariables(String cmd, Map<String, String> vars) {
        Matcher m = VAR_PATTERN.matcher(cmd);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String content = m.group(1);
            String[] parts  = content.split("\\|", 2);
            String varName  = parts[0].trim();
            String func     = parts.length > 1 ? parts[1].trim() : null;
            String value = vars.get(varName);
            if (value == null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            if (func != null) value = applyFunction(value, func, varName);
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String applyFunction(String value, String funcExpr, String varName) {
        String funcName;
        String funcArg = null;
        int colon = funcExpr.indexOf(':');
        if (colon >= 0) {
            funcName = funcExpr.substring(0, colon).trim();
            funcArg  = funcExpr.substring(colon + 1);
        } else {
            funcName = funcExpr.trim();
        }
        switch (funcName.toLowerCase()) {
            case "stripext": {
                int dot = value.lastIndexOf('.');
                return dot >= 0 ? value.substring(0, dot) : value;
            }
            case "basename": {
                int slash = value.lastIndexOf('/');
                return slash >= 0 ? value.substring(slash + 1) : value;
            }
            case "dirname": {
                int slash = value.lastIndexOf('/');
                return slash >= 0 ? value.substring(0, slash) : value;
            }
            case "upper":  return value.toUpperCase();
            case "lower":  return value.toLowerCase();
            case "replace": {
                if (funcArg != null) {
                    int sep = funcArg.indexOf(':');
                    String oldText = sep >= 0 ? funcArg.substring(0, sep) : funcArg;
                    String newText = sep >= 0 ? funcArg.substring(sep + 1) : "";
                    return value.replace(oldText, newText);
                }
                return value;
            }
            default:
                log.warn("Unknown string function '{}' on variable '{}'", funcName, varName);
                return value;
        }
    }

    private boolean hasVariablePattern(String cmd) {
        if (cmd == null) return false;
        int start = cmd.indexOf("${");
        return start >= 0 && cmd.indexOf('}', start) > start;
    }

    private boolean evaluateCondition(String condition) {
        if (condition == null || condition.trim().isEmpty()) return false;
        String resolved = resolveVariables(resolveConstants(condition)).trim();
        int neIdx = resolved.indexOf("!=");
        if (neIdx >= 0) {
            return !matchesPattern(resolved.substring(0, neIdx).trim(),
                                   resolved.substring(neIdx + 2).trim());
        }
        int eqIdx = resolved.indexOf("==");
        if (eqIdx >= 0) {
            return matchesPattern(resolved.substring(0, eqIdx).trim(),
                                  resolved.substring(eqIdx + 2).trim());
        }
        return !resolved.isEmpty();
    }

    private boolean matchesPattern(String value, String pattern) {
        if (!pattern.contains("X")) return value.equals(pattern);
        StringBuilder regex = new StringBuilder("^");
        for (char c : pattern.toCharArray()) {
            if (c == 'X') regex.append(".*");
            else if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) regex.append('\\').append(c);
            else regex.append(c);
        }
        regex.append("$");
        return value.matches(regex.toString());
    }

    // =========================================================================
    // Conditional entry expansion
    // =========================================================================

    private List<CommandEntry> expandEntries(List<CommandEntry> entries) {
        List<CommandEntry> result = new ArrayList<>();
        for (CommandEntry entry : entries) {
            if (entry.isPlain()) {
                result.add(entry);
            } else if (entry.isConditional()) {
                List<CommandEntry> branch = evaluateCondition(entry.getCondition())
                        ? entry.getThenEntries() : entry.getElseEntries();
                result.addAll(expandEntries(branch));
            }
        }
        return result;
    }

    // =========================================================================
    // Generic row helpers
    // =========================================================================

    /**
     * Extend {@link #variableContext} and {@link #ciqRows} from a list of generic JSON rows.
     * Only scalar (String) values are considered for single-value variable promotion.
     */
    private void enrichVariableContextFromGenericRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return;
        for (Map<String, Object> row : rows) ciqRows.add(rowToStringMap(row));
        Map<String, String> colValues = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (!(e.getValue() instanceof String)) continue;
                String col = e.getKey();
                String val = (String) e.getValue();
                if (val.isEmpty()) continue;
                if (!colValues.containsKey(col)) {
                    colValues.put(col, val);
                } else if (!val.equals(colValues.get(col))) {
                    colValues.put(col, null);
                }
            }
        }
        for (Map.Entry<String, String> e : colValues.entrySet()) {
            if (e.getValue() != null && !variableContext.containsKey(e.getKey())) {
                variableContext.put(e.getKey(), e.getValue());
            }
        }
    }

    /** Convert a generic JSON object row to a {@code Map<String,String>} for variable expansion. */
    private static Map<String, String> rowToStringMap(Map<String, Object> row) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getValue() != null) result.put(e.getKey(), e.getValue().toString());
        }
        return result;
    }
}
