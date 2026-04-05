import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * JstreeAttributePropagator.java
 *
 * Propaga bottom-up il valore di un attributo A (campo dentro "data") lungo
 * l'albero jstree (formato flat id/parent), partendo dalle foglie verso la radice.
 *
 * Regole applicate a ogni nodo interno N (dopo aver processato tutti i suoi figli):
 *
 *   1. Siano C_valued = figli di N che hanno l'attributo A
 *      Siano C_empty  = figli di N che NON hanno l'attributo A
 *
 *   2. Se C_valued è vuoto  → nessuna modifica ad A su N
 *
 *   3. Se tutti i valori in C_valued sono uguali a k
 *      (indipendentemente da C_empty)  →  assegna A=k a N
 *
 *   4. Se C_valued ha valori distinti  →  rimuove A da N
 *
 * Dopo la propagazione:
 *   Per ogni valore distinto k di A, cerca il nodo con hier_label di
 *   lunghezza minima (stringa più corta) che ha A=k e assegna root_attribute=k.
 *   In caso di parità di lunghezza, viene scelto il nodo con hier_label
 *   lessicograficamente più piccolo.
 *
 * Chiamato da Main oppure direttamente:
 *   new JstreeAttributePropagator().process("input.json", "color", "output.json");
 */
public class JstreeAttributePropagator {

    // ── Rappresentazione interna del nodo ────────────────────────────────────
    static class Node {
        String id;
        String parent;
        String text;
        String hierLabel;
        final Map<String, String> data    = new LinkedHashMap<>();
        final List<String>        childIds = new ArrayList<>();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Punto d'ingresso pubblico
    // ════════════════════════════════════════════════════════════════════════
    public void process(String jsonPath, String attribute, String outputPath) throws Exception {

        String raw = new String(Files.readAllBytes(Paths.get(jsonPath)), "UTF-8");
        List<Node> nodes = parseJson(raw);

        // Costruisce lookup e lista figli
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

        // ── Propagazione bottom-up (DFS post-order) ──────────────────────
        int[] stats = {0, 0, 0}; // [assegnati, rimossi, invariati]
        propagate(root, byId, attribute, stats);

        System.out.printf("    Attributo propagato     : '%s'%n", attribute);
        System.out.printf("    Nodi con valore assegnato: %d%n", stats[0]);
        System.out.printf("    Nodi con valore rimosso  : %d%n", stats[1]);

        // ── Assegna root_attribute ──────────────────────────────────────
        assignRootLabels(nodes, attribute);

        // ── Scrive TSV dei nodi root_attribute ───────────────────────────
        String rootsTsv = outputPath.replaceAll("\\.[^.]+$", "") + "_roots.tsv";
        writeRootsTsv(nodes, rootsTsv);

        writeJson(nodes, outputPath);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  DFS post-order: prima si processano i figli, poi il padre
    // ════════════════════════════════════════════════════════════════════════
    private void propagate(Node node, Map<String, Node> byId, String attr, int[] stats) {

        // Prima processa ricorsivamente tutti i figli
        for (String childId : node.childIds) {
            Node child = byId.get(childId);
            if (child != null) propagate(child, byId, attr, stats);
        }

        // Nodo foglia: nessuna logica di propagazione (tiene il valore originale)
        if (node.childIds.isEmpty()) return;

        // Raccoglie valori distinti tra i figli che hanno l'attributo
        Set<String> distinctValues = new LinkedHashSet<>();
        for (String childId : node.childIds) {
            Node child = byId.get(childId);
            if (child == null) continue;
            String val = child.data.get(attr);
            if (val != null && !val.isEmpty()) {
                distinctValues.add(val);
            }
        }

        // Nessun figlio ha l'attributo: non toccare questo nodo
        if (distinctValues.isEmpty()) return;

        if (distinctValues.size() == 1) {
            // Tutti i figli valorizzati concordano su k
            String k = distinctValues.iterator().next();
            node.data.put(attr, k);
            stats[0]++;
        } else {
            // Valori discordanti: rimuovi l'attributo da questo nodo
            node.data.remove(attr);
            stats[1]++;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Per ogni valore distinto k di A, assegna root_attribute=k al nodo con
    //  hier_label di lunghezza minima (stringa più corta) che ha A=k.
    //  In caso di parità di lunghezza, sceglie il hier_label lessicograficamente
    //  minore.
    // ════════════════════════════════════════════════════════════════════════
    private void assignRootLabels(List<Node> nodes, String attr) {

        // Prima rimuove root_attribute preesistenti
        for (Node n : nodes) n.data.remove("root_attribute");

        // Raggruppa i nodi per valore di A: tiene quello con hier_label più corto
        Map<String, Node> bestNodeForValue = new LinkedHashMap<>();

        for (Node n : nodes) {
            String val = n.data.get(attr);
            if (val == null || val.isEmpty()) continue;
            if (n.hierLabel == null || n.hierLabel.isEmpty()) continue;

            Node current = bestNodeForValue.get(val);
            if (current == null) {
                bestNodeForValue.put(val, n);
            } else {
                int lenN   = n.hierLabel.length();
                int lenCur = current.hierLabel.length();
                if (lenN < lenCur ||
                    (lenN == lenCur && n.hierLabel.compareTo(current.hierLabel) < 0)) {
                    bestNodeForValue.put(val, n);
                }
            }
        }

        int assigned = 0;
        for (Map.Entry<String, Node> e : bestNodeForValue.entrySet()) {
            e.getValue().data.put("root_attribute", e.getKey());
            assigned++;
        }
        System.out.printf("    root_attribute assegnati : %d valori distinti%n", assigned);
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
                    case "id":         n.id        = val; break;
                    case "parent":     n.parent    = val; break;
                    case "text":       n.text      = val; break;
                    case "hier_label": n.hierLabel = val; break;
                }
            } else if (c == '{') {
                int[] p = {i};
                if ("data".equals(key)) parseDataObject(json, p, n);
                else skipObject(json, p);
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

    private void skipObject(String json, int[] pos) {
        int i = pos[0] + 1;
        int depth = 1;
        while (i < json.length() && depth > 0) {
            char c = json.charAt(i);
            if (c == '"') { int[] p = {i}; parseString(json, p); i = p[0]; continue; }
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
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
    //  TSV dei nodi con root_attribute: sample, hier_label, root_attribute
    // ════════════════════════════════════════════════════════════════════════
    private void writeRootsTsv(List<Node> nodes, String path) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("sample\thier_label\troot_attribute\n");
        for (Node n : nodes) {
            String ra = n.data.get("root_attribute");
            if (ra == null) continue;
            sb.append(n.text   == null ? "" : n.text).append('\t');
            sb.append(n.hierLabel == null ? "" : n.hierLabel).append('\t');
            sb.append(ra).append('\n');
        }
        Files.write(Paths.get(path), sb.toString().getBytes("UTF-8"));
        long count = sb.toString().chars().filter(c -> c == '\n').count() - 1;
        System.out.printf("    Root nodes TSV           : %d nodi → %s%n", count, path);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Serializzazione JSON
    // ════════════════════════════════════════════════════════════════════════
    private void writeJson(List<Node> nodes, String path) throws Exception {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            sb.append("  {\n");
            sb.append("    \"id\": \""    ).append(esc(n.id))    .append("\",\n");
            sb.append("    \"parent\": \"").append(esc(n.parent)).append("\",\n");
            sb.append("    \"text\": \""  ).append(esc(n.text))  .append("\"");

            if (n.hierLabel != null) {
                sb.append(",\n    \"hier_label\": \"").append(esc(n.hierLabel)).append("\"");
            }

            if (!n.data.isEmpty()) {
                sb.append(",\n    \"data\": {\n");
                int j = 0;
                for (Map.Entry<String, String> e : n.data.entrySet()) {
                    sb.append("      \"").append(esc(e.getKey()))
                      .append("\": \"").append(esc(e.getValue())).append("\"");
                    if (++j < n.data.size()) sb.append(",");
                    sb.append("\n");
                }
                sb.append("    }");
            }

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
