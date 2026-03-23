import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * JstreeToNexus.java
 *
 * Inverso di NexusToJstree: legge un file JSON jstree (formato flat id/parent)
 * e ricostruisce un file NEXUS/Newick annotato compatibile con FigTree.
 *
 * Mapping attributi JSON → annotazioni Newick:
 *   data.color            →  [&!color=#rrggbb]
 *   data.hilight          →  [&!hilight={n,v,#color}]
 *   data.label            →  [&label=N]      (senza !)
 *   data.annotation_name  →  [&!name="val"]
 *   altri campi custom    →  [&!key=val]
 *   data.branch_length    →  :valore
 *   data.hier_label       →  ignorato
 *
 * Chiamato da Main oppure direttamente:
 *   new JstreeToNexus().process("input.json", "output.nexus");
 */
public class JstreeToNexus {

    // ── Campi da NON serializzare come annotazioni ────────────────────────────
    private static final Set<String> SKIP_FIELDS = new HashSet<>(Arrays.asList(
            "hier_label", "branch_length"
    ));

    // ── Campi senza prefisso '!' ───────────────────────────────────────────────
    private static final Set<String> NO_BANG = new HashSet<>(Arrays.asList("label"));

    // ── Rappresentazione interna del nodo ────────────────────────────────────
    static class Node {
        String              id;
        String              parent;
        String              text;
        Map<String, String> data     = new LinkedHashMap<>();
        List<String>        childIds = new ArrayList<>();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Punto d'ingresso pubblico
    // ════════════════════════════════════════════════════════════════════════
    public void process(String inputPath, String outputPath) throws Exception {

        String raw = new String(Files.readAllBytes(Paths.get(inputPath)), "UTF-8");
        List<Node> nodes = parseJson(raw);

        // Lookup id → Node e lista figli
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

        String newick = buildNewick(root, byId);
        writeNexus(newick, outputPath);

        // Statistiche
        long leaves   = nodes.stream().filter(n -> n.childIds.isEmpty()).count();
        long annotated = nodes.stream().filter(n -> !effectiveAnnotations(n).isEmpty()).count();
        System.out.printf("    Foglie           : %d%n", leaves);
        System.out.printf("    Nodi annotati    : %d%n", annotated);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Generazione Newick — post-order iterativo (evita stack overflow
    //  su alberi con decine di livelli di profondita')
    // ════════════════════════════════════════════════════════════════════════
    private String buildNewick(Node root, Map<String, Node> byId) {
        Deque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[]{root, 0, new ArrayList<String>()});

        String result = null;

        while (!stack.isEmpty()) {
            Object[] frame    = stack.peek();
            Node     current  = (Node)         frame[0];
            int      idx      = (int)           frame[1];
            @SuppressWarnings("unchecked")
            List<String> childNewick = (List<String>) frame[2];

            if (idx < current.childIds.size()) {
                frame[1] = idx + 1;
                Node child = byId.get(current.childIds.get(idx));
                if (child != null) {
                    stack.push(new Object[]{child, 0, new ArrayList<String>()});
                }
            } else {
                stack.pop();

                StringBuilder sb = new StringBuilder();

                boolean isLeaf = current.childIds.isEmpty();
                if (isLeaf) {
                    sb.append(quoteNewickName(current.text));
                } else {
                    sb.append("(");
                    for (int i = 0; i < childNewick.size(); i++) {
                        if (i > 0) sb.append(",");
                        sb.append(childNewick.get(i));
                    }
                    sb.append(")");
                }

                String annot = buildAnnotationBlock(current);
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

    // ════════════════════════════════════════════════════════════════════════
    //  Costruisce [& k1=v1, k2=v2, ... ] per un nodo
    //
    //  Ordine: color, hilight, label, annotation_name, campi custom
    // ════════════════════════════════════════════════════════════════════════
    private String buildAnnotationBlock(Node n) {
        List<String> parts = effectiveAnnotations(n);
        if (parts.isEmpty()) return "";
        return "[&" + String.join(",", parts) + "]";
    }

    private List<String> effectiveAnnotations(Node n) {
        Map<String, String> d = n.data;
        if (d.isEmpty()) return Collections.emptyList();

        List<String> parts = new ArrayList<>();

        if (d.containsKey("color"))            parts.add("!color="  + d.get("color"));
        if (d.containsKey("hilight"))          parts.add("!hilight=" + d.get("hilight"));
        if (d.containsKey("label"))            parts.add("label="   + d.get("label"));
        if (d.containsKey("annotation_name"))
            parts.add("!name=\"" + esc(d.get("annotation_name")) + "\"");

        for (Map.Entry<String, String> e : d.entrySet()) {
            String k = e.getKey();
            if (SKIP_FIELDS.contains(k)) continue;
            if (k.equals("color") || k.equals("hilight")
                    || k.equals("label") || k.equals("annotation_name")) continue;

            parts.add((NO_BANG.contains(k) ? "" : "!") + k + "=" + e.getValue());
        }
        return parts;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Quoting dei nomi Newick
    // ════════════════════════════════════════════════════════════════════════
    private String quoteNewickName(String name) {
        if (name == null || name.isEmpty()) return "''";

        boolean needsQuote = false;
        for (char c : name.toCharArray()) {
            if (c == '(' || c == ')' || c == ',' || c == ':' ||
                c == ';' || c == '[' || c == ']' || c == ' ' || c == '\'') {
                needsQuote = true; break;
            }
        }
        if (!needsQuote && (Character.isDigit(name.charAt(0)) || name.contains("."))) {
            needsQuote = true;
        }
        if (!needsQuote) return name;
        return "'" + name.replace("'", "''") + "'";
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Scrittura del file NEXUS con header FigTree
    // ════════════════════════════════════════════════════════════════════════
    private void writeNexus(String newick, String path) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("#NEXUS\n");
        sb.append("begin trees;\n");
        sb.append("\ttree tree_1 = [&R] ").append(newick).append(";\n");
        sb.append("end;\n\n");
        sb.append("begin figtree;\n");
        sb.append("\tset appearance.backgroundColorAttribute=\"Default\";\n");
        sb.append("\tset appearance.backgroundColour=#ffffff;\n");
        sb.append("\tset appearance.branchColorAttribute=\"User selection\";\n");
        sb.append("\tset appearance.branchColorGradient=false;\n");
        sb.append("\tset appearance.branchLineWidth=1.0;\n");
        sb.append("\tset appearance.foregroundColour=#000000;\n");
        sb.append("\tset appearance.hilightingGradient=true;\n");
        sb.append("\tset branchLabels.isShown=false;\n");
        sb.append("\tset layout.layoutType=\"RECTILINEAR\";\n");
        sb.append("\tset nodeLabels.isShown=false;\n");
        sb.append("\tset tipLabels.displayAttribute=\"Names\";\n");
        sb.append("\tset tipLabels.isShown=true;\n");
        sb.append("end;\n");
        Files.write(Paths.get(path), sb.toString().getBytes("UTF-8"));
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
                    case "hier_label": /* ignorato */ break;
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

    private String esc(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
