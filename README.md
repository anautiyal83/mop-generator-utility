# MOP Generator Utility

Generates human-readable **MOP (Method of Procedure) Approval Summary** documents from a unified
CIQ JSON file produced by `ciq-processor`.  All activity steps, commands, descriptions, and
validation instructions are driven by an external YAML template — no code changes are needed
when a new node type or activity is added.

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Data Flow](#data-flow)
4. [Running the Utility](#running-the-utility)
5. [Input Files](#input-files)
   - [Unified JSON File](#unified-json-file)
   - [JSON Output Config (`*_json-output.yaml`)](#json-output-config-_json-outputyaml)
   - [MOP Template YAML (`*_MOP-Template.yaml`)](#mop-template-yaml-_mop-templateyaml)
6. [Output Files](#output-files)
7. [MOP Template YAML Reference](#mop-template-yaml-reference)
   - [Top-Level Keys](#top-level-keys)
   - [Activity Sections Order](#activity-sections-order)
   - [Activity Block Fields](#activity-block-fields)
   - [Command Entry Formats](#command-entry-formats)
   - [Variable Substitution (`${VAR}`)](#variable-substitution-var)
   - [String Functions](#string-functions)
   - [Constants (`$KEY`)](#constants-key)
   - [Conditional Commands (`if/then/else`)](#conditional-commands-ifthenelse)
   - [Template Include / Reuse](#template-include--reuse)
8. [HTML Summary Template Reference](#html-summary-template-reference)
   - [Document-Level Tokens](#document-level-tokens)
   - [Loops and Conditionals](#loops-and-conditionals)
   - [Section Tokens](#section-tokens)
   - [Command Tokens](#command-tokens)
9. [Class Reference](#class-reference)
10. [Adding a New Node Type / Activity](#adding-a-new-node-type--activity)

---

## Overview

The MOP Generator Utility takes the CIQ data (already validated and exported to JSON by
`ciq-processor`) and produces a **per-CR-group approval document** suitable for sign-off before
execution.  The document lists every node covered, and for each node shows all activity and
rollback steps with their commands, descriptions, and expected validations.

Supported output formats:

| `mopApprovalFormatType` | Output |
|---|---|
| `HTML` | `<base>_<crGroup>_SUMMARY.html` |
| `MSWORD` | `<base>_<crGroup>_SUMMARY.docx` |
| `TEXT` (default) | No separate approval document produced |

---

## Architecture

```
MopGeneratorMain          — CLI argument parsing; delegates everything to MopGenerator
      │
      └─► MopGenerator.generateSummary()
               │
               ├─ MopConfigLoader        — loads *_MOP-Template.yaml  (+ includes)
               ├─ JsonOutputConfigLoader — loads *_json-output.yaml
               ├─ MopJsonReader          — reads unified JSON, applies scope filter
               │
               ├─ (per node) buildYamlGroupFromNodeData()
               │        enriches variable context from CIQ rows
               │        resolves constants → variables → per-row expansion
               │        builds YamlMopGroup  (activity + rollback sections)
               │
               ├─ GroupApprovalMopGenerator.toSummaryUnit()
               │        converts YamlMopGroup → MopSummaryUnit
               │
               └─ GroupApprovalMopGenerator.forConfig()  (factory)
                        HtmlGroupApprovalMopGenerator  — template-driven HTML
                        DocxGroupApprovalMopGenerator  — MS Word DOCX
```

**Key design principle:** all HTML lives in `mop-summary-template.html`.
`HtmlGroupApprovalMopGenerator` only drives token substitution and loop expansion — it produces
no HTML markup of its own.

---

## Data Flow

```
CIQ Excel
   │
   └─► ciq-processor ──► unified JSON file  ◄─── json-output.yaml  (structure descriptor)
                                │
                                ▼
                       MopGenerator.generateSummary()
                                │
                                ├── MOP-Template.yaml   (commands, sections, variables)
                                │
                                ▼
                       MopSummaryDocument
                        └─ MopSummaryUnit  (one per node)
                             ├─ activity sections
                             └─ rollback sections
                                │
                                ▼
                       HtmlGroupApprovalMopGenerator
                        └─ mop-summary-template.html  +  token expansion
                                │
                                ▼
                       <base>_<crGroup>_SUMMARY.html
```

---

## Running the Utility

```
java -jar mop-generator-utility.jar \
  --json-file               <file>   \
  --json-output-config-file <file>   \
  --node-type               <type>   \
  --activity                <name>   \
  --output-dir              <dir>    \
  [--json-dir               <dir>]   \
  [--mop-template           <file>]  \
  [--mop-summary-template   <file>]  \
  [--mop-summary-file-name  <name>]  \
  [--crgroup                <id>]
```

### Required arguments

| Argument | Description |
|---|---|
| `--json-file` | JSON file name produced by `ciq-processor` |
| `--json-output-config-file` | Matching `*_json-output.yaml` config file |
| `--node-type` | Node type identifier, e.g. `MRF` |
| `--activity` | Activity identifier, e.g. `ANNOUNCEMENT_LOADING` |
| `--output-dir` | Directory where the summary file will be written |

### Optional arguments

| Argument | Description |
|---|---|
| `--json-dir` | Directory containing the JSON file; combined with `--json-file` to form the full path |
| `--mop-template` | Path to the MOP Template YAML; uses built-in defaults if omitted |
| `--mop-summary-template` | Path to an external HTML summary template; overrides the bundled `mop-summary-template.html` |
| `--mop-summary-file-name` | Base name for the output file; defaults to `<nodeType>_<activity>` |
| `--crgroup` | CR group scope filter; omit to include all nodes |

### Example

```bash
java -jar mop-generator-utility.jar \
  --json-dir                /output/mrf-mop-json          \
  --json-file               MRF_ANNOUNCEMENT_LOADING.json \
  --json-output-config-file MRF_ANNOUNCEMENT_LOADING_json-output.yaml \
  --node-type               MRF                           \
  --activity                ANNOUNCEMENT_LOADING          \
  --mop-template            MRF_ANNOUNCEMENT_LOADING_MOP-Template.yaml \
  --output-dir              /output/mop-approval          \
  --crgroup                 CR-001
```

Output: `/output/mop-approval/MRF_ANNOUNCEMENT_LOADING_CR-001_SUMMARY.html`

---

## Input Files

### Unified JSON File

Produced by `ciq-processor`.  At minimum it must have a `nodes` array where each entry contains
a `nodeInfo` object (node identifiers) and a `configData` array (CIQ data rows).

```json
{
  "nodeType": "MRF",
  "activity": "ANNOUNCEMENT_LOADING",
  "nodes": [
    {
      "node":    "MRF1",
      "crGroup": "CR-001",
      "niamID":  "mrf1-neid",
      "configData": [
        {
          "INPUT_FILE":           "announcements_v17.tar",
          "MRF_DESTINATION_PATH": "/var/opt/swms/clips/persistent/provisioned/audioclips/RJ/PREPAID"
        }
      ]
    }
  ]
}
```

The exact JSON structure is described by the `*_json-output.yaml` file.

---

### JSON Output Config (`*_json-output.yaml`)

Describes how the unified JSON is structured so `MopJsonReader` can extract `nodeInfo` and
`configData` for each node.  The `_each` directive marks array fields.

```yaml
# MRF_ANNOUNCEMENT_LOADING_json-output.yaml
output_mode: single
data:
  nodeType: MRF
  activity: ANNOUNCEMENT_LOADING
  nodes:
    _each: "DISTINCT Index.Node AS $node"
    node:    $node
    crGroup: Index.CRGroup
    niamID:  "Node_Details.'NIAM NAME' WHERE Node_Details.Node_Name = $node"
    configData:
      _each: "ANNOUNCEMENT_FILES WHERE GROUP = Index.GROUP"
      INPUT_FILE:           INPUT_FILE
      MRF_DESTINATION_PATH: MRF_DESTINATION_PATH
```

The fields declared directly under the `_each` node block become the `nodeInfo` map
(available as `${FIELD}` variables in commands).  Nested `_each` blocks become `configData`
rows (used for per-row `${VAR}` expansion in commands).

---

### MOP Template YAML (`*_MOP-Template.yaml`)

Naming convention: `{NODE_TYPE}_{ACTIVITY}_MOP-Template.yaml`

This file defines every MOP section, command, description, and validation.
It is the only file that needs to change when the procedure changes.

Full example: see [`MRF_ANNOUNCEMENT_LOADING_MOP-Template.yaml`](src/main/resources/MRF_ANNOUNCEMENT_LOADING_MOP-Template.yaml)

---

## Output Files

| File | When produced |
|---|---|
| `<base>[_<crGroup>]_SUMMARY.html` | `mopApprovalFormatType: HTML` |
| `<base>[_<crGroup>]_SUMMARY.docx` | `mopApprovalFormatType: MSWORD` |

`<base>` is derived from `--mop-summary-file-name` or defaults to `<nodeType>_<activity>`.

---

## MOP Template YAML Reference

### Top-Level Keys

| Key | Type | Description |
|---|---|---|
| `mopApprovalFormatType` | `TEXT` \| `HTML` \| `MSWORD` | Output format for the approval document (default: `TEXT`) |
| `jsonMapping` | map | Maps JSON field names to MOP variable names (see below) |
| `constants` | map | Named string constants substituted with `$KEY` in commands |
| `preNodeHealthCheck` | list of activity blocks | Section 1 — pre-activity node health checks |
| `backup` | list of activity blocks | Section 2 — backup steps before the activity |
| `activity.precheck` | list of activity blocks | Section 3a — activity pre-checks |
| `activity.execution` | list of activity blocks | Section 3b — main activity steps |
| `activity.postcheck` | list of activity blocks | Section 3c — activity post-checks |
| `postNodeHealthCheck` | list of activity blocks | Section 4 — post-activity node health checks |
| `rollback.precheck` | list of activity blocks | Section 5a — rollback pre-checks |
| `rollback.execution` | list of activity blocks | Section 5b — rollback execution steps |
| `rollback.postcheck` | list of activity blocks | Section 5c — rollback post-checks |
| `includes` | list of paths | Include other YAML files as base configuration (see [Template Include](#template-include--reuse)) |

#### `jsonMapping` keys

| Key | Default | Description |
|---|---|---|
| `nodeNameKey` | `node` | JSON field name for the node identifier; bound to `${NODE}` |
| `neIdKey` | `niamID` | JSON field name for the NEID/NIAM name; bound to `${NEID}` |

---

### Activity Sections Order

The approval document renders sections in this fixed order:

```
1. preNodeHealthCheck   (one section per block)
2. backup               (one section per block)
3. activity.precheck    (one section per block)
4. activity.execution   (one section per block)
5. activity.postcheck   (one section per block)
6. postNodeHealthCheck  (one section per block)
── rollback divider ──
7. rollback.precheck    (one section per block)
8. rollback.execution   (one section per block)
9. rollback.postcheck   (one section per block)
```

Any section with zero activity blocks is omitted from the output.

---

### Activity Block Fields

```yaml
- name: "SECTION_NAME"           # required — displayed as the section heading
  description: "..."             # optional — displayed as italic sub-heading
  targetNode:  "MRF_NODE"        # optional — displayed as "Target: MRF_NODE"
  method:      CLI               # optional — displayed as a badge (CLI, NETCONF, etc.)
  commands:                      # list of command entries (see formats below)
    - ...
```

---

### Command Entry Formats

#### Plain string

```yaml
commands:
  - "show alarms active"
  - "# This is a comment line"   # lines starting with # are styled differently
```

#### Rich command (with description and validation)

```yaml
commands:
  - cmd:         "service swms status"
    description: "Verify SWMS services are running"
    validation:  "Output must show 'active (running)'"
```

When any command in a section has `description` or `validation`, the entire section is rendered
as a table with columns `#`, `Command`, `Description`, `Validation`.  Otherwise a plain
`<pre>` code block is used.

#### Conditional command (`if/then/else`)

```yaml
commands:
  - if:   "${VERSION} == 13.X.X.X"
    then:
      - cmd: "cp -r /tmp/${INPUT_FILE | stripExt}/* ${DEST}"
        description: "Copy individual files"
        validation:  "No errors"
    else:
      - cmd: "tar -xf /tmp/${INPUT_FILE} -C ${DEST}"
        description: "Untar to destination"
        validation:  "No errors"
```

The condition is evaluated at generation time.  Only the matching branch is included in the
output.  Nesting is supported.

---

### Variable Substitution (`${VAR}`)

Variables are referenced in commands, descriptions, and validations using `${VAR}`.

**Sources (resolved in this order):**

1. **`NODE`** — the node name (from `nodeNameKey` field in the JSON)
2. **`NEID`** — the NEID / NIAM name (from `neIdKey` field in the JSON)
3. **`nodeInfo` fields** — all scalar fields from the node's JSON object
4. **Single-value CIQ columns** — columns where every row has the same value are promoted to
   variables (e.g. `${VERSION}` when all rows share the same version)
5. **Per-row CIQ columns** — columns that vary across rows cause the command to be expanded
   once per row (e.g. `${INPUT_FILE}` produces one command line per file)

If a variable is not found, the `${VAR}` placeholder is left as-is in the output.

---

### String Functions

Functions are applied to variable values using the pipe syntax: `${VAR | function}`.

| Function | Syntax | Description | Example |
|---|---|---|---|
| `stripExt` | `${FILE \| stripExt}` | Removes the file extension | `file.tar` → `file` |
| `basename` | `${PATH \| basename}` | Returns the filename portion of a path | `/tmp/file.tar` → `file.tar` |
| `dirname` | `${PATH \| dirname}` | Returns the directory portion of a path | `/tmp/file.tar` → `/tmp` |
| `upper` | `${VAR \| upper}` | Converts to upper case | `mrf1` → `MRF1` |
| `lower` | `${VAR \| lower}` | Converts to lower case | `MRF1` → `mrf1` |
| `replace` | `${VAR \| replace:old:new}` | Replaces all occurrences of `old` with `new` | `a.b` → `a_b` with `replace:.:_` |

---

### Constants (`$KEY`)

Named constants are defined at the top level and substituted before variable resolution.
Reference them in commands using `$KEY` (no braces, no pipe functions).

```yaml
constants:
  ANN_DIR: "/var/opt/swms/clips/persistent/provisioned/audioclips/RJ/PREPAID"
  ANN_FILE: "announcements_v17.tar"

activity:
  execution:
    - name: "LOAD"
      commands:
        - "ls -la $ANN_DIR"            # → "ls -la /var/opt/swms/clips/..."
        - "cp /tmp/$ANN_FILE $ANN_DIR" # constant + variable mix
```

Longer constant names are substituted before shorter ones to avoid partial replacement.

---

### Conditional Commands (`if/then/else`)

Conditions support three forms:

| Form | Behaviour |
|---|---|
| `${VAR} == value` | True when VAR equals `value` exactly |
| `${VAR} == valueXrest` | True when VAR matches the pattern (`X` acts as wildcard `.*`) |
| `${VAR} != value` | Inverse of the above |
| `${VAR}` (bare) | True when VAR is non-empty |

Pattern matching: `X` in the right-hand side is treated as `.*` (matches any sequence of
characters).  All other regex special characters are escaped, so they match literally.

```yaml
- if:   "${VERSION} == 13.X.X.X"   # matches "13.0.0.1", "13.99.22.3", etc.
  then:
    - "command for v13"
  else:
    - "command for other versions"
```

---

### Template Include / Reuse

A template can inherit from one or more base files using the `includes` key.  The included
files are merged first; keys in the current file override the merged result.

```yaml
# MRF_ANNOUNCEMENT_LOADING_MOP-Template.yaml
includes:
  - "MRF_common_health_checks.yaml"   # defines preNodeHealthCheck, postNodeHealthCheck

activity:
  execution:
    - name: "LOAD_ANNOUNCEMENT_FILES"
      ...
```

Paths are relative to the directory of the file declaring the `includes`.
Circular includes are not detected — avoid them.

---

## HTML Summary Template Reference

The bundled template is `src/main/resources/mop-summary-template.html`.
An external template can be provided via `--mop-summary-template`.

The template engine uses the same `{{...}}` syntax as the CIQ validation-report template.

### Token syntax

| Syntax | Meaning |
|---|---|
| `{{token}}` | Scalar value substitution |
| `{{#section}}...{{/section}}` | Loop — inner block repeated per item |
| `{{#if_condition}}...{{/if_condition}}` | Conditional — inner block kept or removed |

---

### Document-Level Tokens

These are replaced once across the entire document.

| Token | Value |
|---|---|
| `{{TITLE}}` | Computed page title (e.g. `Approval MOP: MRF_ANNOUNCEMENT_LOADING — CR-001`) |
| `{{nodeType}}` | Node type, e.g. `MRF` |
| `{{activity}}` | Activity name, e.g. `ANNOUNCEMENT_LOADING` |
| `{{crGroup}}` | CR group value, e.g. `CR-001` (blank if no scope filter) |
| `{{group}}` | Group value when using GROUP-based CIQ mode |
| `{{generated}}` | Generation timestamp, e.g. `2026-04-12 14:30` |

---

### Loops and Conditionals

#### `{{#nodeChips}}` — repeats per node (overview section)

```html
{{#nodeChips}}
  <span class="node-chip">{{nodeInfo.node}}</span>
{{/nodeChips}}
```

Any `{{nodeInfo.<key>}}` token is available inside this loop.

---

#### `{{#nodes}}` — repeats per node (full detail block)

```html
{{#nodes}}
  {{nodeInfo.node}}       — node name
  {{nodeInfo.niamID}}     — NEID / NIAM name
  {{nodeInfo.<anyKey>}}   — any field from nodeInfo
  {{NODE_ID}}             — HTML-safe id for anchor links (special characters replaced with _)

  {{#nodeExtraInfo}}      — repeats per extra nodeInfo field (excludes nodeNameKey and neIdKey)
    {{extraInfo.key}}
    {{extraInfo.value}}
  {{/nodeExtraInfo}}

  {{#if_hasActivitySections}}  — present only when the node has activity sections
    ...
  {{/if_hasActivitySections}}

  {{#activitySections}}   — repeats per activity section
    ...section tokens...
  {{/activitySections}}

  {{#if_hasRollbackSections}}  — present only when the node has rollback sections
    ...
  {{/if_hasRollbackSections}}

  {{#rollbackSections}}   — repeats per rollback section (same tokens as activitySections)
    ...section tokens...
  {{/rollbackSections}}
{{/nodes}}
```

---

### Section Tokens

Available inside `{{#activitySections}}` and `{{#rollbackSections}}`.

| Token | Value |
|---|---|
| `{{section.number}}` | Sequential section number (1, 2, 3, …) |
| `{{section.name}}` | Section name from the YAML template |
| `{{section.description}}` | Description text |
| `{{section.targetNode}}` | Target node label |
| `{{section.method}}` | Method badge label (e.g. `CLI`) |
| `{{section.typeMarker}}` | Raw type marker (e.g. `CREATE`, `DELETE`) |
| `{{section.typeLabel}}` | Display label (e.g. `CREATE`, `ROLLBACK`) |
| `{{section.typeBadgeClass}}` | CSS class for the badge (`badge-create`, `badge-delete`, `badge-modify`, `badge-rollback`) |

#### Section conditionals

| Conditional | Shown when |
|---|---|
| `{{#if_sectionDescription}}` | `section.description` is non-empty |
| `{{#if_sectionTargetNode}}` | `section.targetNode` is non-empty |
| `{{#if_sectionMethod}}` | `section.method` is non-empty |
| `{{#if_sectionTypeLabel}}` | `section.typeLabel` is non-empty |

#### Command rendering (mutually exclusive)

| Block | When used |
|---|---|
| `{{#commandsTable}}` | At least one command in the section has a `description` or `validation` |
| `{{#commandsBlock}}` | All commands are plain text (rendered as a `<pre>` block) |

Only one of these two blocks is present in the output for each section.

---

### Command Tokens

Available inside `{{#commandRow}}` (within `{{#commandsTable}}`) and
`{{#commandLine}}` (within `{{#commandsBlock}}`).

| Token | Value |
|---|---|
| `{{cmd.number}}` | Sequential command number within the section |
| `{{cmd.text}}` | The command text (HTML-escaped) |
| `{{cmd.description}}` | Command description (empty string if not set) |
| `{{cmd.validation}}` | Expected validation output (empty string if not set) |
| `{{cmd.commentClass}}` | `cmd-comment` when the command starts with `#`; empty otherwise |

---

## Class Reference

| Class | Role |
|---|---|
| `MopGeneratorMain` | CLI entry point; parses arguments and calls `MopGenerator.generateSummary()` |
| `MopGenerator` | Orchestrates the full pipeline: loads config, reads JSON, builds summary document, invokes approval generator |
| `MopConfigLoader` | Loads `*_MOP-Template.yaml` with deep-merge `includes` support |
| `MopConfig` | Root model for the YAML template (approval format, JSON mapping, constants, sections) |
| `ActivitySectionConfig` | Groups precheck / execution / postcheck lists for the `activity` section |
| `RollbackConfig` | Groups precheck / execution / postcheck lists for the `rollback` section |
| `ActivityConfig` | One named activity block (name, description, targetNode, method, commands) |
| `CommandEntry` | One command — plain string, rich (with description/validation), or conditional (if/then/else) |
| `JsonOutputConfigLoader` | Loads `*_json-output.yaml` into a raw map for `MopJsonReader` |
| `MopJsonReader` | Reads the unified JSON file; applies scope filter; returns a `List<NodeData>` |
| `NodeData` | Holds one node's data: `nodeInfo` (scalar fields) and `allTables` (CIQ row lists) |
| `GroupApprovalMopGenerator` | Interface for generating approval documents; factory method `forConfig()` returns the right implementation |
| `HtmlGroupApprovalMopGenerator` | Template-driven HTML approval summary; handles token substitution and loop/conditional expansion |
| `DocxGroupApprovalMopGenerator` | MS Word DOCX approval summary |
| `MopSummaryDocument` | Root model for the approval document: metadata map + list of `MopSummaryUnit` |
| `MopSummaryUnit` | One node in the document: nodeInfo map + activity sections + rollback sections |
| `MopSummarySection` | One MOP block: name, description, targetNode, method, typeMarker, rollback flag, commands |
| `MopSummaryCommand` | One command line with optional description and validation |
| `YamlMopGroup` | Intermediate model: node name, NIAM mapping, ordered activity and rollback section lists |
| `YamlMopSection` | Intermediate model: one section with name, method, typeMarker, and command list |
| `YamlMopCommand` | Intermediate model: one command with text, description, validation |

---

## Adding a New Node Type / Activity

No Java code needs to change.  Create two YAML files:

### 1. `{NODE_TYPE}_{ACTIVITY}_MOP-Template.yaml`

Define `mopApprovalFormatType`, `jsonMapping`, `constants`, and all activity/rollback sections.
See [MOP Template YAML Reference](#mop-template-yaml-reference) for all available keys.

Use `includes` to share common sections (e.g. standard health check blocks) across templates:

```yaml
includes:
  - "COMMON_health_checks.yaml"

mopApprovalFormatType: HTML

activity:
  execution:
    - name: "MY_ACTIVITY"
      description: "Description of what this step does"
      targetNode: "TARGET_NODE"
      method: CLI
      commands:
        - cmd: "some-command ${VARIABLE}"
          description: "What this command does"
          validation: "Expected output"
```

### 2. `{NODE_TYPE}_{ACTIVITY}_json-output.yaml`

Describe the structure of the unified JSON file produced by `ciq-processor` so
`MopJsonReader` can extract node info and CIQ rows.

```yaml
output_mode: single
data:
  nodeType: MY_NODE_TYPE
  activity: MY_ACTIVITY
  nodes:
    _each: "DISTINCT Index.Node AS $node"
    node:    $node
    niamID:  "Node_Details.'NIAM NAME' WHERE Node_Details.Node_Name = $node"
    configData:
      _each: "MY_SHEET WHERE CRGroup = Index.CRGroup"
      FIELD1: FIELD1
      FIELD2: FIELD2
```

### 3. Run

```bash
java -jar mop-generator-utility.jar \
  --json-file               MY_NODE_TYPE_MY_ACTIVITY.json                  \
  --json-output-config-file MY_NODE_TYPE_MY_ACTIVITY_json-output.yaml      \
  --node-type               MY_NODE_TYPE                                    \
  --activity                MY_ACTIVITY                                     \
  --mop-template            MY_NODE_TYPE_MY_ACTIVITY_MOP-Template.yaml     \
  --output-dir              /output/mop-approval                           \
  --crgroup                 CR-001
```
