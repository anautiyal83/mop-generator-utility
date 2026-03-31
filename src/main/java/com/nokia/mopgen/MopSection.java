package com.nokia.mopgen;

import java.util.ArrayList;
import java.util.List;

/**
 * One {@code ##ACTIVITY_} block parsed from a generated MOP file.
 */
class MopSection {

    String name;
    String description;
    String targetNode;
    String typeMarker;    // CREATE, CREATE_ROLLBACK, etc. — null for static/check blocks
    String method;        // CLI / NETCONF
    List<CommandLine> commands = new ArrayList<>();
    boolean rollback;

    /** Returns true if any command in this section has description or validation metadata. */
    boolean hasCommandMetadata() {
        for (CommandLine c : commands) {
            if (c.description != null || c.validation != null) return true;
        }
        return false;
    }

    /** Human-readable label derived from the type marker. */
    String typeLabel() {
        if (typeMarker == null) return "STATIC";
        return "$" + typeMarker;
    }

    // -------------------------------------------------------------------------

    /**
     * A single command line parsed from the MOP payload, optionally annotated
     * with description and validation criteria written by the generator as
     * {@code ## cmd-desc:} / {@code ## cmd-validate:} comment lines.
     */
    static class CommandLine {
        final String text;
        final String description;   // from ## cmd-desc:  (may be null)
        final String validation;    // from ## cmd-validate:  (may be null)

        CommandLine(String text, String description, String validation) {
            this.text        = text;
            this.description = description;
            this.validation  = validation;
        }

        CommandLine(String text) {
            this(text, null, null);
        }
    }
}
