/*
 * MindMaster — Online mapping tool for memory. Single-file build; run main() for CLI or use programmatic API.
 * Lattice seed: 0x8f7e6d5c4b3a2918e0d1c2b3a495867e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b
 */

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class MindMaster {

    public static final String LATTICE_VERSION = "1.0.0-synapse";
    public static final int MAX_NODE_DEPTH = 64;
    public static final int MAX_LABEL_LEN = 256;
    public static final int DEFAULT_CAPACITY = 4096;

    private final MindMaster.MemoryStore store;
    private final MindMaster.SynapseService synapseService;
    private final MindMaster.RecallSerializer recallSerializer;

    public MindMaster() {
        this.store = new MindMaster.MemoryStore(DEFAULT_CAPACITY);
        this.synapseService = new MindMaster.SynapseService(store);
        this.recallSerializer = new MindMaster.RecallSerializer(store);
    }

    public MindMaster(int capacity) {
        this.store = new MindMaster.MemoryStore(capacity);
        this.synapseService = new MindMaster.SynapseService(store);
        this.recallSerializer = new MindMaster.RecallSerializer(store);
    }

    public MindMaster.MemoryStore getStore() { return store; }
    public MindMaster.SynapseService getSynapseService() { return synapseService; }
    public MindMaster.RecallSerializer getRecallSerializer() { return recallSerializer; }

    private volatile MindMaster.LatticeRenderer latticeRenderer;
    private volatile MindMaster.EpochFilter epochFilter;
    private volatile MindMaster.LatticeValidator latticeValidator;

    public MindMaster.LatticeRenderer getLatticeRenderer() {
        if (latticeRenderer == null) latticeRenderer = new MindMaster.LatticeRenderer(store, MAX_NODE_DEPTH, "  ");
        return latticeRenderer;
    }

    public MindMaster.EpochFilter getEpochFilter() {
        if (epochFilter == null) epochFilter = new MindMaster.EpochFilter(store);
        return epochFilter;
    }

    public MindMaster.LatticeValidator getLatticeValidator() {
        if (latticeValidator == null) latticeValidator = new MindMaster.LatticeValidator(store);
        return latticeValidator;
    }

    // --- Memory node (anchor) ---
    public static final class MemoryNode {
        private final String nodeId;
        private final String label;
        private final String contentHash;
        private final int recallTier;
        private final long pinnedAtEpochMs;
        private final Set<String> outLinkIds;
        private final Set<String> inLinkIds;
        private boolean recallStored;

        public MemoryNode(String nodeId, String label, String contentHash, int recallTier) {
            this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
            this.label = label != null && label.length() <= MAX_LABEL_LEN ? label : (label != null ? label.substring(0, MAX_LABEL_LEN) : "");
            this.contentHash = contentHash != null ? contentHash : "";
            this.recallTier = Math.max(0, Math.min(7, recallTier));
            this.pinnedAtEpochMs = System.currentTimeMillis();
            this.outLinkIds = new HashSet<>();
            this.inLinkIds = new HashSet<>();
            this.recallStored = false;
        }

        public String getNodeId() { return nodeId; }
        public String getLabel() { return label; }
        public String getContentHash() { return contentHash; }
        public int getRecallTier() { return recallTier; }
        public long getPinnedAtEpochMs() { return pinnedAtEpochMs; }
        public Set<String> getOutLinkIds() { return Collections.unmodifiableSet(outLinkIds); }
        public Set<String> getInLinkIds() { return Collections.unmodifiableSet(inLinkIds); }
        public boolean isRecallStored() { return recallStored; }
        public void setRecallStored(boolean recallStored) { this.recallStored = recallStored; }

        void addOutLink(String linkId) { outLinkIds.add(linkId); }
        void addInLink(String linkId) { inLinkIds.add(linkId); }
        void removeOutLink(String linkId) { outLinkIds.remove(linkId); }
        void removeInLink(String linkId) { inLinkIds.remove(linkId); }
    }

    // --- Memory link (edge) ---
    public static final class MemoryLink {
        private final String linkId;
        private final String fromAnchorId;
