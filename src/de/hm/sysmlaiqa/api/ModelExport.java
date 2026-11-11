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
    /** Exakte Tokenzahl laut Google (countTokens) für {@link #exactTokensModel}; -1 = noch nicht gezählt. */
    public volatile int exactTokens = -1;
    public volatile String exactTokensModel = "";

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
        if (targetFormat.equals(format)) return this;
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

    public boolean isV2() {
        return FORMAT_V2.equals(format);
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
