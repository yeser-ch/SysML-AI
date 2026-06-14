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
    // (Niko) 4er-Prompt-Struktur: Modus (Basic/Expert) x Ansicht (Element/Model View), als LLM-optimierte .md

    /**
     * Loads the correct prompt template based on mode,
     * then fills in elementName, elementType, ownerName, and exportedXML.
     *
     * @param baseDir     Plugin installation directory (contains the prompts/ subfolder)
     * @param detailLevel "EXPERT" or "BASIC" (case-insensitive)
     * @param view        "Model View" or "Element View" (anything != "Model View" = Element View)
     * @param elementName Human-readable name of the Cameo element
     * @param elementType Human-readable type of the Cameo element
     * @param ownerName   Name of the owning element, or "root"
     * @param exportedXml JSON export string from Cameo
     * @return            Fully resolved prompt string ready to send to the LLM
     */
    public static String buildPrompt(File baseDir,
                                     String detailLevel,
                                     String view,
                                     String elementName,
                                     String elementType,
                                     String ownerName,
                                     String exportedXml) {

        // (Niko) Datei je Modus x Ansicht: z.B. Expert_ElementView.md
        boolean expert    = "EXPERT".equalsIgnoreCase(detailLevel);
        boolean modelView = "Model View".equalsIgnoreCase(view); // alles andere = Element View (konsistent mit ExplanationDialog)
        String fileName = (expert ? "Expert" : "Basic") + "_" + (modelView ? "ModelView" : "ElementView") + ".md";
        String template = loadTemplate(baseDir, fileName);
        return template
                .replace("{elementName}", elementName)
                .replace("{elementType}", elementType)
                .replace("{ownerName}",   ownerName)
                .replace("{exportedJson}", exportedXml != null ? exportedXml : "No model data available."); // (Niko) JSON, nicht XML
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
