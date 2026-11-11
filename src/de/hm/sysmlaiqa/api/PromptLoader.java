package de.hm.sysmlaiqa.api;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Baut die Nachrichten an das LLM.
 *
 * System-Prompt (FR9): ein Prompt je Format (SysML v1 JSON / SysML v2 Text). Er ist im Fenster sichtbar und
 * änderbar ("System prompt…"). Quelle in dieser Reihenfolge:
 *   1. eigene Fassung des Nutzers: Dokumente/SysML-AI-QA/prompts/System_Prompt[_v2].md (im Fenster bearbeitet)
 *   2. installierte Datei im Plugin-Ordner: prompts/System_Prompt[_v2].md
 *   3. eingebaute Standardfassung
 * Eine unveränderte Standardfassung einer älteren Version (die install.bat stehen lässt) zählt als Standard.
 *
 * Platzhalter: {idInstruction} wird bei "Cite element IDs" durch die ID-Anweisung ersetzt, sonst entfernt.
 * Die Antwortsprache wird nicht vorgegeben (NFR5) – sie ergibt sich aus der Frage.
 *
 * Erste Nachricht eines Chats: Modelldaten. Danach je Frage: optional das ausgewählte Element + Frage.
 * Wichtig für die Evaluation: Der System-Prompt ist eine Versuchsvariable. Jede verwendete Fassung wird mit
 * Prüfsumme archiviert (archive/prompts) und im Log referenziert.
 */
public class PromptLoader {

    private static final String PROMPT_DIR = "prompts";

    public static final String SOURCE_CUSTOM = "custom";
    public static final String SOURCE_INSTALLED = "installed";
    public static final String SOURCE_DEFAULT = "default";

    /** Standard für SysML v1 – inhaltlich identisch zu resources/System_Prompt.md. */
    static final String DEFAULT_V1 =
            "You are an assistant for SysML v1 models created in CATIA Magic.\n"
                    + "With the first user message you receive the model as a JSON export: a flat list of elements, "
                    + "each with id, name, type, stereotypes, owner_id, documentation, values and relations "
                    + "(outgoing_relations, routing_details, association_ends, connector_details). "
                    + "Relations reference other elements by id.\n"
                    + "Answer the user's questions about this model. Base your answers on the model data.\n"
                    + "{idInstruction}\n";

    /** Standard für SysML v2 – inhaltlich identisch zu resources/System_Prompt_v2.md. */
    static final String DEFAULT_V2 =
            "You are an assistant for SysML models.\n"
                    + "With the first user message you receive the model in SysML v2 textual notation. "
                    + "It was generated from a SysML v1 model created in CATIA Magic. "
                    + "Every element has its model id as a short name in angle brackets, e.g. part def <_19_0_abc> Battery. "
                    + "Elements without a direct SysML v2 counterpart are kept as comments that name the SysML v1 type.\n"
                    + "Answer the user's questions about this model. Base your answers on the model data.\n"
                    + "{idInstruction}\n";

    /** Standard für SysML-v2-Modelle aus CATIA – inhaltlich identisch zu resources/System_Prompt_v2_native.md. */
    static final String DEFAULT_V2_NATIVE =
            "You are an assistant for SysML v2 models created in CATIA Magic.\n"
                    + "With the first user message you receive the model in SysML v2 textual notation, as exported by CATIA Magic. "
                    + "If the model consists of several files, each file starts with a line \"// ===== File: <name> =====\".\n"
                    + "Answer the user's questions about this model. Base your answers on the model data.\n"
                    + "{idInstruction}\n";

    /** Wird angehängt (oder ersetzt {idInstruction}), wenn "Cite IDs" aktiv ist. */
    static final String ID_INSTRUCTION =
            "Whenever you mention a model element, add its id from the JSON directly after the name "
                    + "in the form [id:<id>], for example: Battery [id:_19_0_abc_123].";
    static final String ID_INSTRUCTION_V2_NATIVE =
            "Whenever you mention a model element, add its qualified name directly after the name "
                    + "in the form [id:<qualified name>], for example: Battery [id:RobotSystem::Battery].";
    static final String ID_INSTRUCTION_V2 =
            "Whenever you mention a model element, add its id (the short name in angle brackets) directly after the name "
                    + "in the form [id:<id>], for example: Battery [id:_19_0_abc_123].";

    /** Prüfsummen (normalisiert) früher ausgelieferter Standardfassungen – gelten als "nicht geändert". */
    private static final Set<String> LEGACY_DEFAULTS = new HashSet<>(Arrays.asList(
            "You are an assistant for SysML v1 models created in CATIA Magic.\n"
                    + "With the first user message you receive the model as a JSON export: a flat list of elements, each with id, name, type, stereotypes, owner_id, documentation, values and relations (outgoing_relations, routing_details, association_ends, connector_details). Relations reference other elements by id.\n"
                    + "Answer the user's questions about this model. Base your answers on the model data.\n"
                    + "Answer in the language of the question.\n"
                    + "{idInstruction}",
            "You are an assistant for SysML v1 models created in CATIA Magic.\n"
                    + "With the first user message you receive the model as a JSON export: a flat list of elements, each with id, name, type, stereotypes, owner_id, documentation, values and relations (outgoing_relations, routing_details, association_ends, connector_details). Relations reference other elements by id.\n"
                    + "Answer the user's questions about this model. Base your answers on the model data.\n"
                    + "Answer in the language of the question.\n"
                    + "{answerStyle}\n"
                    + "{idInstruction}"));

    /** Ergebnis: Vorlage (mit Platzhaltern) und woher sie stammt. */
    public static class Template {
        public final String text;
        public final String source;   // custom / installed / default
        public final File file;       // null bei eingebauter Fassung

        Template(String text, String source, File file) {
            this.text = text;
            this.source = source;
            this.file = file;
        }
    }

    public static String fileName(String format) {
        if (ModelExport.FORMAT_V2_NATIVE.equals(format)) return "System_Prompt_v2_native.md";
        return ModelExport.FORMAT_V2.equals(format) ? "System_Prompt_v2.md" : "System_Prompt.md";
    }

    /** Ordner für die im Fenster bearbeiteten Prompts (ohne Administratorrechte beschreibbar). */
    public static File customDir() {
        return new File(EvaluationLog.getBaseDir(), PROMPT_DIR);
    }

    public static File customFile(String format) {
        return new File(customDir(), fileName(format));
    }

    public static String builtinDefault(String format) {
        if (ModelExport.FORMAT_V2_NATIVE.equals(format)) return DEFAULT_V2_NATIVE;
        return ModelExport.FORMAT_V2.equals(format) ? DEFAULT_V2 : DEFAULT_V1;
    }

    /** Aktuelle Vorlage (noch mit Platzhaltern) – das, was der Nutzer im Fenster sieht und bearbeitet. */
    public static Template loadTemplate(File baseDir, String format) {
        File custom = customFile(format);
        String text = read(custom);
        if (text != null) return new Template(text, SOURCE_CUSTOM, custom);
        File installed = new File(baseDir, PROMPT_DIR + File.separator + fileName(format));
        text = read(installed);
        // Unveränderte Standardfassung (aktuell oder aus einer älteren Version) zählt als "default"
        if (text != null && !LEGACY_DEFAULTS.contains(normalize(text))
                && !normalize(text).equals(normalize(builtinDefault(format)))) {
            return new Template(text, SOURCE_INSTALLED, installed);
        }
        return new Template(builtinDefault(format), SOURCE_DEFAULT, null);
    }

    /** Speichert die im Fenster bearbeitete Fassung. */
    public static void saveCustom(String format, String text) throws IOException {
        File f = customFile(format);
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), text, StandardCharsets.UTF_8);
    }

    /** Eigene Fassung löschen -> wieder installierte bzw. Standardfassung. */
    public static boolean resetCustom(String format) {
        File f = customFile(format);
        return !f.exists() || f.delete();
    }

    /** Fertiger System-Prompt für SysML v1 (für ältere Aufrufer). */
    public static String buildSystemPrompt(File baseDir, boolean requireIds) {
        return buildSystemPrompt(baseDir, requireIds, ModelExport.FORMAT_V1);
    }

    /** Fertiger System-Prompt, genau so, wie er an die KI geht. */
    public static String buildSystemPrompt(File baseDir, boolean requireIds, String format) {
        return fill(loadTemplate(baseDir, format).text, requireIds, format);
    }

    /** Platzhalter ersetzen. */
    public static String fill(String template, boolean requireIds, String format) {
        // Ältere Fassungen enthalten noch {answerStyle}: samt Zeile entfernen
        String result = template.replace("\r\n", "\n")
                .replaceAll("(?m)^[ \\t]*\\{answerStyle\\}[ \\t]*\\n?", "")
                .replace("{answerStyle}", "");
        String ids = !requireIds ? "" : ModelExport.FORMAT_V2_NATIVE.equals(format) ? ID_INSTRUCTION_V2_NATIVE
                : ModelExport.FORMAT_V2.equals(format) ? ID_INSTRUCTION_V2 : ID_INSTRUCTION;
        if (result.contains("{idInstruction}")) {
            result = result.replace("{idInstruction}", ids);
        } else if (requireIds) {
            result = result.stripTrailing() + "\n" + ids;
        }
        return result.stripTrailing();
    }

    /**
     * Nachricht mit den Modelldaten. Steht immer als ERSTE Nachricht im Chat –
     * entweder direkt in der Anfrage (inline) oder im Gemini-Cache (einmal hochgeladen).
     */
    public static String buildModelMessage(String modelContent, String format) {
        String head = ModelExport.FORMAT_V2_NATIVE.equals(format) ? "# Model data (SysML v2 textual notation, exported from CATIA)\n"
                : ModelExport.FORMAT_V2.equals(format) ? "# Model data (SysML v2 textual notation)\n" : "# Model data (JSON export)\n";
        return head + (modelContent != null ? modelContent : "No model data available.");
    }

    public static String buildModelMessage(String modelJson) {
        return buildModelMessage(modelJson, ModelExport.FORMAT_V1);
    }

    /**
     * Nachricht mit der Frage. Das ausgewählte Element wird nur bei der ersten Frage eines Chats genannt –
     * und nur, wenn "Mention selected element" an ist (focusInfo != null).
     */
    public static String buildQuestionMessage(String focusInfo, String question, boolean firstInChat) {
        StringBuilder sb = new StringBuilder();
        if (firstInChat && focusInfo != null && !focusInfo.isBlank()) {
            sb.append("# Currently selected element\n").append(focusInfo).append("\n\n");
        }
        sb.append("# Question\n").append(question);
        return sb.toString();
    }

    private static String read(File f) {
        if (f == null || !f.isFile()) return null;
        try {
            return Files.readString(f.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static String normalize(String s) {
        return s.replace("﻿", "").replace("\r\n", "\n").trim();
    }
}
