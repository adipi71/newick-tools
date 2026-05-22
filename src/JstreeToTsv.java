import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Conversione bidirezionale tra JSON jstree flat e TSV.
 *
 * Formato TSV:  id | parent | text | <campi data...>
 *   - "id", "parent", "text" sono colonne fisse (top-level del nodo)
 *   - le colonne restanti vanno nel blocco "data"
 *
 * json → tsv:  new JstreeToTsv().toTsv("input.json",  "output.tsv");
 * tsv → json:  new JstreeToTsv().toJson("input.tsv",  "output.json");
 */
public class JstreeToTsv {

    private static final Set<String> FIXED = new LinkedHashSet<>(Arrays.asList("id", "parent", "text"));

    // ════════════════════════════════════════════════════════════════════════
    //  API pubblica
    // ════════════════════════════════════════════════════════════════════════

    /** JSON jstree → TSV. */
    public void toTsv(String jsonPath, String tsvPath) throws Exception {
        List<Node> nodes = parseJson(
                new String(Files.readAllBytes(Paths.get(jsonPath)), "UTF-8"));
        ensureParentDir(tsvPath);
        writeTsv(nodes, tsvPath);
        System.out.println("[JstreeToTsv] " + nodes.size() + " nodi → " + tsvPath);
    }

    /** TSV → JSON jstree. */
    public void toJson(String tsvPath, String jsonPath) throws Exception {
        List<Node> nodes = parseTsv(
                new String(Files.readAllBytes(Paths.get(tsvPath)), "UTF-8"));
        ensureParentDir(jsonPath);
        writeJson(nodes, jsonPath);
        System.out.println("[JstreeToTsv] " + nodes.size() + " nodi → " + jsonPath);
    }

    /** Alias retrocompatibile → toTsv. */
    public void process(String jsonPath, String tsvPath) throws Exception {
        toTsv(jsonPath, tsvPath);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Modello nodo
    // ════════════════════════════════════════════════════════════════════════

    static class Node {
        String id     = "";
        String parent = "";
        String text   = "";
        final Map<String, String> data = new LinkedHashMap<>();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  TSV → nodi
    // ════════════════════════════════════════════════════════════════════════

    private List<Node> parseTsv(String raw) {
        List<Node> nodes = new ArrayList<>();
        String[] lines = raw.split("\r?\n", -1);
        if (lines.length == 0) return nodes;

        String[] headers = lines[0].split("\t", -1);

        for (int r = 1; r < lines.length; r++) {
            if (lines[r].trim().isEmpty()) continue;
            String[] cols = lines[r].split("\t", -1);

            Node n = new Node();
            for (int c = 0; c < headers.length; c++) {
                String key = headers[c].trim();
                String val = (c < cols.length) ? cols[c] : "";
                switch (key) {
                    case "id":     n.id     = val; break;
                    case "parent": n.parent = val; break;
                    case "text":   n.text   = val; break;
                    default:
                        if (!key.isEmpty()) n.data.put(key, val);
                }
            }
            if (n.text.isEmpty()) n.text = n.id;
            nodes.add(n);
        }
        return nodes;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Scrittura TSV
    // ════════════════════════════════════════════════════════════════════════

    private void writeTsv(List<Node> nodes, String path) throws Exception {
        LinkedHashSet<String> dataKeys = new LinkedHashSet<>();
        for (Node n : nodes) dataKeys.addAll(n.data.keySet());

        StringBuilder sb = new StringBuilder();

        // intestazione: colonne fisse + colonne data
        sb.append("id\tparent\ttext");
        for (String k : dataKeys) sb.append('\t').append(k);
        sb.append('\n');

        for (Node n : nodes) {
            sb.append(n.id).append('\t').append(n.parent).append('\t').append(n.text);
            for (String k : dataKeys) {
                sb.append('\t');
                String v = n.data.get(k);
                if (v != null) sb.append(v);
            }
            sb.append('\n');
        }

        Files.write(Paths.get(path), sb.toString().getBytes("UTF-8"));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Scrittura JSON
    // ════════════════════════════════════════════════════════════════════════

    private void writeJson(List<Node> nodes, String path) throws Exception {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            sb.append("  {\n");
            sb.append("    \"id\": \""     ).append(esc(n.id))    .append("\",\n");
            sb.append("    \"parent\": \"" ).append(esc(n.parent)).append("\",\n");
            sb.append("    \"text\": \""   ).append(esc(n.text))  .append("\"");

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
    //  Parser JSON (hand-rolled)
    // ════════════════════════════════════════════════════════════════════════

    private List<Node> parseJson(String json) {
        List<Node> nodes = new ArrayList<>();
        int[] pos = {0};
        pos[0] = skipWS(json, pos[0]);
        if (pos[0] >= json.length() || json.charAt(pos[0]) != '[')
            throw new RuntimeException("[JstreeToTsv] Il JSON non inizia con '['");
        pos[0]++;
        while (pos[0] < json.length()) {
            pos[0] = skipWS(json, pos[0]);
            if (pos[0] >= json.length()) break;
            char c = json.charAt(pos[0]);
            if (c == ']') break;
            if (c == ',') { pos[0]++; continue; }
            if (c == '{') nodes.add(parseNode(json, pos));
            else pos[0]++;
        }
        return nodes;
    }

    private Node parseNode(String json, int[] pos) {
        Node n = new Node();
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
                }
            } else if (v == '{') {
                cur[0] = i;
                if ("data".equals(key)) parseDataObject(json, cur, n);
                else skipObject(json, cur);
                i = cur[0];
            } else if (v == '[') {
                cur[0] = i; skipArray(json, cur); i = cur[0];
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
                n.data.put(key, parseString(json, cur));
                i = cur[0];
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
    //  Utility
    // ════════════════════════════════════════════════════════════════════════

    private String parseString(String json, int[] pos) {
        int i = skipWS(json, pos[0]);
        if (i >= json.length() || json.charAt(i) != '"')
            throw new RuntimeException("[JstreeToTsv] Atteso '\"' alla posizione " + i);
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
