import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Arricchisce un JSON jstree flat con attributi provenienti da un TSV.
 *
 * Il TSV deve avere una riga di intestazione con un campo "id" e uno o più
 * campi attributo. Per ogni riga del TSV il cui "id" corrisponde all'id di un
 * nodo JSON, gli attributi vengono scritti nel blocco "data" del nodo:
 * aggiunti se non esistono, sovrascritti se già presenti.
 *
 * Uso:
 *   new JstreeEnricher().process("input.json", "attrs.tsv", "output.json");
 */
public class JstreeEnricher {

    // ════════════════════════════════════════════════════════════════════════
    //  Entry point
    // ════════════════════════════════════════════════════════════════════════

    public void process(String jsonPath, String tsvPath, String outPath) throws Exception {
        String jsonRaw = new String(Files.readAllBytes(Paths.get(jsonPath)), "UTF-8");
        String tsvRaw  = new String(Files.readAllBytes(Paths.get(tsvPath)),  "UTF-8");

        // ── Parse TSV → mappa id → {attributo → valore} ──────────────────
        Map<String, Map<String, String>> tsvById = parseTsv(tsvRaw);

        // ── Parse JSON ────────────────────────────────────────────────────
        List<Node> nodes = parseJson(jsonRaw);

        // ── Arricchimento ─────────────────────────────────────────────────
        int enriched = 0;
        for (Node n : nodes) {
            Map<String, String> attrs = tsvById.get(n.id);
            if (attrs == null) continue;
            n.data.putAll(attrs);   // aggiunge o sovrascrive
            enriched++;
        }
        System.out.println("[JstreeEnricher] nodi arricchiti: " + enriched
                + " / " + nodes.size());

        // ── Scrittura JSON ────────────────────────────────────────────────
        ensureParentDir(outPath);
        writeJson(nodes, outPath);
        System.out.println("[JstreeEnricher] output: " + outPath);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Modello nodo (struttura piatta jstree)
    // ════════════════════════════════════════════════════════════════════════

    static class Node {
        String id     = "";
        String parent = "";
        String text   = "";
        /** Tutti gli altri campi top-level non riconosciuti (es. hier_label). */
        final Map<String, String> extra = new LinkedHashMap<>();
        final Map<String, String> data  = new LinkedHashMap<>();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Parser TSV
    // ════════════════════════════════════════════════════════════════════════

    private Map<String, Map<String, String>> parseTsv(String raw) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        String[] lines = raw.split("\r?\n", -1);
        if (lines.length == 0) return result;

        String[] headers = splitTsvLine(lines[0]);
        int idCol = -1;
        for (int i = 0; i < headers.length; i++) {
            if ("id".equals(headers[i].trim())) { idCol = i; break; }
        }
        if (idCol < 0)
            throw new RuntimeException("[JstreeEnricher] Il TSV non contiene una colonna 'id'");

        for (int r = 1; r < lines.length; r++) {
            String line = lines[r];
            if (line.trim().isEmpty()) continue;
            String[] cols = splitTsvLine(line);
            if (idCol >= cols.length) continue;
            String id = cols[idCol].trim();
            if (id.isEmpty()) continue;

            Map<String, String> attrs = new LinkedHashMap<>();
            for (int c = 0; c < headers.length; c++) {
                if (c == idCol) continue;
                String key = headers[c].trim();
                if (key.isEmpty()) continue;
                String val = (c < cols.length) ? cols[c] : "";
                attrs.put(key, val);
            }
            result.put(id, attrs);
        }
        return result;
    }

    private String[] splitTsvLine(String line) {
        return line.split("\t", -1);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Parser JSON (hand-rolled, compatibile con il formato del progetto)
    // ════════════════════════════════════════════════════════════════════════

    private List<Node> parseJson(String json) {
        List<Node> nodes = new ArrayList<>();
        int[] pos = {0};
        pos[0] = skipWS(json, pos[0]);
        if (pos[0] >= json.length() || json.charAt(pos[0]) != '[')
            throw new RuntimeException("[JstreeEnricher] Il JSON non inizia con '['");
        pos[0]++;
        while (pos[0] < json.length()) {
            pos[0] = skipWS(json, pos[0]);
            if (pos[0] >= json.length()) break;
            char c = json.charAt(pos[0]);
            if (c == ']') break;
            if (c == ',') { pos[0]++; continue; }
            if (c == '{') {
                nodes.add(parseNode(json, pos));
            } else {
                pos[0]++;
            }
        }
        return nodes;
    }

    private Node parseNode(String json, int[] pos) {
        Node n = new Node();
        int i = pos[0] + 1;   // salta '{'
        while (i < json.length()) {
            i = skipWS(json, i);
            if (i >= json.length()) break;
            char c = json.charAt(i);
            if (c == '}') { i++; break; }
            if (c == ',') { i++; continue; }

            int[] cur = {i};
            String key = parseString(json, cur);
            i = skipWS(json, cur[0]);
            if (i < json.length() && json.charAt(i) == ':') i++;
            i = skipWS(json, i);
            if (i >= json.length()) break;

            char v = json.charAt(i);
            if (v == '"') {
                cur[0] = i;
                String val = parseString(json, cur);
                i = cur[0];
                switch (key) {
                    case "id":     n.id     = val; break;
                    case "parent": n.parent = val; break;
                    case "text":   n.text   = val; break;
                    default:       n.extra.put(key, val); break;
                }
            } else if (v == '{') {
                cur[0] = i;
                if ("data".equals(key)) {
                    parseDataObject(json, cur, n);
                } else {
                    skipObject(json, cur);
                }
                i = cur[0];
            } else if (v == '[') {
                cur[0] = i;
                skipArray(json, cur);
                i = cur[0];
            } else {
                // valore numerico / booleano / null
                StringBuilder sb = new StringBuilder();
                while (i < json.length() && json.charAt(i) != ',' && json.charAt(i) != '}') {
                    sb.append(json.charAt(i++));
                }
                n.extra.put(key, sb.toString().trim());
            }
        }
        pos[0] = i;
        return n;
    }

    private void parseDataObject(String json, int[] pos, Node n) {
        int i = pos[0] + 1;
        while (i < json.length()) {
            i = skipWS(json, i);
            if (i >= json.length()) break;
            char c = json.charAt(i);
            if (c == '}') { i++; break; }
            if (c == ',') { i++; continue; }

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
        int depth = 0, i = pos[0];
        while (i < json.length()) {
            char c = json.charAt(i++);
            if (c == '{') depth++;
            else if (c == '}') { if (--depth == 0) break; }
            else if (c == '"') { int[] p = {i - 1}; parseString(json, p); i = p[0]; }
        }
        pos[0] = i;
    }

    private void skipArray(String json, int[] pos) {
        int depth = 0, i = pos[0];
        while (i < json.length()) {
            char c = json.charAt(i++);
            if (c == '[') depth++;
            else if (c == ']') { if (--depth == 0) break; }
            else if (c == '"') { int[] p = {i - 1}; parseString(json, p); i = p[0]; }
        }
        pos[0] = i;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Serializzazione JSON
    // ════════════════════════════════════════════════════════════════════════

    private void writeJson(List<Node> nodes, String path) throws Exception {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            sb.append("  {\n");
            sb.append("    \"id\": \""     ).append(esc(n.id))    .append("\",\n");
            sb.append("    \"parent\": \"" ).append(esc(n.parent)).append("\",\n");
            sb.append("    \"text\": \""   ).append(esc(n.text))  .append("\"");

            // campi top-level extra (es. hier_label, hier_label2, …)
            for (Map.Entry<String, String> e : n.extra.entrySet()) {
                sb.append(",\n    \"").append(esc(e.getKey()))
                  .append("\": \"").append(esc(e.getValue())).append("\"");
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

    // ════════════════════════════════════════════════════════════════════════
    //  Utility
    // ════════════════════════════════════════════════════════════════════════

    private String parseString(String json, int[] pos) {
        int i = skipWS(json, pos[0]);
        if (i >= json.length() || json.charAt(i) != '"')
            throw new RuntimeException("[JstreeEnricher] Atteso '\"' alla posizione " + i);
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '"') { i++; break; }
            if (c == '\\' && i + 1 < json.length()) {
                char e = json.charAt(++i);
                switch (e) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/');  break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    default:   sb.append(e);    break;
                }
            } else {
                sb.append(c);
            }
            i++;
        }
        pos[0] = i;
        return sb.toString();
    }

    private int skipWS(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void ensureParentDir(String path) throws IOException {
        Path p = Paths.get(path).toAbsolutePath().getParent();
        if (p != null) Files.createDirectories(p);
    }
}
