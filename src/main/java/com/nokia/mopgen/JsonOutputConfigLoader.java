package com.nokia.mopgen;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Loads a standalone {@code *_json-output.yaml} file as a raw map.
 *
 * <p>The file is produced alongside ciq-processor's validation rules and describes the
 * structure of the unified JSON written by ciq-processor when
 * {@code --json-output-config-file} is supplied.  Both ciq-processor and mop-generator
 * share the same file so that the JSON structure is defined in one place.
 *
 * <p>File naming convention: {@code {NODE_TYPE}_{ACTIVITY}_json-output.yaml}
 *
 * <p>Top-level keys in the file:
 * <ul>
 *   <li>{@code output_mode}   — {@code single} or {@code individual}</li>
 *   <li>{@code segregate_by}  — sheet/column/variable used for individual mode</li>
 *   <li>{@code data}          — free-form JSON template tree with {@code _each} directives</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   Map&lt;String, Object&gt; config = new JsonOutputConfigLoader().load("MRF_ANNOUNCEMENT_LOADING_json-output.yaml");
 * </pre>
 */
public class JsonOutputConfigLoader {

    /**
     * Loads the json-output YAML file at the given path.
     *
     * @param filePath absolute or relative path to the {@code *_json-output.yaml} file
     * @return raw template map with {@code output_mode}, {@code segregate_by}, and {@code data} sections
     * @throws IOException if the file does not exist or cannot be parsed
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> load(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("JSON output config file not found: " + filePath);
        }

        Yaml yaml = new Yaml();
        try (InputStream is = new FileInputStream(file)) {
            Object raw = yaml.load(is);
            if (raw == null) {
                throw new IOException("JSON output config file is empty: " + filePath);
            }
            if (!(raw instanceof Map)) {
                throw new IOException("JSON output config file must be a YAML map at root level: " + filePath);
            }
            return (Map<String, Object>) raw;
        }
    }
}
