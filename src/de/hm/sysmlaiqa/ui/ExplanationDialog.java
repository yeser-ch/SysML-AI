/**
 * "Ask AI about the model"-Fenster: Einstellungen, Schnellfragen, Chat und freie Frage-Eingabe auf EINER Oberfläche.
 *
 * Ablauf:
 * 1. Beim Öffnen wird das Modell exportiert (Standard: ganzes Modell).
 * 2. Der Nutzer tippt eine Frage oder wählt eine Schnellfrage und drückt "Send".
 *    Standard: Das Modell wird mit der ersten Frage EINMAL hochgeladen (Gemini-Cache); danach geht nur die Frage raus.
 *    "Resend model" exportiert neu aus CATIA und lädt bei der nächsten Frage neu hoch.
 *    Optional wird jede Frage mehrfach gestellt (Repetitions) und auf erfundene Element-IDs geprüft.
 * 3. "Run test suite…" arbeitet eine CSV mit Testfällen automatisch ab (auch für mehrere Modelle).
 * 4. Jede Antwort wird mit dem kompletten Versuchsaufbau protokolliert (siehe EvaluationLog).
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

public class ExplanationDialog extends JFrame {

    private static final String SCOPE_FULL = "Whole model";
    private static final String SCOPE_FOCUS = "Selected element + surroundings";

    private final Element focusElement;
    private final boolean elementMode;   // true = "Ask AI about this element" (nur Umgebung des Elements)
    private JPanel quickPanel;
    private long quickSeed = System.nanoTime();
    private volatile ModelIndex modelIndex;
    private JComboBox<String> thinkingBox;
    private final GeminiExplanationService apiService = new GeminiExplanationService();
    private final Preferences prefs = Preferences.userNodeForPackage(SysmlAiQaPlugin.class);

    // Einstellungen
    private JComboBox<String> modelBox;
    private JComboBox<String> styleBox;
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

    // Chat
    private JPanel chatPanel;
    private JScrollPane scrollPane;
    private JTextArea inputArea;
    private JButton sendBtn;

    // Zustand
    private volatile ModelExport export;
    private boolean busy = false;
    private boolean suiteRunning = false;
    private volatile boolean cancelRequested = false;
    private boolean loading = false;

    public ExplanationDialog(Element focusElement) {
        this(focusElement, false);
    }

    /**
     * @param elementMode true: Fragen zu einem Element – es wird nur das Element mit seiner Umgebung exportiert
     *                    (kleiner, schneller). false: ganzes Modell.
     */
    public ExplanationDialog(Element focusElement, boolean elementMode) {
        this.focusElement = focusElement;
        this.elementMode = elementMode && focusElement != null;

        setTitle(SysmlAiQaPlugin.DISPLAY_NAME + " – " + (this.elementMode
                ? "Ask AI about " + getFocusName() : "Ask AI about the model"));
        setSize(800, 900);
        setLocationRelativeTo(null);
        setAlwaysOnTop(true);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        buildUI();
        loadSettings();
        runExport();
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
        JLabel title = new JLabel(elementMode ? "Ask the AI about this element" : "Ask the AI about your SysML model");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        header.add(title, BorderLayout.NORTH);

        String focusText = focusElement == null
                ? "No element selected – questions refer to the whole model."
                : "Selected element: " + getFocusName() + " [" + focusElement.getHumanType() + "]";
        JLabel focus = new JLabel(focusText);
        focus.setFont(focus.getFont().deriveFont(Font.ITALIC, 12f));
        focus.setForeground(Color.DARK_GRAY);
        header.add(focus, BorderLayout.SOUTH);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        return header;
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

        // Zeile 0: KI-Modell + Antwortstil
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
        styleBox = new JComboBox<>(new String[]{"Basic", "Expert"});
        styleBox.setToolTipText("Basic: short, plain language. Expert: detailed, names the model elements.");
        styleBox.addActionListener(e -> saveSettings());
        addRow(p, c, 0, "AI model:", modelRow, "Answer style:", styleBox);

        // Zeile 1: Kontext + Temperatur
        scopeBox = new JComboBox<>(focusElement == null ? new String[]{SCOPE_FULL} : new String[]{SCOPE_FULL, SCOPE_FOCUS});
        scopeBox.setToolTipText("<html>Which part of the model is sent to the AI.<br>'Whole model': everything except SysML/UML libraries.<br>"
                + "'Selected element + surroundings': only the element, its owners, neighbours and relations (smaller, faster).</html>");
        temperatureSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 2.0, 0.1));
        temperatureSpinner.setToolTipText("0 = most deterministic (recommended for evaluation). Higher = more varied answers.");
        temperatureSpinner.addChangeListener(e -> saveSettings());
        thinkingBox = new JComboBox<>(new String[]{"Model default", "Minimal", "Low", "Medium", "High"});
        thinkingBox.setToolTipText("<html>How much the AI \"thinks\" before answering. Less thinking = faster answers,<br>"
                + "but possibly worse reasoning about indirect links. Keep it constant within an experiment<br>"
                + "(it is logged). Not every model supports every level – then the model default is used.</html>");
        thinkingBox.addActionListener(e -> saveSettings());
        addRow(p, c, 1, "Context:", scopeBox, "Thinking:", thinkingBox);

        // Zeile 2: Optionen + Max Tokens
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
                + "Changing AI model, answer style, ID check or grounding uploads once more automatically.<br>"
                + "Not available for every model on the free tier – then the model is sent with each question.</html>");
        for (JCheckBox cb : new JCheckBox[]{groundingBox, freshChatBox, fallbackBox, cacheModelBox}) cb.addActionListener(e -> saveSettings());
        JPanel checks = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        checks.add(cacheModelBox);
        checks.add(Box.createHorizontalStrut(8));
        checks.add(groundingBox);
        checks.add(Box.createHorizontalStrut(8));
        checks.add(freshChatBox);
        checks.add(Box.createHorizontalStrut(8));
        checks.add(fallbackBox);
        maxTokensSpinner = new JSpinner(new SpinnerNumberModel(8192, 256, 65536, 256));
        maxTokensSpinner.setToolTipText("Maximum length of the answer (incl. internal reasoning of thinking models).");
        maxTokensSpinner.addChangeListener(e -> saveSettings());
        addRow(p, c, 2, "Options:", checks, "Temperature:", temperatureSpinner);

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
        addRow(p, c, 3, "Evaluation:", evalRow, "Max tokens:", maxTokensSpinner);

        // Zeile 4: Status + Buttons
        exportStatusLabel = new JLabel("Exporting model …");
        exportStatusLabel.setForeground(Color.DARK_GRAY);
        JButton reexportBtn = new JButton("Resend model");
        reexportBtn.setToolTipText("Exports the model again from CATIA (use after changes) and uploads it with the next question.");
        reexportBtn.addActionListener(e -> {
            if (!busy) runExport();
        });
        JButton apiKeyBtn = new JButton("API key…");
        apiKeyBtn.addActionListener(e -> new SettingsDialog().setVisible(true));
        JButton logBtn = new JButton("Open results");
        logBtn.setToolTipText("Opens the results folder (log, test runs, archived prompts and exports): "
                + EvaluationLog.getBaseDir().getAbsolutePath());
        logBtn.addActionListener(e -> openResultsFolder());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.add(reexportBtn);
        buttons.add(apiKeyBtn);
        buttons.add(logBtn);

        c.gridy = 4; c.gridx = 0; c.gridwidth = 2; c.weightx = 1;
        p.add(exportStatusLabel, c);
        c.gridwidth = 1;
        c.gridx = 2; c.weightx = 0; p.add(new JLabel("Repetitions:"), c);
        c.gridx = 3; c.weightx = 0.3; p.add(repetitionsSpinner, c);
        c.gridy = 5; c.gridx = 0; c.gridwidth = 4; c.weightx = 1;
        p.add(buttons, c);
        c.gridwidth = 1;

        scopeBox.addActionListener(e -> {
            if (loading || busy) return;
            saveSettings();
            runExport();
        });
        return p;
    }

    private void addRow(JPanel p, GridBagConstraints c, int row, String l1, JComponent comp1, String l2, JComponent comp2) {
        c.gridy = row;
        c.gridx = 0; c.weightx = 0; p.add(new JLabel(l1), c);
        c.gridx = 1; c.weightx = 1; p.add(comp1, c);
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
            boolean elementQuestions = focusElement != null && !isFullScope();
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
        styleBox.setSelectedItem(prefs.get(SysmlAiQaPlugin.DEFAULT_MODE_PREF, "Basic"));
        scopeBox.setSelectedItem(elementMode ? SCOPE_FOCUS : SCOPE_FULL);
        thinkingBox.setSelectedItem(prefs.get(SysmlAiQaPlugin.THINKING_PREF, "Model default"));
        temperatureSpinner.setValue(prefs.getDouble(SysmlAiQaPlugin.TEMPERATURE_PREF, 0.0));
        maxTokensSpinner.setValue(prefs.getInt(SysmlAiQaPlugin.MAX_TOKENS_PREF, 8192));
        repetitionsSpinner.setValue(Math.max(1, Math.min(20, prefs.getInt(SysmlAiQaPlugin.REPETITIONS_PREF, 1))));
        groundingBox.setSelected(prefs.getBoolean(SysmlAiQaPlugin.GROUNDING_PREF, false));
        freshChatBox.setSelected(prefs.getBoolean(SysmlAiQaPlugin.FRESH_CHAT_PREF, true));
        fallbackBox.setSelected(prefs.getBoolean(SysmlAiQaPlugin.FALLBACK_PREF, false));
        requireIdsBox.setSelected(prefs.getBoolean(SysmlAiQaPlugin.REQUIRE_IDS_PREF, false));
        cacheModelBox.setSelected(prefs.getBoolean(SysmlAiQaPlugin.CACHE_MODEL_PREF, true));
        loading = false;
    }

    private void saveSettings() {
        if (loading || modelBox == null || styleBox == null || scopeBox == null || temperatureSpinner == null
                || maxTokensSpinner == null || repetitionsSpinner == null || groundingBox == null
                || freshChatBox == null || fallbackBox == null || requireIdsBox == null || cacheModelBox == null
                || thinkingBox == null) {
            return;
        }
        prefs.put(SysmlAiQaPlugin.MODEL_PREF, currentModel());
        prefs.put(SysmlAiQaPlugin.DEFAULT_MODE_PREF, (String) styleBox.getSelectedItem());
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
        s.detailLevel = (String) styleBox.getSelectedItem();
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
        s.scope = isFullScope() ? "FULL_MODEL" : "FOCUS_NEIGHBOURHOOD";
        return s;
    }

    private boolean isFullScope() {
        return !SCOPE_FOCUS.equals(scopeBox.getSelectedItem());
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
        final boolean full = isFullScope();
        busy = true;
        updateControls();
        exportStatusLabel.setText("Exporting " + (full ? "whole model" : "element surroundings") + " …");
        addMessage("System", "Exporting " + (full ? "the whole model" : "the surroundings of the selected element") + " …");

        new SwingWorker<ModelExport, Void>() {
            @Override
            protected ModelExport doInBackground() throws Exception {
                Project project = Application.getInstance().getProject();
                if (project == null) return null;
                File f = DataExtractionService.exportModel(project, focusElement,
                        full ? DataExtractionService.Scope.FULL_MODEL : DataExtractionService.Scope.FOCUS_NEIGHBOURHOOD);
                return f == null ? null : ModelExport.fromFile(f);
            }

            @Override
            protected void done() {
                busy = false;
                try {
                    ModelExport ex = get();
                    if (ex == null) {
                        exportStatusLabel.setText("Export failed – see CATIA log.");
                        addMessage("System", "Error: model export failed (see CATIA log window).");
                    } else {
                        export = ex;
                        modelIndex = ModelIndex.parse(ex.json);
                        refreshQuickQuestions();
                        apiService.resetConversation();     // neue Modelldaten -> neuer Chat
                        apiService.discardUploadedModel();  // alter Upload ungültig -> nächste Frage lädt neu hoch
                        String info = String.format("Model ready: %d elements · %d relations · %.0f KB · ~%,d tokens · export %s",
                                ex.elementCount, ex.relationCount, ex.sizeKb(), ex.approxTokens(), ex.sha);
                        exportStatusLabel.setText(info);
                        addMessage("System", escapeHtml(info) + "<br>"
                                + (cacheModelBox.isSelected()
                                ? "The model will be uploaded once with your first question; after that only questions are sent."
                                : "The model is sent with each question.")
                                + "<br>Type a question below or pick a quick question.");
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

    // ═══════════════════════════════════════════════════════════════════════
    // Einzelne Frage (mit Wiederholungen)
    // ═══════════════════════════════════════════════════════════════════════

    private void sendQuestion() {
        String question = inputArea.getText().trim();
        if (question.isEmpty() || busy) return;
        if (export == null) {
            addMessage("System", "The model has not been exported yet.");
            return;
        }
        if (question.matches("(?s).*<[^<>]{1,40}>.*")) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "Your question still contains a placeholder like <element>. Send anyway?",
                    "Placeholder", JOptionPane.YES_NO_OPTION);
            if (choice != JOptionPane.YES_OPTION) return;
        }

        final AskSettings settings = currentSettings();
        final String focusInfo = buildFocusInfo();
        final ModelExport ex = export;
        final int reps = settings.repetitions;

        addMessage("You", escapeHtml(question) + (reps > 1 ? " <i>(× " + reps + ")</i>" : ""));
        inputArea.setText("");
        addMessage("System", "Waiting for " + escapeHtml(settings.model) + " …");
        busy = true;
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
                try {
                    get();
                } catch (Exception e) {
                    removeLastMessage();
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    addMessage("System", "⚠ Error: " + escapeHtml(String.valueOf(cause.getMessage())));
                }
                updateControls();
                inputArea.requestFocusInWindow();
            }
        }.execute();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Testlauf aus CSV
    // ═══════════════════════════════════════════════════════════════════════

    private void startTestSuite() {
        if (busy || export == null) {
            addMessage("System", "Wait until the model export is finished.");
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
        form.add(new JLabel("<html>Uses the current settings (style, temperature, max tokens, grounding, ID check).<br>"
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
            addMessage("System", "⚠ " + escapeHtml(a.text));
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
        sendBtn.setEnabled(!busy && export != null);
        sendBtn.setText(busy ? "Busy…" : "Send");
        if (suiteRunning) {
            suiteBtn.setText(cancelRequested ? "Stopping…" : "Stop test run");
            suiteBtn.setEnabled(!cancelRequested);
        } else {
            suiteBtn.setText("Run test suite…");
            suiteBtn.setEnabled(!busy && export != null);
        }
        scopeBox.setEnabled(!busy);
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

    private String buildFocusInfo() {
        if (focusElement == null) return null;
        String owner = focusElement.getOwner() != null ? focusElement.getOwner().getHumanName() : "root";
        return getFocusName() + " [type: " + focusElement.getHumanType() + ", id: " + focusElement.getID()
                + ", owner: " + owner + "]";
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
