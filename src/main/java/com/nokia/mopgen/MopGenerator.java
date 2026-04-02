package com.nokia.mopgen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nokia.mopgen.CRGroupIndex;
import com.nokia.ciq.reader.model.CiqIndex;
import com.nokia.ciq.reader.model.CiqRow;
import com.nokia.ciq.reader.model.CiqSheet;
import com.nokia.ciq.reader.store.CiqDataStore;
import com.nokia.ciq.reader.store.JsonFileCiqDataStore;
import com.nokia.ciq.reader.util.FileNamingUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates a Nokia SOI XML MOP file from CIQ JSON data.
 *
 * <h3>MOP structure produced</h3>
 * <pre>
 * ## header
 *
 * ##ACTIVITY_..._PRE_NODE_HEALTH_CHECK       (static, if configured)
 * ##ACTIVITY_..._BACKUP                      (static, if configured)
 * ##ACTIVITY_..._ACTIVITY_PRECHECK           (static, if configured)
 *
 * ##ACTIVITY_..._<TABLE>_<ACTION>_ACTIVITY_CONFIGURATION   (one per table/action, auto from CIQ)
 * ...
 * ##ACTIVITY_..._<TABLE>_TABLE_POSTCHECK         (one per table, auto — CREATE/DELETE/MODIFY checks)
 * ...
 *
 * ##ACTIVITY_..._ACTIVITY_POSTCHECK          (static, if configured)
 * ##ACTIVITY_..._POST_NODE_HEALTH_CHECK      (static, if configured)
 *
 * ##ACTIVITY_..._ROLLBACK_PRECHECK           (static, if configured)
 * ##ACTIVITY_..._<TABLE>_ROLLBACK_CONFIGURATION  (one per CREATE table, auto DELETE XML)
 * ...
 * ##ACTIVITY_..._ROLLBACK_POSTCHECK          (static, if configured)
 * </pre>
 *
 * <p>Every static block and every XML tag is driven by the YAML template —
 * zero code changes are required to support a new node type, sheet, or column.
 */
public class MopGenerator {

    private static final Logger log = LoggerFactory.getLogger(MopGenerator.class);

    /**
     * Matches {@code ${VAR}} or {@code ${VAR | func}} or {@code ${VAR | func:arg}}.
     * Group 1 = full content between the braces.
     */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    private final MopConfig config;
    private final XmlBuilder xmlBuilder;

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
     * Each entry is a flat column→value map for one data row.
     */
    private final List<Map<String, String>> ciqRows = new ArrayList<>();

    /**
     * Command metadata (description + validation) collected per block during generation.
     * Written to a sidecar {@code .meta.json} file so the approval summary can render
     * rich per-command annotations without polluting the actual MOP payload.
     */
    private final Map<String, List<MopSection.CommandLine>> sectionMetadata = new LinkedHashMap<>();

    public MopGenerator(MopConfig config) {
        this.config = config;
        XmlBuilder base = XmlBuilderFactory.create(config.getXmlBuilderName(), config);
        // Wrap with TemplateXmlBuilder: uses YAML xmlTemplates when defined per table/action,
        // falls back to the base programmatic builder when no template is found.
        this.xmlBuilder = new TemplateXmlBuilder(config, base);
    }

    /**
     * Generate the MOP file for a specific child order.
     *
     * @param jsonDir    directory containing per-child-order CIQ JSON files
     *                   (e.g. {@code mop-json/SBC-1_CR1/})
     * @param nodeType   e.g. "SBC"
     * @param activity   e.g. "FIXED_LINE_CONFIGURATION"
     * @param childOrder e.g. "SBC-1_CR1" — must match the postfix used by ciq-validator
     * @param outputPath destination MOP file path
     */
    public void generate(String jsonDir, String nodeType, String activity,
                         String childOrder, String outputPath)
            throws IOException {

        log.info("=== MOP Generator ===");
        log.info("JSON dir:    {}", jsonDir);
        log.info("Node type:   {}, Activity: {}", nodeType, activity);
        log.info("Child order: {}", childOrder);

        CiqDataStore store = new JsonFileCiqDataStore(jsonDir, nodeType, activity, childOrder);
        CiqIndex index = store.getIndex();
        // Use template-configured table order if provided; fall back to CIQ index order.
        List<String> configuredTables = config.getActivity().getConfiguration();
        List<String> tables = configuredTables.isEmpty() ? index.getAllTables() : configuredTables;
        String prefix = nodeType + "_" + activity;

        log.info("Tables: {}", tables);

        // Build variable context for ${KEY} substitution in commands.
        // Seed with node identity, then extend with any CIQ columns that have a
        // consistent single value across all rows in their sheet.
        buildVariableContext(store, childOrder,
                index.getNiamMapping().getOrDefault(childOrder, childOrder));

        StringBuilder sb = new StringBuilder();

        // ---------------------------------------------------------------
        // 1. File header — for NODE mode (childOrder set), show only that node
        // ---------------------------------------------------------------
        String mopName = mopNameFrom(outputPath);
        CiqIndex headerIndex = index;
        if (childOrder != null && !childOrder.isEmpty()) {
            headerIndex = new CiqIndex();
            headerIndex.setNodeType(nodeType);
            headerIndex.setActivity(activity);
            Map<String, String> niamSubset = new LinkedHashMap<>();
            String neid = index.getNiamMapping().get(childOrder);
            if (neid != null) {
                niamSubset.put(childOrder, neid);
            } else if (!index.getNiamMapping().isEmpty()) {
                Map.Entry<String, String> first = index.getNiamMapping().entrySet().iterator().next();
                niamSubset.put(first.getKey(), first.getValue());
            }
            headerIndex.setNiamMapping(niamSubset);
        }
        writeHeader(sb, mopName, nodeType, activity, headerIndex);

        // ---------------------------------------------------------------
        // 2. PRE_NODE_HEALTH_CHECK
        // ---------------------------------------------------------------
        for (ActivityConfig ac : config.getPreNodeHealthCheck()) writeStaticActivity(sb, prefix, ac);

        // ---------------------------------------------------------------
        // 3. BACKUP
        // ---------------------------------------------------------------
        for (ActivityConfig ac : config.getBackup()) writeStaticActivity(sb, prefix, ac);

        // ---------------------------------------------------------------
        // 4. ACTIVITY_PRECHECK  (one or more blocks before configuration)
        // ---------------------------------------------------------------
        for (ActivityConfig ac : config.getActivity().getPrecheck()) writeStaticActivity(sb, prefix, ac);

        // ---------------------------------------------------------------
        // 4b. Per-table TABLE_PRECHECK  (auto-generated: download + existence checks)
        // ---------------------------------------------------------------
        if (config.getActivity().getTablePrecheck().isEnabled()) {
            writeTablePrecheckBlocks(sb, prefix, tables, store);
        }

        // ---------------------------------------------------------------
        // 5. Per-table ACTIVITY_CONFIGURATION  (auto-generated from CIQ)
        //    Also collect tables that have CREATE rows → needed for rollback
        // ---------------------------------------------------------------
        // tableName → list of (neId, createRows) pairs for rollback generation
        Map<String, List<RollbackEntry>> rollbackEntries = new LinkedHashMap<>();

        for (String tableName : tables) {
            CiqSheet sheet = store.getSheet(tableName);
            if (sheet == null || sheet.getRows().isEmpty()) {
                log.debug("Skipping empty table: {}", tableName);
                continue;
            }

            TableConfig tableConfig = config.getTableConfig(tableName);
            Map<String, Map<String, List<CiqRow>>> grouped = groupRows(sheet);

            for (Map.Entry<String, Map<String, List<CiqRow>>> nodeEntry : grouped.entrySet()) {
                String nodeName = nodeEntry.getKey();
                String neId = index.getNiamMapping().getOrDefault(nodeName, nodeName);

                for (Map.Entry<String, List<CiqRow>> actionEntry : nodeEntry.getValue().entrySet()) {
                    String action = actionEntry.getKey();
                    List<CiqRow> rows = actionEntry.getValue();

                    log.info("  {} / {} / {} — {} row(s)", tableName, nodeName, action, rows.size());

                    String xml = xmlBuilder.buildXml(sheet, neId, tableName, tableConfig, rows, action);
                    writeConfigurationActivity(sb, prefix, tableName, action, xml,
                            config.getActivity().getConfigurationTargetNode());

                    // Track CREATE rows for rollback section
                    if ("CREATE".equals(action)) {
                        rollbackEntries
                            .computeIfAbsent(tableName, k -> new ArrayList<>())
                            .add(new RollbackEntry(sheet, neId, tableConfig, rows));
                    }
                }
            }
        }

        // ---------------------------------------------------------------
        // 5b. Static execution blocks  (non-CIQ activities: MRF, DPA, etc.)
        // ---------------------------------------------------------------
        for (ActivityConfig ac : config.getActivity().getExecution()) writeStaticActivity(sb, prefix, ac);

        // ---------------------------------------------------------------
        // 5c. Per-table TABLE_POSTCHECK  (auto-generated: download + verification)
        // ---------------------------------------------------------------
        if (config.getActivity().getTablePostcheck().isEnabled()) {
            writeTablePostcheckBlocks(sb, prefix, tables, store);
        }

        // ---------------------------------------------------------------
        // 6. ACTIVITY_POSTCHECK  (one or more blocks after configuration)
        // ---------------------------------------------------------------
        for (ActivityConfig ac : config.getActivity().getPostcheck()) writeStaticActivity(sb, prefix, ac);

        // ---------------------------------------------------------------
        // 7. POST_NODE_HEALTH_CHECK
        // ---------------------------------------------------------------
        for (ActivityConfig ac : config.getPostNodeHealthCheck()) writeStaticActivity(sb, prefix, ac);

        // ---------------------------------------------------------------
        // 8. ROLLBACK section
        // ---------------------------------------------------------------
        sb.append("\n").append(repeat('#', 78)).append("\n");
        sb.append("## ROLLBACK\n");
        sb.append(repeat('#', 78)).append("\n");

        // Resolve rollback table order once — used by pre/postcheck and configuration blocks.
        List<String> rollbackTableOrder = config.getRollback().getConfiguration();
        Iterable<String> rollbackTables = rollbackTableOrder.isEmpty()
                ? rollbackEntries.keySet()
                : rollbackTableOrder;

        // 8a. ROLLBACK_PRECHECK (from rollback config)
        for (ActivityConfig ac : config.getRollback().getPrecheck()) {
            writeStaticActivity(sb, prefix, ac);
        }

        // 8a2. Per-table ROLLBACK TABLE_PRECHECK (auto-generated: verify CREATE records exist)
        if (config.getRollback().getTablePrecheck().isEnabled()) {
            writeRollbackTablePrecheckBlocks(sb, prefix, rollbackTables, rollbackEntries);
        }

        // 8b. ROLLBACK_CONFIGURATION — one per table that had CREATE rows.
        //     Honour rollback.configuration order if specified; otherwise use
        //     the order in which CREATE rows were encountered.
        for (String tableName : rollbackTables) {
            List<RollbackEntry> entries = rollbackEntries.get(tableName);
            if (entries == null) continue;
            for (RollbackEntry re : entries) {
                String rollbackXml = xmlBuilder.buildRollbackXml(
                        re.sheet, re.neId, tableName, re.tableConfig, re.rows);
                writeRollbackConfiguration(sb, prefix, tableName, rollbackXml,
                        config.getRollback().getConfigurationTargetNode());
            }
        }

        // 8b2. Static rollback execution blocks  (non-CIQ activities: MRF, DPA, etc.)
        for (ActivityConfig ac : config.getRollback().getExecution()) writeStaticActivity(sb, prefix, ac);

        // 8b3. Per-table ROLLBACK TABLE_POSTCHECK (auto-generated: verify CREATE records gone)
        if (config.getRollback().getTablePostcheck().isEnabled()) {
            writeRollbackTablePostcheckBlocks(sb, prefix, rollbackTables, rollbackEntries);
        }

        // 8c. ROLLBACK_POSTCHECK (from rollback config)
        for (ActivityConfig ac : config.getRollback().getPostcheck()) {
            writeStaticActivity(sb, prefix, ac);
        }

        // ---------------------------------------------------------------
        // Write to file
        // ---------------------------------------------------------------
        File outFile = new File(outputPath);
        if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
        writeSectionMetadata(outputPath);
        log.info("MOP written: {}", outputPath);
    }

    // -------------------------------------------------------------------------
    // Shared block-writing helpers — all sections use these for uniformity
    // -------------------------------------------------------------------------

    /**
     * Write the {@code ##ACTIVITY_} header lines common to every block:
     * activity name, description, targetNode, and the type/optional marker.
     *
     * @param typeMarker {@code "$CREATE"}, {@code "$CREATE_ROLLBACK"}, etc. for
     *                   CIQ-driven blocks; {@code null} for static/check blocks
     */
    private void writeBlockHeader(StringBuilder sb, String activityName,
                                  String description, String targetNode,
                                  String typeMarker) {
        sb.append("\n##").append(activityName).append("\n");
        if (description != null && !description.isEmpty()) {
            sb.append("## Description: ").append(description).append("\n");
        }
        if (targetNode != null && !targetNode.isEmpty()) {
            sb.append("## TargetNode: ").append(targetNode).append("\n");
        }
        if (typeMarker != null) {
            sb.append("$").append(typeMarker).append("\n");
        }
    }

    /**
     * Write one action entry: {@code ACTIVITY_EXECUTION_ACTION_N}, {@code METHOD_N},
     * and {@code PAYLOAD_N={ … }}.
     *
     * <p>Each command is resolved in two passes:
     * <ol>
     *   <li>Constants ({@code $KEY}) — values from {@link MopConfig#getConstants()}</li>
     *   <li>Group-level variables ({@code ${KEY}}) — columns with a single consistent
     *       value across all CIQ rows (from {@link #variableContext})</li>
     * </ol>
     * If after both passes a command still contains {@code ${KEY}} patterns, it is
     * expanded once per CIQ row using that row's column values.  This handles
     * per-row variables such as {@code INPUT_FILE} and {@code MRF_DESTINATION_PATH}
     * that differ across rows — each row generates one expanded command line.
     */
    private void writeBlockAction(StringBuilder sb, int index, String actionName,
                                  String method, List<String> commands) {
        sb.append("ACTIVITY_EXECUTION_ACTION_").append(index).append("=").append(actionName).append("\n");
        sb.append("ACTIVITY_EXECUTION_METHOD_").append(index).append("=").append(method).append("\n");
        sb.append("ACTIVITY_EXECUTION_PAYLOAD_").append(index).append("={\n");
        for (String cmd : commands) {
            String resolved = resolveVariables(resolveConstants(cmd));
            if (!ciqRows.isEmpty() && hasVariablePattern(resolved)) {
                // Per-row expansion: write one line per CIQ row
                for (Map<String, String> row : ciqRows) {
                    sb.append(resolveRowVariables(resolved, row)).append("\n");
                }
            } else {
                sb.append(resolved).append("\n");
            }
        }
        sb.append("}\n");
    }

    /** Returns true if {@code cmd} still contains an unresolved {@code ${...}} pattern. */
    private boolean hasVariablePattern(String cmd) {
        if (cmd == null) return false;
        int start = cmd.indexOf("${");
        return start >= 0 && cmd.indexOf('}', start) > start;
    }

    /**
     * Resolve {@code ${KEY}} or {@code ${KEY | func}} patterns using a single CIQ row's data.
     */
    private String resolveRowVariables(String cmd, Map<String, String> row) {
        if (cmd == null) return cmd;
        return applyVariables(cmd, row);
    }

    /**
     * Core variable resolution: scan {@code cmd} for {@code ${...}} patterns and
     * replace each one whose variable name is present in {@code vars}.
     *
     * <p>Supported syntax inside the braces:
     * <ul>
     *   <li>{@code VAR} — plain substitution</li>
     *   <li>{@code VAR | func} — apply a named function to the value</li>
     *   <li>{@code VAR | func:arg} — apply a function with an argument</li>
     * </ul>
     *
     * Unresolved patterns (variable not in {@code vars}) are left unchanged.
     */
    private String applyVariables(String cmd, Map<String, String> vars) {
        Matcher m = VAR_PATTERN.matcher(cmd);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String content = m.group(1);           // everything inside ${...}
            String[] parts  = content.split("\\|", 2);
            String varName  = parts[0].trim();
            String func     = parts.length > 1 ? parts[1].trim() : null;

            String value = vars.get(varName);
            if (value == null) {
                // Leave the pattern as-is; another pass may resolve it
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            if (func != null) {
                value = applyFunction(value, func, varName);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Apply a named string-manipulation function to {@code value}.
     *
     * <p>Built-in functions:
     * <table>
     *   <tr><th>Function</th><th>Description</th><th>Example</th></tr>
     *   <tr><td>{@code stripExt}</td><td>Remove last file extension</td>
     *       <td>{@code file.tar} → {@code file}</td></tr>
     *   <tr><td>{@code basename}</td><td>File name without directory</td>
     *       <td>{@code /path/to/file.tar} → {@code file.tar}</td></tr>
     *   <tr><td>{@code dirname}</td><td>Directory without trailing file name</td>
     *       <td>{@code /path/to/file.tar} → {@code /path/to}</td></tr>
     *   <tr><td>{@code upper}</td><td>Convert to upper-case</td></tr>
     *   <tr><td>{@code lower}</td><td>Convert to lower-case</td></tr>
     *   <tr><td>{@code replace:old:new}</td><td>Replace all occurrences of {@code old}
     *       with {@code new}</td>
     *       <td>{@code replace:.tar:}</td></tr>
     * </table>
     */
    private String applyFunction(String value, String funcExpr, String varName) {
        String funcName;
        String funcArg = null;
        int colon = funcExpr.indexOf(':');
        if (colon >= 0) {
            funcName = funcExpr.substring(0, colon).trim();
            funcArg  = funcExpr.substring(colon + 1);    // preserve arg as-is
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
            case "upper":
                return value.toUpperCase();
            case "lower":
                return value.toLowerCase();
            case "replace": {
                if (funcArg != null) {
                    // format: replace:oldText:newText  (newText may be empty)
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

    /**
     * Replace {@code $KEY} (no braces) references with values from
     * {@link MopConfig#getConstants()}.  Keys are sorted longest-first so a
     * shorter key (e.g. {@code $DIR}) cannot partially match a longer one
     * (e.g. {@code $DIR_BACKUP}).
     */
    private String resolveConstants(String cmd) {
        Map<String, String> constants = config.getConstants();
        if (constants == null || constants.isEmpty() || cmd == null) return cmd;
        List<String> keys = new ArrayList<>(constants.keySet());
        keys.sort((a, b) -> b.length() - a.length());
        for (String key : keys) {
            cmd = cmd.replace("$" + key, constants.get(key));
        }
        return cmd;
    }

    /**
     * Replace {@code ${KEY}} or {@code ${KEY | func}} patterns using
     * {@link #variableContext} (group-level variables).
     * Patterns whose key is not in the context are left unchanged so they can
     * be resolved later by {@link #resolveRowVariables}.
     */
    private String resolveVariables(String cmd) {
        if (cmd == null || variableContext.isEmpty()) return cmd;
        return applyVariables(cmd, variableContext);
    }

    /**
     * Build the {@link #variableContext} map for the node currently being generated.
     *
     * <p>The context is seeded with:
     * <ul>
     *   <li>{@code NODE} — the node/child-order name</li>
     *   <li>{@code NEID} — the NE identifier from the NIAM mapping</li>
     * </ul>
     * Then every CIQ sheet is scanned: for each column whose value is identical
     * across all rows in that sheet, that column name and value are added to the
     * context.  This lets per-group attributes such as {@code CIRCLE} or
     * {@code VERSION} be referenced as {@code ${CIRCLE}} in command templates
     * without being hard-coded as constants.
     */
    private void buildVariableContext(CiqDataStore store, String nodeName, String neId) throws IOException {
        variableContext.clear();
        ciqRows.clear();
        sectionMetadata.clear();
        if (nodeName != null) variableContext.put("NODE", nodeName);
        if (neId != null)     variableContext.put("NEID", neId);
        for (String tableName : store.getIndex().getAllTables()) {
            enrichVariableContext(store.getSheet(tableName));
        }
        log.debug("Variable context for node {}: {}", nodeName, variableContext.keySet());
    }

    /**
     * Extend {@link #variableContext} with columns from {@code sheet} that have
     * exactly one distinct non-null value across all rows.  Already-present keys
     * (including {@code NODE} and {@code NEID}) are never overwritten.
     * Called incrementally as each GROUP-mode sheet is loaded.
     */
    private void enrichVariableContext(CiqSheet sheet) {
        if (sheet == null || sheet.getRows().isEmpty()) return;
        // Collect rows for per-row expansion
        for (CiqRow row : sheet.getRows()) {
            ciqRows.add(row.getData());
        }
        // Add columns with a single consistent value across all rows as group-level variables
        Map<String, String> colValues = new LinkedHashMap<>();
        for (CiqRow row : sheet.getRows()) {
            for (Map.Entry<String, String> cell : row.getData().entrySet()) {
                String col = cell.getKey();
                String val = cell.getValue();
                if (val == null || val.isEmpty()) continue;
                if (!colValues.containsKey(col)) {
                    colValues.put(col, val);
                } else if (!val.equals(colValues.get(col))) {
                    colValues.put(col, null); // inconsistent across rows — per-row only
                }
            }
        }
        for (Map.Entry<String, String> e : colValues.entrySet()) {
            if (e.getValue() != null && !variableContext.containsKey(e.getKey())) {
                variableContext.put(e.getKey(), e.getValue());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Static activity block writer
    // -------------------------------------------------------------------------

    /**
     * Write a static (non-CIQ) activity block using the given {@link ActivityConfig}.
     * Skipped silently if {@code ac} is null or has no command entries.
     * Conditional entries ({@code if}/{@code then}/{@code else}) are evaluated
     * using the current variable context before the block is written.
     */
    private void writeStaticActivity(StringBuilder sb, String prefix, ActivityConfig ac) {
        if (ac == null || ac.getName() == null || ac.getCommandEntries().isEmpty()) return;
        List<CommandEntry> entries = expandEntries(ac.getCommandEntries());
        if (entries.isEmpty()) return;
        writeBlockHeader(sb, ac.getName(), ac.getDescription(), ac.getTargetNode(), null);
        writeBlockActionEntries(sb, 1, ac.getName(), ac.getMethod(), entries);
    }

    /**
     * Expand a list of {@link CommandEntry} objects into a flat list of resolved leaf
     * entries (all conditionals evaluated, no more branching nodes).
     */
    private List<CommandEntry> expandEntries(List<CommandEntry> entries) {
        List<CommandEntry> result = new ArrayList<>();
        for (CommandEntry entry : entries) {
            if (entry.isPlain()) {
                result.add(entry);
            } else if (entry.isConditional()) {
                List<CommandEntry> branch = evaluateCondition(entry.getCondition())
                        ? entry.getThenEntries()
                        : entry.getElseEntries();
                result.addAll(expandEntries(branch));
            }
        }
        return result;
    }

    /**
     * Write a block action from a list of {@link CommandEntry} leaf entries.
     * Description and validation metadata are NOT written to the MOP payload;
     * they are collected in {@link #sectionMetadata} and serialised to a
     * {@code .meta.json} sidecar file so the approval summary can use them
     * without polluting the executable MOP.
     */
    private void writeBlockActionEntries(StringBuilder sb, int index, String actionName,
                                         String method, List<CommandEntry> entries) {
        sb.append("ACTIVITY_EXECUTION_ACTION_").append(index).append("=").append(actionName).append("\n");
        sb.append("ACTIVITY_EXECUTION_METHOD_").append(index).append("=").append(method).append("\n");
        sb.append("ACTIVITY_EXECUTION_PAYLOAD_").append(index).append("={\n");
        List<MopSection.CommandLine> metaLines =
                sectionMetadata.computeIfAbsent(actionName, k -> new ArrayList<>());
        for (CommandEntry entry : entries) {
            String resolved = resolveVariables(resolveConstants(entry.getText()));
            String desc     = entry.getDescription() != null
                    ? resolveVariables(resolveConstants(entry.getDescription())) : null;
            String validate = entry.getValidation() != null
                    ? resolveVariables(resolveConstants(entry.getValidation())) : null;
            if (!ciqRows.isEmpty() && hasVariablePattern(resolved)) {
                // Per-row expansion — each expanded line shares the same desc/validation
                for (Map<String, String> row : ciqRows) {
                    String rowResolved = resolveRowVariables(resolved, row);
                    sb.append(rowResolved).append("\n");
                    metaLines.add(new MopSection.CommandLine(rowResolved, desc, validate));
                }
            } else {
                sb.append(resolved).append("\n");
                metaLines.add(new MopSection.CommandLine(resolved, desc, validate));
            }
        }
        sb.append("}\n");
    }

    /**
     * Serialise per-block command metadata collected in {@link #sectionMetadata}
     * to a sidecar JSON file alongside the MOP.  The approval summary reads this
     * file to render per-command description and validation without reading them
     * from the MOP payload.
     *
     * <p>The file is written as {@code <mopOutputPath>.meta.json}.
     * If no block has any command metadata the file is not created.
     */
    private void writeSectionMetadata(String mopOutputPath) throws IOException {
        boolean hasAny = false;
        for (List<MopSection.CommandLine> lines : sectionMetadata.values()) {
            for (MopSection.CommandLine cl : lines) {
                if (cl.description != null || cl.validation != null) { hasAny = true; break; }
            }
            if (hasAny) break;
        }
        if (!hasAny) return;

        Map<String, List<Map<String, String>>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<MopSection.CommandLine>> e : sectionMetadata.entrySet()) {
            List<Map<String, String>> cmds = new ArrayList<>();
            for (MopSection.CommandLine cl : e.getValue()) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("text", cl.text);
                if (cl.description != null) m.put("description", cl.description);
                if (cl.validation  != null) m.put("validation",  cl.validation);
                cmds.add(m);
            }
            out.put(e.getKey(), cmds);
        }
        new ObjectMapper().writeValue(new File(mopOutputPath + ".meta.json"), out);
        log.debug("Metadata sidecar written: {}", mopOutputPath + ".meta.json");
    }

    /**
     * Evaluate a condition expression after resolving constants and group-level variables.
     *
     * <p>Supported forms:
     * <ul>
     *   <li>{@code LHS == RHS} — true when LHS matches RHS (exact or pattern)</li>
     *   <li>{@code LHS != RHS} — true when LHS does not match RHS</li>
     *   <li>{@code LHS} alone — true when non-empty after resolution</li>
     * </ul>
     *
     * <p>If the RHS contains {@code X}, it is treated as a wildcard pattern where
     * each {@code X} matches any sequence of characters.  Otherwise exact string
     * equality is used.  Examples:
     * <pre>
     *   ${VERSION} == 13.X.X.X   →  true  for "13.1.2.3", "13.20.0.1"
     *   ${VERSION} == 13.X.X.X   →  false for "14.1.2.3"
     *   ${VERSION} == 13          →  exact match only
     * </pre>
     */
    private boolean evaluateCondition(String condition) {
        if (condition == null || condition.trim().isEmpty()) return false;
        String resolved = resolveVariables(resolveConstants(condition)).trim();

        int neIdx = resolved.indexOf("!=");
        if (neIdx >= 0) {
            String lhs = resolved.substring(0, neIdx).trim();
            String rhs = resolved.substring(neIdx + 2).trim();
            return !matchesPattern(lhs, rhs);
        }
        int eqIdx = resolved.indexOf("==");
        if (eqIdx >= 0) {
            String lhs = resolved.substring(0, eqIdx).trim();
            String rhs = resolved.substring(eqIdx + 2).trim();
            return matchesPattern(lhs, rhs);
        }
        // Truthy: non-empty after resolution
        return !resolved.isEmpty();
    }

    /**
     * Match {@code value} against {@code pattern}.
     *
     * <p>If {@code pattern} contains the character {@code X}, each {@code X} is
     * treated as a wildcard that matches any sequence of characters (equivalent
     * to {@code .*} in a regular expression).  All other characters in the
     * pattern are matched literally.
     *
     * <p>If {@code pattern} contains no {@code X}, plain string equality is used.
     *
     * <p>Examples:
     * <pre>
     *   matchesPattern("13.1.2.3",  "13.X.X.X") → true
     *   matchesPattern("14.1.2.3",  "13.X.X.X") → false
     *   matchesPattern("13.1.2.3",  "13.X")      → true   (X matches "1.2.3")
     *   matchesPattern("13",         "13")        → true   (exact)
     * </pre>
     */
    private boolean matchesPattern(String value, String pattern) {
        if (!pattern.contains("X")) {
            return value.equals(pattern);
        }
        // Build a regex from the pattern: escape regex meta-chars, replace X with .*
        StringBuilder regex = new StringBuilder("^");
        for (char c : pattern.toCharArray()) {
            if (c == 'X') {
                regex.append(".*");
            } else if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        regex.append("$");
        return value.matches(regex.toString());
    }

    // -------------------------------------------------------------------------
    // CIQ-driven activity writers
    // -------------------------------------------------------------------------

    /**
     * Write one ACTIVITY_CONFIGURATION block.
     * Activity name: {@code {prefix}_{tableName}_{action}_ACTIVITY_CONFIGURATION}
     */
    private void writeConfigurationActivity(StringBuilder sb, String prefix,
                                            String tableName, String action, String xml,
                                            String targetNode) {
        CommandConfig cmds = config.getCommands();
        String activityName = tableName + "_" + action + "_ACTIVITY_CONFIGURATION";
        String actionName   = tableName + "_" + action + "_CONFIGURATION";
        String storageFile  = config.getStoragePath() + "/" + prefix + "_" + tableName + "_" + action + ".xml";
        String description  = "Apply " + action + " configuration for table " + tableName;

        writeBlockHeader(sb, activityName, description, targetNode, action);

        List<String> commands = new ArrayList<>();
        commands.add(cmds.resolveStageFile(storageFile));
        commands.add(xml.endsWith("\n") ? xml.substring(0, xml.length() - 1) : xml);
        commands.add(cmds.getHeredocEnd());
        commands.add(cmds.resolveApplyConfig(storageFile));
        writeBlockAction(sb, 1, actionName, "CLI", commands);
    }

    /**
     * Write one ROLLBACK_CONFIGURATION block (DELETE XML for a CREATE table).
     * Activity name: {@code {prefix}_{tableName}_ROLLBACK_CONFIGURATION}
     */
    private void writeRollbackConfiguration(StringBuilder sb, String prefix,
                                            String tableName, String rollbackXml,
                                            String targetNode) {
        CommandConfig cmds = config.getCommands();
        String activityName = tableName + "_ROLLBACK_CONFIGURATION";
        String actionName   = tableName + "_ROLLBACK_CONFIGURATION";
        String storageFile  = config.getStoragePath() + "/" + prefix + "_" + tableName + "_ROLLBACK.xml";
        String description  = "Rollback: delete " + tableName + " records created by this MOP";

        writeBlockHeader(sb, activityName, description, targetNode, "CREATE_ROLLBACK");

        List<String> commands = new ArrayList<>();
        commands.add(cmds.resolveStageFile(storageFile));
        commands.add(rollbackXml.endsWith("\n") ? rollbackXml.substring(0, rollbackXml.length() - 1) : rollbackXml);
        commands.add(cmds.getHeredocEnd());
        commands.add(cmds.resolveApplyConfig(storageFile));
        writeBlockAction(sb, 1, actionName, "CLI", commands);
    }

    // -------------------------------------------------------------------------
    // Header
    // -------------------------------------------------------------------------

    /** Derives the MOP name from the output file path (filename without extension). */
    private static String mopNameFrom(String outputPath) {
        String name = new java.io.File(outputPath).getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void writeHeader(StringBuilder sb, String mopName, String nodeType, String activity, CiqIndex index) {
        String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        sb.append(repeat('#', 78)).append("\n");
        sb.append("## MOP         : ").append(mopName).append("\n");
        sb.append("## Description : ").append(nodeType).append(" ").append(activity)
          .append(" configuration\n");
        sb.append("## Generated   : ").append(today).append("\n");
        sb.append("## Node type   : ").append(nodeType).append("\n");
        sb.append("## Activity    : ").append(activity).append("\n");
        if (!index.getNiamMapping().isEmpty()) {
            boolean first = true;
            for (Map.Entry<String, String> e : index.getNiamMapping().entrySet()) {
                sb.append(first ? "## Nodes       : " : "##               ");
                sb.append(e.getKey()).append(" -> ").append(e.getValue()).append("\n");
                first = false;
            }
        }
        sb.append(repeat('#', 78)).append("\n");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Table precheck writer
    // -------------------------------------------------------------------------

    /**
     * Generate one TABLE_PRECHECK activity block per configured table.
     *
     * <p>Each block contains:
     * <ol>
     *   <li>One download command (reads current table config from the NE).</li>
     *   <li>For each distinct primary key in CREATE rows: a command verifying
     *       the record does NOT already exist.</li>
     *   <li>For each distinct primary key in DELETE/MODIFY rows: a command
     *       verifying the record DOES already exist.</li>
     * </ol>
     *
     * <p>The primary key is auto-detected as the first depth-1 {@code Record.*}
     * column present in the sheet, resolved to its XML tag via {@link TableConfig}.
     */
    private void writeTablePrecheckBlocks(StringBuilder sb, String prefix,
                                          List<String> tables, CiqDataStore store) throws IOException {
        TablePrecheckConfig tpc = config.getActivity().getTablePrecheck();

        for (String tableName : tables) {
            CiqSheet sheet = store.getSheet(tableName);
            if (sheet == null || sheet.getRows().isEmpty()) continue;

            TableConfig tableConfig = config.getTableConfig(tableName);

            // Detect primary key column and its resolved XML tag
            String keyCol = findPrimaryKeyColumn(sheet);
            String keyTag = keyCol != null ? tableConfig.resolveFieldTag(keyCol) : null;

            // Build payload lines
            List<String> payload = new ArrayList<>();

            // 1. Download command
            if (tpc.getDownloadCommand() != null) {
                payload.add(resolve(tpc.getDownloadCommand(), tableName, null, null, null));
            }

            // 2. Per-record existence checks
            if (keyCol != null) {
                Map<String, Map<String, List<CiqRow>>> grouped = groupRows(sheet);

                for (Map<String, List<CiqRow>> byAction : grouped.values()) {
                    for (Map.Entry<String, List<CiqRow>> actionEntry : byAction.entrySet()) {
                        String action = actionEntry.getKey();
                        List<CiqRow> rows = actionEntry.getValue();

                        // Collect distinct key values for this action
                        Set<String> keyValues = new LinkedHashSet<>();
                        for (CiqRow row : rows) {
                            String kv = row.getData().get(keyCol);
                            if (kv != null) keyValues.add(kv);
                        }

                        boolean isCreate = "CREATE".equals(action);
                        String template = isCreate
                                ? tpc.getCreateCheckCommand()
                                : tpc.getExistsCheckCommand();

                        if (template != null) {
                            for (String keyValue : keyValues) {
                                payload.add(resolve(template, tableName, keyTag, keyValue, action));
                            }
                        }
                    }
                }
            }

            // Write the activity block
            String activityName = prefix + "_" + tableName + "_TABLE_PRECHECK";
            String description  = "Verify pre-conditions for table " + tableName + " before configuration";
            writeBlockHeader(sb, activityName, description, tpc.getTargetNode(), null);
            writeBlockAction(sb, 1, tableName + "_TABLE_PRECHECK", "CLI", payload);
        }
    }

    // -------------------------------------------------------------------------
    // Table postcheck writer
    // -------------------------------------------------------------------------

    /**
     * Generate one TABLE_POSTCHECK activity block per configured table.
     *
     * <p>Each block contains:
     * <ol>
     *   <li>One download command (re-reads table config after changes were applied).</li>
     *   <li>For CREATE rows: verify the record now exists.</li>
     *   <li>For DELETE rows: verify the record no longer exists.</li>
     *   <li>For MODIFY rows:
     *     <ul>
     *       <li>SubAction=MOD (or none): verify each non-null field matches CIQ value.</li>
     *       <li>SubAction=ADD: verify the sub-record key now exists.</li>
     *       <li>SubAction=DEL: verify the sub-record key no longer exists.</li>
     *     </ul>
     *   </li>
     * </ol>
     */
    private void writeTablePostcheckBlocks(StringBuilder sb, String prefix,
                                           List<String> tables, CiqDataStore store) throws IOException {
        TablePostcheckConfig tpc = config.getActivity().getTablePostcheck();

        for (String tableName : tables) {
            CiqSheet sheet = store.getSheet(tableName);
            if (sheet == null || sheet.getRows().isEmpty()) continue;

            TableConfig tableConfig = config.getTableConfig(tableName);

            // Detect primary key column and its resolved XML tag
            String keyCol = findPrimaryKeyColumn(sheet);
            String keyTag = keyCol != null ? tableConfig.resolveFieldTag(keyCol) : null;

            List<String> payload = new ArrayList<>();

            // 1. Download command
            if (tpc.getDownloadCommand() != null) {
                payload.add(resolve(tpc.getDownloadCommand(), tableName, null, null, null));
            }

            // 2. Per-row verification checks
            if (keyCol != null) {
                Map<String, Map<String, List<CiqRow>>> grouped = groupRows(sheet);

                for (Map<String, List<CiqRow>> byAction : grouped.values()) {
                    for (Map.Entry<String, List<CiqRow>> actionEntry : byAction.entrySet()) {
                        String action = actionEntry.getKey();
                        List<CiqRow> rows = actionEntry.getValue();

                        // Distinct primary keys already seen (avoid duplicate checks)
                        Set<String> seenKeys = new LinkedHashSet<>();

                        for (CiqRow row : rows) {
                            String keyValue = row.getData().get(keyCol);
                            String subAction = row.getData().get("SubAction");

                            if ("CREATE".equals(action)) {
                                // Record must now exist
                                if (tpc.getCreateCheckCommand() != null
                                        && keyValue != null && seenKeys.add(keyValue)) {
                                    payload.add(resolve(tpc.getCreateCheckCommand(),
                                            tableName, keyTag, keyValue, action));
                                }

                            } else if ("DELETE".equals(action)) {
                                // Record must no longer exist
                                if (tpc.getDeleteCheckCommand() != null
                                        && keyValue != null && seenKeys.add(keyValue)) {
                                    payload.add(resolve(tpc.getDeleteCheckCommand(),
                                            tableName, keyTag, keyValue, action));
                                }

                            } else if ("MODIFY".equals(action)) {
                                if (subAction == null || "MOD".equalsIgnoreCase(subAction)) {
                                    // Verify each non-null depth-1 non-key field value
                                    if (tpc.getModFieldCheckCommand() != null && keyValue != null) {
                                        for (String col : sheet.getColumns()) {
                                            if (col.equals(keyCol)) continue;
                                            if (!col.startsWith("Record.")) continue;
                                            String suffix = col.substring("Record.".length());
                                            if (suffix.contains(".")) continue; // depth-2, skip
                                            String fieldValue = row.getData().get(col);
                                            if (fieldValue == null || fieldValue.isEmpty()) continue;
                                            String fieldTag = tableConfig.resolveFieldTag(col);
                                            payload.add(resolve(tpc.getModFieldCheckCommand(),
                                                    tableName, keyTag, keyValue, action,
                                                    fieldTag, fieldValue));
                                        }
                                    }

                                } else if ("ADD".equalsIgnoreCase(subAction)) {
                                    // Sub-record key must now exist
                                    if (tpc.getSubAddCheckCommand() != null) {
                                        String[] subKey = findSubRecordKey(sheet, row, tableConfig);
                                        if (subKey != null) {
                                            payload.add(resolve(tpc.getSubAddCheckCommand(),
                                                    tableName, subKey[0], subKey[1], action));
                                        }
                                    }

                                } else if ("DEL".equalsIgnoreCase(subAction)) {
                                    // Sub-record key must no longer exist
                                    if (tpc.getSubDelCheckCommand() != null) {
                                        String[] subKey = findSubRecordKey(sheet, row, tableConfig);
                                        if (subKey != null) {
                                            payload.add(resolve(tpc.getSubDelCheckCommand(),
                                                    tableName, subKey[0], subKey[1], action));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Write the activity block
            String activityName = prefix + "_" + tableName + "_TABLE_POSTCHECK";
            String description  = "Verify post-conditions for table " + tableName + " after configuration";
            writeBlockHeader(sb, activityName, description, tpc.getTargetNode(), null);
            writeBlockAction(sb, 1, tableName + "_TABLE_POSTCHECK", "CLI", payload);
        }
    }

    // -------------------------------------------------------------------------
    // Rollback table pre/postcheck writers
    // -------------------------------------------------------------------------

    /**
     * Generate one ROLLBACK_TABLE_PRECHECK block per rollback table.
     *
     * <p>Since rollback only deletes previously CREATEd records, each block:
     * <ol>
     *   <li>Downloads current table config from the NE.</li>
     *   <li>For each CREATE row: verifies the record still EXISTS on the NE
     *       (required before it can be deleted by rollback).</li>
     * </ol>
     * Uses {@link TablePrecheckConfig#getExistsCheckCommand()}.
     */
    private void writeRollbackTablePrecheckBlocks(StringBuilder sb, String prefix,
                                                  Iterable<String> tables,
                                                  Map<String, List<RollbackEntry>> rollbackEntries) {
        TablePrecheckConfig tpc = config.getRollback().getTablePrecheck();

        for (String tableName : tables) {
            List<RollbackEntry> entries = rollbackEntries.get(tableName);
            if (entries == null) continue;

            List<String> payload = new ArrayList<>();

            // 1. Download command
            if (tpc.getDownloadCommand() != null) {
                payload.add(resolve(tpc.getDownloadCommand(), tableName, null, null, null));
            }

            // 2. For each CREATE row: record must still exist before rollback deletes it
            if (tpc.getExistsCheckCommand() != null) {
                for (RollbackEntry re : entries) {
                    String keyCol = findPrimaryKeyColumn(re.sheet);
                    if (keyCol == null) continue;
                    String keyTag = re.tableConfig.resolveFieldTag(keyCol);
                    Set<String> seen = new LinkedHashSet<>();
                    for (CiqRow row : re.rows) {
                        String keyValue = row.getData().get(keyCol);
                        if (keyValue != null && seen.add(keyValue)) {
                            payload.add(resolve(tpc.getExistsCheckCommand(),
                                    tableName, keyTag, keyValue, "ROLLBACK"));
                        }
                    }
                }
            }

            String activityName = prefix + "_" + tableName + "_ROLLBACK_TABLE_PRECHECK";
            String description  = "Verify " + tableName + " records exist before rollback";
            writeBlockHeader(sb, activityName, description, tpc.getTargetNode(), null);
            writeBlockAction(sb, 1, tableName + "_ROLLBACK_TABLE_PRECHECK", "CLI", payload);
        }
    }

    /**
     * Generate one ROLLBACK_TABLE_POSTCHECK block per rollback table.
     *
     * <p>After rollback configuration deletes the CREATEd records, each block:
     * <ol>
     *   <li>Downloads current table config from the NE.</li>
     *   <li>For each CREATE row: verifies the record NO LONGER EXISTS on the NE.</li>
     * </ol>
     * Uses {@link TablePostcheckConfig#getDeleteCheckCommand()}.
     */
    private void writeRollbackTablePostcheckBlocks(StringBuilder sb, String prefix,
                                                   Iterable<String> tables,
                                                   Map<String, List<RollbackEntry>> rollbackEntries) {
        TablePostcheckConfig tpc = config.getRollback().getTablePostcheck();

        for (String tableName : tables) {
            List<RollbackEntry> entries = rollbackEntries.get(tableName);
            if (entries == null) continue;

            List<String> payload = new ArrayList<>();

            // 1. Download command
            if (tpc.getDownloadCommand() != null) {
                payload.add(resolve(tpc.getDownloadCommand(), tableName, null, null, null));
            }

            // 2. For each CREATE row: record must no longer exist after rollback
            if (tpc.getDeleteCheckCommand() != null) {
                for (RollbackEntry re : entries) {
                    String keyCol = findPrimaryKeyColumn(re.sheet);
                    if (keyCol == null) continue;
                    String keyTag = re.tableConfig.resolveFieldTag(keyCol);
                    Set<String> seen = new LinkedHashSet<>();
                    for (CiqRow row : re.rows) {
                        String keyValue = row.getData().get(keyCol);
                        if (keyValue != null && seen.add(keyValue)) {
                            payload.add(resolve(tpc.getDeleteCheckCommand(),
                                    tableName, keyTag, keyValue, "ROLLBACK"));
                        }
                    }
                }
            }

            String activityName = prefix + "_" + tableName + "_ROLLBACK_TABLE_POSTCHECK";
            String description  = "Verify " + tableName + " records deleted after rollback";
            writeBlockHeader(sb, activityName, description, tpc.getTargetNode(), null);
            writeBlockAction(sb, 1, tableName + "_ROLLBACK_TABLE_POSTCHECK", "CLI", payload);
        }
    }

    /**
     * Find the first depth-2 {@code Record.SubTable.FIELD} column that has a non-null
     * value in the given row.  Returns {@code {xmlTag, value}} or {@code null} if none.
     * Used to identify the sub-record key for MODIFY/ADD and MODIFY/DEL checks.
     */
    private String[] findSubRecordKey(CiqSheet sheet, CiqRow row, TableConfig tableConfig) {
        for (String col : sheet.getColumns()) {
            if (!col.startsWith("Record.")) continue;
            String suffix = col.substring("Record.".length());
            if (!suffix.contains(".")) continue; // depth-1, skip
            String fieldValue = row.getData().get(col);
            if (fieldValue == null || fieldValue.isEmpty()) continue;
            String fieldTag = tableConfig.resolveSubFieldTag(col);
            return new String[]{fieldTag, fieldValue};
        }
        return null;
    }

    /**
     * Substitute all known placeholders in a command template string.
     * Any placeholder whose argument is null is left as-is (not substituted).
     */
    private String resolve(String template, String tableName,
                           String keyTag, String keyValue, String action,
                           String fieldTag, String fieldValue) {
        String result = template;
        result = result.replace("{tableName}",   tableName);
        result = result.replace("{storagePath}", config.getStoragePath());
        if (keyTag    != null) result = result.replace("{keyTag}",    keyTag);
        if (keyValue  != null) result = result.replace("{keyValue}",  keyValue);
        if (action    != null) result = result.replace("{action}",    action);
        if (fieldTag  != null) result = result.replace("{fieldTag}",  fieldTag);
        if (fieldValue != null) result = result.replace("{fieldValue}", fieldValue);
        return result;
    }

    /** Convenience overload — no fieldTag/fieldValue substitution. */
    private String resolve(String template, String tableName,
                           String keyTag, String keyValue, String action) {
        return resolve(template, tableName, keyTag, keyValue, action, null, null);
    }

    /**
     * Find the first depth-1 {@code Record.*} column in the sheet.
     * This column is treated as the primary key for existence checks.
     */
    private String findPrimaryKeyColumn(CiqSheet sheet) {
        for (String col : sheet.getColumns()) {
            if (!col.startsWith("Record.")) continue;
            String suffix = col.substring("Record.".length());
            if (!suffix.contains(".")) return col;   // depth-1 only
        }
        return null;
    }

    /** Group sheet rows by Node → Action (upper-cased). */
    private Map<String, Map<String, List<CiqRow>>> groupRows(CiqSheet sheet) {
        Map<String, Map<String, List<CiqRow>>> result = new LinkedHashMap<>();
        for (CiqRow row : sheet.getRows()) {
            String node   = row.getData().get("Node");
            String action = row.getData().get("Action");
            if (node == null || action == null) continue;
            result.computeIfAbsent(node, k -> new LinkedHashMap<>())
                  .computeIfAbsent(action.toUpperCase(), k -> new ArrayList<>())
                  .add(row);
        }
        return result;
    }

    /** Java 8-compatible replacement for String.repeat(). */
    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }

    // =========================================================================
    // CRGROUP-mode MOP generation
    // =========================================================================

    /**
     * Generate MOP files for all GROUPs in a CRGROUP folder, then produce one
     * CR-level approval summary covering all groups and nodes.
     *
     * <p>MOP naming depends on {@code mopGenerationMode} in the template:
     * <ul>
     *   <li>{@code GROUP} — one shared MOP per GROUP: {@code <base>_<group>_MOP.<ext>}</li>
     *   <li>{@code NODE}  — one MOP per node:         {@code <base>_<node>_MOP.<ext>}</li>
     * </ul>
     * Approval summary (when {@code mopApprovalFormatType != TEXT}):
     * {@code <base>_<crGroup>_SUMMARY.<ext>}
     *
     * @param crGroupDir    directory of the CRGROUP (e.g. {@code mop-json/CR-001/})
     * @param nodeType      e.g. "MRF"
     * @param activity      e.g. "ANNOUNCEMENT_LOADING"
     * @param crGroup       e.g. "CR-001"
     * @param outputDir     directory where MOP files and summary will be written
     * @param mopExtension  file extension for MOP files (default "mop")
     * @param mopFileNameBase  base name for generated files, e.g. "MRF_ANNOUNCEMENT_LOADING"
     */
    public void generateCRGroupMops(String crGroupDir, String nodeType, String activity,
                                     String crGroup, String outputDir, String mopExtension,
                                     String mopFileNameBase) throws IOException {

        boolean groupMode = "GROUP".equalsIgnoreCase(config.getMopGenerationMode());

        log.info("=== MOP Generator (CRGROUP mode) ===");
        log.info("CRGROUP dir: {}", crGroupDir);
        log.info("CRGROUP: {}, Node type: {}, Activity: {}, mopGenerationMode: {}",
                crGroup, nodeType, activity, groupMode ? "GROUP" : "NODE");

        String ext  = (mopExtension    != null && !mopExtension.isEmpty())    ? mopExtension    : "mop";
        String base = (mopFileNameBase != null && !mopFileNameBase.isEmpty()) ? mopFileNameBase : nodeType + "_" + activity;

        CRGroupIndex crGroupIndex = new CRGroupIndexLoader().load(crGroupDir, nodeType, activity, crGroup);

        // GROUP name → MOP file path (for approval summary)
        Map<String, String> groupMopPaths = new LinkedHashMap<>();

        for (CRGroupIndex.GroupEntry groupEntry : crGroupIndex.getGroups()) {
            String groupName = groupEntry.getGroup();
            List<String> nodes = groupEntry.getNodes();

            if (groupMode) {
                // One shared MOP for all nodes in this GROUP
                String outputPath = outputDir + "/" + base + "_" + groupName + "_MOP." + ext;
                generateForCRGroupEntry(crGroupDir, nodeType, activity, crGroup,
                        groupEntry, groupName, groupName, outputPath, nodes);
                groupMopPaths.put(groupName, outputPath);
            } else {
                // One MOP per node; for summary, use the first node's MOP path
                String firstPath = null;
                for (String nodeName : nodes) {
                    String neId = groupEntry.getNiamMapping().getOrDefault(nodeName, nodeName);
                    String outputPath = outputDir + "/" + base + "_" + nodeName + "_MOP." + ext;
                    generateForCRGroupEntry(crGroupDir, nodeType, activity, crGroup,
                            groupEntry, nodeName, neId, outputPath, null);
                    if (firstPath == null) firstPath = outputPath;
                }
                if (firstPath != null) groupMopPaths.put(groupName, firstPath);
            }
        }

        // CR-level approval summary
        GroupApprovalMopGenerator approvalGen = GroupApprovalMopGenerator.forConfig(config);
        if (approvalGen != null) {
            String approvalExt = "MSWORD".equalsIgnoreCase(config.getMopApprovalFormatType()) ? "docx" : "html";
            String summaryPath = outputDir + "/" + base + "_" + crGroup + "_SUMMARY." + approvalExt;
            try {
                approvalGen.generateForCRGroup(crGroup, crGroupIndex, groupMopPaths, summaryPath);
                log.info("CRGROUP summary written: {}", summaryPath);
            } catch (java.io.IOException e) {
                log.warn("Failed to generate CRGROUP summary for {}: {}", crGroup, e.getMessage());
            }
        }
    }

    /**
     * Generate one MOP file for a single node or a whole GROUP within a CRGROUP folder.
     * Data files use GROUP name as the postfix (written by ciq-processor).
     *
     * @param headerNodes if non-null, all these node names appear in the MOP header
     *                    (used for GROUP-mode where one MOP covers many nodes)
     */
    private void generateForCRGroupEntry(String crGroupDir, String nodeType, String activity,
                                          String crGroup, CRGroupIndex.GroupEntry groupEntry,
                                          String nodeName, String neId, String outputPath,
                                          List<String> headerNodes) throws IOException {

        log.info("  Generating for node/group {} (neId={})", nodeName, neId);

        ObjectMapper mapper = new ObjectMapper();

        // Discover tables from the data files in the CRGROUP folder
        // (same files used by all nodes in this GROUP within this CRGROUP)
        String groupName = groupEntry.getGroup();
        String prefix    = nodeType + "_" + activity;

        // Build a minimal CiqIndex for header writing
        CiqIndex headerIndex = new CiqIndex();
        headerIndex.setNodeType(nodeType);
        headerIndex.setActivity(activity);
        Map<String, String> headerNiam = new java.util.LinkedHashMap<>();
        if (headerNodes != null && !headerNodes.isEmpty()) {
            for (String n : headerNodes) {
                headerNiam.put(n, groupEntry.getNiamMapping().getOrDefault(n, n));
            }
        } else {
            headerNiam.put(nodeName, neId);
        }
        headerIndex.setNiamMapping(headerNiam);

        // Seed variable context with node identity
        variableContext.clear();
        ciqRows.clear();
        sectionMetadata.clear();
        variableContext.put("NODE", nodeName);
        variableContext.put("NEID", neId);

        // Load each table's data file from the CRGROUP folder (GROUP as postfix)
        List<String> configuredTables = config.getActivity().getConfiguration();
        // If template specifies table order, use it; otherwise scan for files
        List<String> tableNames;
        if (!configuredTables.isEmpty()) {
            tableNames = configuredTables;
        } else {
            // Discover from CRGroupIndex (tables aren't stored there — scan folder)
            tableNames = new java.util.ArrayList<>();
            File dir = new File(crGroupDir);
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    // Match: {nodeType}_{activity}_{table}_{group}.json
                    String expectedPrefix = nodeType + "_" + activity + "_";
                    String expectedSuffix = "_" + groupName + ".json";
                    if (name.startsWith(expectedPrefix) && name.endsWith(expectedSuffix)) {
                        // Extract table name
                        String middle = name.substring(expectedPrefix.length(),
                                name.length() - expectedSuffix.length());
                        if (!middle.equalsIgnoreCase("index")) {
                            tableNames.add(middle);
                        }
                    }
                }
            }
        }

        // Pre-scan all sheets to populate variable context before any blocks are written
        for (String tableName : tableNames) {
            File f = new File(crGroupDir, FileNamingUtil.sheetFileName(nodeType, activity, tableName, groupName));
            if (f.exists()) {
                try {
                    enrichVariableContext(mapper.readValue(f, CiqSheet.class));
                } catch (IOException e) {
                    log.debug("Could not pre-scan {} for variable context: {}", tableName, e.getMessage());
                }
            }
        }
        log.debug("Variable context for node {}: {}", nodeName, variableContext.keySet());

        StringBuilder sb = new StringBuilder();
        writeHeader(sb, mopNameFrom(outputPath), nodeType, activity, headerIndex);

        for (ActivityConfig ac : config.getPreNodeHealthCheck())     writeStaticActivity(sb, prefix, ac);
        for (ActivityConfig ac : config.getBackup())                 writeStaticActivity(sb, prefix, ac);
        for (ActivityConfig ac : config.getActivity().getPrecheck()) writeStaticActivity(sb, prefix, ac);

        Map<String, List<RollbackEntry>> rollbackEntries = new LinkedHashMap<>();

        for (String tableName : tableNames) {
            String sheetFile = FileNamingUtil.sheetFileName(nodeType, activity, tableName, groupName);
            File file = new File(crGroupDir, sheetFile);
            if (!file.exists()) {
                log.debug("Skipping missing sheet file: {}", sheetFile);
                continue;
            }
            CiqSheet sheet = mapper.readValue(file, CiqSheet.class);
            if (sheet.getRows().isEmpty()) continue;

            enrichVariableContext(sheet);
            TableConfig tableConfig = config.getTableConfig(tableName);
            Map<String, List<CiqRow>> byAction = groupRowsByAction(sheet);

            for (Map.Entry<String, List<CiqRow>> ae : byAction.entrySet()) {
                String action = ae.getKey();
                List<CiqRow> rows = ae.getValue();

                log.info("  {} / {} / {} — {} row(s)", tableName, nodeName, action, rows.size());

                String xml = xmlBuilder.buildXml(sheet, neId, tableName, tableConfig, rows, action);
                writeConfigurationActivity(sb, prefix, tableName, action, xml,
                        config.getActivity().getConfigurationTargetNode());

                if ("CREATE".equals(action)) {
                    rollbackEntries.computeIfAbsent(tableName, k -> new ArrayList<>())
                                   .add(new RollbackEntry(sheet, neId, tableConfig, rows));
                }
            }
        }

        for (ActivityConfig ac : config.getActivity().getExecution())  writeStaticActivity(sb, prefix, ac);
        for (ActivityConfig ac : config.getActivity().getPostcheck())   writeStaticActivity(sb, prefix, ac);
        for (ActivityConfig ac : config.getPostNodeHealthCheck())        writeStaticActivity(sb, prefix, ac);

        // ROLLBACK
        sb.append("\n").append(repeat('#', 78)).append("\n");
        sb.append("## ROLLBACK\n");
        sb.append(repeat('#', 78)).append("\n");

        List<String> rollbackOrder = config.getRollback().getConfiguration();
        Iterable<String> rollbackTables = rollbackOrder.isEmpty()
                ? rollbackEntries.keySet() : rollbackOrder;

        for (ActivityConfig ac : config.getRollback().getPrecheck()) writeStaticActivity(sb, prefix, ac);

        for (String tableName : rollbackTables) {
            List<RollbackEntry> entries = rollbackEntries.get(tableName);
            if (entries == null) continue;
            for (RollbackEntry re : entries) {
                String rollbackXml = xmlBuilder.buildRollbackXml(
                        re.sheet, re.neId, tableName, re.tableConfig, re.rows);
                writeRollbackConfiguration(sb, prefix, tableName, rollbackXml,
                        config.getRollback().getConfigurationTargetNode());
            }
        }

        for (ActivityConfig ac : config.getRollback().getExecution())  writeStaticActivity(sb, prefix, ac);
        for (ActivityConfig ac : config.getRollback().getPostcheck())   writeStaticActivity(sb, prefix, ac);

        File outFile = new File(outputPath);
        if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
        writeSectionMetadata(outputPath);
        log.info("  MOP written: {}", outputPath);
    }

    // =========================================================================
    // GROUP-mode MOP generation
    // =========================================================================

    /**
     * Generate one MOP file per node in a GROUP-mode group folder.
     *
     * <p>Each per-node MOP is identical in structure to the existing node-based MOP
     * except:
     * <ul>
     *   <li>Rows are grouped by {@code Action} only (no Node outer key).</li>
     *   <li>The NEID is taken from the group's NIAM mapping, not from row data.</li>
     * </ul>
     *
     * @param groupDir   directory that contains the GroupIndex and filtered sheet JSONs
     *                   (e.g. {@code mop-json/A/})
     * @param nodeType   e.g. "MRF"
     * @param activity   e.g. "ANNOUNCEMENT_LOADING"
     * @param group      group letter, e.g. "A"
     * @param outputDir  directory where per-node MOP files will be written
     */
    public void generateGroupMops(String groupDir, String nodeType, String activity,
                                  String group, String outputDir, String mopExtension,
                                  String mopFileNameBase) throws IOException {

        boolean groupMode = "GROUP".equalsIgnoreCase(config.getMopGenerationMode());

        log.info("=== MOP Generator (GROUP mode) ===");
        log.info("Group dir: {}", groupDir);
        log.info("Group: {}, Node type: {}, Activity: {}, mopGenerationMode: {}",
                group, nodeType, activity, groupMode ? "GROUP" : "NODE");

        String ext  = (mopExtension != null    && !mopExtension.isEmpty())    ? mopExtension    : "mop";
        String base = (mopFileNameBase != null && !mopFileNameBase.isEmpty()) ? mopFileNameBase : nodeType + "_" + activity;

        GroupIndex groupIndex = new GroupIndexLoader().load(groupDir, nodeType, activity, group);
        GroupApprovalMopGenerator approvalGen = GroupApprovalMopGenerator.forConfig(config);

        String approvalExt     = "MSWORD".equalsIgnoreCase(config.getMopApprovalFormatType()) ? "docx" : "html";
        boolean nodeSummary    = "NODE".equalsIgnoreCase(config.getMopSummaryGenerationMode());

        if (groupMode) {
            // Single shared MOP for the whole group → always one summary
            String outputPath = outputDir + "/" + base + "_" + group + "_MOP." + ext;
            generateForNode(groupDir, nodeType, activity, group, groupIndex,
                    group, group, outputPath, groupIndex.getNodes());
            generateApproval(approvalGen, outputPath, groupIndex,
                    outputDir + "/" + base + "_" + group + "_SUMMARY." + approvalExt);
        } else {
            // One MOP per node
            String firstMopPath = null;
            for (String nodeName : groupIndex.getNodes()) {
                String outputPath = outputDir + "/" + base + "_" + nodeName + "_MOP." + ext;
                generateForNode(groupDir, nodeType, activity, group, groupIndex,
                        nodeName, nodeName, outputPath, null);
                if (nodeSummary) {
                    // One summary per node
                    generateApproval(approvalGen, outputPath, singleNodeIndex(groupIndex, nodeName),
                            outputDir + "/" + base + "_" + nodeName + "_SUMMARY." + approvalExt);
                } else {
                    if (firstMopPath == null) firstMopPath = outputPath;
                }
            }
            if (!nodeSummary && firstMopPath != null) {
                // One group-level summary (uses first node's MOP content, lists all nodes)
                generateApproval(approvalGen, firstMopPath, groupIndex,
                        outputDir + "/" + base + "_" + group + "_SUMMARY." + approvalExt);
            }
        }
    }

    /** Build a GroupIndex containing only {@code nodeName} — used for per-node summaries. */
    private static GroupIndex singleNodeIndex(GroupIndex source, String nodeName) {
        GroupIndex single = new GroupIndex();
        single.setNodeType(source.getNodeType());
        single.setActivity(source.getActivity());
        single.setGroup(source.getGroup());
        single.setTables(source.getTables());
        single.setNodes(java.util.Collections.singletonList(nodeName));
        return single;
    }

    /**
     * Generate the approval summary document.
     * No-op when approvalGen is null (TEXT mode).
     */
    private void generateApproval(GroupApprovalMopGenerator approvalGen,
                                   String mopOutputPath, GroupIndex groupIndex,
                                   String approvalPath) {
        if (approvalGen == null) return;
        try {
            approvalGen.generate(mopOutputPath, groupIndex, approvalPath);
        } catch (java.io.IOException e) {
            log.warn("Failed to generate approval summary for {}: {}", mopOutputPath, e.getMessage());
        }
    }

    /**
     * Generate one MOP file for a single node in a GROUP-mode group.
     * Uses {@link #groupRowsByAction} to group rows (no Node outer key).
     */
    /**
     * @param headerNodes if non-null, all these node names appear in the MOP header
     *                    (used for shared-mode where one MOP covers many nodes)
     */
    private void generateForNode(String groupDir, String nodeType, String activity,
                                  String group, GroupIndex groupIndex,
                                  String nodeName, String neId, String outputPath,
                                  List<String> headerNodes)
            throws IOException {

        log.info("  Generating for node/group {} (neId={})", nodeName, neId);

        ObjectMapper mapper = new ObjectMapper();
        List<String> tables = groupIndex.getTables();
        String prefix = nodeType + "_" + activity;

        // Seed variable context with node identity.  Sheet-level consistent
        // columns will be added below as each sheet is loaded.
        variableContext.clear();
        ciqRows.clear();
        sectionMetadata.clear();
        variableContext.put("NODE", nodeName);
        variableContext.put("NEID", neId);

        // Pre-scan all sheets to populate variable context before any blocks are written.
        // This ensures ${KEY} references in early blocks (BACKUP, PRE_NODE_HEALTH_CHECK, etc.)
        // are resolved even when those blocks are written before the table processing loop.
        for (String tableName : tables) {
            File f = new File(groupDir, FileNamingUtil.sheetFileName(nodeType, activity, tableName, group));
            if (f.exists()) {
                try {
                    enrichVariableContext(mapper.readValue(f, CiqSheet.class));
                } catch (IOException e) {
                    log.debug("Could not pre-scan {} for variable context: {}", tableName, e.getMessage());
                }
            }
        }
        log.debug("Variable context for node {}: {}", nodeName, variableContext.keySet());

        // Build a minimal CiqIndex for header writing
        CiqIndex headerIndex = new CiqIndex();
        headerIndex.setNodeType(nodeType);
        headerIndex.setActivity(activity);
        Map<String, String> headerNiam = new java.util.LinkedHashMap<>();
        if (headerNodes != null && !headerNodes.isEmpty()) {
            for (String n : headerNodes) headerNiam.put(n, n);
        } else {
            headerNiam.put(nodeName, neId);
        }
        headerIndex.setNiamMapping(headerNiam);

        StringBuilder sb = new StringBuilder();

        writeHeader(sb, mopNameFrom(outputPath), nodeType, activity, headerIndex);

        for (ActivityConfig ac : config.getPreNodeHealthCheck())     writeStaticActivity(sb, prefix, ac);
        for (ActivityConfig ac : config.getBackup())                 writeStaticActivity(sb, prefix, ac);
        for (ActivityConfig ac : config.getActivity().getPrecheck()) writeStaticActivity(sb, prefix, ac);

        Map<String, List<RollbackEntry>> rollbackEntries = new LinkedHashMap<>();

        for (String tableName : tables) {
            String sheetFile = FileNamingUtil.sheetFileName(nodeType, activity, tableName, group);
            File file = new File(groupDir, sheetFile);
            if (!file.exists()) {
                log.debug("Skipping missing sheet file: {}", sheetFile);
                continue;
            }
            CiqSheet sheet = mapper.readValue(file, CiqSheet.class);
            if (sheet.getRows().isEmpty()) continue;

            enrichVariableContext(sheet);
            TableConfig tableConfig = config.getTableConfig(tableName);
            Map<String, List<CiqRow>> byAction = groupRowsByAction(sheet);

            for (Map.Entry<String, List<CiqRow>> ae : byAction.entrySet()) {
                String action = ae.getKey();
                List<CiqRow> rows = ae.getValue();

                log.info("  {} / {} / {} — {} row(s)", tableName, nodeName, action, rows.size());

                String xml = xmlBuilder.buildXml(sheet, neId, tableName, tableConfig, rows, action);
                writeConfigurationActivity(sb, prefix, tableName, action, xml,
                        config.getActivity().getConfigurationTargetNode());

                if ("CREATE".equals(action)) {
                    rollbackEntries.computeIfAbsent(tableName, k -> new ArrayList<>())
                                   .add(new RollbackEntry(sheet, neId, tableConfig, rows));
                }
            }
        }

        for (ActivityConfig ac : config.getActivity().getExecution())  writeStaticActivity(sb, prefix, ac);
        for (ActivityConfig ac : config.getActivity().getPostcheck())   writeStaticActivity(sb, prefix, ac);
        for (ActivityConfig ac : config.getPostNodeHealthCheck())        writeStaticActivity(sb, prefix, ac);

        // ROLLBACK
        sb.append("\n").append(repeat('#', 78)).append("\n");
        sb.append("## ROLLBACK\n");
        sb.append(repeat('#', 78)).append("\n");

        List<String> rollbackOrder = config.getRollback().getConfiguration();
        Iterable<String> rollbackTables = rollbackOrder.isEmpty()
                ? rollbackEntries.keySet() : rollbackOrder;

        for (ActivityConfig ac : config.getRollback().getPrecheck()) writeStaticActivity(sb, prefix, ac);

        for (String tableName : rollbackTables) {
            List<RollbackEntry> entries = rollbackEntries.get(tableName);
            if (entries == null) continue;
            for (RollbackEntry re : entries) {
                String rollbackXml = xmlBuilder.buildRollbackXml(
                        re.sheet, re.neId, tableName, re.tableConfig, re.rows);
                writeRollbackConfiguration(sb, prefix, tableName, rollbackXml,
                        config.getRollback().getConfigurationTargetNode());
            }
        }

        for (ActivityConfig ac : config.getRollback().getExecution())  writeStaticActivity(sb, prefix, ac);
        for (ActivityConfig ac : config.getRollback().getPostcheck())   writeStaticActivity(sb, prefix, ac);

        File outFile = new File(outputPath);
        if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
        writeSectionMetadata(outputPath);
        log.info("  MOP written: {}", outputPath);
    }

    /**
     * Group sheet rows by {@code Action} only (no Node outer key).
     * Used for GROUP-mode sheets where the {@code Node} column is absent.
     */
    private Map<String, List<CiqRow>> groupRowsByAction(CiqSheet sheet) {
        Map<String, List<CiqRow>> result = new LinkedHashMap<>();
        for (CiqRow row : sheet.getRows()) {
            String action = row.get("Action");
            if (action == null) continue;
            result.computeIfAbsent(action.toUpperCase(), k -> new ArrayList<>()).add(row);
        }
        return result;
    }

    /** Holds the data needed to generate one ROLLBACK_CONFIGURATION block. */
    private static class RollbackEntry {
        final CiqSheet sheet;
        final String neId;
        final TableConfig tableConfig;
        final List<CiqRow> rows;

        RollbackEntry(CiqSheet sheet, String neId, TableConfig tableConfig, List<CiqRow> rows) {
            this.sheet       = sheet;
            this.neId        = neId;
            this.tableConfig = tableConfig;
            this.rows        = rows;
        }
    }
}
