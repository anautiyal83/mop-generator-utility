package com.nokia.mopgen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nokia.mopgen.CRGroupIndex;
import com.nokia.ciq.reader.util.FileNamingUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.stream.Collectors;

/**
 * Loads a {@link CRGroupIndex} JSON file from a ciq-processor CRGROUP-mode folder.
 *
 * <p>The expected file name follows the same convention as {@link FileNamingUtil}:
 * {@code {NODE_TYPE}_{ACTIVITY}_index_{crGroup}.json}
 *
 * <p>CRGROUP folders are produced by {@code CiqProcessorImpl.segregateCRGroupBased()}
 * when the CIQ INDEX sheet has {@code GROUP | CRGROUP | NODE} columns.
 */
public class CRGroupIndexLoader {

    private static final Logger log = LoggerFactory.getLogger(CRGroupIndexLoader.class);

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Load the CRGroupIndex from a CRGROUP folder.
     *
     * @param crGroupDir directory of the CRGROUP (e.g. {@code mop-json/CR-001/})
     * @param nodeType   e.g. "MRF"
     * @param activity   e.g. "ANNOUNCEMENT_LOADING"
     * @param crGroup    e.g. "CR-001"
     * @return the loaded CRGroupIndex
     * @throws IOException if the file does not exist or cannot be parsed
     */
    public CRGroupIndex load(String crGroupDir, String nodeType, String activity, String crGroup)
            throws IOException {
        String fileName = FileNamingUtil.indexFileName(nodeType, activity, crGroup);
        File file = new File(crGroupDir, fileName);
        if (!file.exists()) {
            throw new IOException("CRGroupIndex file not found: " + file.getAbsolutePath());
        }
        CRGroupIndex idx = mapper.readValue(file, CRGroupIndex.class);
        log.info("Loaded CRGroupIndex: crGroup={} groups={} totalNodes={}",
                idx.getCrGroup(),
                idx.getGroups().stream().map(CRGroupIndex.GroupEntry::getGroup)
                               .collect(Collectors.joining(", ")),
                idx.getAllNodes().size());
        return idx;
    }

    /**
     * Detect whether {@code dir} is a CRGROUP folder by checking for the presence
     * of a CRGroupIndex file (index file with a {@code crGroup} field).
     */
    public boolean isCRGroupFolder(String dir, String nodeType, String activity, String crGroup) {
        String fileName = FileNamingUtil.indexFileName(nodeType, activity, crGroup);
        File file = new File(dir, fileName);
        if (!file.exists()) return false;
        try {
            CRGroupIndex idx = mapper.readValue(file, CRGroupIndex.class);
            return idx.getCrGroup() != null;
        } catch (IOException e) {
            return false;
        }
    }
}
