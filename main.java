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
        private final String toAnchorId;
        private final int linkKind;
        private final long forgedAtEpochMs;
        private final String configHash;

        public MemoryLink(String linkId, String fromAnchorId, String toAnchorId, int linkKind, String configHash) {
            this.linkId = Objects.requireNonNull(linkId, "linkId");
            this.fromAnchorId = Objects.requireNonNull(fromAnchorId, "fromAnchorId");
            this.toAnchorId = Objects.requireNonNull(toAnchorId, "toAnchorId");
            this.linkKind = Math.max(0, linkKind);
            this.forgedAtEpochMs = System.currentTimeMillis();
            this.configHash = configHash != null ? configHash : "";
        }

        public String getLinkId() { return linkId; }
        public String getFromAnchorId() { return fromAnchorId; }
        public String getToAnchorId() { return toAnchorId; }
        public int getLinkKind() { return linkKind; }
        public long getForgedAtEpochMs() { return forgedAtEpochMs; }
        public String getConfigHash() { return configHash; }
    }

    // --- In-memory store ---
    public static final class MemoryStore {
        private final int capacity;
        private final Map<String, MindMaster.MemoryNode> nodes;
        private final Map<String, MindMaster.MemoryLink> links;
        private final List<String> anchorIdOrder;
        private long currentSynapseEpoch;

        public MemoryStore(int capacity) {
            this.capacity = Math.max(1, capacity);
            this.nodes = new ConcurrentHashMap<>();
            this.links = new ConcurrentHashMap<>();
            this.anchorIdOrder = new ArrayList<>();
            this.currentSynapseEpoch = 0;
        }

        public int getCapacity() { return capacity; }
        public int nodeCount() { return nodes.size(); }
        public int linkCount() { return links.size(); }
        public long getCurrentSynapseEpoch() { return currentSynapseEpoch; }
        public void advanceSynapseEpoch() { currentSynapseEpoch++; }

        public Optional<MindMaster.MemoryNode> getNode(String nodeId) {
            return Optional.ofNullable(nodes.get(nodeId));
        }

        public Optional<MindMaster.MemoryLink> getLink(String linkId) {
            return Optional.ofNullable(links.get(linkId));
        }

        public MindMaster.MemoryNode pinAnchor(String nodeId, String label, String contentHash, int recallTier) {
            if (nodes.size() >= capacity) throw new IllegalStateException("Anchor slot full");
            if (nodeId == null || nodeId.isEmpty()) throw new IllegalArgumentException("Zero anchor id");
            if (nodes.containsKey(nodeId)) throw new IllegalStateException("Duplicate anchor id");
            MindMaster.MemoryNode node = new MindMaster.MemoryNode(nodeId, label, contentHash, recallTier);
            nodes.put(nodeId, node);
            anchorIdOrder.add(nodeId);
            return node;
        }

        public MindMaster.MemoryLink forgeLink(String linkId, String fromAnchorId, String toAnchorId, int linkKind, String configHash) {
            if (fromAnchorId == null || toAnchorId == null || fromAnchorId.isEmpty() || toAnchorId.isEmpty())
                throw new IllegalArgumentException("Invalid link endpoints");
            if (!nodes.containsKey(fromAnchorId) || !nodes.containsKey(toAnchorId))
                throw new IllegalArgumentException("Anchor not found");
            MindMaster.MemoryLink link = new MindMaster.MemoryLink(linkId, fromAnchorId, toAnchorId, linkKind, configHash);
            links.put(linkId, link);
            nodes.get(fromAnchorId).addOutLink(linkId);
            nodes.get(toAnchorId).addInLink(linkId);
            return link;
        }

        public void storeRecall(String nodeId, String recallHash) {
            MindMaster.MemoryNode node = nodes.get(nodeId);
            if (node == null) throw new IllegalArgumentException("Anchor not found");
            if (node.isRecallStored()) throw new IllegalStateException("Recall already stored");
            node.setRecallStored(true);
        }

        public List<String> listAnchorIds() { return new ArrayList<>(anchorIdOrder); }
        public Collection<MindMaster.MemoryNode> listNodes() { return new ArrayList<>(nodes.values()); }
        public Collection<MindMaster.MemoryLink> listLinks() { return new ArrayList<>(links.values()); }
    }

    // --- Synapse service (search, filters, traversal) ---
    public static final class SynapseService {
        private final MindMaster.MemoryStore store;

        public SynapseService(MindMaster.MemoryStore store) {
            this.store = store;
        }

        public List<MindMaster.MemoryNode> findByLabelContains(String substring) {
            if (substring == null || substring.isEmpty()) return store.listNodes().stream().collect(Collectors.toList());
            return store.listNodes().stream()
                    .filter(n -> n.getLabel().toLowerCase().contains(substring.toLowerCase()))
                    .collect(Collectors.toList());
        }

        public List<MindMaster.MemoryNode> findByRecallTier(int tier) {
            return store.listNodes().stream()
                    .filter(n -> n.getRecallTier() == tier)
                    .collect(Collectors.toList());
        }

        public List<MindMaster.MemoryNode> findByRecallStored(boolean stored) {
            return store.listNodes().stream()
                    .filter(n -> n.isRecallStored() == stored)
                    .collect(Collectors.toList());
        }

        public List<MindMaster.MemoryLink> findLinksFrom(String fromAnchorId) {
            return store.listLinks().stream()
                    .filter(l -> fromAnchorId.equals(l.getFromAnchorId()))
                    .collect(Collectors.toList());
        }

        public List<MindMaster.MemoryLink> findLinksTo(String toAnchorId) {
            return store.listLinks().stream()
                    .filter(l -> toAnchorId.equals(l.getToAnchorId()))
                    .collect(Collectors.toList());
        }

        public List<MindMaster.MemoryNode> traverseOut(String startNodeId, int maxDepth) {
            int depth = Math.min(maxDepth, MAX_NODE_DEPTH);
            Set<String> visited = new HashSet<>();
            List<MindMaster.MemoryNode> result = new ArrayList<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(startNodeId);
            int level = 0;
            while (!queue.isEmpty() && level <= depth) {
                int levelSize = queue.size();
                for (int i = 0; i < levelSize; i++) {
                    String id = queue.poll();
                    if (id == null || visited.contains(id)) continue;
                    visited.add(id);
                    store.getNode(id).ifPresent(result::add);
                    store.getNode(id).ifPresent(n -> n.getOutLinkIds().stream()
                            .map(lid -> store.getLink(lid))
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .map(MindMaster.MemoryLink::getToAnchorId)
                            .forEach(queue::add));
                }
                level++;
            }
            return result;
        }

        public List<MindMaster.MemoryNode> traverseIn(String startNodeId, int maxDepth) {
            int depth = Math.min(maxDepth, MAX_NODE_DEPTH);
            Set<String> visited = new HashSet<>();
            List<MindMaster.MemoryNode> result = new ArrayList<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(startNodeId);
            int level = 0;
            while (!queue.isEmpty() && level <= depth) {
                int levelSize = queue.size();
                for (int i = 0; i < levelSize; i++) {
                    String id = queue.poll();
                    if (id == null || visited.contains(id)) continue;
                    visited.add(id);
                    store.getNode(id).ifPresent(result::add);
                    store.getNode(id).ifPresent(n -> n.getInLinkIds().stream()
                            .map(lid -> store.getLink(lid))
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .map(MindMaster.MemoryLink::getFromAnchorId)
                            .forEach(queue::add));
                }
                level++;
            }
            return result;
        }

        public Map<String, Integer> nodeDegreeOut() {
            Map<String, Integer> out = new HashMap<>();
            for (MindMaster.MemoryNode n : store.listNodes())
                out.put(n.getNodeId(), n.getOutLinkIds().size());
            return out;
        }

        public Map<String, Integer> nodeDegreeIn() {
            Map<String, Integer> in = new HashMap<>();
            for (MindMaster.MemoryNode n : store.listNodes())
                in.put(n.getNodeId(), n.getInLinkIds().size());
            return in;
        }

        public List<MindMaster.MemoryNode> sortByPinnedTime(boolean ascending) {
            List<MindMaster.MemoryNode> list = new ArrayList<>(store.listNodes());
            list.sort(ascending ? Comparator.comparingLong(MindMaster.MemoryNode::getPinnedAtEpochMs) : (a, b) -> Long.compare(b.getPinnedAtEpochMs(), a.getPinnedAtEpochMs()));
            return list;
        }

        public Optional<MindMaster.MemoryNode> nodeByContentHash(String contentHash) {
            return store.listNodes().stream().filter(n -> contentHash.equals(n.getContentHash())).findFirst();
        }
    }

    // --- Lattice renderer (text map) ---
    public static final class LatticeRenderer {
        private final MindMaster.MemoryStore store;
        private final int maxDepth;
        private final String indentStr;

        public LatticeRenderer(MindMaster.MemoryStore store, int maxDepth, String indentStr) {
            this.store = store;
            this.maxDepth = Math.min(maxDepth, MAX_NODE_DEPTH);
            this.indentStr = indentStr != null ? indentStr : "  ";
        }

        public String renderFrom(String startNodeId) {
            StringBuilder sb = new StringBuilder();
            Set<String> visited = new HashSet<>();
            renderNode(sb, startNodeId, 0, visited);
            return sb.toString();
        }

        private void renderNode(StringBuilder sb, String nodeId, int depth, Set<String> visited) {
            if (depth > maxDepth || visited.contains(nodeId)) return;
            Optional<MindMaster.MemoryNode> opt = store.getNode(nodeId);
            if (!opt.isPresent()) return;
            visited.add(nodeId);
            MindMaster.MemoryNode n = opt.get();
            for (int i = 0; i < depth; i++) sb.append(indentStr);
            sb.append("[T").append(n.getRecallTier()).append("] ").append(n.getLabel()).append(" (").append(n.getNodeId()).append(")\n");
            for (String linkId : n.getOutLinkIds()) {
                store.getLink(linkId).map(MindMaster.MemoryLink::getToAnchorId).ifPresent(toId -> renderNode(sb, toId, depth + 1, visited));
            }
        }

        public String renderFullMap() {
            StringBuilder sb = new StringBuilder();
            for (String anchorId : store.listAnchorIds()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("--- Root: ").append(anchorId).append(" ---\n");
                sb.append(renderFrom(anchorId));
            }
            return sb.toString();
        }
    }

    // --- Epoch filter and stats ---
    public static final class EpochFilter {
        private final MindMaster.MemoryStore store;

        public EpochFilter(MindMaster.MemoryStore store) {
            this.store = store;
        }

        public List<MindMaster.MemoryNode> nodesPinnedAfter(long epochMs) {
            return store.listNodes().stream().filter(n -> n.getPinnedAtEpochMs() >= epochMs).collect(Collectors.toList());
        }

        public List<MindMaster.MemoryLink> linksForgedAfter(long epochMs) {
            return store.listLinks().stream().filter(l -> l.getForgedAtEpochMs() >= epochMs).collect(Collectors.toList());
        }

        public long oldestPinnedTime() {
            return store.listNodes().stream().mapToLong(MindMaster.MemoryNode::getPinnedAtEpochMs).min().orElse(0L);
        }

        public long newestPinnedTime() {
            return store.listNodes().stream().mapToLong(MindMaster.MemoryNode::getPinnedAtEpochMs).max().orElse(0L);
        }
    }

    // --- Validation and invariants ---
    public static final class LatticeValidator {
        private final MindMaster.MemoryStore store;

        public LatticeValidator(MindMaster.MemoryStore store) {
            this.store = store;
        }

        public boolean allLinksHaveAnchors() {
            for (MindMaster.MemoryLink l : store.listLinks()) {
