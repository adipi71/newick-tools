import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * JstreeLabeler.java
 *
 * Legge un file JSON jstree (formato flat id/parent) prodotto da NexusToJstree,
 * assegna a ogni nodo una label gerarchica e scrive il JSON arricchito.
 *
 * Schema di labeling:
 *   - Radice (parent="#")   →  "0"
 *   - Figli della radice    →  "1", "2", "3", ...
 *   - Figli di "1"          →  "1.1", "1.2", "1.3", ...
 *   - Figli di "1.2"        →  "1.2.1", "1.2.2", ...
 *
 * Il campo "hier_label" viene aggiunto sia a livello top del nodo
 * sia all'interno del blocco "data" (per compatibilita' jsTree).
 *
 * Vengono prodotti anche:
 *   hier_label16 — segmenti raggruppati in quartetti, ciascuno → cifra hex (0–F)
 *                  mapping: posizione p → bit (p-1); right-pad 0 sull'ultimo gruppo
 *   hier_label32 — segmenti raggruppati in quintetti, ciascuno → cifra base32 (0–9A–V)
 *
 * Chiamato da Main oppure direttamente:
 *   new JstreeLabeler().process("input.json", "output.json");
 */
public class JstreeLabeler {

    // ── Rappresentazione interna del nodo ────────────────────────────────────
    static class Node {
        String id;
        String parent;
        String text;
        String hierLabel;
        String hierLabel2;
        String hierLabel16;
        String hierLabel32;
        final Map<String, String> data    = new LinkedHashMap<>();
        final List<String>        childIds = new ArrayList<>();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Punto d'ingresso pubblico
    // ════════════════════════════════════════════════════════════════════════
    public void process(String inputPath, String outputPath) throws Exception {

        String raw = new String(Files.readAllBytes(Paths.get(inputPath)), "UTF-8");
        List<Node> nodes = parseJson(raw);

        // Lookup id → Node e costruzione lista figli
        Map<String, Node> byId = new LinkedHashMap<>(nodes.size() * 2);
        for (Node n : nodes) byId.put(n.id, n);

        for (Node n : nodes) {
            if (!"#".equals(n.parent)) {
                Node p = byId.get(n.parent);
                if (p != null) p.childIds.add(n.id);
            }
        }

        // Trova la radice
        Node root = null;
        for (Node n : nodes) {
            if ("#".equals(n.parent)) { root = n; break; }
        }
        if (root == null) throw new RuntimeException("Radice non trovata nel JSON.");

        // Assegna label gerarchiche con BFS
        root.hierLabel   = "0";
        root.hierLabel2  = "0";
        root.hierLabel16 = "0";
        root.hierLabel32 = "0";
        Queue<String> queue = new ArrayDeque<>();
        queue.add(root.id);

        while (!queue.isEmpty()) {
            String nid = queue.poll();
            Node   cur = byId.get(nid);
            String base = cur.hierLabel;

            for (int i = 0; i < cur.childIds.size(); i++) {
                String childId = cur.childIds.get(i);
                Node   child   = byId.get(childId);
                if (child == null) continue;

                int position = i + 1;   // 1-based
                child.hierLabel   = "0".equals(base)
                        ? String.valueOf(position)
                        : base + "." + position;
                child.hierLabel2  = toHierLabel2(child.hierLabel);
                child.hierLabel16 = toHierLabel16(child.hierLabel2);
                child.hierLabel32 = toHierLabel32(child.hierLabel2);

                queue.add(childId);
            }
        }

        assignRootColors(nodes);
        printStats(nodes);
        writeJson(nodes, outputPath);

        String base = outputPath.replaceAll("\\.[^.]+$", "");
        writeTsv(nodes, base + ".tsv");
        writeRootColorTsv(nodes, base + "_root_colors.tsv");
        writePeartreeNexus(nodes, root, byId, base + "_peartree.nexus");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Per ogni colore distinto, assegna root_color=colore al nodo con
    //  hier_label di lunghezza minima (a parità, lessicograficamente minore).
    // ════════════════════════════════════════════════════════════════════════
    private void assignRootColors(List<Node> nodes) {
        // Rimuove root_color preesistenti
        for (Node n : nodes) n.data.remove("root_color");

        Map<String, Node> best = new LinkedHashMap<>();
        for (Node n : nodes) {
            String color = n.data.get("color");
            if (color == null || color.isEmpty()) continue;
            if (n.hierLabel == null || n.hierLabel.isEmpty()) continue;

            Node cur = best.get(color);
            if (cur == null) {
                best.put(color, n);
            } else {
                int lenN   = n.hierLabel.length();
                int lenCur = cur.hierLabel.length();
                if (lenN < lenCur || (lenN == lenCur && n.hierLabel.compareTo(cur.hierLabel) < 0))
                    best.put(color, n);
            }
        }

        for (Map.Entry<String, Node> e : best.entrySet())
            e.getValue().data.put("root_color", e.getKey());

        System.out.printf("    root_color assegnati : %d colori distinti%n", best.size());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Statistiche
    // ════════════════════════════════════════════════════════════════════════
    private void printStats(List<Node> nodes) {
        long labeled  = nodes.stream().filter(n -> n.hierLabel != null).count();
        int  maxDepth = nodes.stream()
                .mapToInt(n -> n.hierLabel == null ? 0
                             : (int) n.hierLabel.chars().filter(c -> c == '.').count())
                .max().orElse(0);
        Node deepest  = nodes.stream()
                .filter(n -> n.hierLabel != null)
                .max(Comparator.comparingInt(n -> n.hierLabel.length()))
                .orElse(null);

        System.out.printf("    Nodi etichettati : %d%n", labeled);
        System.out.printf("    Profondita' max  : %d livelli%n", maxDepth);
        if (deepest != null)
            System.out.printf("    Label piu' lunga : %s  ->  %s%n",
                    deepest.hierLabel, deepest.text);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  hier_label2:  per ogni segmento s di hier_label
    //    s == 1  →  "0"   (ridotto di 1, ≤ 1 → cifra singola)
    //    s == 2  →  "1"   (ridotto di 1, ≤ 1 → cifra singola)
    //    s >  2  →  (s-1) come stringa binaria a 4 bit, con punto tra le cifre
    //               es. 3→(2)→"0.0.1.0",  5→(4)→"0.1.0.0"
    //
    //  hier_label16: hier_label2 diviso in blocchi da 4 cifre (sinistra→destra),
    //                ultimo blocco zero-padded a destra → cifra 0-9A-F per blocco
    //  hier_label32: hier_label2 diviso in blocchi da 5 cifre,
    //                stesso schema → cifra 0-9A-V per blocco
    // ════════════════════════════════════════════════════════════════════════
    static String toHierLabel2(String hierLabel) {
        if (hierLabel == null || hierLabel.isEmpty() || "0".equals(hierLabel)) return "0";
        String[] parts = hierLabel.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(".");
            int reduced = Integer.parseInt(parts[i]) - 1;  // 1→0, 2→1, 3→2, …
            if (reduced <= 1) {
                sb.append(reduced);
            } else {
                // encode reduced as 4-bit binary with dots between digits
                String bin = String.format("%4s", Integer.toBinaryString(reduced)).replace(' ', '0');
                for (int b = 0; b < bin.length(); b++) {
                    if (b > 0) sb.append(".");
                    sb.append(bin.charAt(b));
                }
            }
        }
        return sb.toString();
    }

    // Riceve hier_label2 direttamente (cifre 0/1 separate da punto).
    // Divide in blocchi da 4 cifre (zero-pad a destra sull'ultimo blocco)
    // e converte ogni blocco in una cifra esadecimale.
    static String toHierLabel16(String label2) {
        if (label2 == null || label2.isEmpty() || "0".equals(label2)) return "0";
        String[] bits = label2.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bits.length; i += 4) {
            int val = 0;
            int end = Math.min(i + 4, bits.length);
            for (int j = i; j < end; j++)
                val = val * 2 + Integer.parseInt(bits[j]);
            for (int j = end; j < i + 4; j++) val *= 2;   // zero-pad a destra
            sb.append(Integer.toHexString(val).toUpperCase());
        }
        return sb.toString();
    }

    // Riceve hier_label2 direttamente (cifre 0/1 separate da punto).
    // Divide in blocchi da 5 cifre (zero-pad a destra sull'ultimo blocco)
    // e converte ogni blocco in una cifra base32 (0-9A-V).
    static String toHierLabel32(String label2) {
        if (label2 == null || label2.isEmpty() || "0".equals(label2)) return "0";
        final String CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUV";
        String[] bits = label2.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bits.length; i += 5) {
            int val = 0;
            int end = Math.min(i + 5, bits.length);
            for (int j = i; j < end; j++)
                val = val * 2 + Integer.parseInt(bits[j]);
            for (int j = end; j < i + 5; j++) val *= 2;   // zero-pad a destra
            sb.append(CHARS.charAt(val & 31));
        }
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Parser JSON minimalista (zero dipendenze esterne)
    // ════════════════════════════════════════════════════════════════════════
    private List<Node> parseJson(String json) {
        List<Node> result = new ArrayList<>();
        int i = skipWS(json, 0);
        if (i >= json.length() || json.charAt(i) != '[')
            throw new RuntimeException("JSON non inizia con '['");
        i++;

        while (i < json.length()) {
            i = skipWS(json, i);
            if (i >= json.length() || json.charAt(i) == ']') break;
            if (json.charAt(i) == ',')  { i++; continue; }
            if (json.charAt(i) == '{')  {
                int[] cur = {i};
                result.add(parseObject(json, cur));
                i = cur[0];
            } else { i++; }
        }
        return result;
    }

    private Node parseObject(String json, int[] pos) {
        Node n = new Node();
        int i = pos[0] + 1;

        while (i < json.length()) {
            i = skipWS(json, i);
            if (json.charAt(i) == '}') { i++; break; }
            if (json.charAt(i) == ',') { i++; continue; }

            int[] cur = {i};
            String key = parseString(json, cur);
            i = skipWS(json, cur[0]);
            if (i < json.length() && json.charAt(i) == ':') i++;
            i = skipWS(json, i);
            if (i >= json.length()) break;

            char c = json.charAt(i);
            if (c == '"') {
                cur[0] = i;
                String val = parseString(json, cur);
                i = cur[0];
                switch (key) {
                    case "id":         n.id     = val; break;
                    case "parent":     n.parent = val; break;
                    case "text":       n.text   = val; break;
                    case "hier_label": /* top-level ignorato */ break;
                }
            } else if (c == '{') {
                int[] p = {i};
                parseDataObject(json, p, n);
                i = p[0];
            } else {
                while (i < json.length() && json.charAt(i) != ',' && json.charAt(i) != '}') i++;
            }
        }
        pos[0] = i;
        return n;
    }

    private void parseDataObject(String json, int[] pos, Node n) {
        int i = pos[0] + 1;
        while (i < json.length()) {
            i = skipWS(json, i);
            if (json.charAt(i) == '}') { i++; break; }
            if (json.charAt(i) == ',') { i++; continue; }

            int[] cur = {i};
            String key = parseString(json, cur);
            i = skipWS(json, cur[0]);
            if (i < json.length() && json.charAt(i) == ':') i++;
            i = skipWS(json, i);

            if (i < json.length() && json.charAt(i) == '"') {
                cur[0] = i;
                String val = parseString(json, cur);
                i = cur[0];
                n.data.put(key, val);
            } else {
                while (i < json.length() && json.charAt(i) != ',' && json.charAt(i) != '}') i++;
            }
        }
        pos[0] = i;
    }

    private String parseString(String json, int[] pos) {
        int i = skipWS(json, pos[0]);
        if (i >= json.length() || json.charAt(i) != '"')
            throw new RuntimeException("Atteso '\"' alla posizione " + i);
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '"') { i++; break; }
            if (c == '\\' && i + 1 < json.length()) {
                char e = json.charAt(i + 1);
                switch (e) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/');  break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    default:   sb.append(e);
                }
                i += 2;
            } else { sb.append(c); i++; }
        }
        pos[0] = i;
        return sb.toString();
    }

    private int skipWS(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  TSV completo: sample + hier_label* + tutti i campi data
    // ════════════════════════════════════════════════════════════════════════
    private void writeTsv(List<Node> nodes, String path) throws Exception {
        LinkedHashSet<String> allKeys = new LinkedHashSet<>();
        allKeys.add("hier_label");
        allKeys.add("hier_label2");
        allKeys.add("hier_label16");
        allKeys.add("hier_label32");
        for (Node n : nodes) allKeys.addAll(n.data.keySet());

        StringBuilder sb = new StringBuilder();
        sb.append("sample");
        for (String k : allKeys) sb.append('\t').append(k);
        sb.append('\n');

        for (Node n : nodes) {
            Map<String, String> row = buildDataRow(n);
            sb.append(n.text == null ? "" : n.text);
            for (String k : allKeys) {
                sb.append('\t');
                String v = row.get(k);
                if (v != null) sb.append(v);
            }
            sb.append('\n');
        }

        Files.write(Paths.get(path), sb.toString().getBytes("UTF-8"));
        System.out.printf("    TSV nodi totali      : %d nodi → %s%n", nodes.size(), path);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  TSV dei soli nodi con root_color: stesse colonne del TSV completo
    // ════════════════════════════════════════════════════════════════════════
    private void writeRootColorTsv(List<Node> nodes, String path) throws Exception {
        LinkedHashSet<String> allKeys = new LinkedHashSet<>();
        allKeys.add("hier_label");
        allKeys.add("hier_label2");
        allKeys.add("hier_label16");
        allKeys.add("hier_label32");
        for (Node n : nodes) allKeys.addAll(n.data.keySet());

        StringBuilder sb = new StringBuilder();
        sb.append("sample");
        for (String k : allKeys) sb.append('\t').append(k);
        sb.append('\n');

        int count = 0;
        for (Node n : nodes) {
            if (n.data.get("root_color") == null) continue;
            Map<String, String> row = buildDataRow(n);
            sb.append(n.text == null ? "" : n.text);
            for (String k : allKeys) {
                sb.append('\t');
                String v = row.get(k);
                if (v != null) sb.append(v);
            }
            sb.append('\n');
            count++;
        }

        Files.write(Paths.get(path), sb.toString().getBytes("UTF-8"));
        System.out.printf("    TSV root_color       : %d nodi → %s%n", count, path);
    }

    /** Costruisce la mappa chiave→valore effettiva di un nodo (come in writeJson). */
    private Map<String, String> buildDataRow(Node n) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("hier_label",   n.hierLabel   == null ? "" : n.hierLabel);
        row.put("hier_label2",  n.hierLabel2  == null ? "" : n.hierLabel2);
        row.put("hier_label16", n.hierLabel16 == null ? "" : n.hierLabel16);
        row.put("hier_label32", n.hierLabel32 == null ? "" : n.hierLabel32);
        row.putAll(n.data);
        return row;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  NEXUS compatibile peartree: annotazioni [&key="val"] senza prefisso !
    // ════════════════════════════════════════════════════════════════════════
    private void writePeartreeNexus(List<Node> nodes, Node root,
                                    Map<String, Node> byId, String path) throws Exception {
        String newick = buildPeartreeNewick(root, byId);

        StringBuilder sb = new StringBuilder();
        sb.append("#NEXUS\n");
        sb.append("begin trees;\n");
        sb.append("\ttree tree_1 = [&R] ").append(newick).append(";\n");
        sb.append("end;\n");
        Files.write(Paths.get(path), sb.toString().getBytes("UTF-8"));
        System.out.printf("    Peartree NEXUS       : %s%n", path);
    }

    private String buildPeartreeNewick(Node root, Map<String, Node> byId) {
        Deque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[]{root, 0, new ArrayList<String>()});

        String result = null;
        while (!stack.isEmpty()) {
            Object[] frame   = stack.peek();
            Node     current = (Node)         frame[0];
            int      idx     = (int)           frame[1];
            @SuppressWarnings("unchecked")
            List<String> childNewick = (List<String>) frame[2];

            if (idx < current.childIds.size()) {
                frame[1] = idx + 1;
                Node child = byId.get(current.childIds.get(idx));
                if (child != null) stack.push(new Object[]{child, 0, new ArrayList<String>()});
            } else {
                stack.pop();
                StringBuilder sb = new StringBuilder();

                if (current.childIds.isEmpty()) {
                    sb.append(current.text == null ? "" : current.text);
                } else {
                    sb.append("(");
                    for (int i = 0; i < childNewick.size(); i++) {
                        if (i > 0) sb.append(",");
                        sb.append(childNewick.get(i));
                    }
                    sb.append(")");
                    if (current.text != null && !current.text.isEmpty())
                        sb.append(current.text);
                }

                String annot = buildPeartreeAnnotation(current);
                if (!annot.isEmpty()) sb.append(annot);

                boolean isRoot = "#".equals(current.parent);
                if (!isRoot) {
                    String bl = current.data.get("branch_length");
                    sb.append((bl != null && !bl.isEmpty()) ? ":" + bl : ":0.0");
                }

                String token = sb.toString();
                if (stack.isEmpty()) {
                    result = token;
                } else {
                    @SuppressWarnings("unchecked")
                    List<String> parentList = (List<String>) stack.peek()[2];
                    parentList.add(token);
                }
            }
        }
        return result;
    }

    private String buildPeartreeAnnotation(Node n) {
        List<String> parts = new ArrayList<>();

        // hier_label* dai campi dedicati del nodo
        if (n.hierLabel   != null) parts.add("hier_label=\""   + esc(n.hierLabel)   + "\"");
        if (n.hierLabel2  != null) parts.add("hier_label2=\""  + esc(n.hierLabel2)  + "\"");
        if (n.hierLabel16 != null) parts.add("hier_label16=\"" + esc(n.hierLabel16) + "\"");
        if (n.hierLabel32 != null) parts.add("hier_label32=\"" + esc(n.hierLabel32) + "\"");

        // tutti gli altri campi data (escluso branch_length e hier_label* già scritti)
        for (Map.Entry<String, String> e : n.data.entrySet()) {
            String k = e.getKey();
            if (k.equals("branch_length") ||
                k.equals("hier_label") || k.equals("hier_label2") ||
                k.equals("hier_label16") || k.equals("hier_label32"))
                continue;
            parts.add(k + "=\"" + esc(e.getValue()) + "\"");
        }

        // user_colour = color (campo richiesto da peartree)
        String color = n.data.get("color");
        if (color != null && !color.isEmpty())
            parts.add("user_colour=\"" + esc(color) + "\"");

        if (parts.isEmpty()) return "";
        return "[&" + String.join(",", parts) + "]";
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Serializzazione JSON
    // ════════════════════════════════════════════════════════════════════════
    private void writeJson(List<Node> nodes, String path) throws Exception {
        StringBuilder sb = new StringBuilder("[\n");

        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            sb.append("  {\n");
            sb.append("    \"id\": \""          ).append(esc(n.id))        .append("\",\n");
            sb.append("    \"parent\": \""       ).append(esc(n.parent))    .append("\",\n");
            sb.append("    \"text\": \""         ).append(esc(n.text))      .append("\",\n");
            sb.append("    \"hier_label\": \""   ).append(esc(n.hierLabel)).append("\",\n");
            sb.append("    \"hier_label2\": \""  ).append(esc(n.hierLabel2)).append("\"");

            // "data": hier_label* come primi campi, poi tutti gli originali;
            // i valori calcolati vengono re-imposti dopo putAll per sovrascrivere
            // eventuali etichette obsolete già presenti nel blocco data di input.
            Map<String, String> dataOut = new LinkedHashMap<>();
            dataOut.put("hier_label",   n.hierLabel   == null ? "" : n.hierLabel);
            dataOut.put("hier_label2",  n.hierLabel2  == null ? "" : n.hierLabel2);
            dataOut.put("hier_label16", n.hierLabel16 == null ? "" : n.hierLabel16);
            dataOut.put("hier_label32", n.hierLabel32 == null ? "" : n.hierLabel32);
            dataOut.putAll(n.data);
            // restore freshly computed labels (putAll may have overwritten them)
            dataOut.put("hier_label",   n.hierLabel   == null ? "" : n.hierLabel);
            dataOut.put("hier_label2",  n.hierLabel2  == null ? "" : n.hierLabel2);
            dataOut.put("hier_label16", n.hierLabel16 == null ? "" : n.hierLabel16);
            dataOut.put("hier_label32", n.hierLabel32 == null ? "" : n.hierLabel32);

            sb.append(",\n    \"data\": {\n");
            int j = 0;
            for (Map.Entry<String, String> e : dataOut.entrySet()) {
                sb.append("      \"").append(esc(e.getKey()))
                  .append("\": \"") .append(esc(e.getValue())).append("\"");
                if (++j < dataOut.size()) sb.append(",");
                sb.append("\n");
            }
            sb.append("    }");

            sb.append("\n  }");
            if (i < nodes.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]\n");
        Files.write(Paths.get(path), sb.toString().getBytes("UTF-8"));
    }

    private String esc(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }
}
