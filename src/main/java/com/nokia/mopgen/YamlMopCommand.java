package com.nokia.mopgen;

/**
 * One command line in a {@link YamlMopSection}.
 *
 * <p>In the generated YAML:
 * <ul>
 *   <li>Commands without metadata are written as a plain YAML scalar:
 *       {@code - "cp /tmp/file.tar /export/ann/"}</li>
 *   <li>Commands with description or validation are written as a YAML map:
 *       {@code - cmd: "cp /tmp/file.tar /export/ann/"}<br>
 *       {@code   description: "Copy announcement archive"}<br>
 *       {@code   validation: "No error output; exit code 0"}</li>
 * </ul>
 */
public class YamlMopCommand {

    /** The resolved command text (constants and variables already substituted). */
    public String text;

    /**
     * Human-readable description of what this command does.
     * {@code null} when not configured in the YAML template.
     */
    public String description;

    /**
     * Expected outcome / validation criteria for the command output.
     * {@code null} when not configured in the YAML template.
     */
    public String validation;

    public YamlMopCommand(String text, String description, String validation) {
        this.text        = text;
        this.description = description;
        this.validation  = validation;
    }

    public YamlMopCommand(String text) {
        this(text, null, null);
    }

    /** Returns true if either description or validation is set. */
    public boolean hasMetadata() {
        return description != null || validation != null;
    }
}
