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
        root.hierLabel = "0";
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
                child.hierLabel = "0".equals(base)
                        ? String.valueOf(position)
                        : base + "." + position;

                queue.add(childId);
            }
        }

        printStats(nodes);
        writeJson(nodes, outputPath);
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
            sb.append("    \"hier_label\": \""   ).append(esc(n.hierLabel)).append("\"");

            // "data": hier_label come primo campo, poi tutti gli originali
            Map<String, String> dataOut = new LinkedHashMap<>();
            dataOut.put("hier_label", n.hierLabel == null ? "" : n.hierLabel);
            dataOut.putAll(n.data);

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
