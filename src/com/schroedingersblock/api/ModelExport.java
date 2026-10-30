package com.schroedingersblock.api;

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
 * - sha:            Prüfsumme des Exports -> gleiche Prüfsumme = exakt gleiche Modelldaten
 * - elementCount:   Anzahl exportierter Elemente
 * - relationCount:  Beziehungen (outgoing_relations + Flüsse/Transitionen + Assoziationen + Konnektoren)
 * - knownIds:       alle IDs, die irgendwo im Export vorkommen (für den ID-Check)
 */
public class ModelExport {

    private static final Pattern ELEMENT = Pattern.compile("\\{\"id\":\"([^\"]*)\",\"name\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ANY_ID = Pattern.compile("\"[a-z_]*id\"\\s*:\\s*\"([^\"]+)\"");

    public final File file;
    public final String json;
    public final String sha;
    public final int elementCount;
    public final int relationCount;
    public final Set<String> knownIds = new HashSet<>();
    public final Map<String, String> namesById = new HashMap<>();

    private ModelExport(File file, String json) {
        this.file = file;
        this.json = json;
        this.sha = Hashing.sha256Short(json);

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

    public static ModelExport fromFile(File f) throws IOException {
        return new ModelExport(f, Files.readString(f.toPath(), StandardCharsets.UTF_8));
    }

    /** Grobe Schätzung: ca. 4 Zeichen pro Token. */
    public long approxTokens() {
        return json.length() / 4;
    }

    public double sizeKb() {
        return file.length() / 1024.0;
    }

    private static int count(String s, String sub) {
        int c = 0, i = 0;
        while ((i = s.indexOf(sub, i)) != -1) { c++; i += sub.length(); }
        return c;
    }
}
