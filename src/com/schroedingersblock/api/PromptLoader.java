package com.schroedingersblock.api;

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
        String template = loadTemplate(fileName);

        return template
                .replace("{elementName}", elementName)
                .replace("{elementType}", elementType)
                .replace("{ownerName}",   ownerName)
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