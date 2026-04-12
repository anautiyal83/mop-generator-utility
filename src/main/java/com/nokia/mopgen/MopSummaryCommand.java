package com.nokia.mopgen;

/**
 * One command line within a {@link MopSummarySection}.
 *
 * <p>Carries the command text plus optional human-readable metadata
 * (description and expected validation output) that is displayed in the
 * approval summary report.
 */
public class MopSummaryCommand {

    /** The command text to be executed (e.g. {@code "service swms status"}). */
    private String text;

    /**
     * Human-readable description of what this command does.
     * {@code null} when no description was specified in the MOP template.
     */
    private String description;

    /**
     * Expected output / validation criterion for this command.
     * {@code null} when no validation was specified in the MOP template.
     */
    private String validation;

    public MopSummaryCommand() {}

    public MopSummaryCommand(String text, String description, String validation) {
        this.text        = text;
        this.description = description;
        this.validation  = validation;
    }

    /** Returns {@code true} when at least one of description or validation is non-null. */
    public boolean hasMetadata() {
        return (description != null && !description.isEmpty())
                || (validation != null && !validation.isEmpty());
    }

    public String getText()        { return text; }
    public void   setText(String v) { this.text = v; }

    public String getDescription()        { return description; }
    public void   setDescription(String v) { this.description = v; }

    public String getValidation()        { return validation; }
    public void   setValidation(String v) { this.validation = v; }
}
