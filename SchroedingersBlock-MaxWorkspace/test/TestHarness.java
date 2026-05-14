package test;

import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.schroedingersblock.api.ApiKeyManager;
import com.schroedingersblock.api.DummyExplanationService;
import com.schroedingersblock.api.PromptLoader;
import com.schroedingersblock.ui.ExplanationDialog;
import java.util.Scanner;

public class TestHarness {

    static Element mockElement(String name, String type, String ownerName) {
        return new Element() {
            public String getHumanName() { return name; }
            public String getHumanType() { return type; }
            public Element getOwner() {
                if (ownerName == null) return null;
                return new Element() {
                    public String getHumanName() { return ownerName; }
                    public String getHumanType() { return "Package"; }
                    public Element getOwner() { return null; }
                };
            }
        };
    }

    static Scanner scanner = new Scanner(System.in);

    static void ensureApiKey() {
        if (ApiKeyManager.hasKey()) {
            System.out.println("API key loaded from " + ApiKeyManager.getKeyFilePath());
            return;
        }
        System.out.print("Enter your Gemini API key: ");
        String key = scanner.nextLine().trim();
        if (!key.isEmpty()) {
            ApiKeyManager.saveKey(key);
            System.out.println("Key saved to " + ApiKeyManager.getKeyFilePath());
        } else {
            System.out.println("No key entered — API call will fail.");
        }
    }

    static String chooseMode() {
        while (true) {
            System.out.print("Choose prompt mode [1] Basic  [2] Expert: ");
            String input = scanner.nextLine().trim();
            if (input.equals("1") || input.equalsIgnoreCase("basic"))  return "BASIC";
            if (input.equals("2") || input.equalsIgnoreCase("expert")) return "EXPERT";
            System.out.println("Please enter 1 or 2.");
        }
    }

    public static void main(String[] args) throws Exception {
        boolean showUi = args.length > 0 && args[0].equals("--ui");

        System.out.println("=== SchroedingersBlock Test Harness ===\n");

        ensureApiKey();
        String mode = chooseMode();

        System.out.println("\nRunning in " + mode + " mode.\n");

        // ── 1. PromptLoader ──────────────────────────────────────────────────
        System.out.println("[1] PromptLoader");
        try {
            String prompt = PromptLoader.buildPrompt(
                    mode, "PowerSupply", "Block", "ElectricalSystem", "<xml/>");
            System.out.println("    OK — first 120 chars: "
                    + prompt.substring(0, Math.min(120, prompt.length())).replace("\n", " "));
        } catch (Exception e) {
            System.out.println("    FAIL: " + e.getMessage());
        }

        // ── 2. Gemini API call ───────────────────────────────────────────────
        System.out.println("\n[2] DummyExplanationService — " + mode + " mode");
        Element el = mockElement("PowerSupply", "Block", "ElectricalSystem");
        DummyExplanationService service = new DummyExplanationService();
        System.out.println("    Calling Gemini...");
        String response = service.fetchExplanation(el, mode);
        System.out.println("    Response:\n" + response);

        // ── 3. UI (optional — pass --ui flag) ────────────────────────────────
        if (showUi) {
            System.out.println("\n[3] ExplanationDialog — opening UI window");
            javax.swing.SwingUtilities.invokeLater(
                    () -> new ExplanationDialog(mode, el).setVisible(true));
        } else {
            System.out.println("\n[3] ExplanationDialog — skipped (run with --ui to open)");
        }
    }
}
