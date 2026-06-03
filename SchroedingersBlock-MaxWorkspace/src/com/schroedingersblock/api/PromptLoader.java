package com.schroedingersblock.api;

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
        String template = loadTemplate(fileName);

        return template
                .replace("{elementName}", elementName)
                .replace("{elementType}", elementType)
                .replace("{ownerName}",   ownerName)
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
