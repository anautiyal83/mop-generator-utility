package com.nokia.mopgen;

import java.util.ArrayList;
import java.util.List;

/**
 * A single entry in a command list — either a plain shell command string or a
 * conditional block ({@code if}/{@code then}/{@code else}).
 *
 * <p>YAML examples:
 * <pre>
 * # Plain command
 * commands:
 *   - "ls -la $ANN_DIR"
 *
 * # Conditional block
 * commands:
 *   - if: "${VERSION} == 13"
 *     then:
 *       - "cp /tmp/$ANN_FILE $ANN_DIR"
 *       - "chmod 777 $ANN_DIR/$ANN_FILE"
 *     else:
 *       - "tar -xf /tmp/$ANN_FILE -C $ANN_DIR"
 *
 * # Conditionals can be nested
 * commands:
 *   - if: "${ACTION} == CREATE"
 *     then:
 *       - if: "${VERSION} == 13"
 *         then:
 *           - "cp /tmp/$ANN_FILE $ANN_DIR"
 *         else:
 *           - "tar -xf /tmp/$ANN_FILE -C $ANN_DIR"
 *     else:
 *       - "rm -f $ANN_DIR/$ANN_FILE"
 * </pre>
 *
 * <p>Supported condition operators (evaluated after constant + variable resolution):
 * <ul>
 *   <li>{@code LHS == RHS} — string equality</li>
 *   <li>{@code LHS != RHS} — string inequality</li>
 *   <li>{@code LHS} alone — truthy if non-empty</li>
 * </ul>
 */
public class CommandEntry {

    private final String text;                      // non-null for plain/rich commands
    private final String description;               // optional — shown in approval summary
    private final String validation;                // optional — shown in approval summary
    private final String condition;                 // non-null for conditional blocks
    private final List<CommandEntry> thenEntries;   // branch when condition is true
    private final List<CommandEntry> elseEntries;   // branch when condition is false (may be empty)

    private CommandEntry(String text, String description, String validation,
                         String condition,
                         List<CommandEntry> thenEntries, List<CommandEntry> elseEntries) {
        this.text        = text;
        this.description = description;
        this.validation  = validation;
        this.condition   = condition;
        this.thenEntries = thenEntries != null ? thenEntries : new ArrayList<>();
        this.elseEntries = elseEntries != null ? elseEntries : new ArrayList<>();
    }

    /** Create a plain command entry (no description or validation). */
    public static CommandEntry plain(String text) {
        return new CommandEntry(text, null, null, null, null, null);
    }

    /**
     * Create a rich command entry with optional description and validation criteria.
     *
     * <pre>
     * commands:
     *   - cmd: "service swms status"
     *     description: "Verify SWMS service is running"
     *     validation: "Output must show 'active (running)'"
     * </pre>
     */
    public static CommandEntry rich(String text, String description, String validation) {
        return new CommandEntry(text, description, validation, null, null, null);
    }

    /** Create a conditional command entry. */
    public static CommandEntry conditional(String condition,
                                           List<CommandEntry> thenEntries,
                                           List<CommandEntry> elseEntries) {
        return new CommandEntry(null, null, null, condition, thenEntries, elseEntries);
    }

    public boolean isConditional() { return condition != null; }
    public boolean isPlain()       { return text != null; }
    public boolean hasMetadata()   { return description != null || validation != null; }

    public String getText()                      { return text; }
    public String getDescription()               { return description; }
    public String getValidation()                { return validation; }
    public String getCondition()                 { return condition; }
    public List<CommandEntry> getThenEntries()   { return thenEntries; }
    public List<CommandEntry> getElseEntries()   { return elseEntries; }
}
