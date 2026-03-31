package com.nokia.mopgen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nokia.ciq.reader.util.FileNamingUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * Loads a {@link GroupIndex} JSON file from a ciq-processor GROUP-mode group folder.
 *
 * <p>The expected file name follows the same convention as {@link FileNamingUtil}:
 * {@code {NODE_TYPE}_{ACTIVITY}_index_{group}.json}
 */
public class GroupIndexLoader {

    private static final Logger log = LoggerFactory.getLogger(GroupIndexLoader.class);

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Load the GroupIndex from the group folder.
     *
     * @param groupDir  path to the group folder (e.g. {@code mop-json/A/})
     * @param nodeType  e.g. "MRF"
     * @param activity  e.g. "ANNOUNCEMENT_LOADING"
     * @param group     e.g. "A"
     * @return the loaded GroupIndex
     * @throws IOException if the file does not exist or cannot be parsed
     */
    public GroupIndex load(String groupDir, String nodeType, String activity, String group)
            throws IOException {
        String fileName = FileNamingUtil.indexFileName(nodeType, activity, group);
        File file = new File(groupDir, fileName);
        if (!file.exists()) {
            throw new IOException("GroupIndex file not found: " + file.getAbsolutePath());
        }
        GroupIndex gi = mapper.readValue(file, GroupIndex.class);
        log.info("Loaded GroupIndex: group={} nodes={} tables={}", gi.getGroup(), gi.getNodes(), gi.getTables());
        return gi;
    }

    /**
     * Detect whether {@code dir} is a GROUP-mode group folder by checking for the
     * presence of a GroupIndex file (index file with {@code group} field).
     *
     * @param dir      directory to check
     * @param nodeType e.g. "MRF"
     * @param activity e.g. "ANNOUNCEMENT_LOADING"
     * @param group    e.g. "A"
     * @return true if the GroupIndex JSON exists and can be read as a GroupIndex
     */
    public boolean isGroupFolder(String dir, String nodeType, String activity, String group) {
        String fileName = FileNamingUtil.indexFileName(nodeType, activity, group);
        File file = new File(dir, fileName);
        if (!file.exists()) return false;
        try {
            GroupIndex gi = mapper.readValue(file, GroupIndex.class);
            return gi.getGroup() != null;
        } catch (IOException e) {
            return false;
        }
    }
}
