/**
 * Frage-Fenster: Einstellungen, Schnellfragen, Chat und freie Frage-Eingabe auf EINER Oberfläche.
 *
 * Ablauf:
 * 1. Der Nutzer wählt Context (ganzes Modell, ausgewählte Pakete/Elemente oder Element mit Umgebung) und Format
 *    und klickt "Export model". Erst dann wird aus CATIA exportiert und im gewählten Format bereitgestellt
 *    (SysML v1 JSON oder SysML v2 Text). Größe in Elementen und Tokens wird angezeigt. Wird der Context danach
 *    geändert, muss neu exportiert werden, bevor wieder gefragt werden kann.
 * 2. Der Nutzer tippt eine Frage oder wählt eine Schnellfrage und drückt "Send".
 *    Standard: Das Modell wird mit der ersten Frage EINMAL hochgeladen (Gemini-Cache); danach geht nur die Frage raus.
 *    "Resend model" exportiert neu aus CATIA und lädt bei der nächsten Frage neu hoch.
 *    Optional wird jede Frage mehrfach gestellt (Repetitions) und auf erfundene Element-IDs geprüft.
 * 3. "Run test suite…" arbeitet eine CSV mit Testfällen automatisch ab (auch für mehrere Modelle).
 * 4. Jede Antwort wird mit dem kompletten Versuchsaufbau protokolliert (siehe EvaluationLog).
 * 5. "Show request…" zeigt genau, was gesendet wird; "System prompt…" macht den System-Prompt sichtbar und änderbar.
 *    Eine laufende Anfrage lässt sich mit "Stop" abbrechen.
 */

package de.hm.sysmlaiqa.ui;

import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import de.hm.sysmlaiqa.api.AskSettings;
import de.hm.sysmlaiqa.api.Evaluation;
import de.hm.sysmlaiqa.api.EvaluationLog;
import de.hm.sysmlaiqa.api.EvaluationRecord;
import de.hm.sysmlaiqa.api.ExplanationService;
import de.hm.sysmlaiqa.api.GeminiExplanationService;
import de.hm.sysmlaiqa.api.ModelExport;
import de.hm.sysmlaiqa.api.ModelIndex;
import de.hm.sysmlaiqa.api.QuickQuestions;
import de.hm.sysmlaiqa.api.TestSuite;
import de.hm.sysmlaiqa.core.SysmlAiQaPlugin;
import de.hm.sysmlaiqa.data_extraction.DataExtractionService;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.prefs.Preferences;

public class ExplanationDialog extends JDialog {

    private static final String SCOPE_FULL = "Whole model";
    private static final String SCOPE_SELECTION = "Selected packages / elements";
    private static final String SCOPE_FOCUS = "Selected element + surroundings";
    static final String FORMAT_V1_LABEL = "SysML v1 (JSON export)";
    static final String FORMAT_V2_LABEL = "SysML v2 (converted from v1)";
    static final String FORMAT_NATIVE_LABEL = "SysML v2 model (CATIA export)";

    private Element focusElement;          // angeklicktes/übernommenes Element (darf null sein)
    private JLabel focusLabel;
    private JButton exportBtn;
    private JButton useSelectionBtn;
    private String exportedKey = null;      // Context des aktuellen Exports (siehe exportKey())
    private JPanel quickPanel;
    private long quickSeed = System.nanoTime();
    private volatile ModelIndex modelIndex;
    private JComboBox<String> thinkingBox;
    private final GeminiExplanationService apiService = new GeminiExplanationService();
    private final Preferences prefs = Preferences.userNodeForPackage(SysmlAiQaPlugin.class);

    // Einstellungen
    private JComboBox<String> modelBox;
    private JComboBox<String> scopeBox;
    private JSpinner temperatureSpinner;
    private JSpinner maxTokensSpinner;
    private JSpinner repetitionsSpinner;
    private JCheckBox groundingBox;
    private JCheckBox freshChatBox;
    private JCheckBox fallbackBox;
    private JCheckBox requireIdsBox;
    private JCheckBox cacheModelBox;
    private JLabel exportStatusLabel;
    private JButton suiteBtn;
    private JComboBox<String> formatBox;
    private JCheckBox mentionFocusBox;
    private JSpinner relationDepthSpinner;
    private JSpinner siblingDepthSpinner;
    private JButton chooseBtn;
    private JPanel depthPanel;
    private JPanel v2Panel;                    // nur bei "SysML v2 model (CATIA export)": Files… / Export in CATIA…
    private Object lastFormat = FORMAT_V1_LABEL;
    private List<Element> selectedRoots = new ArrayList<>();
    private Object lastScope = SCOPE_FULL;     // Umfang des letzten erfolgreichen Exports
    private Object lastScopeChoice = SCOPE_FULL; // zuletzt gewählter (gültiger) Eintrag im Context-Feld

    // Chat
    private JPanel chatPanel;
    private JScrollPane scrollPane;
    private JTextArea inputArea;
    private JButton sendBtn;

    // Zustand
    private volatile ModelExport export;       // im gewählten Format (das geht an die KI)
    private volatile ModelExport baseExport;   // SysML-v1-Export aus CATIA (Quelle)
    private boolean requestRunning = false;    // Einzelfrage läuft -> "Send" wird zu "Stop"
    private int tokenCountGeneration = 0;
    private boolean busy = false;
    private boolean suiteRunning = false;
    private volatile boolean cancelRequested = false;
    private boolean loading = false;

    /**
     * @param focusElement angeklicktes bzw. ausgewähltes Element (darf null sein). Es ist im Context-Feld als
     *                     "Selected element + surroundings" wählbar und kann im Fenster mit "Take CATIA selection" ersetzt werden.
     */
    public ExplanationDialog(Element focusElement) {
        // Kein "always on top" mehr: Das Fenster gehört zum CATIA-Hauptfenster. Es bleibt dadurch über CATIA,
        // wird mit CATIA minimiert und liegt NICHT über anderen Programmen. Nicht modal: CATIA bleibt bedienbar.
        super(catiaMainFrame(), Dialog.ModalityType.MODELESS);
        this.focusElement = focusElement;

        setTitle(SysmlAiQaPlugin.DISPLAY_NAME);
        setSize(800, 900);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        buildUI();
        loadSettings();
        addMessage("System", "Choose the <b>context</b> and the <b>format</b> above, then click <b>Export model</b>. "
                + "The model is only exported from CATIA when you click the button.");
    }

    /** CATIA-Hauptfenster als Besitzer der Plugin-Fenster (null, falls nicht verfügbar – dann normales Fenster). */
    static Frame catiaMainFrame() {
        try {
            return Application.getInstance().getMainFrame();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void dispose() {
        cancelRequested = true; // laufenden Testlauf beim Schließen beenden
        apiService.discardUploadedModel(); // Upload bei Google löschen (läuft sonst nach 60 min ab)
        super.dispose();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UI-Aufbau
    // ═══════════════════════════════════════════════════════════════════════

    private void buildUI() {
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
        top.add(createHeader());
        top.add(Box.createVerticalStrut(6));
        top.add(createSettingsPanel());
        top.add(Box.createVerticalStrut(6));
        top.add(createQuickQuestionPanel());
        add(top, BorderLayout.NORTH);

        chatPanel = new ScrollablePanel();
        chatPanel.setLayout(new BoxLayout(chatPanel, BoxLayout.Y_AXIS));
        scrollPane = new JScrollPane(chatPanel);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        JPanel center = new JPanel(new BorderLayout());
        center.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        center.add(scrollPane, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        add(createInputPanel(), BorderLayout.SOUTH);
    }

    private JComponent createHeader() {
        JPanel header = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Ask the AI about your SysML model");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        header.add(title, BorderLayout.NORTH);

        focusLabel = new JLabel(focusText());
        focusLabel.setFont(focusLabel.getFont().deriveFont(Font.ITALIC, 12f));
        focusLabel.setForeground(Color.DARK_GRAY);
        header.add(focusLabel, BorderLayout.SOUTH);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        return header;
    }

    private String focusText() {
        return focusElement == null
                ? "No element selected. To ask about one element: select it in CATIA and click 'Take CATIA selection'."
                : "Selected element: " + getFocusName() + " [" + focusElement.getHumanType() + "]";
    }

    private JComponent createSettingsPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), "Settings",
                TitledBorder.LEFT, TitledBorder.TOP));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 5, 3, 5);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;

        // Zeile 0: KI-Modell + Denktiefe
        modelBox = new JComboBox<>();
        modelBox.setEditable(true);
        modelBox.setToolTipText("Gemini model ID. Pick one or type any model ID. 'Refresh' loads all models available for your API key.");
        modelBox.addActionListener(e -> saveSettings());
        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.setToolTipText("Load the list of models available for your API key.");
        refreshBtn.addActionListener(e -> refreshModels(refreshBtn));
        JPanel modelRow = new JPanel(new BorderLayout(4, 0));
        modelRow.add(modelBox, BorderLayout.CENTER);
        modelRow.add(refreshBtn, BorderLayout.EAST);

        // Zeile 1: Kontext (+ Auswahl) + Temperatur
        scopeBox = new JComboBox<>(new String[]{SCOPE_FULL, SCOPE_SELECTION, SCOPE_FOCUS});
        // Ohne ausgewähltes Element ist "Selected element + surroundings" grau, mit Erklärung beim Darüberfahren
        scopeBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                boolean unavailable = SCOPE_FOCUS.equals(value) && focusElement == null;
                super.getListCellRendererComponent(list, value, index, selected && !unavailable, focus && !unavailable);
                if (unavailable) {
                    setForeground(UIManager.getColor("Label.disabledForeground") != null
                            ? UIManager.getColor("Label.disabledForeground") : Color.GRAY);
                    setToolTipText("No element selected – select one in CATIA and click 'Take CATIA selection'.");
                } else {
                    setToolTipText(null);
                }
                return this;
            }
        });
        scopeBox.setToolTipText("<html>Which part of the model is sent to the AI.<br>'Whole model': everything except SysML/UML libraries.<br>"
                + "'Selected packages / elements': only what you choose (each with everything it contains).<br>"
                + "'Selected element + surroundings': the element, its owners, neighbours and related elements up to the chosen depth.</html>");
        chooseBtn = new JButton("Choose…");
        chooseBtn.setToolTipText("Choose the packages or elements that are sent to the AI.");
        chooseBtn.addActionListener(e -> {
            if (chooseSelection()) updateControls();
        });
        useSelectionBtn = new JButton("Take CATIA selection");
        useSelectionBtn.setToolTipText("<html>Takes the element currently selected in CATIA (containment tree, diagram or table row)<br>"
                + "as the selected element and switches the context to 'Selected element + surroundings'.</html>");
        useSelectionBtn.addActionListener(e -> useCurrentSelection());
        JPanel scopeButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        scopeButtons.add(chooseBtn);
        scopeButtons.add(useSelectionBtn);
        JPanel scopeRow = new JPanel(new BorderLayout(4, 0));
        scopeRow.add(scopeBox, BorderLayout.CENTER);
        scopeRow.add(scopeButtons, BorderLayout.EAST);
        temperatureSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 2.0, 0.1));
        temperatureSpinner.setToolTipText("0 = most deterministic (recommended for evaluation). Higher = more varied answers.");
        temperatureSpinner.addChangeListener(e -> saveSettings());
        thinkingBox = new JComboBox<>(new String[]{"Model default", "Minimal", "Low", "Medium", "High"});
        thinkingBox.setToolTipText("<html>How much the AI \"thinks\" before answering. Less thinking = faster answers,<br>"
                + "but possibly worse reasoning about indirect links. Keep it constant within an experiment<br>"
                + "(it is logged). Not every model supports every level – then the model default is used.</html>");
        thinkingBox.addActionListener(e -> saveSettings());
        addRow(p, c, 0, "AI model:", modelRow, "Thinking:", thinkingBox);
        addRow(p, c, 1, "Context:", scopeRow, "Temperature:", temperatureSpinner);

        // Zeile 2: Format + Kontexttiefe + Max Tokens
        formatBox = new JComboBox<>(new String[]{FORMAT_V1_LABEL, FORMAT_V2_LABEL, FORMAT_NATIVE_LABEL});
        formatBox.setToolTipText("<html>Which model is sent and how.<br>"
                + "<b>SysML v1 (JSON export)</b>: a SysML v1 model, exported by the plugin.<br>"
                + "<b>SysML v2 (converted from v1)</b>: the same SysML v1 model written in SysML v2 textual notation.<br>"
                + "<b>SysML v2 model (CATIA export)</b>: a SysML v2 project – the .sysml files CATIA exports<br>"
                + "(File → Export To → SysML v2 Textual Notation). Each format has its own system prompt.</html>");
        relationDepthSpinner = new JSpinner(new SpinnerNumberModel(DataExtractionService.DEFAULT_RELATION_DEPTH, 0, 10, 1));
        relationDepthSpinner.setToolTipText("How many relation steps from the selected element are followed (e.g. satisfy → block → its parts).");
        siblingDepthSpinner = new JSpinner(new SpinnerNumberModel(DataExtractionService.DEFAULT_SIBLING_DEPTH, 0, 5, 1));
        siblingDepthSpinner.setToolTipText("Over how many owner levels neighbouring elements are collected (0 = no neighbours).");
        depthPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        depthPanel.add(new JLabel("Depth: relations"));
        depthPanel.add(relationDepthSpinner);
        depthPanel.add(new JLabel("neighbours"));
        depthPanel.add(siblingDepthSpinner);
        JButton v2FilesBtn = new JButton("Files…");
        v2FilesBtn.setToolTipText("Choose the .sysml files (or the folder) that CATIA exported from your SysML v2 project.");
        v2FilesBtn.addActionListener(e -> {
            if (!busy) loadSysmlV2(true);
        });
        JButton v2ExportBtn = new JButton("Export in CATIA…");
        v2ExportBtn.setToolTipText("Opens CATIA's own export: File → Export To → SysML v2 Textual Notation.");
        v2ExportBtn.addActionListener(e -> openCatiaV2Export());
        v2Panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        v2Panel.add(v2FilesBtn);
        v2Panel.add(v2ExportBtn);
        JPanel extra = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        extra.add(depthPanel);
        extra.add(v2Panel);
        JPanel formatRow = new JPanel(new BorderLayout(8, 0));
        formatRow.add(formatBox, BorderLayout.WEST);
        formatRow.add(extra, BorderLayout.CENTER);

        // Zeile 3: Optionen
        groundingBox = new JCheckBox("Grounding");
        groundingBox.setToolTipText("Google Search grounding. Off for evaluation: answers should come from the model only. "
                + "Not every model supports it on the free tier.");
        freshChatBox = new JCheckBox("New chat per question");
        freshChatBox.setToolTipText("On: every question is answered independently (recommended for tests). Off: follow-up questions keep the conversation.");
        fallbackBox = new JCheckBox("Fallback model");
        fallbackBox.setToolTipText("If the chosen model is overloaded, automatically try other models. Off for evaluation, so results are not mixed.");
        cacheModelBox = new JCheckBox("Upload model once");
        cacheModelBox.setToolTipText("<html>On: the model is uploaded to Google once (cache, valid 60 min) with the first question.<br>"
                + "After that only your question is sent. Click 'Resend model' after changing the model in CATIA.<br>"
                + "Changing AI model, ID check or grounding uploads once more automatically.<br>"
                + "Not available for every model on the free tier – then the model is sent with each question.</html>");
        mentionFocusBox = new JCheckBox("Mention selected element");
        updateMentionFocusBox();
        for (JCheckBox cb : new JCheckBox[]{groundingBox, freshChatBox, fallbackBox, cacheModelBox, mentionFocusBox}) cb.addActionListener(e -> saveSettings());
        // Mehrere Zeilen, damit bei schmalem Fenster keine Option abgeschnitten wird
        JPanel checks = new JPanel(new GridLayout(3, 2, 8, 0));
        checks.add(cacheModelBox);
        checks.add(groundingBox);
        checks.add(freshChatBox);
        checks.add(fallbackBox);
        checks.add(mentionFocusBox);
        maxTokensSpinner = new JSpinner(new SpinnerNumberModel(8192, 256, 65536, 256));
        maxTokensSpinner.setToolTipText("Maximum length of the answer (incl. internal reasoning of thinking models).");
        maxTokensSpinner.addChangeListener(e -> saveSettings());
        addRow(p, c, 2, "Format:", formatRow, "Max tokens:", maxTokensSpinner);
        addRow(p, c, 3, "Options:", checks, null, null);

        // Zeile 3: Evaluation – ID-Check, Testlauf, Wiederholungen
        requireIdsBox = new JCheckBox("Cite element IDs (hallucination check)");
        requireIdsBox.setToolTipText("Adds an instruction to the system prompt: the AI must give the id of every element it mentions. "
                + "The plugin then checks whether these ids exist in the model. Changes the prompt – keep it constant within an experiment.");
        requireIdsBox.addActionListener(e -> saveSettings());
        suiteBtn = new JButton("Run test suite…");
        suiteBtn.setToolTipText("Load a CSV with test cases (test_id;category;question;expected_answer) and run all of them automatically.");
        suiteBtn.addActionListener(e -> {
            if (suiteRunning) {
                cancelRequested = true;
                apiService.cancel();   // laufende Anfrage sofort abbrechen
                suiteBtn.setText("Stopping…");
                suiteBtn.setEnabled(false);
            } else {
                startTestSuite();
            }
        });
        JPanel evalRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        evalRow.add(requireIdsBox);
        evalRow.add(Box.createHorizontalStrut(12));
        evalRow.add(suiteBtn);
        repetitionsSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 20, 1));
        repetitionsSpinner.setToolTipText("How often each question is asked (each time in a new chat). >1 shows how consistent the answers are.");
        repetitionsSpinner.addChangeListener(e -> saveSettings());
        addRow(p, c, 4, "Evaluation:", evalRow, "Repetitions:", repetitionsSpinner);

        // Zeile 5: Status + Buttons
        exportStatusLabel = new JLabel("Not exported yet – click 'Export model'.");
        exportStatusLabel.setForeground(Color.DARK_GRAY);
        // Feste Breite: ein langer Status soll die rechte Spalte nicht zusammendrücken (voller Text im Tooltip)
        exportStatusLabel.setPreferredSize(new Dimension(300, exportStatusLabel.getPreferredSize().height));
        exportBtn = new JButton("Export model");
        exportBtn.setToolTipText("<html>Exports the chosen context from CATIA. Click again after changes in CATIA or after changing<br>"
                + "the context; the model is uploaded again with the next question.<br>"
                + "SysML v2 model: reads the .sysml files again – export them in CATIA first.</html>");
        exportBtn.setFont(exportBtn.getFont().deriveFont(Font.BOLD));
        exportBtn.addActionListener(e -> {
            if (busy) return;
            if (isSelectionScope() && selectedRoots.isEmpty() && !isNativeFormat() && !chooseSelection()) return;
            runExport();
        });
        JButton requestBtn = new JButton("Show request…");
        requestBtn.setToolTipText("Shows exactly what the next question sends to the AI (system prompt, model data, chat, question) and lets you save it.");
        requestBtn.addActionListener(e -> showRequest());
        JButton promptBtn = new JButton("System prompt…");
        promptBtn.setToolTipText("View and edit the system prompt that is sent with every request.");
        promptBtn.addActionListener(e -> new SystemPromptDialog(this, GeminiExplanationService.getBaseDirectory(),
                currentFormat(), () -> addMessage("System", "System prompt saved. It is used from the next question on "
                + "(the model is uploaded again automatically).")).setVisible(true));
        JButton apiKeyBtn = new JButton("API key…");
        apiKeyBtn.addActionListener(e -> new SettingsDialog().setVisible(true));
        JButton logBtn = new JButton("Open results");
        logBtn.setToolTipText("Opens the results folder (log, test runs, archived prompts and exports): "
                + EvaluationLog.getBaseDir().getAbsolutePath());
        logBtn.addActionListener(e -> openResultsFolder());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.add(exportBtn);
        buttons.add(requestBtn);
        buttons.add(promptBtn);
        buttons.add(apiKeyBtn);
        buttons.add(logBtn);

        c.gridy = 5; c.gridx = 0; c.gridwidth = 4; c.weightx = 1;
        p.add(exportStatusLabel, c);
        c.gridwidth = 1;
        c.gridy = 6; c.gridx = 0; c.gridwidth = 4; c.weightx = 1;
        p.add(buttons, c);
        c.gridwidth = 1;

        scopeBox.addActionListener(e -> {
            if (loading || busy) return;
            if (isFocusScope() && focusElement == null) {   // grauer Eintrag: nicht wählbar
                loading = true;
                scopeBox.setSelectedItem(lastScopeChoice);
                loading = false;
                addMessage("System", "No element selected. Select an element in CATIA and click <b>Take CATIA selection</b>.");
                return;
            }
            if (isSelectionScope() && selectedRoots.isEmpty() && !chooseSelection()) {
                return; // Auswahl abgebrochen: chooseSelection hat den alten Umfang wiederhergestellt
            }
            lastScopeChoice = scopeBox.getSelectedItem();
            saveSettings();
            updateControls();
        });
        formatBox.addActionListener(e -> {
            if (loading || busy) return;
            saveSettings();
            switchFormat();
        });
        // Tiefe geändert: nur merken – exportiert wird mit "Export model"
        javax.swing.event.ChangeListener depthListener = e -> {
            if (loading) return;
            saveSettings();
            updateControls();
        };
        relationDepthSpinner.addChangeListener(depthListener);
        siblingDepthSpinner.addChangeListener(depthListener);
        modelBox.addActionListener(e -> {
            if (!loading && export != null) countTokensAsync();
        });
        return p;
    }

    private void addRow(JPanel p, GridBagConstraints c, int row, String l1, JComponent comp1, String l2, JComponent comp2) {
        c.gridy = row;
        c.gridx = 0; c.weightx = 0; p.add(new JLabel(l1), c);
        c.gridx = 1; c.weightx = 1; p.add(comp1, c);
        if (l2 == null) return;
        c.gridx = 2; c.weightx = 0; p.add(new JLabel(l2), c);
        c.gridx = 3; c.weightx = 0.3; p.add(comp2, c);
    }

    private JComponent createQuickQuestionPanel() {
        quickPanel = new JPanel(new BorderLayout(4, 4));
        quickPanel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(),
                "Quick questions about your model (click to insert, then edit and send)", TitledBorder.LEFT, TitledBorder.TOP));
        quickPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        quickPanel.add(new JLabel("Questions are generated from your model after the export …"), BorderLayout.CENTER);
        return quickPanel;
    }

    /** Baut die Schnellfragen aus echten Elementen und Beziehungen des exportierten Modells. */
    private void refreshQuickQuestions() {
        if (quickPanel == null) return;
        quickPanel.removeAll();
        ModelIndex idx = modelIndex;
        if (idx == null || idx.elements.isEmpty()) {
            quickPanel.add(new JLabel("No model data yet."), BorderLayout.CENTER);
        } else {
            JPanel grid = new JPanel(new GridLayout(0, 2, 4, 4));
            String focusId = focusElement == null ? null : focusElement.getID();
            // Element-Fragen, wenn nur die Umgebung eines Elements exportiert ist; sonst Fragen zum ganzen Modell
            boolean elementQuestions = focusElement != null && isFocusScope();
            ((javax.swing.border.TitledBorder) quickPanel.getBorder()).setTitle(elementQuestions
                    ? "Quick questions about " + getFocusName() + " (click to insert, then edit and send)"
                    : "Quick questions about the whole model (click to insert, then edit and send)");
            String focusName = focusElement == null ? null : getFocusName();
            String focusType = focusElement == null ? null : focusElement.getHumanType();
            if (elementQuestions) {
                boolean found = QuickQuestions.findFocus(idx, focusId, focusName) != null;
                logInfo("Quick questions for element '" + focusName + "' – found in export: " + (found ? "yes" : "no")
                        + " (export has " + idx.elements.size() + " elements)");
            }
            for (QuickQuestions.Question q : QuickQuestions.generate(idx, focusId, focusName, focusType, quickSeed, elementQuestions)) {
                String label = q.category + ": " + q.label;
                if (label.length() > 70) label = label.substring(0, 69) + "…";
                JButton b = new JButton(label);
                b.setHorizontalAlignment(SwingConstants.LEFT);
                b.setMargin(new Insets(2, 6, 2, 6));
                b.setToolTipText("<html><div style='width:420px'>" + escapeHtml(q.text) + "</div></html>");
                b.addActionListener(e -> insertQuickQuestion(q.text));
                grid.add(b);
            }
            quickPanel.add(grid, BorderLayout.CENTER);
            JLabel hint = new JLabel("Fill in the parts in <angle brackets> (the first one is selected when you click), then send.");
            hint.setForeground(Color.GRAY);
            quickPanel.add(hint, BorderLayout.SOUTH);
        }
        quickPanel.revalidate();
        quickPanel.repaint();
    }

    private JComponent createInputPanel() {
        JPanel p = new JPanel(new BorderLayout(6, 4));
        p.setBorder(BorderFactory.createEmptyBorder(4, 10, 10, 10));

        inputArea = new JTextArea(3, 40);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setToolTipText("Type your question. Enter = send, Shift+Enter = new line.");
        InputMap im = inputArea.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = inputArea.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "insert-break");
        am.put("send", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendQuestion();
            }
        });

        sendBtn = new JButton("Send");
        sendBtn.addActionListener(e -> sendQuestion());
        JButton newChatBtn = new JButton("New chat");
        newChatBtn.setToolTipText("Clear the conversation (the model data stays loaded).");
        newChatBtn.addActionListener(e -> {
            if (busy) return;
            apiService.resetConversation();
            chatPanel.removeAll();
            chatPanel.revalidate();
            chatPanel.repaint();
            addMessage("System", "New chat started.");
        });
        JPanel btns = new JPanel(new GridLayout(2, 1, 0, 4));
        btns.add(sendBtn);
        btns.add(newChatBtn);

        p.add(new JLabel("Your question:"), BorderLayout.NORTH);
        p.add(new JScrollPane(inputArea), BorderLayout.CENTER);
        p.add(btns, BorderLayout.EAST);
        return p;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Einstellungen
    // ═══════════════════════════════════════════════════════════════════════

    private void loadSettings() {
        loading = true;
        String model = prefs.get(SysmlAiQaPlugin.MODEL_PREF, GeminiExplanationService.DEFAULT_MODELS[0]);
        for (String m : GeminiExplanationService.mergeModelLists(null, model)) modelBox.addItem(m);
        modelBox.setSelectedItem(model);
        // Zuletzt benutzter Context ("Selected packages" startet als "Whole model", die Auswahl wird nicht gespeichert)
        String scope = prefs.get(SysmlAiQaPlugin.SCOPE_PREF, SCOPE_FULL);
        scopeBox.setSelectedItem(SCOPE_FOCUS.equals(scope) && focusElement != null ? SCOPE_FOCUS : SCOPE_FULL);
        lastScopeChoice = scopeBox.getSelectedItem();
        thinkingBox.setSelectedItem(prefs.get(SysmlAiQaPlugin.THINKING_PREF, "Model default"));
        temperatureSpinner.setValue(prefs.getDouble(SysmlAiQaPlugin.TEMPERATURE_PREF, 0.0));
        maxTokensSpinner.setValue(prefs.getInt(SysmlAiQaPlugin.MAX_TOKENS_PREF, 8192));
        repetitionsSpinner.setValue(Math.max(1, Math.min(20, prefs.getInt(SysmlAiQaPlugin.REPETITIONS_PREF, 1))));
        groundingBox.setSelected(prefs.getBoolean(SysmlAiQaPlugin.GROUNDING_PREF, false));
        freshChatBox.setSelected(prefs.getBoolean(SysmlAiQaPlugin.FRESH_CHAT_PREF, true));
        fallbackBox.setSelected(prefs.getBoolean(SysmlAiQaPlugin.FALLBACK_PREF, false));
        requireIdsBox.setSelected(prefs.getBoolean(SysmlAiQaPlugin.REQUIRE_IDS_PREF, false));
        cacheModelBox.setSelected(prefs.getBoolean(SysmlAiQaPlugin.CACHE_MODEL_PREF, true));
        formatBox.setSelectedItem(formatLabel(prefs.get(SysmlAiQaPlugin.FORMAT_PREF, ModelExport.FORMAT_V1)));
        lastFormat = formatBox.getSelectedItem();
        mentionFocusBox.setSelected(focusElement != null && prefs.getBoolean(SysmlAiQaPlugin.MENTION_FOCUS_PREF, true));
        relationDepthSpinner.setValue(clamp(prefs.getInt(SysmlAiQaPlugin.RELATION_DEPTH_PREF, DataExtractionService.DEFAULT_RELATION_DEPTH), 0, 10));
        siblingDepthSpinner.setValue(clamp(prefs.getInt(SysmlAiQaPlugin.SIBLING_DEPTH_PREF, DataExtractionService.DEFAULT_SIBLING_DEPTH), 0, 5));
        loading = false;
        updateControls();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void saveSettings() {
        if (loading || modelBox == null || scopeBox == null || temperatureSpinner == null
                || maxTokensSpinner == null || repetitionsSpinner == null || groundingBox == null
                || freshChatBox == null || fallbackBox == null || requireIdsBox == null || cacheModelBox == null
                || thinkingBox == null || formatBox == null || mentionFocusBox == null
                || relationDepthSpinner == null || siblingDepthSpinner == null) {
            return;
        }
        prefs.put(SysmlAiQaPlugin.FORMAT_PREF, currentFormat());
        prefs.put(SysmlAiQaPlugin.SCOPE_PREF, String.valueOf(scopeBox.getSelectedItem()));
        if (focusElement != null) prefs.putBoolean(SysmlAiQaPlugin.MENTION_FOCUS_PREF, mentionFocusBox.isSelected());
        prefs.putInt(SysmlAiQaPlugin.RELATION_DEPTH_PREF, ((Number) relationDepthSpinner.getValue()).intValue());
        prefs.putInt(SysmlAiQaPlugin.SIBLING_DEPTH_PREF, ((Number) siblingDepthSpinner.getValue()).intValue());
        prefs.put(SysmlAiQaPlugin.MODEL_PREF, currentModel());
        prefs.put(SysmlAiQaPlugin.THINKING_PREF, (String) thinkingBox.getSelectedItem());
        prefs.putDouble(SysmlAiQaPlugin.TEMPERATURE_PREF, ((Number) temperatureSpinner.getValue()).doubleValue());
        prefs.putInt(SysmlAiQaPlugin.MAX_TOKENS_PREF, ((Number) maxTokensSpinner.getValue()).intValue());
        prefs.putInt(SysmlAiQaPlugin.REPETITIONS_PREF, ((Number) repetitionsSpinner.getValue()).intValue());
        prefs.putBoolean(SysmlAiQaPlugin.GROUNDING_PREF, groundingBox.isSelected());
        prefs.putBoolean(SysmlAiQaPlugin.FRESH_CHAT_PREF, freshChatBox.isSelected());
        prefs.putBoolean(SysmlAiQaPlugin.FALLBACK_PREF, fallbackBox.isSelected());
        prefs.putBoolean(SysmlAiQaPlugin.REQUIRE_IDS_PREF, requireIdsBox.isSelected());
        prefs.putBoolean(SysmlAiQaPlugin.CACHE_MODEL_PREF, cacheModelBox.isSelected());
    }

    private String currentModel() {
        Object v = modelBox.isEditable() ? modelBox.getEditor().getItem() : modelBox.getSelectedItem();
        return normalizeModelId(v == null ? "" : v.toString());
    }

    private static String normalizeModelId(String raw) {
        String s = raw.trim();
        if (s.startsWith("models/")) s = s.substring("models/".length());
        return s.isEmpty() ? GeminiExplanationService.DEFAULT_MODELS[0] : s;
    }

    private AskSettings currentSettings() {
        AskSettings s = new AskSettings();
        s.model = currentModel();
        s.temperature = Math.round(((Number) temperatureSpinner.getValue()).doubleValue() * 10) / 10.0;
        s.maxOutputTokens = ((Number) maxTokensSpinner.getValue()).intValue();
        s.grounding = groundingBox.isSelected();
        s.freshChat = freshChatBox.isSelected();
        s.allowFallback = fallbackBox.isSelected();
        s.requireIds = requireIdsBox.isSelected();
        s.repetitions = ((Number) repetitionsSpinner.getValue()).intValue();
        s.cacheModel = cacheModelBox.isSelected();
        String th = (String) thinkingBox.getSelectedItem();
        s.thinkingLevel = th == null || th.startsWith("Model") ? "default" : th.toLowerCase();
        s.scope = export != null && export.isNative() ? "SYSML_V2_FILES"
                : isFocusScope() ? "FOCUS_NEIGHBOURHOOD" : isSelectionScope() ? "SELECTION" : "FULL_MODEL";
        s.format = export != null ? export.format : currentFormat();
        s.mentionFocus = currentFocusInfo() != null;
        if (isFocusScope()) {
            s.relationDepth = ((Number) relationDepthSpinner.getValue()).intValue();
            s.siblingDepth = ((Number) siblingDepthSpinner.getValue()).intValue();
        }
        return s;
    }

    private boolean isFocusScope() {
        return SCOPE_FOCUS.equals(scopeBox.getSelectedItem());
    }

    private boolean isSelectionScope() {
        return SCOPE_SELECTION.equals(scopeBox.getSelectedItem());
    }

    private String currentFormat() {
        return formatId(formatBox.getSelectedItem());
    }

    static String formatId(Object label) {
        if (FORMAT_NATIVE_LABEL.equals(label)) return ModelExport.FORMAT_V2_NATIVE;
        return FORMAT_V2_LABEL.equals(label) ? ModelExport.FORMAT_V2 : ModelExport.FORMAT_V1;
    }

    static String formatLabel(String id) {
        if (ModelExport.FORMAT_V2_NATIVE.equals(id)) return FORMAT_NATIVE_LABEL;
        return ModelExport.FORMAT_V2.equals(id) ? FORMAT_V2_LABEL : FORMAT_V1_LABEL;
    }

    private boolean isNativeFormat() {
        return FORMAT_NATIVE_LABEL.equals(formatBox.getSelectedItem());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SysML-v2-Modelle aus CATIA (FR7)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Liest die von CATIA exportierten .sysml-Dateien (File → Export To → SysML v2 Textual Notation).
     * CATIA bietet dafür keine dokumentierte Programmierschnittstelle; der eigene Export von CATIA ist die
     * verlässlichste Quelle für die offizielle SysML-v2-Textnotation.
     * @param choose true: Dateiauswahl öffnen; false: zuletzt gewählte Dateien neu lesen (Resend model)
     */
    private void loadSysmlV2(boolean choose) {
        List<File> files = new ArrayList<>();
        for (String p : prefs.get(SysmlAiQaPlugin.V2_FILES_PREF, "").split("\\|")) {
            if (!p.isBlank()) files.add(new File(p));
        }
        if (choose || files.isEmpty() || files.stream().noneMatch(File::exists)) {
            JFileChooser ch = new JFileChooser(files.isEmpty() ? System.getProperty("user.home") : files.get(0).getParent());
            ch.setDialogTitle("Choose the SysML v2 files exported by CATIA (or their folder)");
            ch.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            ch.setMultiSelectionEnabled(true);
            ch.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("SysML v2 textual notation (*.sysml)", "sysml"));
            if (ch.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
                if (export == null || !export.isNative()) {   // nichts gewählt: vorheriges Format wiederherstellen
                    loading = true;
                    formatBox.setSelectedItem(lastFormat == FORMAT_NATIVE_LABEL ? FORMAT_V1_LABEL : lastFormat);
                    loading = false;
                    saveSettings();
                    updateControls();
                }
                return;
            }
            files = new ArrayList<>(java.util.Arrays.asList(ch.getSelectedFiles()));
        }
        final List<File> sysml = collectSysml(files);
        if (sysml.isEmpty()) {
            addMessage("System", "⚠ No .sysml files found. Export your SysML v2 project in CATIA first "
                    + "(<b>Export in CATIA…</b> or File → Export To → SysML v2 Textual Notation), then choose the files or their folder.");
            return;
        }
        StringBuilder remember = new StringBuilder();
        for (File f : files) remember.append(remember.length() > 0 ? "|" : "").append(f.getAbsolutePath());
        prefs.put(SysmlAiQaPlugin.V2_FILES_PREF, remember.toString());

        busy = true;
        updateControls();
        exportStatusLabel.setText("Reading SysML v2 files …");
        addMessage("System", "Reading " + sysml.size() + " SysML v2 file(s) exported by CATIA …");
        new SwingWorker<ModelExport, Void>() {
            @Override
            protected ModelExport doInBackground() throws Exception {
                return ModelExport.fromSysmlFiles(sysml, DataExtractionService.getExportDirectory());
            }

            @Override
            protected void done() {
                busy = false;
                try {
                    ModelExport ex = get();
                    baseExport = null;
                    exportedKey = "native";
                    lastFormat = FORMAT_NATIVE_LABEL;
                    modelIndex = ex.index;
                    refreshQuickQuestions();
                    applyExport(ex, true);
                    long oldest = sysml.stream().mapToLong(File::lastModified).min().orElse(System.currentTimeMillis());
                    if (System.currentTimeMillis() - oldest > 60 * 60 * 1000L) {
                        addMessage("System", "Note: the files are older than one hour. After changes in CATIA, export again and click <b>Resend model</b>.");
                    }
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    exportStatusLabel.setText("Reading the SysML v2 files failed.");
                    addMessage("System", "⚠ Could not read the SysML v2 files: " + escapeHtml(String.valueOf(cause.getMessage())));
                }
                updateControls();
            }
        }.execute();
    }

    /** Dateien und Ordner (rekursiv) zu einer Liste von .sysml-Dateien. */
    private static List<File> collectSysml(List<File> in) {
        List<File> out = new ArrayList<>();
        for (File f : in) {
            if (f.isDirectory()) {
                File[] kids = f.listFiles();
                if (kids != null) out.addAll(collectSysml(java.util.Arrays.asList(kids)));
            } else if (f.isFile() && f.getName().toLowerCase().endsWith(".sysml")) {
                out.add(f);
            }
        }
        return out;
    }

    /** Öffnet den SysML-v2-Textexport von CATIA (Menü File → Export To → SysML v2 Textual Notation). */
    private void openCatiaV2Export() {
        JMenuItem item = null;
        try {
            JFrame main = Application.getInstance().getMainFrame();
            if (main != null && main.getJMenuBar() != null) item = findMenuItem(main.getJMenuBar(), "SysML v2 Textual Notation", "Export");
        } catch (Throwable ignored) { }
        if (item != null && item.isEnabled()) {
            addMessage("System", "CATIA's export opens. Choose the namespaces and a folder, click <b>Export</b>, "
                    + "then click <b>Files…</b> here and select that folder.");
            item.doClick();
        } else {
            addMessage("System", "Please export in CATIA: <b>File → Export To → SysML v2 Textual Notation</b> "
                    + "(only available in SysML v2 projects), choose a folder, then click <b>Files…</b> here and select that folder.");
        }
    }

    /** Sucht im Menü einen Eintrag mit dem Text, dessen Elternmenü "within" enthält. */
    private static JMenuItem findMenuItem(MenuElement root, String text, String within) {
        for (MenuElement me : root.getSubElements()) {
            Component c = me.getComponent();
            if (c instanceof JMenuItem && !(c instanceof JMenu)) {
                JMenuItem mi = (JMenuItem) c;
                Component parent = mi.getParent() instanceof JPopupMenu ? ((JPopupMenu) mi.getParent()).getInvoker() : null;
                String pText = parent instanceof JMenu ? ((JMenu) parent).getText() : "";
                if (mi.getText() != null && mi.getText().contains(text) && (pText == null || pText.contains(within) || within == null)) return mi;
            }
            JMenuItem found = findMenuItem(me, text, within);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Pakete/Elemente für "Selected packages / elements" wählen (exportiert wird mit "Export model").
     * @return true, wenn etwas gewählt wurde
     */
    private boolean chooseSelection() {
        if (busy) return false;
        Project project = Application.getInstance().getProject();
        List<Element> pre = new ArrayList<>(selectedRoots);
        if (pre.isEmpty()) pre.addAll(treeSelection());
        if (pre.isEmpty() && focusElement != null) pre.add(focusElement);
        List<Element> chosen = ElementPickerDialog.choose(this, project, pre);
        if (chosen == null || chosen.isEmpty()) {
            if (selectedRoots.isEmpty() && isSelectionScope()) {
                loading = true;
                scopeBox.setSelectedItem(lastScopeChoice);   // Abbruch: vorherigen Umfang wiederherstellen
                loading = false;
                updateControls();
            }
            return false;
        }
        selectedRoots = DataExtractionService.topLevelRoots(chosen);
        if (!isSelectionScope()) {
            loading = true;
            scopeBox.setSelectedItem(SCOPE_SELECTION);
            loading = false;
        }
        lastScopeChoice = SCOPE_SELECTION;
        saveSettings();
        return true;
    }

    /** "Take CATIA selection": das in CATIA ausgewählte Element übernehmen und den Context darauf stellen. */
    private void useCurrentSelection() {
        if (busy) return;
        Element el = null;
        try {
            el = ShowExplanationAction.currentSelection();
        } catch (Throwable t) {
            logInfo("Could not read the selection: " + t);
        }
        if (el == null) {
            addMessage("System", "No element selected in CATIA. Click an element in the containment tree, a diagram "
                    + "or a table row, then click <b>Take CATIA selection</b> again.");
            return;
        }
        focusElement = el;
        focusLabel.setText(focusText());
        updateMentionFocusBox();
        mentionFocusBox.setSelected(prefs.getBoolean(SysmlAiQaPlugin.MENTION_FOCUS_PREF, true));
        loading = true;
        scopeBox.setSelectedItem(SCOPE_FOCUS);
        loading = false;
        lastScopeChoice = SCOPE_FOCUS;
        scopeBox.repaint();
        saveSettings();
        updateControls();
        addMessage("System", "Selected element: <b>" + escapeHtml(getFocusName()) + "</b>. Click <b>Export model</b> to export it with its surroundings.");
    }

    private void updateMentionFocusBox() {
        mentionFocusBox.setToolTipText(focusElement == null ? "No element selected."
                : "<html>On: the first question of a chat starts with<br><tt># Currently selected element<br>"
                + escapeHtml(buildFocusText()) + "</tt><br>Off: nothing about the selection is sent. See 'Show request…'.</html>");
        mentionFocusBox.setEnabled(focusElement != null);
        if (focusElement == null) mentionFocusBox.setSelected(false);
    }

    /**
     * Beschreibt den im Fenster eingestellten Context. Weicht er vom Context des letzten Exports ab,
     * muss neu exportiert werden, bevor gefragt wird (sonst passt das Protokoll nicht zum gesendeten Modell).
     */
    private String exportKey() {
        if (isNativeFormat()) return "native";
        if (isFocusScope()) return "focus|" + (focusElement == null ? "" : focusElement.getID()) + "|"
                + relationDepthSpinner.getValue() + "|" + siblingDepthSpinner.getValue();
        if (isSelectionScope()) {
            StringBuilder sb = new StringBuilder("selection");
            for (Element e : selectedRoots) sb.append('|').append(e.getID());
            return sb.toString();
        }
        return "full";
    }

    /** true, wenn ein Export vorliegt, der Context im Fenster aber inzwischen geändert wurde. */
    private boolean exportOutdated() {
        return export != null && exportedKey != null && !exportedKey.equals(exportKey());
    }

    /** Im Containment Tree markierte Elemente (Mehrfachauswahl) als Vorauswahl. */
    private static List<Element> treeSelection() {
        List<Element> l = new ArrayList<>();
        try {
            Object project = Application.getInstance().getProject();
            Object browser = project.getClass().getMethod("getBrowser").invoke(project);
            Object tree = browser.getClass().getMethod("getContainmentTree").invoke(browser);
            Object nodes = tree.getClass().getMethod("getSelectedNodes").invoke(tree);
            if (nodes instanceof Object[]) {
                for (Object n : (Object[]) nodes) {
                    Object u = n instanceof javax.swing.tree.DefaultMutableTreeNode ? ((javax.swing.tree.DefaultMutableTreeNode) n).getUserObject() : null;
                    if (u instanceof Element) l.add((Element) u);
                }
            }
        } catch (Throwable ignored) { }
        return l;
    }

    private String selectionLabel() {
        StringBuilder sb = new StringBuilder();
        for (Element e : selectedRoots) {
            if (sb.length() > 0) sb.append(", ");
            String n = e instanceof NamedElement ? ((NamedElement) e).getName() : null;
            sb.append(n == null || n.isEmpty() ? e.getHumanType() : n).append(" [").append(e.getID()).append(']');
        }
        return selectedRoots.size() + " selected: " + sb;
    }

    private void refreshModels(JButton btn) {
        btn.setEnabled(false);
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() {
                return apiService.listAvailableModels();
            }

            @Override
            protected void done() {
                btn.setEnabled(true);
                try {
                    List<String> fromApi = get();
                    String current = currentModel();
                    loading = true;
                    modelBox.removeAllItems();
                    for (String m : GeminiExplanationService.mergeModelLists(fromApi, current)) modelBox.addItem(m);
                    modelBox.setSelectedItem(current);
                    loading = false;
                    addMessage("System", fromApi.isEmpty()
                            ? "Could not load the model list (see CATIA log). Default models are shown."
                            : fromApi.size() + " models available for your API key.");
                } catch (Exception ex) {
                    loading = false;
                    addMessage("System", "Could not load the model list: " + ex.getMessage());
                }
            }
        }.execute();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Export
    // ═══════════════════════════════════════════════════════════════════════

    private void runExport() {
        if (isNativeFormat()) {          // SysML-v2-Modell: Dateien aus dem CATIA-Export neu lesen
            loadSysmlV2(false);
            return;
        }
        final DataExtractionService.Scope scope = isFocusScope() ? DataExtractionService.Scope.FOCUS_NEIGHBOURHOOD
                : isSelectionScope() && !selectedRoots.isEmpty() ? DataExtractionService.Scope.SELECTION
                : DataExtractionService.Scope.FULL_MODEL;
        final List<Element> roots = new ArrayList<>(selectedRoots);
        final int relDepth = ((Number) relationDepthSpinner.getValue()).intValue();
        final int sibDepth = ((Number) siblingDepthSpinner.getValue()).intValue();
        final String format = currentFormat();
        final Object scopeItem = scopeBox.getSelectedItem();
        final Element focus = focusElement;
        final String focusName = getFocusName();
        final String key = exportKey();
        final String what = scope == DataExtractionService.Scope.FULL_MODEL ? "the whole model"
                : scope == DataExtractionService.Scope.SELECTION ? selectionLabel()
                : "the surroundings of the selected element (relations " + relDepth + ", neighbours " + sibDepth + ")";
        busy = true;
        updateControls();
        exportStatusLabel.setText("Exporting " + what + " …");
        addMessage("System", "Exporting " + escapeHtml(what) + " …");

        new SwingWorker<ModelExport, Void>() {
            @Override
            protected ModelExport doInBackground() throws Exception {
                Project project = Application.getInstance().getProject();
                if (project == null) return null;
                File f = DataExtractionService.exportModel(project, focus, scope, roots, relDepth, sibDepth);
                if (f == null) return null;
                ModelExport base = ModelExport.fromFile(f);
                base.scopeDetail = scope == DataExtractionService.Scope.FULL_MODEL ? "whole model"
                        : scope == DataExtractionService.Scope.SELECTION ? selectionLabel()
                        : "surroundings of " + focusName + " [" + focus.getID() + "] · relation depth "
                        + relDepth + " · neighbour depth " + sibDepth;
                baseExport = base;
                return base.inFormat(format);
            }

            @Override
            protected void done() {
                busy = false;
                try {
                    ModelExport ex = get();
                    if (ex == null) {
                        exportStatusLabel.setText("Export failed – see CATIA log.");
                        addMessage("System", "Error: model export failed (see CATIA log window). If this is a <b>SysML v2 project</b>, "
                                + "choose <b>Format: " + FORMAT_NATIVE_LABEL + "</b>.");
                    } else {
                        lastScope = scopeItem;
                        exportedKey = key;
                        lastFormat = formatBox.getSelectedItem();
                        modelIndex = ModelIndex.parse(ex.json);
                        refreshQuickQuestions();
                        applyExport(ex, true);
                        if (ex.elementCount == 0) {
                            addMessage("System", "⚠ The export contains no SysML v1 elements. If this is a <b>SysML v2 project</b>, "
                                    + "choose <b>Format: " + FORMAT_NATIVE_LABEL + "</b> – the plugin then sends the SysML v2 text that CATIA exports.");
                        }
                    }
                } catch (Exception e) {
                    exportStatusLabel.setText("Export failed.");
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    addMessage("System", "Critical error during export: " + escapeHtml(String.valueOf(cause.getMessage())));
                }
                updateControls();
            }
        }.execute();
    }

    /** Format gewechselt: dasselbe Modell neu schreiben (ohne neuen Export aus CATIA). */
    private void switchFormat() {
        final ModelExport base = baseExport;
        if (isNativeFormat() || base == null || export == null || export.isNative()) {
            // SysML-v2-Dateien bzw. noch kein SysML-v1-Export: nichts automatisch lesen, auf "Export model" warten
            boolean had = export != null;
            export = null;
            exportedKey = null;
            if (isNativeFormat()) baseExport = null;
            modelIndex = null;
            apiService.resetConversation();
            apiService.discardUploadedModel();
            refreshQuickQuestions();
            exportStatusLabel.setForeground(Color.DARK_GRAY);
            exportStatusLabel.setText("Not exported yet – click 'Export model'.");
            if (isNativeFormat()) {
                addMessage("System", "Format: SysML v2 model. Export the project in CATIA (<b>Export in CATIA…</b>), then click "
                        + "<b>Files…</b> to choose the .sysml files, or <b>Export model</b> to read the files chosen last time.");
            } else if (had) {
                addMessage("System", "Format changed. Click <b>Export model</b> to export the model from CATIA.");
            }
            lastFormat = formatBox.getSelectedItem();
            updateControls();
            return;
        }
        final String format = currentFormat();
        busy = true;
        updateControls();
        new SwingWorker<ModelExport, Void>() {
            @Override
            protected ModelExport doInBackground() throws Exception {
                return base.inFormat(format);
            }

            @Override
            protected void done() {
                busy = false;
                try {
                    lastFormat = formatBox.getSelectedItem();
                    applyExport(get(), false);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    addMessage("System", "⚠ Could not convert the model: " + escapeHtml(String.valueOf(cause.getMessage())));
                }
                updateControls();
            }
        }.execute();
    }

    /** Neuer Export bzw. neues Format ist bereit: Chat zurücksetzen, Größe anzeigen, Tokens zählen. */
    private void applyExport(ModelExport ex, boolean newExport) {
        export = ex;
        apiService.resetConversation();     // neue Modelldaten -> neuer Chat
        apiService.discardUploadedModel();  // alter Upload ungültig -> nächste Frage lädt neu hoch
        updateExportStatus();
        addMessage("System", escapeHtml(exportStatusText()) + "<br>"
                + (newExport ? "" : "Format changed – same model, now as " + (ex.isV2() ? "SysML v2 textual notation" : "SysML v1 JSON") + ".<br>")
                + (ex.isNative() ? escapeHtml(ex.scopeDetail) + "<br>" : "")
                + (cacheModelBox.isSelected()
                ? "The model will be uploaded once with your first question; after that only questions are sent."
                : "The model is sent with each question.")
                + "<br>Type a question below or pick a quick question. <i>Show request…</i> shows exactly what is sent.");
        countTokensAsync();
    }

    private String exportStatusText() {
        ModelExport ex = export;
        if (ex == null) return "";
        StringBuilder sb = new StringBuilder(String.format("Model ready (%s): %d %s · %d relations · %.0f KB · ~%,d tokens",
                ex.formatLabel(), ex.elementCount, ex.isNative() ? "declarations" : "elements", ex.relationCount, ex.sizeKb(), ex.approxTokens()));
        if (ex.exactTokens >= 0) sb.append(String.format(" (exact %,d for %s)", ex.exactTokens, ex.exactTokensModel));
        sb.append(" · export ").append(ex.sha);
        return sb.toString();
    }

    private void updateExportStatus() {
        String info = exportStatusText();
        exportStatusLabel.setText(info);
        exportStatusLabel.setToolTipText("<html>" + escapeHtml(info) + "<br>Scope: " + escapeHtml(export == null ? "" : export.scopeDetail) + "</html>");
        exportStatusLabel.setForeground(Color.DARK_GRAY);
    }

    /**
     * FR2: exakte Tokenzahl der Modelldaten für das gewählte KI-Modell bei Google abfragen und mit dessen
     * Eingabelimit vergleichen (FR12: Warnung schon vor dem Senden).
     */
    private void countTokensAsync() {
        final ModelExport ex = export;
        if (ex == null) return;
        final String model = currentModel();
        final int gen = ++tokenCountGeneration;
        new SwingWorker<int[], Void>() {
            @Override
            protected int[] doInBackground() {
                return new int[]{apiService.countTokens(model, ex.content, ex.format), apiService.inputTokenLimit(model)};
            }

            @Override
            protected void done() {
                if (gen != tokenCountGeneration || ex != export) return; // inzwischen anderes Modell/Export
                try {
                    int[] r = get();
                    if (r[0] >= 0) {
                        ex.exactTokens = r[0];
                        ex.exactTokensModel = model;
                    }
                    updateExportStatus();
                    long tokens = r[0] >= 0 ? r[0] : ex.approxTokens();
                    if (r[1] > 0 && tokens > r[1]) {
                        exportStatusLabel.setForeground(new Color(0xB0, 0x00, 0x20));
                        exportStatusLabel.setText("⚠ " + exportStatusLabel.getText() + String.format(" – exceeds the input limit of %s (%,d)", model, r[1]));
                        addMessage("System", String.format("<span style='color:#B00020'>⚠ The model data (%,d tokens) is larger than the input limit of %s (%,d tokens). "
                                + "Choose a smaller context (selected packages or element surroundings) or another AI model.</span>",
                                tokens, escapeHtml(model), r[1]));
                    }
                } catch (Exception ignored) {
                    // Zählung ist nur eine Zusatzinformation
                }
            }
        }.execute();
    }

    /** "Show request…": Klartext dessen, was mit der nächsten Frage gesendet würde. */
    private void showRequest() {
        if (export == null) {
            addMessage("System", "The model has not been exported yet. Click <b>Export model</b>.");
            return;
        }
        if (exportOutdated()) {
            addMessage("System", "The context was changed. Click <b>Export model</b> first.");
            return;
        }
        String q = inputArea.getText().trim();
        AskSettings s = currentSettings();
        ExplanationService.RequestPreview pr = apiService.preview(q.isEmpty() ? "(your question)" : q, export.content,
                currentFocusInfo(), s);
        new RequestDialog(this, pr, export).setVisible(true);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Einzelne Frage (mit Wiederholungen)
    // ═══════════════════════════════════════════════════════════════════════

    private void sendQuestion() {
        if (requestRunning) {               // "Stop" (NFR4)
            cancelRequested = true;
            apiService.cancel();
            sendBtn.setText("Stopping…");
            sendBtn.setEnabled(false);
            return;
        }
        String question = inputArea.getText().trim();
        if (question.isEmpty() || busy) return;
        if (export == null) {
            addMessage("System", "The model has not been exported yet. Click <b>Export model</b>.");
            return;
        }
        if (exportOutdated()) {
            addMessage("System", "The context was changed. Click <b>Export model</b> to export it, then send the question.");
            return;
        }
        if (question.matches("(?s).*<[^<>]{1,40}>.*")) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "Your question still contains a placeholder like <element>. Send anyway?",
                    "Placeholder", JOptionPane.YES_NO_OPTION);
            if (choice != JOptionPane.YES_OPTION) return;
        }

        final AskSettings settings = currentSettings();
        final String focusInfo = currentFocusInfo();
        final ModelExport ex = export;
        final int reps = settings.repetitions;

        addMessage("You", escapeHtml(question) + (reps > 1 ? " <i>(× " + reps + ")</i>" : ""));
        inputArea.setText("");
        addMessage("System", "Waiting for " + escapeHtml(settings.model) + " … (click <b>Stop</b> to cancel)");
        busy = true;
        requestRunning = true;
        cancelRequested = false;
        updateControls();

        new SwingWorker<Void, EvaluationRecord>() {
            @Override
            protected Void doInBackground() {
                for (int rep = 1; rep <= reps && !cancelRequested; rep++) {
                    publish(Evaluation.run(apiService, settings, ex, question, focusInfo, null, "chat", rep, null));
                }
                return null;
            }

            @Override
            protected void process(List<EvaluationRecord> chunks) {
                for (EvaluationRecord r : chunks) {
                    removeLastMessage(); // "Waiting …"
                    showRecord(r, reps);
                    if (r.repetition < reps) addMessage("System", "Waiting for repetition " + (r.repetition + 1) + "/" + reps + " …");
                }
            }

            @Override
            protected void done() {
                busy = false;
                requestRunning = false;
                try {
                    get();
                } catch (Exception e) {
                    removeLastMessage();
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    addMessage("System", "⚠ Error: " + escapeHtml(String.valueOf(cause.getMessage())));
                }
                cancelRequested = false;
                updateControls();
                inputArea.requestFocusInWindow();
            }
        }.execute();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Testlauf aus CSV
    // ═══════════════════════════════════════════════════════════════════════

    private void startTestSuite() {
        if (busy || export == null || exportOutdated()) {
            addMessage("System", export == null || exportOutdated()
                    ? "Click <b>Export model</b> first." : "Wait until the model export is finished.");
            return;
        }
        JFileChooser chooser = new JFileChooser(prefs.get("sysmlaiqa_last_suite_dir", System.getProperty("user.home")));
        chooser.setDialogTitle("Choose test cases (CSV)");
        chooser.setFileFilter(new FileNameExtensionFilter("CSV test cases", "csv", "txt"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        if (file.getParentFile() != null) prefs.put("sysmlaiqa_last_suite_dir", file.getParentFile().getAbsolutePath());

        TestSuite suite;
        try {
            suite = TestSuite.load(file);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Could not read the test cases:\n" + e.getMessage(),
                    "Test suite", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Bestätigungsdialog: Modelle, Pause, Aufwand
        AskSettings base = currentSettings();
        JTextField modelsField = new JTextField(base.model, 30);
        modelsField.setToolTipText("Several models separated by commas, e.g. gemini-3.5-flash, gemini-3.1-flash-lite");
        JSpinner pauseSpinner = new JSpinner(new SpinnerNumberModel(prefs.getInt(SysmlAiQaPlugin.PAUSE_PREF, 0), 0, 600, 5));
        pauseSpinner.setToolTipText("<html>0 = no fixed pause (recommended). If Google's per-minute limit is hit,<br>"
                + "the plugin waits exactly as long as Google says (up to 2 min) and retries automatically.</html>");
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
        form.add(new JLabel("<html><b>" + suite.cases.size() + " test cases</b> loaded (" + escapeHtml(suite.categorySummary())
                + ")<br>File: " + escapeHtml(file.getName()) + "</html>"), c);
        c.gridy = 1; c.gridwidth = 1;
        form.add(new JLabel("Models:"), c);
        c.gridx = 1;
        form.add(modelsField, c);
        c.gridx = 0; c.gridy = 2;
        form.add(new JLabel("Extra pause between requests (s):"), c);
        c.gridx = 1;
        form.add(pauseSpinner, c);
        c.gridx = 0; c.gridy = 3; c.gridwidth = 2;
        form.add(new JLabel("<html>Uses the current settings (format: " + export.formatLabel()
                + ", context, temperature, thinking, max tokens, grounding, ID check).<br>"
                + "Repetitions per question: <b>" + base.repetitions + "</b> · every question in a new chat.<br>"
                + (base.cacheModel
                ? "Model is uploaded once per AI model (~" + String.format("%,d", export.approxTokens()) + " tokens), then only questions are sent."
                : "Each request sends the model data: ~" + String.format("%,d", export.approxTokens()) + " tokens.")
                + "<br>Rate limits: the plugin waits automatically when Google asks for it.</html>"), c);

        int ok = JOptionPane.showConfirmDialog(this, form, "Run test suite", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;

        List<String> models = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String m : modelsField.getText().split(",")) {
            if (m.isBlank()) continue;
            String id = normalizeModelId(m);
            if (seen.add(id)) models.add(id);
        }
        if (models.isEmpty()) models.add(base.model);
        int pauseSec = ((Number) pauseSpinner.getValue()).intValue();
        prefs.putInt(SysmlAiQaPlugin.PAUSE_PREF, pauseSec);
        base.pauseSec = pauseSec;

        int total = suite.cases.size() * models.size() * base.repetitions;
        if (total > 1) {
            String effort = base.cacheModel
                    ? String.format("This will send %d questions to %d AI model(s); the model data is uploaded once per AI model.", total, models.size())
                    : String.format("This will send %d requests (≈ %,d input tokens in total).", total, total * export.approxTokens());
            int go = JOptionPane.showConfirmDialog(this, effort + "\nStart now?", "Run test suite", JOptionPane.YES_NO_OPTION);
            if (go != JOptionPane.YES_OPTION) return;
        }
        runTestSuite(suite, models, base, pauseSec, total);
    }

    private void runTestSuite(TestSuite suite, List<String> models, AskSettings base, int pauseSec, int total) {
        final String runId = EvaluationLog.newRunId();
        final File runFile = EvaluationLog.newRunFile(runId);
        final ModelExport ex = export;

        busy = true;
        suiteRunning = true;
        cancelRequested = false;
        updateControls();
        addMessage("System", "<b>Test run " + runId + " started</b>: " + suite.cases.size() + " cases × "
                + models.size() + " model(s) × " + base.repetitions + " repetition(s) = " + total + " requests.<br>"
                + "Results: " + escapeHtml(runFile.getAbsolutePath()));

        new SwingWorker<int[], String>() {
            @Override
            protected int[] doInBackground() throws Exception {
                int done = 0, ok = 0, unknownIdAnswers = 0;
                outer:
                for (String model : models) {
                    AskSettings s = base.copy();
                    s.model = model;
                    s.freshChat = true;      // Testfälle immer unabhängig
                    s.allowFallback = false; // keine vermischten Modelle im Testlauf
                    s.max429WaitSec = 120;   // im Testlauf geduldiger auf Rate-Limits warten
                    s.max429Retries = 5;
                    modelLoop:
                    for (TestSuite.TestCase tc : suite.cases) {
                        for (int rep = 1; rep <= s.repetitions; rep++) {
                            if (cancelRequested) break outer;
                            if (done > 0 && pauseSec > 0) {
                                for (int w = 0; w < pauseSec * 10 && !cancelRequested; w++) Thread.sleep(100);
                                if (cancelRequested) break outer;
                            }
                            EvaluationRecord r = Evaluation.run(apiService, s, ex, tc.question, null, tc, runId, rep, runFile);
                            done++;
                            if (r.answer.success) ok++;
                            if (r.idCheck != null && !r.idCheck.unknown.isEmpty()) unknownIdAnswers++;
                            publish(progressLine(done, total, model, tc, rep, s.repetitions, r));
                            if (r.answer.dailyLimit) {
                                publish("<span style='color:#B00020'><b>Daily limit reached for " + escapeHtml(model)
                                        + "</b> – skipping the remaining questions for this model.</span>");
                                break modelLoop;
                            }
                        }
                    }
                }
                return new int[]{done, ok, unknownIdAnswers};
            }

            @Override
            protected void process(List<String> lines) {
                for (String l : lines) addMessage("Run", l);
            }

            @Override
            protected void done() {
                busy = false;
                suiteRunning = false;
                try {
                    int[] res = get();
                    addMessage("System", "<b>Test run " + runId + (cancelRequested ? " stopped" : " finished") + "</b>: "
                            + res[0] + "/" + total + " requests, " + res[1] + " successful, "
                            + res[2] + " answer(s) with unknown IDs.<br>Open the results file to rate the answers "
                            + "(columns <i>rating</i> and <i>rating_notes</i>).");
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    addMessage("System", "⚠ Test run aborted: " + escapeHtml(String.valueOf(cause.getMessage())));
                }
                cancelRequested = false;
                updateControls();
            }
        }.execute();
    }

    private String progressLine(int done, int total, String model, TestSuite.TestCase tc, int rep, int reps, EvaluationRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append(done).append("/").append(total).append(" · ").append(escapeHtml(tc.id));
        if (!tc.category.isBlank()) sb.append(" [").append(escapeHtml(tc.category)).append("]");
        if (reps > 1) sb.append(" · rep ").append(rep).append("/").append(reps);
        sb.append(" · ").append(modelLabel(model, r.answer)).append(" · ");
        if (r.answer.success) {
            sb.append("<span style='color:#1E7B34'>ok</span>")
                    .append(String.format(" %.1f s", r.answer.durationMs / 1000.0));
            if ("cache_new".equals(r.answer.transfer)) sb.append(" · model uploaded");
            if (r.answer.rateLimitWaitSec > 0) sb.append(" · waited ").append(r.answer.rateLimitWaitSec).append(" s (rate limit)");
            sb.append(idCheckHtml(r));
            String preview = r.answer.text.replaceAll("\\s+", " ").trim();
            if (preview.length() > 140) preview = preview.substring(0, 140) + " …";
            sb.append("<br><span style='color:gray'>").append(escapeHtml(preview)).append("</span>");
        } else {
            sb.append("<span style='color:#B00020'>failed</span>: ").append(escapeHtml(r.answer.text));
        }
        if (!r.answer.note.isEmpty() && !"cache_new".equals(r.answer.transfer) && r.repetition == 1) {
            sb.append("<br><span style='color:#9A6700'>").append(escapeHtml(r.answer.note)).append("</span>");
        }
        if (r.logWarning != null) sb.append("<br><span style='color:#B00020'>⚠ ").append(escapeHtml(r.logWarning)).append("</span>");
        return sb.toString();
    }

    /** "gemini-3.5-flash" bzw. "gemini-3.5-flash → gemini-3.6-flash", wenn Google auf ein anderes Modell umleitet. */
    private static String modelLabel(String requested, ExplanationService.Answer a) {
        String v = a.modelVersion == null ? "" : a.modelVersion;
        if (v.isEmpty() || v.equals(requested) || v.startsWith(requested + "-")) return escapeHtml(requested);
        return escapeHtml(requested) + " → <b>" + escapeHtml(v) + "</b>";
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Anzeige
    // ═══════════════════════════════════════════════════════════════════════

    private void showRecord(EvaluationRecord r, int reps) {
        ExplanationService.Answer a = r.answer;
        if (!a.note.isEmpty() && !"cache_new".equals(a.transfer)) addMessage("System", "ℹ " + escapeHtml(a.note));
        if (r.logWarning != null) addMessage("System", "⚠ " + escapeHtml(r.logWarning));
        if (!a.success) {
            addMessage("System", (GeminiExplanationService.CANCELLED.equals(a.text) ? "■ " : "⚠ ") + escapeHtml(a.text));
            return;
        }
        if ("cache_new".equals(a.transfer)) {
            addMessage("System", "Model uploaded to Google for " + escapeHtml(a.modelUsed)
                    + " (valid 60 min). Next questions send only the question – until you click <b>Resend model</b>.");
            exportStatusLabel.setText(exportStatusLabel.getText().replaceAll(" · uploaded.*$", "")
                    + " · uploaded for " + a.modelUsed);
        }
        StringBuilder meta = new StringBuilder();
        if (reps > 1) meta.append("rep ").append(r.repetition).append("/").append(reps).append(" · ");
        meta.append(modelLabel(a.modelUsed, a)).append(String.format(" · %.1f s", a.durationMs / 1000.0));
        if (a.promptTokens >= 0) meta.append(String.format(" · %,d → %,d tokens", a.promptTokens, a.answerTokens));
        if (a.cachedTokens > 0) meta.append(String.format(" (%,d from cache)", a.cachedTokens));
        if (a.thoughtsTokens > 0) meta.append(String.format(" · %,d thinking tokens", a.thoughtsTokens));
        if (a.totalTokens > 0) meta.append(String.format(" · %,d total", a.totalTokens));
        if (a.rateLimitWaitSec > 0) meta.append(" · waited ").append(a.rateLimitWaitSec).append(" s");
        if (!a.modelUsed.equals(r.settings.model)) meta.append(" · <b>fallback model!</b>");

        String body = convertMarkdownToHtml(a.text);
        String ids = idCheckHtml(r);
        if (!ids.isEmpty()) body += "<br><span style='font-size:10px'>ID check" + ids + "</span>";
        addMessage("AI <span style='color:gray;font-weight:normal'>(" + meta + ")</span>", body);
    }

    /** Kurzbeschreibung des ID-Checks, z. B. " · 6 IDs cited, all found" oder " · 2 of 5 IDs unknown: …". */
    private String idCheckHtml(EvaluationRecord r) {
        if (r.idCheck == null) return "";
        int cited = r.idCheck.cited.size();
        if (cited == 0) {
            return r.settings.requireIds ? " · <span style='color:#9A6700'>no IDs cited</span>" : "";
        }
        if (r.idCheck.unknown.isEmpty()) {
            return " · <span style='color:#1E7B34'>" + cited + " IDs cited, all found in model</span>";
        }
        List<String> shown = r.idCheck.unknown.subList(0, Math.min(5, r.idCheck.unknown.size()));
        return " · <span style='color:#B00020'>" + r.idCheck.unknown.size() + " of " + cited
                + " IDs NOT in model: " + escapeHtml(String.join(", ", shown))
                + (r.idCheck.unknown.size() > 5 ? " …" : "") + "</span>";
    }

    private void updateControls() {
        if (requestRunning) {
            sendBtn.setText(cancelRequested ? "Stopping…" : "Stop");
            sendBtn.setEnabled(!cancelRequested);
            sendBtn.setToolTipText("Cancel the running request.");
        } else {
            sendBtn.setEnabled(!busy && export != null && !exportOutdated());
            sendBtn.setText(busy ? "Busy…" : "Send");
            sendBtn.setToolTipText(null);
        }
        if (formatBox != null) formatBox.setEnabled(!busy);
        boolean nativeV2 = formatBox != null && isNativeFormat();
        if (scopeBox != null) scopeBox.setEnabled(!busy && !nativeV2);
        if (v2Panel != null) {
            v2Panel.setVisible(nativeV2);
            for (Component comp : v2Panel.getComponents()) comp.setEnabled(!busy);
        }
        if (chooseBtn != null) {
            chooseBtn.setEnabled(!busy);
            chooseBtn.setVisible(isSelectionScope() && !nativeV2);
        }
        if (depthPanel != null) {
            boolean focus = isFocusScope();
            for (Component comp : depthPanel.getComponents()) comp.setEnabled(focus && !busy);
            depthPanel.setVisible(focus && !nativeV2);
        }
        if (suiteRunning) {
            suiteBtn.setText(cancelRequested ? "Stopping…" : "Stop test run");
            suiteBtn.setEnabled(!cancelRequested);
        } else {
            suiteBtn.setText("Run test suite…");
            suiteBtn.setEnabled(!busy && export != null && !exportOutdated());
        }
        scopeBox.setEnabled(!busy && !nativeV2);
        if (useSelectionBtn != null) useSelectionBtn.setEnabled(!busy && !nativeV2);
        if (exportBtn != null) {
            exportBtn.setEnabled(!busy);
            exportBtn.setText(export == null || exportOutdated() ? "Export model" : "Resend model");
        }
        if (exportStatusLabel != null && !busy) {
            if (exportOutdated()) {
                exportStatusLabel.setForeground(new Color(0x9A, 0x67, 0x00));
                exportStatusLabel.setText("Context changed – click 'Export model' to export it.");
            } else if (export != null && exportStatusLabel.getText().startsWith("Context changed")) {
                updateExportStatus();
            }
        }
    }

    private void insertQuickQuestion(String template) {
        String text = template;
        if (focusElement != null) {
            text = text.replace("<element>", getFocusName());
            if (focusElement.getHumanType() != null && focusElement.getHumanType().contains("Requirement")) {
                text = text.replace("<requirement>", getFocusName());
            }
        }
        inputArea.setText(text);
        inputArea.requestFocusInWindow();
        int start = text.indexOf('<');
        int end = text.indexOf('>', start);
        if (start >= 0 && end > start) {
            inputArea.select(start, end + 1);
        } else {
            inputArea.setCaretPosition(text.length());
        }
    }

    private void openResultsFolder() {
        File dir = EvaluationLog.getBaseDir();
        if (!dir.exists()) dir.mkdirs();
        de.hm.sysmlaiqa.api.ExcelExport.refreshAll(dir);   // Excel-Ansichten für ältere Logs nachholen
        try {
            Desktop.getDesktop().open(dir);
        } catch (Exception ex) {
            addMessage("System", "Results folder: " + escapeHtml(dir.getAbsolutePath()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Hilfsfunktionen
    // ═══════════════════════════════════════════════════════════════════════

    private static void logInfo(String msg) {
        try {
            Application.getInstance().getGUILog().log(SysmlAiQaPlugin.LOG_PREFIX + msg);
        } catch (Throwable ignored) { }
    }

    private String getFocusName() {
        if (focusElement instanceof NamedElement) {
            String n = ((NamedElement) focusElement).getName();
            if (n != null && !n.isEmpty()) return n;
        }
        return focusElement == null ? "" : "Unnamed " + focusElement.getHumanType();
    }

    /** Text über das ausgewählte Element, wie er der KI genannt wird. */
    private String buildFocusText() {
        if (focusElement == null) return null;
        String owner = focusElement.getOwner() != null ? focusElement.getOwner().getHumanName() : "root";
        return getFocusName() + " [type: " + focusElement.getHumanType() + ", id: " + focusElement.getID()
                + ", owner: " + owner + "]";
    }

    /** Nur wenn "Mention selected element" an ist, wird das Element überhaupt erwähnt (NFR3: nichts Verstecktes). */
    private String currentFocusInfo() {
        return mentionFocusBox != null && mentionFocusBox.isSelected() ? buildFocusText() : null;
    }

    private void addMessage(String sender, String htmlMessage) {
        JPanel row = new JPanel(new BorderLayout());
        row.add(createTextPane(sender, htmlMessage), BorderLayout.CENTER);
        chatPanel.add(row);
        chatPanel.revalidate();
        SwingUtilities.invokeLater(() -> {
            if (sender.startsWith("AI")) {
                chatPanel.scrollRectToVisible(new Rectangle(0, row.getY(), 1, 1));
            } else {
                JScrollBar bar = scrollPane.getVerticalScrollBar();
                bar.setValue(bar.getMaximum());
            }
        });
    }

    private void removeLastMessage() {
        int count = chatPanel.getComponentCount();
        if (count > 0) {
            chatPanel.remove(count - 1);
            chatPanel.revalidate();
            chatPanel.repaint();
        }
    }

    private JEditorPane createTextPane(String sender, String message) {
        JEditorPane textPane = new JEditorPane();
        textPane.setContentType("text/html");
        textPane.setText("<html><body style='font-family: sans-serif; font-size: 11px; margin: 0; padding: 0;'>"
                + "<b>" + sender + ":</b><br>" + message.replace("\n", "<br>") + "</body></html>");
        textPane.setEditable(false);
        textPane.setOpaque(false);
        textPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        return textPane;
    }

    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Einfaches Markdown -> HTML für die Anzeige (Überschriften, fett, Listen, Code). */
    static String convertMarkdownToHtml(String markdown) {
        if (markdown == null) return "";
        String html = escapeHtml(markdown);
        html = html.replaceAll("(?m)^###\\s+(.+)$", "<b style='color:#2C3E50'>$1</b>");
        html = html.replaceAll("(?m)^##\\s+(.+)$", "<b style='color:#2C3E50; font-size:12px'>$1</b>");
        html = html.replaceAll("(?m)^#\\s+(.+)$", "<b style='color:#2C3E50; font-size:13px'>$1</b>");
        html = html.replaceAll("\\*\\*(.*?)\\*\\*", "<b>$1</b>");
        html = html.replaceAll("(?m)^\\s*[*-]\\s+(.+)$", "&nbsp;&nbsp;• $1");
        html = html.replaceAll("(?<![\\w*])\\*(?![\\s*])([^*\\n]+?)(?<!\\s)\\*(?![\\w*])", "<i>$1</i>");
        html = html.replaceAll("`([^`\\n]+)`", "<code>$1</code>");
        return html;
    }

    private static class ScrollablePanel extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 16; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
