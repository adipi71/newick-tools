import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * JstreeColorizer.java
 *
 * Legge un file CSV con colonne "codice" e "colore" e assegna il colore
 * ai nodi corrispondenti di un file JSON jstree (formato flat id/parent).
 *
 * Il matching viene fatto sul campo "text" del nodo (nome tassone); in
 * caso di mancato riscontro si prova anche sul campo "id".
 *
 * Il colore viene scritto in data.color del nodo, sovrascrivendo
 * eventuali valori preesistenti.
 *
 * Formato CSV accettato:
 *   - Separatore: virgola o punto-e-virgola (rilevato automaticamente)
 *   - Prima riga: intestazione con i nomi delle colonne (case-insensitive)
 *     es.  codice,colore  oppure  code;color
 *   - Righe successive: coppie codice/colore
 *     es.  PX632943.1,#FF0000
 *
 * Chiamato da Main oppure direttamente:
 *   new JstreeColorizer().process("input.json", "colors.csv", "output.json");
 */
public class JstreeColorizer {

    // ── Rappresentazione interna del nodo ────────────────────────────────────
    static class Node {
        String id;
        String parent;
        String text;
        String hierLabel;
        final Map<String, String> data     = new LinkedHashMap<>();
        final Map<String, String> extraTop = new LinkedHashMap<>(); // campi top-level extra
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Punto d'ingresso pubblico
    // ════════════════════════════════════════════════════════════════════════
    public void process(String jsonPath, String csvPath, String outputPath) throws Exception {

        // 1. Carica la mappa codice → colore dal CSV
        Map<String, String> colorMap = loadCsv(csvPath);
        System.out.printf("    Colori caricati dal CSV : %d%n", colorMap.size());

        // 2. Legge e parsifica il JSON jstree
        String raw = new String(Files.readAllBytes(Paths.get(jsonPath)), "UTF-8");
        List<Node> nodes = parseJson(raw);

        // 3. Applica i colori
        int matched = 0;
        for (Node n : nodes) {
            String color = colorMap.get(n.text);
            if (color == null) color = colorMap.get(n.id);
            if (color != null) {
                n.data.put("color", color);
                matched++;
            }
        }

        System.out.printf("    Nodi colorati           : %d / %d%n", matched, nodes.size());
        if (matched < colorMap.size()) {
            int notFound = colorMap.size() - matched;
            System.out.printf("    Codici CSV non trovati  : %d%n", notFound);
        }

        // 4. Scrive il JSON arricchito
        writeJson(nodes, outputPath);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Lettura CSV
    // ════════════════════════════════════════════════════════════════════════
    private Map<String, String> loadCsv(String csvPath) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(csvPath), java.nio.charset.StandardCharsets.UTF_8);
        if (lines.isEmpty()) throw new RuntimeException("Il file CSV e' vuoto: " + csvPath);

        // Rileva separatore dalla prima riga
        String header = lines.get(0);
        char sep = header.contains(";") ? ';' : ',';

        // Individua gli indici delle colonne "codice" e "colore"
        String[] headers = split(header, sep);
        int colCode  = -1;
        int colColor = -1;
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim().toLowerCase();
            if (h.equals("codice") || h.equals("code"))   colCode  = i;
            if (h.equals("colore") || h.equals("color"))  colColor = i;
        }
        if (colCode  < 0) throw new RuntimeException("Colonna 'codice'/'code' non trovata nel CSV.");
        if (colColor < 0) throw new RuntimeException("Colonna 'colore'/'color' non trovata nel CSV.");

        Map<String, String> map = new LinkedHashMap<>();
        for (int r = 1; r < lines.size(); r++) {
            String line = lines.get(r).trim();
            if (line.isEmpty()) continue;
            String[] cols = split(line, sep);
            if (cols.length <= Math.max(colCode, colColor)) continue;
            String code  = cols[colCode].trim();
            String color = cols[colColor].trim();
            if (!code.isEmpty() && !color.isEmpty()) {
                map.put(code, color);
            }
        }
        return map;
    }

    /** Splitta una riga rispettando le virgolette. */
    private String[] split(String line, char sep) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') { inQuote = !inQuote; }
            else if (c == sep && !inQuote) { parts.add(cur.toString()); cur.setLength(0); }
            else { cur.append(c); }
        }
        parts.add(cur.toString());
        return parts.toArray(new String[0]);
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
                    case "id":         n.id         = val; break;
                    case "parent":     n.parent     = val; break;
                    case "text":       n.text       = val; break;
                    case "hier_label": n.hierLabel  = val; break;
                    default:           n.extraTop.put(key, val); break;
                }
            } else if (c == '{') {
                int[] p = {i};
                if ("data".equals(key)) {
                    parseDataObject(json, p, n);
                } else {
                    skipObject(json, p);
                }
                i = p[0];
            } else {
                // numero o booleano: leggi fino al prossimo delimitatore
                int start = i;
                while (i < json.length() && json.charAt(i) != ',' && json.charAt(i) != '}') i++;
                n.extraTop.put(key, json.substring(start, i).trim());
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

            if (n.hierLabel != null) {
                sb.append(",\n    \"hier_label\": \"").append(esc(n.hierLabel)).append("\"");
            }

            // Campi top-level extra (es. booleani o numeri)
            for (Map.Entry<String, String> e : n.extraTop.entrySet()) {
                sb.append(",\n    \"").append(esc(e.getKey())).append("\": ").append(e.getValue());
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
