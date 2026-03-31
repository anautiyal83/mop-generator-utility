# MOP Generator Utility

Generates Nokia SOI XML MOP (Method of Procedure) files from JSON data produced by **ciq-processor**. Driven entirely by a YAML template — zero code changes required to support a new node type or activity.

---

## Overview

```
ciq-processor JSON  ──►  MopGeneratorMain (CLI)
                               │
                     ┌─────────┼─────────┐
                  CRGROUP     GROUP      NODE
                     │          │          │
                     ▼          ▼          ▼
               MOP per GROUP  MOP per    Single MOP
               + CR summary   node       for host
```

---

## MOP Structure

Every generated MOP follows the same section order:

```
##########...
## MOP         : <name>
## Description : <nodeType> <activity> configuration
## Generated   : <date>
## Node type   : <nodeType>
## Nodes       : <node> -> <neid>
##########...

## Activity
  ##PRE_NODE_HEALTH_CHECK
  ##BACKUP
  ##ACTIVITY_PRECHECK
  ##<TABLE>_CREATE_ACTIVITY_CONFIGURATION  (auto-generated from CIQ, one per table/action)
  ...
  ##ACTIVITY_POSTCHECK
  ##POST_NODE_HEALTH_CHECK

## ROLLBACK
  ##ROLLBACK_PRECHECK
  ##<TABLE>_ROLLBACK_CONFIGURATION         (auto-generated: DELETE XML for CREATE rows)
  ...
  ##ROLLBACK_POSTCHECK
```

Block names use the short form `##BLOCK_NAME` (no activity prefix).

---

## CLI Usage

The scope parameter must match `groupByColumnName` from the ciq-processor validation-rules YAML:

| ciq-processor `groupByColumnName` | mop-generator parameter |
|---|---|
| `CRGROUP` | `--crgroup <id>` |
| `GROUP` | `--group <name>` |
| `NODE` | `--host <node>` |

If there is a mismatch, the tool prints a detailed error showing the detected mode and the correct parameter to use.

### CRGROUP mode
```
java -jar mop-generator-utility.jar \
  --json-dir      <mop-json>                                \
  --node-type     MRF                                       \
  --activity      ANNOUNCEMENT_LOADING                      \
  --crgroup       CR-001                                    \
  --template      MRF_ANNOUNCEMENT_LOADING_MOP-Template.yaml \
  --output-dir    /output/mop                               \
  --mop-file-name MRF_ANNOUNCEMENT_LOADING
```
Reads `mop-json/CR-001/`. Generates one MOP per GROUP (or per node, based on `mopGenerationMode` in template) plus one CR-level approval summary.

Output files:
- `MRF_ANNOUNCEMENT_LOADING_A_MOP.mop`
- `MRF_ANNOUNCEMENT_LOADING_B_MOP.mop`
- `MRF_ANNOUNCEMENT_LOADING_CR-001_SUMMARY.html`

### GROUP mode
```
java -jar mop-generator-utility.jar \
  --json-dir      <mop-json>                                \
  --node-type     MRF                                       \
  --activity      ANNOUNCEMENT_LOADING                      \
  --group         A                                         \
  --template      MRF_ANNOUNCEMENT_LOADING_MOP-Template.yaml \
  --output-dir    /output/mop                               \
  --mop-file-name MRF_ANNOUNCEMENT_LOADING
```
Reads `mop-json/A/`. Generates MOPs for all nodes in the group plus a group-level approval summary.

Output files:
- `MRF_ANNOUNCEMENT_LOADING_MRF1_MOP.mop`
- `MRF_ANNOUNCEMENT_LOADING_MRF2_MOP.mop`
- `MRF_ANNOUNCEMENT_LOADING_A_SUMMARY.html`

### NODE mode
```
java -jar mop-generator-utility.jar \
  --json-dir      <mop-json>                                  \
  --node-type     SBC                                         \
  --activity      FIXED_LINE_CONFIGURATION                    \
  --host          SBC-1                                       \
  --template      SBC_FIXED_LINE_CONFIGURATION_MOP-Template.yaml \
  --output-dir    /output/mop
```
Reads `mop-json/SBC-1/`. Generates a single MOP for that node.

Output file:
- `SBC_FIXED_LINE_CONFIGURATION_SBC-1_MOP.mop`

### All options

```
Required (all modes):
  --json-dir      <dir>    JSON input directory from ciq-processor
  --node-type     <type>   Node type, e.g. SBC or MRF
  --activity      <name>   Activity name
  --output-dir    <dir>    Directory where MOP files will be written

Scope (exactly one required — must match ciq-processor groupByColumnName):
  --crgroup <id>           CRGROUP mode  (groupByColumnName: CRGROUP)
  --group   <name>         GROUP mode    (groupByColumnName: GROUP)
  --host    <node>         NODE mode     (groupByColumnName: NODE)

Optional:
  --template      <file>   MOP template YAML file
  --mop-file-name <name>   Output file base name (without extension)
  --mop-extension <ext>    File extension (default: mop)
  --child-order   <order>  Alias for --host (legacy)
```

Exit code: `0` = success, `1` = error.

---

## MOP Template YAML

File naming convention: `{NODE_TYPE}_{ACTIVITY}_MOP-Template.yaml`

### Full template structure

```yaml
# XML builder (for CIQ-driven XML activities; omit for static-only MOPs)
xmlBuilder: "netconf-soi"
defaultNamespace: "http://nokia.com/yang/..."
netconfNamespace: "http://nokia.com/yang/..."
configAttributes:
  ne-version: "R24.7"
  ne-type:    "SBC-signaling"

# Shell command templates
commands:
  stageFile:   "cat << 'XMLEOF' > {storageFile}"
  heredocEnd:  "XMLEOF"
  applyConfig: "netconfprov --onerror abort {storageFile}"

storagePath: "/storage"

# CRGROUP/GROUP mode settings
mopGenerationMode: GROUP        # GROUP = one MOP per group, NODE = one MOP per node
mopApprovalFormatType: HTML     # HTML | MSWORD | TEXT
mopSummaryGenerationMode: GROUP # GROUP = one summary per group, NODE = one per node

# Include base templates (merged before this file; keys here override included)
includes:
  - "SBC_common_blocks.yaml"

# Section 1 — PRE_NODE_HEALTH_CHECK
preNodeHealthCheck:
  - name: "PRE_NODE_HEALTH_CHECK"
    description: "Verify node health before activity"
    targetNode: "OAM_NODE"
    method: CLI
    commands:
      - "cm_adm -check"
      - "sbc_health"

# Section 2 — BACKUP
backup:
  - name: "BACKUP"
    description: "Back up current configuration"
    targetNode: "OAM_NODE"
    method: CLI
    commands:
      - "netconfprov --readall > /storage/backup.xml"

# Section 3 — ACTIVITY
activity:
  precheck:
    - name: "ACTIVITY_PRECHECK"
      description: "Capture pre-activity state"
      targetNode: "OAM_NODE"
      method: CLI
      commands:
        - "show status"

  # Tables to process from CIQ (in order). Omit to use all tables from the index.
  configuration:
    - MyTableA
    - MyTableB

  # Static execution blocks (non-CIQ, e.g. file transfers, CLI commands)
  execution:
    - name: "LOAD_FILES"
      description: "Transfer and load files"
      targetNode: "NE_NODE"
      method: CLI
      commands:
        - "cp /tmp/file /dest/file"

  postcheck:
    - name: "ACTIVITY_POSTCHECK"
      description: "Verify post-activity state"
      targetNode: "OAM_NODE"
      method: CLI
      commands:
        - "show status"

  # Auto-generated table precheck / postcheck
  tablePrecheck:
    enabled: true
    targetNode: "OAM_NODE"
    downloadCommand:    "netconfprov --get {tableName}"
    createCheckCommand: "grep -c '{keyTag}={keyValue}' /storage/check.xml; [ $? -eq 1 ]"
    existsCheckCommand: "grep -c '{keyTag}={keyValue}' /storage/check.xml; [ $? -eq 0 ]"

  tablePostcheck:
    enabled: true
    targetNode: "OAM_NODE"
    downloadCommand:    "netconfprov --get {tableName}"
    createCheckCommand: "grep -c '{keyTag}={keyValue}' /storage/check.xml; [ $? -eq 0 ]"
    deleteCheckCommand: "grep -c '{keyTag}={keyValue}' /storage/check.xml; [ $? -eq 1 ]"

  # Target node for auto-generated ACTIVITY_CONFIGURATION blocks
  configurationTargetNode: "OAM_NODE"

# Section 4 — POST_NODE_HEALTH_CHECK
postNodeHealthCheck:
  - name: "POST_NODE_HEALTH_CHECK"
    description: "Verify node health after activity"
    targetNode: "OAM_NODE"
    method: CLI
    commands:
      - "cm_adm -check"

# Section 5 — ROLLBACK
rollback:
  precheck:
    - name: "ROLLBACK_PRECHECK"
      description: "Verify state before rollback"
      targetNode: "OAM_NODE"
      method: CLI
      commands:
        - "show status"

  # Tables to include in rollback (in order). Omit to use tables that had CREATE rows.
  configuration:
    - MyTableA

  execution:
    - name: "ROLLBACK_FILES"
      description: "Restore files from backup"
      targetNode: "NE_NODE"
      method: CLI
      commands:
        - "cp /storage/backup.xml /dest/"

  postcheck:
    - name: "ROLLBACK_POSTCHECK"
      description: "Verify rollback completed"
      targetNode: "OAM_NODE"
      method: CLI
      commands:
        - "show status"

  configurationTargetNode: "OAM_NODE"

# Per-table XML generation overrides (optional)
tables:
  MyTableA:
    xmlTemplate: |
      <MyTableA xmlns:xc="..." xc:operation="{action}">
        <id>{ID}</id>
        <name>{NAME}</name>
      </MyTableA>
    fields:
      Record.SOME_FIELD:
        tag: "some-field"
```

### Constants and variables

Two types of placeholder are resolved in every command at MOP generation time so the generated `.mop` file contains fully expanded values.

| Syntax | Source | Example |
|---|---|---|
| `$KEY` | `constants:` block in the YAML template | `$ANN_DIR` |
| `${KEY}` | CIQ JSON data for the current node/group | `${CIRCLE}` |

**Resolution order:** constants are expanded first, then variables. This allows a constant value to itself contain a `${VAR}` reference that is resolved in the second pass.

**Constants** — define fixed values in the template:

```yaml
constants:
  ANN_DIR:  "/var/opt/swms/clips/persistent/provisioned/audioclips/${CIRCLE}/PREPAID"
  ANN_FILE: "announcements_v17.tar"
```

**Variables** — resolved from the CIQ JSON for the current group. Any CIQ column that has a single consistent value across all rows in its sheet is available as `${COLUMN_NAME}`. Built-in variables always available: `${NODE}` (node name) and `${NEID}` (NE identifier).

```yaml
commands:
  - "ls -la $ANN_DIR"          # $ANN_DIR → constant → still contains ${CIRCLE}
  - "cp /tmp/$ANN_FILE $ANN_DIR"
```

Generated MOP output (assuming `CIRCLE=RJ` from CIQ data):
```
ls -la /var/opt/swms/clips/persistent/provisioned/audioclips/RJ/PREPAID
cp /tmp/announcements_v17.tar /var/opt/swms/clips/persistent/provisioned/audioclips/RJ/PREPAID
```

Longer key names are replaced before shorter ones, so `$ANN_DIR_BACKUP` is replaced before `$ANN_DIR` if both are defined.

### String functions

Variables support an optional pipe function for string manipulation:

```
${VAR | function}
${VAR | function:arg}
```

| Function | Description | Example input | Result |
|---|---|---|---|
| `stripExt` | Remove last file extension | `DOC_VERIFY_5003.tar` | `DOC_VERIFY_5003` |
| `basename` | Filename without directory path | `/var/opt/file.tar` | `file.tar` |
| `dirname` | Directory path without filename | `/var/opt/file.tar` | `/var/opt` |
| `upper` | Convert to uppercase | `hello` | `HELLO` |
| `lower` | Convert to lowercase | `HELLO` | `hello` |
| `replace:old:new` | Replace all occurrences | `file.tar` with `replace:.tar:` | `file` |

Usage in commands:

```yaml
commands:
  - "mkdir -p ${MRF_DESTINATION_PATH}/${INPUT_FILE | stripExt}"
  - "tar -xf /tmp/${INPUT_FILE} -C ${MRF_DESTINATION_PATH}/${INPUT_FILE | stripExt}"
  - "cp /tmp/${INPUT_FILE} ${MRF_DESTINATION_PATH}"
```

If `INPUT_FILE = DOC_VERIFICATION_PROMPT_5003.tar`, the generated MOP contains:
```
mkdir -p /var/.../PREPAID/DOC_VERIFICATION_PROMPT_5003
tar -xf /tmp/DOC_VERIFICATION_PROMPT_5003.tar -C /var/.../PREPAID/DOC_VERIFICATION_PROMPT_5003
cp /tmp/DOC_VERIFICATION_PROMPT_5003.tar /var/.../PREPAID
```

### Conditional commands

Commands in any activity block can be conditionally included using `if`/`then`/`else`. The condition is evaluated after constants and variables are resolved.

```yaml
commands:
  - "# always written"
  - if: "${VERSION} == 13"
    then:
      - "cp -r /tmp/${UNTAR_FOLDER_NAME}/* ${MRF_DESTINATION_PATH}"
      - "chmod 777 ${MRF_DESTINATION_PATH}"
    else:
      - "tar -xf /tmp/${INPUT_FILE} -C ${MRF_DESTINATION_PATH}"
      - "chmod 777 ${MRF_DESTINATION_PATH}"
```

Supported condition operators:

| Syntax | Meaning |
|---|---|
| `LHS == RHS` | true when LHS matches RHS (exact or pattern — see below) |
| `LHS != RHS` | true when LHS does not match RHS |
| `LHS` (no operator) | true when non-empty after resolution |

**Pattern matching:** if the RHS contains `X`, each `X` is a wildcard that matches any character sequence. Otherwise exact string equality is used.

```yaml
- if: "${VERSION} == 13.X.X.X"   # matches 13.1.2.3, 13.20.0.1, etc.
  then:
    - "cp -r /tmp/${UNTAR_FOLDER_NAME}/* ${MRF_DESTINATION_PATH}"
  else:
    - "tar -xf /tmp/${INPUT_FILE} -C ${MRF_DESTINATION_PATH}"
```

Conditions are evaluated using group-level variable values (consistent across all CIQ rows). Conditionals can be nested. The `else` branch is optional — if omitted and the condition is false, no commands are written for that block.

### Command template placeholders

| Placeholder | Replaced with |
|---|---|
| `{tableName}` | Sheet / table name |
| `{storagePath}` | `storagePath` value from template |
| `{storageFile}` | Full path of the staged XML file |
| `{keyTag}` | XML tag of the primary key column |
| `{keyValue}` | Value of the primary key for this row |
| `{action}` | `CREATE`, `DELETE`, or `MODIFY` |
| `{fieldTag}` | XML tag of a specific field (postcheck only) |
| `{fieldValue}` | Value of a specific field (postcheck only) |

---

## Approval Summary

When `mopApprovalFormatType` is `HTML` or `MSWORD`, an approval document is generated alongside the MOP files.

The HTML summary structure:
- **Page header** — node type, activity, group/CRGROUP, generated timestamp
- **APPROVAL COPY** banner
- **Node list** — nodes the MOP will be executed on
- **Table of contents** — Activity phases and Rollback phases (nested)
- **Activity** section — each phase as a sub-heading with commands
- **Rollback** section — each rollback phase as a sub-heading with commands

For CRGROUP mode, the summary spans all GROUPs in the CR, showing each GROUP's nodes and MOP sections.

---

## Auto-generated XML blocks

For CIQ-driven activities (SBC, DPA etc.), the `ACTIVITY_CONFIGURATION` blocks are generated automatically from the CIQ JSON data:

```
##<TABLE>_CREATE_ACTIVITY_CONFIGURATION
## Description: Apply CREATE configuration for table <TABLE>
## TargetNode: OAM_NODE
$CREATE
ACTIVITY_EXECUTION_ACTION_1=<TABLE>_CREATE_CONFIGURATION
ACTIVITY_EXECUTION_METHOD_1=CLI
ACTIVITY_EXECUTION_PAYLOAD_1={
cat << 'XMLEOF' > /storage/<nodeType>_<activity>_<TABLE>_CREATE.xml
<?xml version="1.0"?>
<config ne="<neid>" ...>
  ...rows from CIQ...
</config>
XMLEOF
netconfprov --onerror abort /storage/...xml
}
```

Rollback blocks (`##<TABLE>_ROLLBACK_CONFIGURATION`) are generated automatically for every table that had `CREATE` rows, producing the equivalent `DELETE` XML.

---

## Project layout

```
mop-generator-utility/
├── src/main/java/com/nokia/mopgen/
│   ├── MopGeneratorMain.java          # CLI entry point; mode detection + dispatch
│   ├── MopGenerator.java              # Core MOP file generation
│   ├── MopConfig.java                 # Template model
│   ├── MopConfigLoader.java           # Loads + merges YAML template (with includes)
│   ├── CRGroupIndex.java              # CRGROUP index model (local copy)
│   ├── CRGroupIndexLoader.java        # Loads CRGroupIndex JSON from folder
│   ├── GroupIndex.java                # GROUP index model
│   ├── GroupIndexLoader.java          # Loads GroupIndex JSON from folder
│   ├── GroupApprovalMopGenerator.java # Interface + MOP parser (parseMopFile)
│   ├── HtmlGroupApprovalMopGenerator.java  # HTML approval document
│   └── DocxGroupApprovalMopGenerator.java  # DOCX approval document
└── src/main/resources/
    ├── SBC_FIXED_LINE_CONFIGURATION_MOP-Template.yaml
    ├── MRF_ANNOUNCEMENT_LOADING_MOP-Template.yaml
    ├── DPA_UPDATE_HANDSET_DETAILS_MOP-Template.yaml
    └── SBC_PRE_NODE_HEALTH_CHECK.yaml   # Common blocks (used via includes)
```

---

## Adding a new node type / activity

1. Create `{NODE_TYPE}_{ACTIVITY}_MOP-Template.yaml` — no Java changes needed.
2. Set `xmlBuilder` if XML generation is needed, or use only `execution` blocks for static CLI.
3. Define sections (`preNodeHealthCheck`, `backup`, `activity`, `postNodeHealthCheck`, `rollback`).
4. Use `includes:` to reference shared block files.
5. Pass the template path via `--template` when running the CLI.
