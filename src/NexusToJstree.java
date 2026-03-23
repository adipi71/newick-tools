import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * NexusToJstree.java
 *
 * Converte un file .nexus (formato FigTree/Newick annotato) in JSON
 * compatibile con jsTree nel formato flat id/parent.
 *
 * Ogni annotazione del nodo viene estratta come attributo nel campo "data":
 *   - color         : colore del ramo/nodo   [&!color=#rrggbb]
 *   - hilight       : evidenziazione clade   [&!hilight={n,v,#color}]
 *   - label         : valore bootstrap        [&label=N] o [&!label=N]
 *   - name          : nome annotato           [&!name="..."]
 *   - branch_length : lunghezza del ramo      :valore
 *
 * Chiamato da Main oppure direttamente:
 *   new NexusToJstree().process("input.nexus", "output.json");
 */
public class NexusToJstree {

    // ── stato del parser ─────────────────────────────────────────────────────
    private String s;
    private int    pos;
    private int    nodeCounter;
    private List<Map<String, Object>> nodes;

    // ════════════════════════════════════════════════════════════════════════
    //  Punto d'ingresso pubblico
    // ════════════════════════════════════════════════════════════════════════
    public void process(String inputPath, String outputPath) throws Exception {
        byte[] bytes  = Files.readAllBytes(Paths.get(inputPath));
        String content = new String(bytes, "UTF-8");

        String newick = extractNewick(content);
        s            = newick.trim();
        pos          = 0;
        nodeCounter  = 0;
        nodes        = new ArrayList<>();

        parseNode("#");

        writeJson(outputPath);

        // Statistiche
        long leaves   = nodes.stream().filter(n -> !nodes.stream()
                .anyMatch(c -> n.get("id").equals(c.get("parent")))).count();
        @SuppressWarnings("unchecked")
        long colored  = nodes.stream().filter(n -> n.containsKey("data") &&
                ((Map<?,?>)n.get("data")).containsKey("color")).count();
        @SuppressWarnings("unchecked")
        long hilighted = nodes.stream().filter(n -> n.containsKey("data") &&
                ((Map<?,?>)n.get("data")).containsKey("hilight")).count();

        System.out.printf("    Nodi totali    : %d%n", nodes.size());
        System.out.printf("    Nodi con color : %d%n", colored);
        System.out.printf("    Nodi con hilight: %d%n", hilighted);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Estrazione stringa Newick dal blocco NEXUS
    // ════════════════════════════════════════════════════════════════════════
    private String extractNewick(String nexus) {
        int treeIdx = nexus.toLowerCase().indexOf("tree ");
        if (treeIdx < 0) throw new RuntimeException("Blocco 'tree' non trovato nel file NEXUS.");

        int eqIdx = nexus.indexOf('=', treeIdx);
        if (eqIdx < 0) throw new RuntimeException("'=' non trovato dopo 'tree'.");

        String rest = nexus.substring(eqIdx + 1).trim();

        if (rest.startsWith("[&")) {
            int close = rest.indexOf(']');
            if (close >= 0) rest = rest.substring(close + 1).trim();
        }

        int semiIdx = findTreeEnd(rest);
        if (semiIdx >= 0) rest = rest.substring(0, semiIdx);

        return rest.trim();
    }

    private int findTreeEnd(String txt) {
        int depth = 0, bracket = 0;
        for (int i = 0; i < txt.length(); i++) {
            char c = txt.charAt(i);
            if      (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == '[') bracket++;
            else if (c == ']') bracket--;
            else if (c == ';' && depth == 0 && bracket == 0) return i;
        }
        return -1;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Parser Newick ricorsivo (simulato con stack per alberi profondi)
    // ════════════════════════════════════════════════════════════════════════
    private String parseNode(String parentId) {
        skipWS();

        String myId = "n" + nodeCounter++;

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id",     myId);
        node.put("parent", parentId);
        nodes.add(node);

        // nodo interno: '(' figli ')'
        if (pos < s.length() && s.charAt(pos) == '(') {
            pos++;
            skipWS();
            while (pos < s.length() && s.charAt(pos) != ')') {
                parseNode(myId);
                skipWS();
                if (pos < s.length() && s.charAt(pos) == ',') pos++;
                skipWS();
            }
            if (pos < s.length() && s.charAt(pos) == ')') pos++;
        }

        String name   = parseName();
        Map<String, String> annots = parseAnnotations();
        String branch = parseBranchLength();

        node.put("text", (name != null && !name.isEmpty()) ? name : myId);

        Map<String, Object> data = new LinkedHashMap<>();
        if (annots.containsKey("color"))   data.put("color",   annots.get("color"));
        if (annots.containsKey("hilight")) data.put("hilight", annots.get("hilight"));
        if (annots.containsKey("label"))   data.put("label",   annots.get("label"));
        if (annots.containsKey("name"))    data.put("annotation_name", annots.get("name"));
        for (Map.Entry<String,String> e : annots.entrySet()) {
            String k = e.getKey();
            if (!k.equals("color") && !k.equals("hilight")
                    && !k.equals("label") && !k.equals("name")) {
                data.put(k, e.getValue());
            }
        }
        if (branch != null) data.put("branch_length", branch);

        if (!data.isEmpty()) node.put("data", data);

        return myId;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Parsing del nome del nodo
    // ════════════════════════════════════════════════════════════════════════
    private String parseName() {
        skipWS();
        if (pos >= s.length()) return null;

        char c = s.charAt(pos);

        if (c == '\'') {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < s.length() && s.charAt(pos) != '\'') {
                sb.append(s.charAt(pos++));
            }
            if (pos < s.length()) pos++;
            return sb.toString();
        }

        if (c != '(' && c != ')' && c != ',' && c != ':' && c != '[' && c != ';') {
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char ch = s.charAt(pos);
                if (ch == '(' || ch == ')' || ch == ',' || ch == ':'
                        || ch == '[' || ch == ';') break;
                sb.append(ch);
                pos++;
            }
            String n = sb.toString().trim();
            return n.isEmpty() ? null : n;
        }

        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Parsing dei blocchi di annotazione [& ...]
    // ════════════════════════════════════════════════════════════════════════
    private Map<String, String> parseAnnotations() {
        Map<String, String> result = new LinkedHashMap<>();
        skipWS();
        while (pos < s.length() && s.charAt(pos) == '[') {
            pos++;
            StringBuilder sb = new StringBuilder();
            int braceDepth = 0;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if      (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
                else if (c == ']' && braceDepth == 0) { pos++; break; }
                sb.append(c);
                pos++;
            }
            String inner = sb.toString().trim();
            if (inner.startsWith("&")) {
                parseAnnotationContent(inner.substring(1), result);
            }
            skipWS();
        }
        return result;
    }

    private void parseAnnotationContent(String content, Map<String, String> out) {
        int i = 0;
        while (i < content.length()) {
            if (i < content.length() && content.charAt(i) == '!') i++;

            int keyStart = i;
            while (i < content.length() && content.charAt(i) != '='
                    && content.charAt(i) != ',') i++;
            String key = content.substring(keyStart, i).trim();

            if (i >= content.length() || content.charAt(i) == ',') {
                if (!key.isEmpty()) out.put(key, "true");
                if (i < content.length() && content.charAt(i) == ',') i++;
                continue;
            }

            i++; // '='

            StringBuilder valSb = new StringBuilder();
            int braceD = 0; boolean inQ = false;
            while (i < content.length()) {
                char c = content.charAt(i);
                if (c == '"') inQ = !inQ;
                if (!inQ) {
                    if      (c == '{') braceD++;
                    else if (c == '}') braceD--;
                    else if (c == ',' && braceD == 0) { i++; break; }
                }
                valSb.append(c);
                i++;
            }

            String val = valSb.toString().trim();
            if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
                val = val.substring(1, val.length() - 1);
            }

            if (!key.isEmpty()) out.put(key, val);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Parsing della lunghezza del ramo
    // ════════════════════════════════════════════════════════════════════════
    private String parseBranchLength() {
        skipWS();
        if (pos < s.length() && s.charAt(pos) == ':') {
            pos++;
            skipWS();
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (Character.isDigit(c) || c == '.' || c == '-'
                        || c == '+' || c == 'e' || c == 'E') {
                    sb.append(c); pos++;
                } else break;
            }
            String v = sb.toString();
            return v.isEmpty() ? null : v;
        }
        return null;
    }

    private void skipWS() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Serializzazione JSON
    // ════════════════════════════════════════════════════════════════════════
    @SuppressWarnings("unchecked")
    private void writeJson(String path) throws Exception {
        StringBuilder sb = new StringBuilder("[\n");

        for (int i = 0; i < nodes.size(); i++) {
            Map<String, Object> node = nodes.get(i);
            sb.append("  {\n");
            sb.append("    \"id\": \""      ).append(esc((String) node.get("id")))    .append("\",\n");
            sb.append("    \"parent\": \"" ).append(esc((String) node.get("parent"))).append("\",\n");
            sb.append("    \"text\": \""   ).append(esc((String) node.get("text")))  .append("\"");

            Map<String, Object> data = (Map<String, Object>) node.get("data");
            if (data != null && !data.isEmpty()) {
                sb.append(",\n    \"data\": {\n");
                int j = 0;
                for (Map.Entry<String, Object> e : data.entrySet()) {
                    sb.append("      \"").append(esc(e.getKey())).append("\": \"")
                      .append(esc(e.getValue().toString())).append("\"");
                    if (++j < data.size()) sb.append(",");
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
