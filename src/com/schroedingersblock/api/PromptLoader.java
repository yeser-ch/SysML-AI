package com.schroedingersblock.api;

<<<<<<< HEAD
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads prompt templates from the resources/prompts directory
 * and fills in the placeholders for each request.
 */
public class PromptLoader {

    private static final String PROMPT_DIR = "/prompts/";
    private static final String EXPERT_FILE = "expert_prompt.txt";
    private static final String BASIC_FILE  = "basic_prompt.txt";

    /**
     * Loads the correct prompt template based on mode,
     * then fills in elementName, elementType, ownerName, and exportedXML.
     *
     * @param mode        "EXPERT" or "BASIC" (case-insensitive)
     * @param elementName Human-readable name of the Cameo element
     * @param elementType Human-readable type of the Cameo element
     * @param ownerName   Name of the owning element, or "root"
     * @param exportedXml XML/XMI export string from Cameo
     * @return            Fully resolved prompt string ready to send to the LLM
     */
    public static String buildPrompt(String mode,
                                     String elementName,
                                     String elementType,
                                     String ownerName,
                                     String exportedXml) {

        String fileName = mode.equalsIgnoreCase("EXPERT") ? EXPERT_FILE : BASIC_FILE;
=======
import com.nomagic.magicdraw.plugins.Plugin;
import com.nomagic.magicdraw.plugins.PluginDescriptor;
import com.nomagic.magicdraw.plugins.PluginUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Lädt Prompt-Templates aus dem externen Plugin-Verzeichnis
 * und füllt die Platzhalter dynamisch für jede Anfrage aus.
 */
public class PromptLoader {

    private static final String PLUGIN_ID = "com.schroedingersblock.plugin";
    private static final String PROMPT_DIR = "prompts";
    private static final String EXPERT_FILE = "expert_prompt.txt";
    private static final String BASIC_FILE  = "basic_prompt.txt";

    public static String buildPrompt(String detailLevel, String elementName, String elementType, String ownerName, String exportedXml) {
        String fileName = "Expert".equalsIgnoreCase(detailLevel) ? EXPERT_FILE : BASIC_FILE;
>>>>>>> origin/main
        String template = loadTemplate(fileName);

        return template
                .replace("{elementName}", elementName)
                .replace("{elementType}", elementType)
                .replace("{ownerName}",   ownerName)
<<<<<<< HEAD
                .replace("{exportedXML}", exportedXml != null ? exportedXml : "No XML available.");
    }

    private static String loadTemplate(String fileName) {
        String path = PROMPT_DIR + fileName;
        try (InputStream is = PromptLoader.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("Prompt file not found on classpath: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompt file: " + path, e);
        }
    }
}
=======
                .replace("{exportedXML}", exportedXml != null ? exportedXml : "No JSON/XML available.");
    }

    private static String loadTemplate(String fileName) {
        File baseDir = getBaseDirectory();
        File promptFile = new File(baseDir, PROMPT_DIR + File.separator + fileName);

        if (!promptFile.exists()) {
            // AUSGABE AUF ENGLISCH GEÄNDERT
            return "⚠️ ERROR: Prompt file not found. Please create it here: " + promptFile.getAbsolutePath();
        }

        try {
            return Files.readString(promptFile.toPath());
        } catch (IOException e) {
            // AUSGABE AUF ENGLISCH GEÄNDERT
            return "⚠️ ERROR reading the prompt file: " + e.getMessage();
        }
    }

    /**
     * Ermittelt das Plugin-Verzeichnis (Identisch zur Logik im DataExtractionService)
     */
    private static File getBaseDirectory() {
        try {
            for (Plugin plugin : PluginUtils.getPlugins()) {
                PluginDescriptor descriptor = plugin.getDescriptor();
                if (descriptor != null && PLUGIN_ID.equals(descriptor.getID())) {
                    return descriptor.getPluginDirectory();
                }
            }
        } catch (Throwable t) {
            // Ignoriere Fehler
        }
        return new File(System.getProperty("user.dir"), "plugins" + File.separator + PLUGIN_ID);
    }
}
>>>>>>> origin/main
