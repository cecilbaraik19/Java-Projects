package com.cecil.certify.chain;

import com.cecil.certify.util.Clock;
import com.cecil.certify.util.Crypto;
import com.cecil.certify.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The Pramaan network: three validator nodes, each with its own copy of the chain.
 *
 * Writing:  a new block is sealed (proof of work) by one node and sent to all nodes. Each node checks it on its own.
 *           It is committed only if a MAJORITY (2 of 3) accept it.
 * Reading:  an answer is trusted only if a MAJORITY of nodes hold the same valid chain. A node whose copy was
 *           edited is either broken (fails its own check) or out-voted (valid but different from the others).
 * Repair:   a bad node can be repaired by copying the majority's chain.
 */
public final class Network {

    public static final String[] NODE_IDS = {"A", "B", "C"};

    public final List<Node> nodes = new ArrayList<>();
    public String genesisHash;
    public final int difficulty;
    private final PrivateKey registrarKey;
    private final Path dir;           // null = keep everything in memory (tests)
    private int nextSealer = 0;
    /** Copy of the chain as the network itself last committed it. Used only by the Tamper lab's "Undo all attacks". */
    private List<Block> committed = new ArrayList<>();

    private Network(Path dir, int difficulty, PrivateKey registrarKey) {
        this.dir = dir;
        this.difficulty = difficulty;
        this.registrarKey = registrarKey;
    }

    // ------------------------------------------------------------------ create / load

    /** Creates a brand-new network with a genesis block signed by a new registrar key. */
    public static Network create(Path dir, int difficulty, String networkName, String[] keepers) throws IOException {
        KeyPair reg = Crypto.newKeyPair();
        Network n = new Network(dir, difficulty, reg.getPrivate());
        Map<String, String> p = new LinkedHashMap<>();
        p.put("network", networkName);
        p.put("registrarKey", Crypto.encodePublic(reg.getPublic()));
        Transaction g = Transaction.create(TxType.GENESIS, Clock.now(), Transaction.REGISTRAR, p, reg.getPrivate());
        Block genesis = new Block(0, Clock.now(), ChainValidator.ZERO_HASH, difficulty, "A", List.of(g));
        genesis.mine();
        n.genesisHash = genesis.hash;
        for (int i = 0; i < NODE_IDS.length; i++) {
            Node node = new Node(NODE_IDS[i], "Node " + NODE_IDS[i], keepers[i]);
            node.chain.add(genesis.copy());
            n.nodes.add(node);
        }
        n.committed = List.of(genesis.copy());
        if (dir != null) {
            Files.createDirectories(dir.resolve("nodes"));
            Files.createDirectories(dir.resolve("keys"));
            write(dir.resolve("keys/registrar.key"), Crypto.encodePrivate(reg.getPrivate()));
            write(dir.resolve("network.json"), Json.pretty(Json.map(
                    "network", networkName, "genesisHash", n.genesisHash, "difficulty", difficulty,
                    "keepers", List.of(keepers))));
            n.saveAll();
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    public static Network load(Path dir) throws IOException {
        Map<String, Object> cfg = Json.parseObject(Files.readString(dir.resolve("network.json"), StandardCharsets.UTF_8));
        PrivateKey reg = Crypto.decodePrivate(Files.readString(dir.resolve("keys/registrar.key")).trim());
        Network n = new Network(dir, (int) Json.num(cfg, "difficulty", 4), reg);
        n.genesisHash = Json.str(cfg, "genesisHash");
        List<Object> keepers = Json.arr(cfg, "keepers");
        for (int i = 0; i < NODE_IDS.length; i++) {
            Node node = new Node(NODE_IDS[i], "Node " + NODE_IDS[i], i < keepers.size() ? String.valueOf(keepers.get(i)) : "");
            n.readNode(node);
            n.nodes.add(node);
        }
        n.committed = n.readBlocks(dir.resolve("nodes/lab-backup.json"));
        if (n.committed.isEmpty()) n.committed = n.nodes.get(0).copyChain();
        return n;
    }

    private void readNode(Node node) throws IOException {
        node.chain = readBlocks(nodeFile(node)); // an unreadable file gives an empty chain, shown as broken
        node.changed();
    }

    @SuppressWarnings("unchecked")
    private List<Block> readBlocks(Path f) throws IOException {
        List<Block> out = new ArrayList<>();
        if (!Files.exists(f)) return out;
        try {
            Map<String, Object> m = Json.parseObject(Files.readString(f, StandardCharsets.UTF_8));
            for (Object o : Json.arr(m, "blocks")) out.add(Block.fromJson((Map<String, Object>) o));
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
        return out;
    }

    /** Re-reads every node file from disk (so you can edit a file in Notepad and see the result). */
    public synchronized void reloadFromDisk() throws IOException {
        if (dir == null) return;
        for (Node n : nodes) readNode(n);
    }

    private Path nodeFile(Node n) {
        return dir.resolve("nodes/node-" + n.id.toLowerCase() + ".json");
    }

    private void save(Node n) {
        if (dir == null) return;
        List<Object> blocks = new ArrayList<>();
        for (Block b : n.chain) blocks.add(b.toJson());
        try {
            write(nodeFile(n), Json.pretty(Json.map("node", n.name, "keeper", n.keeper, "blocks", blocks)));
        } catch (IOException e) {
            throw new IllegalStateException("Could not save " + n.name + ": " + e.getMessage(), e);
        }
    }

    private void saveAll() {
        for (Node n : nodes) save(n);
        saveCommitted();
    }

    private void saveCommitted() {
        if (dir == null) return;
        List<Object> blocks = new ArrayList<>();
        for (Block b : committed) blocks.add(b.toJson());
        try {
            write(dir.resolve("nodes/lab-backup.json"), Json.pretty(Json.map(
                    "about", "Tamper lab backup: the chain exactly as the network committed it. Used only by 'Undo all attacks'.",
                    "blocks", blocks)));
        } catch (IOException e) {
            throw new IllegalStateException("Could not save the lab backup: " + e.getMessage(), e);
        }
    }

    /** Tamper lab: puts every node back to the chain the network itself committed (undoes all attacks). */
    public synchronized void undoAllAttacks() {
        for (Node n : nodes) {
            n.chain = new ArrayList<>();
            for (Block b : committed) n.chain.add(b.copy());
            n.changed();
            save(n);
        }
    }

    private static void write(Path file, String text) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public PrivateKey registrarKey() {
        return registrarKey;
    }

    // ------------------------------------------------------------------ consensus

    /** The network's agreed view: which nodes agree, and the chain state they agree on. */
    public static final class Consensus {
        public boolean ok;
        public String message = "";
        public ChainState state;            // state of the majority chain (null if no majority)
        public List<String> agreeing = new ArrayList<>();
        public final Map<String, NodeStatus> status = new LinkedHashMap<>();
        public Node leader;                 // one node from the majority
    }

    public static final class NodeStatus {
        public String nodeId;
        public String state;      // AGREES, BROKEN, OUTVOTED
        public String detail;
        public int height;
        public int badBlock = -1;
        public String problemKind = "";
        public String tipHash;
    }

    public synchronized Consensus consensus() {
        Consensus c = new Consensus();
        Map<String, List<Node>> groups = new LinkedHashMap<>();
        for (Node n : nodes) {
            ChainValidator.Result r = n.check(genesisHash, difficulty);
            if (r.ok) groups.computeIfAbsent(n.tipHash(), k -> new ArrayList<>()).add(n);
        }
        List<Node> majority = null;
        for (List<Node> g : groups.values()) if (g.size() * 2 > nodes.size()) majority = g;
        if (majority != null) {
            c.ok = true;
            c.leader = majority.get(0);
            c.state = c.leader.check(genesisHash, difficulty).state;
            for (Node n : majority) c.agreeing.add(n.id);
            c.message = c.agreeing.size() + " of " + nodes.size() + " nodes agree";
        } else {
            c.ok = false;
            c.message = "No majority: the nodes do not agree, so no answer can be trusted";
        }
        for (Node n : nodes) {
            ChainValidator.Result r = n.check(genesisHash, difficulty);
            NodeStatus s = new NodeStatus();
            s.nodeId = n.id;
            s.height = n.height();
            s.tipHash = n.tipHash();
            if (!r.ok) {
                s.state = "BROKEN";
                s.detail = r.reason;
                s.badBlock = r.badBlock;
                s.problemKind = r.problemKind;
            } else if (c.agreeing.contains(n.id)) {
                s.state = "AGREES";
                s.detail = "Chain is valid and matches the majority";
            } else {
                s.state = "OUTVOTED";
                s.detail = "Chain is valid on its own, but its history is different from the majority, so it is out-voted";
                if (c.state != null) {
                    int fork = forkPoint(n.chain, c.leader.chain);
                    s.badBlock = fork;
                    s.detail += " (differs from block " + fork + ")";
                }
            }
            c.status.put(n.id, s);
        }
        return c;
    }

    private static int forkPoint(List<Block> a, List<Block> b) {
        int i = 0;
        while (i < a.size() && i < b.size() && a.get(i).hash.equals(b.get(i).hash)) i++;
        return i;
    }

    // ------------------------------------------------------------------ writing a new block

    public static final class Commit {
        public Block block;
        public List<String> acceptedBy = new ArrayList<>();
        public List<String> rejectedBy = new ArrayList<>();
    }

    /**
     * Seals the transactions into a new block and sends it to every node.
     * @throws IllegalStateException if there is no majority, or the majority refuses the block
     */
    public synchronized Commit submit(List<Transaction> txs) {
        if (txs.isEmpty()) throw new IllegalArgumentException("Nothing to write");
        Consensus c = consensus();
        if (!c.ok) throw new IllegalStateException("The network has no majority, so nothing can be written. Repair the nodes first.");
        // the sealing job rotates between the agreeing nodes
        String sealer = c.agreeing.get(nextSealer++ % c.agreeing.size());
        List<Block> base = c.leader.chain;
        Block tip = base.get(base.size() - 1);
        Block block = new Block(base.size(), Math.max(Clock.now(), tip.time), tip.hash, difficulty, sealer, txs);
        block.mine();

        Commit result = new Commit();
        result.block = block;
        List<Node> accepted = new ArrayList<>();
        for (Node n : nodes) {
            List<Block> candidate = new ArrayList<>(n.chain);
            candidate.add(block.copy());
            ChainValidator.Result r = ChainValidator.validate(candidate, genesisHash, difficulty);
            if (r.ok) accepted.add(n);
            else result.rejectedBy.add(n.id);
        }
        if (accepted.size() * 2 <= nodes.size()) {
            String why = ChainValidator.validate(appended(c.leader.chain, block), genesisHash, difficulty).reason;
            throw new IllegalStateException("The nodes refused the new block: " + why);
        }
        for (Node n : accepted) {
            n.chain.add(block.copy());
            n.changed();
            result.acceptedBy.add(n.id);
            save(n);
        }
        committed = c.leader.copyChain();
        saveCommitted();
        return result;
    }

    private static List<Block> appended(List<Block> chain, Block b) {
        List<Block> l = new ArrayList<>(chain);
        l.add(b);
        return l;
    }

    // ------------------------------------------------------------------ repair

    /** Replaces a node's chain with a copy of the majority chain. */
    public synchronized void repair(String nodeId) {
        Consensus c = consensus();
        if (!c.ok) throw new IllegalStateException("There is no majority to copy from");
        Node n = node(nodeId);
        n.chain = c.leader.copyChain();
        n.changed();
        save(n);
    }

    public Node node(String id) {
        for (Node n : nodes) if (n.id.equalsIgnoreCase(id)) return n;
        throw new IllegalArgumentException("No node " + id);
    }

    // ------------------------------------------------------------------ Tamper lab (attacks on ONE node's copy)

    /**
     * Attack 1: an insider edits one record inside a block on one node, and does nothing else.
     * The block's Merkle root no longer matches -> that node is BROKEN.
     */
    public synchronized String tamperEdit(String nodeId, String certId, String fieldIndexHashReplacement, String newRoot) {
        Node n = node(nodeId);
        Transaction tx = findIssue(n, certId);
        replaceLeaf(tx, fieldIndexHashReplacement, newRoot);
        n.changed();
        save(n);
        return "Changed the stored fingerprint of " + certId + " on " + n.name + " without re-sealing.";
    }

    /**
     * Attack 2: the insider edits the record AND re-does the proof of work for that block and every block after it,
     * so all hashes and links look right again. The institution's signature still does not match -> BROKEN.
     */
    public synchronized String tamperEditAndReseal(String nodeId, String certId, String replacement, String newRoot) {
        Node n = node(nodeId);
        Transaction tx = findIssue(n, certId);
        replaceLeaf(tx, replacement, newRoot);
        int from = blockOf(n, tx);
        n.chain = reseal(n.chain, from);
        n.changed();
        save(n);
        return "Changed the fingerprint of " + certId + " on " + n.name + " and re-did the proof of work for blocks "
                + from + " to " + n.height() + ".";
    }

    /**
     * Attack 3: the insider DELETES a revocation (to make a cancelled certificate look valid again) and re-seals.
     * Every remaining record is genuinely signed, so the copy passes its own check - but it is out-voted by the
     * two honest nodes.
     */
    public synchronized String tamperDeleteRevocation(String nodeId, String certId) {
        Node n = node(nodeId);
        int at = -1;
        for (Block b : n.chain) {
            for (Transaction t : b.transactions) {
                if (t.type == TxType.REVOKE_CERT && certId.equals(t.get("certId"))) at = b.index;
            }
        }
        if (at < 0) throw new IllegalArgumentException(certId + " has no revocation on " + n.name);
        Block b = n.chain.get(at);
        b.transactions.removeIf(t -> t.type == TxType.REVOKE_CERT && certId.equals(t.get("certId")));
        List<Block> chain = new ArrayList<>(n.chain);
        if (b.transactions.isEmpty()) chain.remove(at); // an empty block is not allowed, so drop it
        n.chain = reseal(chain, at);
        n.changed();
        save(n);
        return "Deleted the revocation of " + certId + " from " + n.name + " and re-sealed blocks " + at + " onwards.";
    }

    private static Transaction findIssue(Node n, String certId) {
        for (Block b : n.chain)
            for (Transaction t : b.transactions)
                if (t.type == TxType.ISSUE_CERT && certId.equals(t.get("certId"))) return t;
        throw new IllegalArgumentException(certId + " is not on " + n.name);
    }

    private static int blockOf(Node n, Transaction tx) {
        for (Block b : n.chain) if (b.transactions.contains(tx)) return b.index;
        return n.height();
    }

    /** replacement = "position:newLeafHash". */
    private static void replaceLeaf(Transaction tx, String replacement, String newRoot) {
        String[] p = replacement.split(":", 2);
        int pos = Integer.parseInt(p[0]);
        String[] leaves = tx.get("fieldHashes").split(",");
        leaves[pos] = p[1];
        tx.payload.put("fieldHashes", String.join(",", leaves));
        tx.payload.put("root", newRoot);
    }

    /** Rebuilds blocks from `from` to the end: new index, new previous hash, new Merkle root, new proof of work. */
    private List<Block> reseal(List<Block> chain, int from) {
        List<Block> out = new ArrayList<>(chain.subList(0, Math.min(from, chain.size())));
        for (int i = from; i < chain.size(); i++) {
            Block old = chain.get(i);
            Block prev = out.get(out.size() - 1);
            Block nb = new Block(out.size(), Math.max(old.time, prev.time), prev.hash, old.difficulty, old.sealedBy, old.transactions);
            nb.mine();
            out.add(nb);
        }
        return out;
    }

    // ------------------------------------------------------------------ explorer helpers

    /** Finds which nodes hold a transaction matching the test (used to show "found on 3 of 3 nodes"). */
    public synchronized List<String> nodesHolding(Predicate<Transaction> test) {
        List<String> ids = new ArrayList<>();
        for (Node n : nodes) {
            boolean found = false;
            for (Block b : n.chain) for (Transaction t : b.transactions) if (test.test(t)) found = true;
            if (found) ids.add(n.id);
        }
        return ids;
    }
}
