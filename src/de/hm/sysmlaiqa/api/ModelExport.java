package de.hm.sysmlaiqa.api;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ein exportiertes Modell plus Kennzahlen für die Auswertung.
 *
 * Zwei Darstellungen desselben Modells:
 * - {@link #json}: der SysML-v1-Export aus CATIA (Quelle, auch für Schnellfragen und ID-Check)
 * - {@link #content}: was tatsächlich an die KI geht – je nach Format der JSON-Export (v1) oder die
 *   daraus erzeugte SysML-v2-Textnotation (v2). Prüfsumme, Größe und Tokens beziehen sich auf {@link #content}.
 *
 * - sha:            Prüfsumme des übertragenen Inhalts -> gleiche Prüfsumme = exakt gleiche Modelldaten
 * - elementCount:   Anzahl exportierter Elemente
 * - relationCount:  Beziehungen (outgoing_relations + Flüsse/Transitionen + Assoziationen + Konnektoren)
 * - knownIds:       alle IDs, die irgendwo im Export vorkommen (für den ID-Check)
 */
public class ModelExport {

    public static final String FORMAT_V1 = "sysml_v1_json";
    public static final String FORMAT_V2 = "sysml_v2_text";
    /** SysML-v2-Modell aus CATIA: Textnotation, die CATIA selbst exportiert (File → Export To → SysML v2 Textual Notation). */
    public static final String FORMAT_V2_NATIVE = "sysml_v2_native";

    private static final Pattern ELEMENT = Pattern.compile("\\{\"id\":\"([^\"]*)\",\"name\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ANY_ID = Pattern.compile("\"[a-z_]*id\"\\s*:\\s*\"([^\"]+)\"");

    /** Datei mit dem übertragenen Inhalt (.json bzw. .sysml). */
    public final File file;
    /** SysML-v1-Export (JSON) – Quelle. */
    public final File jsonFile;
    public final String json;
    /** Exakt der Text, der als Modelldaten an die KI geht. */
    public final String content;
    public final String format;
    public final String sha;
    public final int elementCount;
    public final int relationCount;
    public final Set<String> knownIds = new HashSet<>();
    public final Map<String, String> namesById = new HashMap<>();

    /** Umfang für Anzeige und Log, z. B. "whole model" oder "2 packages: Structure, Requirements". */
    public String scopeDetail = "";
    /** Nur bei SysML v2 aus CATIA: aus dem Text gelesener Index (für Schnellfragen); sonst null. */
    public ModelIndex index;
    /** Exakte Tokenzahl laut Google (countTokens) für {@link #exactTokensModel}; -1 = noch nicht gezählt. */
    public volatile int exactTokens = -1;
    public volatile String exactTokensModel = "";

    /** SysML v2 aus CATIA: Kennzahlen kommen aus dem Text (siehe SysmlV2Reader), es gibt keinen JSON-Export. */
    private ModelExport(File file, String content, SysmlV2Reader.Result read) {
        this.jsonFile = file;
        this.json = "";
        this.file = file;
        this.content = content;
        this.format = FORMAT_V2_NATIVE;
        this.sha = Hashing.sha256Short(content);
        this.elementCount = read.declarations;
        this.relationCount = read.relations;
        this.knownIds.addAll(read.names);   // ID-Check: die KI nennt qualifizierte Namen [id:Paket::Element]
        this.index = read.index;
    }

    /**
     * SysML v2 aus CATIA: liest die exportierten .sysml-Dateien (eine oder mehrere) und fasst sie zu einem Text zusammen.
     * Die Zusammenfassung wird im Export-Ordner gespeichert (Archiv, Show request).
     */
    public static ModelExport fromSysmlFiles(java.util.List<File> files, File outDir) throws IOException {
        StringBuilder sb = new StringBuilder();
        java.util.List<File> sorted = new java.util.ArrayList<>(files);
        sorted.sort(java.util.Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File f : sorted) {
            if (sorted.size() > 1) sb.append("// ===== File: ").append(f.getName()).append(" =====\n");
            String t = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            if (t.startsWith("\uFEFF")) t = t.substring(1);
            sb.append(t.replace("\r\n", "\n"));
            if (!t.endsWith("\n")) sb.append('\n');
            if (sorted.size() > 1) sb.append('\n');
        }
        String content = sb.toString();
        outDir.mkdirs();
        File out = new File(outDir, "export_sysml2_" + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".sysml");
        Files.writeString(out.toPath(), content, StandardCharsets.UTF_8);
        ModelExport ex = new ModelExport(out, content, SysmlV2Reader.read(content));
        StringBuilder detail = new StringBuilder("SysML v2 files from CATIA: ");
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        for (int i = 0; i < sorted.size(); i++) {
            File f = sorted.get(i);
            detail.append(i > 0 ? ", " : "").append(f.getName()).append(" (")
                    .append(java.time.Instant.ofEpochMilli(f.lastModified()).atZone(java.time.ZoneId.systemDefault()).format(fmt))
                    .append(')');
        }
        ex.scopeDetail = detail.toString();
        return ex;
    }

    private ModelExport(File jsonFile, String json, File file, String content, String format) {
        this.jsonFile = jsonFile;
        this.json = json;
        this.file = file;
        this.content = content;
        this.format = format;
        this.sha = Hashing.sha256Short(content);

        Matcher m = ELEMENT.matcher(json);
        int count = 0;
        while (m.find()) {
            count++;
            namesById.put(m.group(1), m.group(2));
        }
        this.elementCount = count;

        Matcher idm = ANY_ID.matcher(json);
        while (idm.find()) knownIds.add(idm.group(1));

        this.relationCount = count(json, "\"relation_type\"") + count(json, "\"routing_details\"")
                + count(json, "\"association_ends\"") + count(json, "\"connector_details\"");
    }

    /** SysML v1: der JSON-Export geht unverändert an die KI. */
    public static ModelExport fromFile(File f) throws IOException {
        String json = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        return new ModelExport(f, json, f, json, FORMAT_V1);
    }

    /** Liefert dasselbe Modell im gewünschten Format (SysML v2: Textdatei neben dem JSON-Export). */
    public ModelExport inFormat(String targetFormat) throws IOException {
        if (targetFormat.equals(format) || isNative()) return this;   // SysML v2 aus CATIA lässt sich nicht umwandeln
        ModelExport r;
        if (FORMAT_V2.equals(targetFormat)) {
            String text = SysmlV2Writer.convert(json, "SysML v2 textual notation, generated from a SysML v1 model (CATIA Magic)\n"
                    + "by SysML AI Q&A " + EvaluationRecord.PLUGIN_VERSION + ". Element ids are given as short names <...>.");
            File out = new File(jsonFile.getParentFile(), jsonFile.getName().replaceAll("\\.json$", "") + ".sysml");
            Files.writeString(out.toPath(), text, StandardCharsets.UTF_8);
            r = new ModelExport(jsonFile, json, out, text, FORMAT_V2);
        } else {
            r = new ModelExport(jsonFile, json, jsonFile, json, FORMAT_V1);
        }
        r.scopeDetail = scopeDetail;
        return r;
    }

    /** Modelldaten in SysML-v2-Textnotation (umgewandelt oder aus CATIA). */
    public boolean isV2() {
        return FORMAT_V2.equals(format) || FORMAT_V2_NATIVE.equals(format);
    }

    /** SysML-v2-Modell aus CATIA (Textexport von CATIA). */
    public boolean isNative() {
        return FORMAT_V2_NATIVE.equals(format);
    }

    /** Kurzbezeichnung für Anzeige und Hinweise. */
    public String formatLabel() {
        return isNative() ? "SysML v2 from CATIA" : FORMAT_V2.equals(format) ? "SysML v2 converted" : "SysML v1";
    }

    /** Dateiendung für Archiv und "Save". */
    public String extension() {
        return isV2() ? ".sysml" : ".json";
    }

    /** Grobe Schätzung: ca. 4 Zeichen pro Token. */
    public long approxTokens() {
        return content.length() / 4;
    }

    public double sizeKb() {
        return content.getBytes(StandardCharsets.UTF_8).length / 1024.0;
    }

    private static int count(String s, String sub) {
        int c = 0, i = 0;
        while ((i = s.indexOf(sub, i)) != -1) { c++; i += sub.length(); }
        return c;
    }
}
