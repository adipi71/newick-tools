import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * ╔══════════════════════════════════════════════════════════════╗
 * ║              Nexus Tree Tools  –  Main entry point           ║
 * ╠══════════════════════════════════════════════════════════════╣
 * ║                                                              ║
 * ║  Tre operazioni disponibili:                                 ║
 * ║                                                              ║
 * ║  nexus2json  →  NexusToJstree   (NEXUS  → JSON jstree)      ║
 * ║  label       →  JstreeLabeler   (JSON   → JSON + hier_label) ║
 * ║  json2nexus  →  JstreeToNexus   (JSON   → NEXUS annotato)   ║
 * ║  pipeline    →  tutte e tre in sequenza                      ║
 * ║                                                              ║
 * ╠══════════════════════════════════════════════════════════════╣
 * ║  UTILIZZO                                                    ║
 * ║                                                              ║
 * ║  Nessun argomento:                                           ║
 * ║    Esegue la pipeline completa sui file di default in        ║
 * ║    resources/ (WND_L2.nexus e Giuliano_colours.nexus)        ║
 * ║                                                              ║
 * ║  nexus2json <input.nexus> <output.json>                      ║
 * ║    Converte un file NEXUS in JSON jstree flat                ║
 * ║                                                              ║
 * ║  label <input.json> <output.json>                            ║
 * ║    Aggiunge label gerarchiche (0, 1, 1.1, 1.2, …)           ║
 * ║                                                              ║
 * ║  json2nexus <input.json> <output.nexus>                      ║
 * ║    Ricostruisce un file NEXUS dagli attributi JSON           ║
 * ║                                                              ║
 * ║  pipeline <input.nexus> [output-dir]                         ║
 * ║    nexus → json → json-labeled → nexus ricostruito           ║
 * ║    output-dir default: output/                               ║
 * ║                                                              ║
 * ║  help                                                        ║
 * ║    Mostra questo messaggio                                   ║
 * ╚══════════════════════════════════════════════════════════════╝
 *
 * Compilazione e avvio rapido:
 *
 *   javac src/*.java -d bin
 *   java -cp bin Main
 *   java -cp bin Main nexus2json resources/WND_L2.nexus output/wnd.json
 *   java -cp bin Main pipeline resources/WND_L2.nexus output/
 */
public class Main {

    // ── Directory radice del progetto (rilevata a runtime) ──────────────────
    private static final String PROJECT_ROOT = detectProjectRoot();

    // ── File di default nella cartella resources ─────────────────────────────
    private static final String[] DEFAULT_NEXUS_FILES = {
        "resources/WND_L2.nexus",
        "resources/Giuliano_colours.nexus"
    };

    // ════════════════════════════════════════════════════════════════════════
    //  Entry point
    // ════════════════════════════════════════════════════════════════════════
    public static void main(String[] args) throws Exception {

        // ── Nessun argomento: pipeline completa sui file default ─────────────
        if (args.length == 0) {
            printBanner();
            runDefaultPipeline();
            return;
        }

        String cmd = args[0].toLowerCase();

        switch (cmd) {

            // ── nexus2json ────────────────────────────────────────────────
            case "nexus2json": {
                requireArgs(args, 2, "nexus2json <input.nexus> <output.json>");
                String in  = resolveInput(args[1]);
                String out = args[2];
                ensureParentDir(out);
                printStep("NEXUS → JSON", in, out);
                new NexusToJstree().process(in, out);
                break;
            }

            // ── label ─────────────────────────────────────────────────────
            case "label": {
                requireArgs(args, 2, "label <input.json> <output.json>");
                String in  = resolveInput(args[1]);
                String out = args[2];
                ensureParentDir(out);
                printStep("JSON → JSON+labels", in, out);
                new JstreeLabeler().process(in, out);
                break;
            }

            // ── json2nexus ────────────────────────────────────────────────
            case "json2nexus": {
                requireArgs(args, 2, "json2nexus <input.json> <output.nexus>");
                String in  = resolveInput(args[1]);
                String out = args[2];
                ensureParentDir(out);
                printStep("JSON → NEXUS", in, out);
                new JstreeToNexus().process(in, out);
                break;
            }

            // ── pipeline ──────────────────────────────────────────────────
            case "pipeline": {
                requireArgs(args, 1, "pipeline <input.nexus> [output-dir]");
                String in      = resolveInput(args[1]);
                String outDir  = (args.length >= 3) ? args[2] : "output";
                ensureDir(outDir);
                runPipeline(in, outDir);
                break;
            }

            // ── help ──────────────────────────────────────────────────────
            case "help":
            case "--help":
            case "-h": {
                printHelp();
                break;
            }

            default: {
                System.err.println("[ERRORE] Comando sconosciuto: " + args[0]);
                System.err.println("Usa 'help' per vedere i comandi disponibili.");
                System.exit(1);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Pipeline completa su tutti i file default in resources/
    // ════════════════════════════════════════════════════════════════════════
    static void runDefaultPipeline() throws Exception {
        System.out.println("Nessun argomento fornito: avvio pipeline su file default in resources/");
        System.out.println();

        boolean anyFound = false;
        for (String rel : DEFAULT_NEXUS_FILES) {
            String nexusPath = resolvePath(rel);
            if (!Files.exists(Paths.get(nexusPath))) {
                System.out.println("[SKIP] File non trovato: " + nexusPath);
                continue;
            }
            anyFound = true;
            ensureDir("output");
            runPipeline(nexusPath, "output");
        }

        if (!anyFound) {
            System.out.println();
            System.out.println("[ATTENZIONE] Nessun file trovato in resources/");
            System.out.println("  Copia i tuoi file .nexus nella cartella resources/");
            System.out.println("  oppure usa: java -cp bin Main pipeline <input.nexus> [output-dir]");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Pipeline:  NEXUS  →  JSON  →  JSON+labels  →  NEXUS ricostruito
    // ════════════════════════════════════════════════════════════════════════
    static void runPipeline(String nexusIn, String outDir) throws Exception {
        String baseName = baseName(nexusIn);

        String jsonOut      = outDir + "/" + baseName + ".json";
        String labeledOut   = outDir + "/" + baseName + "_labeled.json";
        String nexusOut     = outDir + "/" + baseName + "_reconstructed.nexus";

        System.out.println("┌─────────────────────────────────────────────┐");
        System.out.println("│  PIPELINE: " + padRight(baseName, 33) + "│");
        System.out.println("└─────────────────────────────────────────────┘");

        // Passo 1
        printStep("1/3  NEXUS → JSON jstree", nexusIn, jsonOut);
        new NexusToJstree().process(nexusIn, jsonOut);

        // Passo 2
        printStep("2/3  JSON → JSON + hier_label", jsonOut, labeledOut);
        new JstreeLabeler().process(jsonOut, labeledOut);

        // Passo 3
        printStep("3/3  JSON → NEXUS ricostruito", labeledOut, nexusOut);
        new JstreeToNexus().process(labeledOut, nexusOut);

        System.out.println();
        System.out.println("  Output generati:");
        printFileInfo(jsonOut);
        printFileInfo(labeledOut);
        printFileInfo(nexusOut);
        System.out.println();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Utility
    // ════════════════════════════════════════════════════════════════════════

    /** Risolve un path rispettando la root del progetto se il file non esiste as-is. */
    static String resolveInput(String path) {
        if (Files.exists(Paths.get(path))) return path;
        String resolved = resolvePath(path);
        if (Files.exists(Paths.get(resolved))) return resolved;
        return path; // restituisce comunque: l'errore verra' dato dal processo
    }

    /** Compone un path assoluto a partire dalla project root. */
    static String resolvePath(String relative) {
        return PROJECT_ROOT.isEmpty() ? relative : PROJECT_ROOT + "/" + relative;
    }

    /** Crea le directory parent di un file se non esistono. */
    static void ensureParentDir(String filePath) throws IOException {
        Path p = Paths.get(filePath).getParent();
        if (p != null) Files.createDirectories(p);
    }

    /** Crea una directory se non esiste. */
    static void ensureDir(String dir) throws IOException {
        Files.createDirectories(Paths.get(dir));
    }

    /** Estrae il nome base senza estensione da un path. */
    static String baseName(String path) {
        String name = Paths.get(path).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Verifica che ci siano almeno minArgs argomenti (oltre al comando). */
    static void requireArgs(String[] args, int minArgs, String usage) {
        if (args.length <= minArgs) {
            System.err.println("[ERRORE] Argomenti insufficienti.");
            System.err.println("  Uso: java -cp bin Main " + usage);
            System.exit(1);
        }
    }

    /** Stampa una riga descrittiva per ogni passo. */
    static void printStep(String label, String in, String out) {
        System.out.printf("  %-30s%n    IN  : %s%n    OUT : %s%n",
                label, in, out);
    }

    /** Stampa dimensione e nome di un file di output. */
    static void printFileInfo(String path) {
        try {
            long size = Files.size(Paths.get(path));
            System.out.printf("    %-50s  (%,d byte)%n", path, size);
        } catch (IOException e) {
            System.out.printf("    %-50s  (non trovato)%n", path);
        }
    }

    static String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }

    /**
     * Rileva la directory root del progetto cercando la cartella 'resources'
     * a partire dalla working directory corrente.
     */
    static String detectProjectRoot() {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        // La working directory contiene gia' resources?
        if (Files.isDirectory(cwd.resolve("resources"))) return cwd.toString();
        // Salgo di un livello (utile se si lancia da src/ o bin/)
        Path parent = cwd.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("resources"))) {
            return parent.toString();
        }
        return ""; // fallback: path relativi as-is
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Banner e help
    // ════════════════════════════════════════════════════════════════════════
    static void printBanner() {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║         Nexus Tree Tools  v1.0               ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();
    }

    static void printHelp() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                  Nexus Tree Tools  –  Aiuto                 ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║                                                              ║");
        System.out.println("║  COMPILAZIONE                                                ║");
        System.out.println("║    javac src/*.java -d bin                                   ║");
        System.out.println("║                                                              ║");
        System.out.println("║  UTILIZZO                                                    ║");
        System.out.println("║                                                              ║");
        System.out.println("║  java -cp bin Main                                           ║");
        System.out.println("║    Pipeline completa sui file default in resources/           ║");
        System.out.println("║                                                              ║");
        System.out.println("║  java -cp bin Main nexus2json <in.nexus> <out.json>          ║");
        System.out.println("║    Converte NEXUS → JSON jstree (id, parent, data)           ║");
        System.out.println("║                                                              ║");
        System.out.println("║  java -cp bin Main label <in.json> <out.json>                ║");
        System.out.println("║    Aggiunge label gerarchiche: 0 / 1 / 1.1 / 1.2 …          ║");
        System.out.println("║                                                              ║");
        System.out.println("║  java -cp bin Main json2nexus <in.json> <out.nexus>          ║");
        System.out.println("║    Ricostruisce NEXUS dagli attributi JSON                   ║");
        System.out.println("║                                                              ║");
        System.out.println("║  java -cp bin Main pipeline <in.nexus> [output-dir]          ║");
        System.out.println("║    nexus → json → json+labels → nexus ricostruito            ║");
        System.out.println("║    output-dir default: output/                               ║");
        System.out.println("║                                                              ║");
        System.out.println("║  MAPPING ANNOTAZIONI JSON → NEXUS                            ║");
        System.out.println("║    data.color           →  [&!color=#rrggbb]                 ║");
        System.out.println("║    data.hilight         →  [&!hilight={n,v,#color}]          ║");
        System.out.println("║    data.label           →  [&label=N]                        ║");
        System.out.println("║    data.annotation_name →  [&!name=\"val\"]                    ║");
        System.out.println("║    data.branch_length   →  :valore                           ║");
        System.out.println("║    data.hier_label      →  ignorato                          ║");
        System.out.println("║                                                              ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
