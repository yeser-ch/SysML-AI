package com.schroedingersblock.api;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Loads prompt templates from the plugin's prompts directory on the filesystem
 * and fills in the placeholders for each request.
 */
public class PromptLoader {

    private static final String PROMPT_DIR = "prompts";
    private static final String EXPERT_FILE = "expert_prompt.txt";
    private static final String BASIC_FILE  = "basic_prompt.txt";

    /**
     * Loads the correct prompt template based on mode,
     * then fills in elementName, elementType, ownerName, and exportedXML.
     *
     * @param baseDir     Plugin installation directory (contains the prompts/ subfolder)
     * @param mode        "EXPERT" or "BASIC" (case-insensitive)
     * @param elementName Human-readable name of the Cameo element
     * @param elementType Human-readable type of the Cameo element
     * @param ownerName   Name of the owning element, or "root"
     * @param exportedXml JSON export string from Cameo
     * @return            Fully resolved prompt string ready to send to the LLM
     */
    public static String buildPrompt(File baseDir,
                                     String mode,
                                     String elementName,
                                     String elementType,
                                     String ownerName,
                                     String exportedXml) {

        String fileName = mode.equalsIgnoreCase("EXPERT") ? EXPERT_FILE : BASIC_FILE;
        String template = loadTemplate(baseDir, fileName);
        return template
                .replace("{elementName}", elementName)
                .replace("{elementType}", elementType)
                .replace("{ownerName}",   ownerName)
                .replace("{exportedXML}", exportedXml != null ? exportedXml : "No XML available.");
    }

    private static String loadTemplate(File baseDir, String fileName) {
        File promptFile = new File(baseDir, PROMPT_DIR + File.separator + fileName);
        try {
            return Files.readString(promptFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompt file: " + promptFile.getAbsolutePath(), e);
        }
    }
}
