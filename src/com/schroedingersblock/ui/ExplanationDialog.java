/**
 * "Ask the model"-Fenster: Einstellungen, Schnellfragen, Chat und freie Frage-Eingabe auf EINER Oberfläche.
 *
 * Ablauf:
 * 1. Beim Öffnen wird das Modell exportiert (Standard: ganzes Modell).
 * 2. Der Nutzer tippt eine Frage oder wählt eine Schnellfrage und drückt "Send".
 * 3. Jede Antwort wird mit allen Einstellungen ins Evaluations-Log (CSV) geschrieben.
 */

package com.schroedingersblock.ui;

import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.schroedingersblock.api.AskSettings;
import com.schroedingersblock.api.EvaluationLog;
import com.schroedingersblock.api.ExplanationService;
import com.schroedingersblock.api.GeminiExplanationService;
import com.schroedingersblock.core.SchroedingersBlockPlugin;
import com.schroedingersblock.data_extraction.DataExtractionService;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.prefs.Preferences;

public class ExplanationDialog extends JFrame {

    private static final String SCOPE_FULL = "Whole model";
    private static final String SCOPE_FOCUS = "Selected element + surroundings";

    /** Schnellfragen: {Kategorie, Button-Text, Frage-Vorlage}. Platzhalter <...> werden markiert. */
    private static final String[][] QUICK_QUESTIONS = {
            {"General", "Model overview", "Give an overview of the model: its main system, subsystems, requirements and how they are connected."},
            {"General", "Explain selection", "Explain <element> and all its relationships in the model."},
            {"A · Retrieval", "Who satisfies…?", "Which component satisfies the requirement <requirement>?"},
            {"A · Retrieval", "Requirement list", "List all requirements and the model elements that satisfy them."},
            {"B · Structure", "Indirect links", "Which components are directly or indirectly connected to <element>? Show the path for each."},
            {"B · Structure", "Change impact", "If <element> changes, which other elements are affected and why?"},
            {"C · Semantics", "Does satisfy prove it?", "Does the satisfy relationship of <requirement> prove that the requirement is actually fulfilled? Explain."},
            {"C · Semantics", "Meaning of relation", "What does the relationship between <element A> and <element B> mean technically?"},
            {"D · Uncertainty", "Is it verifiable?", "Can it be verified from the model whether <requirement> is met? Which information is missing?"},
            {"D · Uncertainty", "Missing data", "Is there enough information in the model to answer the following? If not, say what is missing: <question>"},
    };

    private final Element focusElement;
    private final GeminiExplanationService apiService = new GeminiExplanationService();
    private final Preferences prefs = Preferences.userNodeForPackage(SchroedingersBlockPlugin.class);

    // Einstellungen
    private JComboBox<String> modelBox;
    private JComboBox<String> styleBox;
    private JComboBox<String> scopeBox;
    private JSpinner temperatureSpinner;
    private JSpinner maxTokensSpinner;
    private JCheckBox groundingBox;
    private JCheckBox freshChatBox;
    private JCheckBox fallbackBox;
    private JLabel exportStatusLabel;

    // Chat
    private JPanel chatPanel;
    private JScrollPane scrollPane;
    private JTextArea inputArea;
    private JButton sendBtn;

    // Modelldaten
    private volatile String modelJson;
    private volatile File exportFile;
    private boolean busy = false;

    public ExplanationDialog(Element focusElement) {
        this.focusElement = focusElement;

        setTitle("Schrödinger's Block – Ask the model");
        setSize(780, 860);
        setLocationRelativeTo(null);
        setAlwaysOnTop(true);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        buildUI();
        loadSettings();
        runExport();
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
        addRow(p, c, 0, "AI model:", modelRow, "Answer style:", styleBox = new JComboBox<>(new String[]{"Basic", "Expert"}));
        styleBox.setToolTipText("Basic: short, plain language. Expert: detailed, names the model elements.");
        styleBox.addActionListener(e -> saveSettings());

        // Zeile 1: Kontext + Temperatur
        scopeBox = new JComboBox<>(focusElement == null ? new String[]{SCOPE_FULL} : new String[]{SCOPE_FULL, SCOPE_FOCUS});
        scopeBox.setToolTipText("Which part of the model is sent to the AI. 'Whole model' sends everything except SysML/UML libraries.");
        temperatureSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 2.0, 0.1));
        temperatureSpinner.setToolTipText("0 = most deterministic (recommended for evaluation). Higher = more varied answers.");
        temperatureSpinner.addChangeListener(e -> saveSettings());
        addRow(p, c, 1, "Context:", scopeBox, "Temperature:", temperatureSpinner);

        // Zeile 2: Checkboxen + Max Tokens
        groundingBox = new JCheckBox("Google Search grounding");
        groundingBox.setToolTipText("Lets the AI use Google Search. Off for evaluation: answers should come from the model only. "
                + "Not every model supports it on the free tier.");
        freshChatBox = new JCheckBox("New chat per question");
        freshChatBox.setToolTipText("On: every question is answered independently (recommended for tests). Off: follow-up questions keep the conversation.");
        fallbackBox = new JCheckBox("Fallback model");
        fallbackBox.setToolTipText("If the chosen model is overloaded, automatically try other models. Off for evaluation, so results are not mixed.");
        for (JCheckBox cb : new JCheckBox[]{groundingBox, freshChatBox, fallbackBox}) cb.addActionListener(e -> saveSettings());
        JPanel checks = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        checks.add(groundingBox);
        checks.add(Box.createHorizontalStrut(8));
        checks.add(freshChatBox);
        checks.add(Box.createHorizontalStrut(8));
        checks.add(fallbackBox);
        maxTokensSpinner = new JSpinner(new SpinnerNumberModel(8192, 256, 65536, 256));
        maxTokensSpinner.setToolTipText("Maximum length of the answer (incl. internal reasoning of thinking models).");
        maxTokensSpinner.addChangeListener(e -> saveSettings());
        addRow(p, c, 2, "Options:", checks, "Max tokens:", maxTokensSpinner);

        // Zeile 3: Status + Buttons
        exportStatusLabel = new JLabel("Exporting model …");
        exportStatusLabel.setForeground(Color.DARK_GRAY);
        JButton reexportBtn = new JButton("Re-export model");
        reexportBtn.setToolTipText("Export again after you changed the model in CATIA.");
        reexportBtn.addActionListener(e -> runExport());
        JButton apiKeyBtn = new JButton("API key…");
        apiKeyBtn.addActionListener(e -> new SettingsDialog().setVisible(true));
        JButton logBtn = new JButton("Open log");
        logBtn.setToolTipText("Opens the CSV with all questions, answers and settings: " + EvaluationLog.getLogFile().getAbsolutePath());
        logBtn.addActionListener(e -> openLog());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.add(reexportBtn);
        buttons.add(apiKeyBtn);
        buttons.add(logBtn);

        c.gridy = 3; c.gridx = 0; c.gridwidth = 2; c.weightx = 1;
        p.add(exportStatusLabel, c);
        c.gridx = 2; c.gridwidth = 2; c.weightx = 0;
        p.add(buttons, c);
        c.gridwidth = 1;

        scopeBox.addActionListener(e -> {
            if (loading) return;
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
        JPanel p = new JPanel(new GridLayout(0, 2, 4, 4));
        p.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(),
                "Quick questions (click to insert, then edit and send)", TitledBorder.LEFT, TitledBorder.TOP));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (String[] q : QUICK_QUESTIONS) {
            JButton b = new JButton(q[0] + ": " + q[1]);
            b.setHorizontalAlignment(SwingConstants.LEFT);
            b.setMargin(new Insets(2, 6, 2, 6));
            b.setToolTipText(q[2]);
            b.addActionListener(e -> insertQuickQuestion(q[2]));
            p.add(b);
        }
        return p;
    }

    private JComponent createInputPanel() {
        JPanel p = new JPanel(new BorderLayout(6, 4));
        p.setBorder(BorderFactory.createEmptyBorder(4, 10, 10, 10));

        inputArea = new JTextArea(3, 40);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setToolTipText("Type your question. Enter = send, Shift+Enter = new line.");
        // Enter = senden, Shift+Enter = Zeilenumbruch
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

    private boolean loading = false;

    private void loadSettings() {
        loading = true;
        String model = prefs.get(SchroedingersBlockPlugin.MODEL_PREF, GeminiExplanationService.DEFAULT_MODELS[0]);
        for (String m : GeminiExplanationService.mergeModelLists(null, model)) modelBox.addItem(m);
        modelBox.setSelectedItem(model);
        styleBox.setSelectedItem(prefs.get(SchroedingersBlockPlugin.DEFAULT_MODE_PREF, "Basic"));
        String scope = prefs.get(SchroedingersBlockPlugin.SCOPE_PREF, SCOPE_FULL);
        scopeBox.setSelectedItem(focusElement == null ? SCOPE_FULL : scope);
        temperatureSpinner.setValue(prefs.getDouble(SchroedingersBlockPlugin.TEMPERATURE_PREF, 0.0));
        maxTokensSpinner.setValue(prefs.getInt(SchroedingersBlockPlugin.MAX_TOKENS_PREF, 8192));
        groundingBox.setSelected(prefs.getBoolean(SchroedingersBlockPlugin.GROUNDING_PREF, false));
        freshChatBox.setSelected(prefs.getBoolean(SchroedingersBlockPlugin.FRESH_CHAT_PREF, true));
        fallbackBox.setSelected(prefs.getBoolean(SchroedingersBlockPlugin.FALLBACK_PREF, false));
        loading = false;
    }

    private void saveSettings() {
        if (loading || modelBox == null || scopeBox == null || temperatureSpinner == null || maxTokensSpinner == null
                || groundingBox == null || freshChatBox == null || fallbackBox == null) {
            return;
        }
        prefs.put(SchroedingersBlockPlugin.MODEL_PREF, currentModel());
        prefs.put(SchroedingersBlockPlugin.DEFAULT_MODE_PREF, (String) styleBox.getSelectedItem());
        if (focusElement != null) prefs.put(SchroedingersBlockPlugin.SCOPE_PREF, (String) scopeBox.getSelectedItem());
        prefs.putDouble(SchroedingersBlockPlugin.TEMPERATURE_PREF, ((Number) temperatureSpinner.getValue()).doubleValue());
        prefs.putInt(SchroedingersBlockPlugin.MAX_TOKENS_PREF, ((Number) maxTokensSpinner.getValue()).intValue());
        prefs.putBoolean(SchroedingersBlockPlugin.GROUNDING_PREF, groundingBox.isSelected());
        prefs.putBoolean(SchroedingersBlockPlugin.FRESH_CHAT_PREF, freshChatBox.isSelected());
        prefs.putBoolean(SchroedingersBlockPlugin.FALLBACK_PREF, fallbackBox.isSelected());
    }

    private String currentModel() {
        Object v = modelBox.isEditable() ? modelBox.getEditor().getItem() : modelBox.getSelectedItem();
        String s = v == null ? "" : v.toString().trim();
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
    // Export & Fragen
    // ═══════════════════════════════════════════════════════════════════════

    private void runExport() {
        final boolean full = isFullScope();
        busy = true;
        updateSendState();
        exportStatusLabel.setText("Exporting " + (full ? "whole model" : "element surroundings") + " …");
        addMessage("System", "Exporting " + (full ? "the whole model" : "the surroundings of the selected element") + " …");

        new SwingWorker<File, Void>() {
            @Override
            protected File doInBackground() throws Exception {
                Project project = Application.getInstance().getProject();
                if (project == null) return null;
                File f = DataExtractionService.exportModel(project, focusElement,
                        full ? DataExtractionService.Scope.FULL_MODEL : DataExtractionService.Scope.FOCUS_NEIGHBOURHOOD);
                if (f != null) modelJson = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                return f;
            }

            @Override
            protected void done() {
                busy = false;
                try {
                    File f = get();
                    if (f == null) {
                        exportStatusLabel.setText("Export failed – see CATIA log.");
                        addMessage("System", "Error: model export failed (see CATIA log window).");
                    } else {
                        exportFile = f;
                        apiService.resetConversation(); // neue Modelldaten -> neuer Chat
                        int elements = countOccurrences(modelJson, "\"owner_id\"");
                        long approxTokens = modelJson.length() / 4; // grobe Faustregel: ~4 Zeichen pro Token
                        String info = String.format("Model ready: %d elements · %.0f KB · ~%,d tokens (estimate)",
                                elements, f.length() / 1024.0, approxTokens);
                        exportStatusLabel.setText(info);
                        addMessage("System", info + "<br>Type a question below or pick a quick question.");
                    }
                } catch (Exception ex) {
                    exportStatusLabel.setText("Export failed.");
                    addMessage("System", "Critical error during export: " + escapeHtml(String.valueOf(ex.getMessage())));
                }
                updateSendState();
            }
        }.execute();
    }

    private void sendQuestion() {
        String question = inputArea.getText().trim();
        if (question.isEmpty() || busy) return;
        if (modelJson == null) {
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
        final String json = modelJson;
        final File usedExport = exportFile;

        addMessage("You", escapeHtml(question));
        inputArea.setText("");
        addMessage("System", "Waiting for " + escapeHtml(settings.model) + " …");
        busy = true;
        updateSendState();

        new SwingWorker<ExplanationService.Answer, Void>() {
            @Override
            protected ExplanationService.Answer doInBackground() {
                return apiService.ask(question, json, focusInfo, settings);
            }

            @Override
            protected void done() {
                removeLastMessage();
                busy = false;
                try {
                    ExplanationService.Answer a = get();
                    EvaluationLog.append(question, focusInfo, a, settings, usedExport);
                    if (a.success) {
                        String meta = escapeHtml(a.modelUsed)
                                + String.format(" · %.1f s", a.durationMs / 1000.0)
                                + (a.promptTokens >= 0 ? String.format(" · %,d → %,d tokens", a.promptTokens, a.answerTokens) : "");
                        if (!a.modelUsed.equals(settings.model)) meta += " · <b>fallback model!</b>";
                        addMessage("AI <span style='color:gray;font-weight:normal'>(" + meta + ")</span>",
                                convertMarkdownToHtml(a.text));
                    } else {
                        addMessage("System", "⚠ " + escapeHtml(a.text));
                    }
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    addMessage("System", "⚠ Error: " + escapeHtml(String.valueOf(cause.getMessage())));
                }
                updateSendState();
                inputArea.requestFocusInWindow();
            }
        }.execute();
    }

    private void updateSendState() {
        sendBtn.setEnabled(!busy && modelJson != null);
        sendBtn.setText(busy ? "Busy…" : "Send");
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
        // ersten Platzhalter markieren, damit man ihn direkt überschreiben kann
        int start = text.indexOf('<');
        int end = text.indexOf('>', start);
        if (start >= 0 && end > start) {
            inputArea.select(start, end + 1);
        } else {
            inputArea.setCaretPosition(text.length());
        }
    }

    private void openLog() {
        File f = EvaluationLog.getLogFile();
        if (!f.exists()) {
            addMessage("System", "No log yet. It is created with the first answer: " + escapeHtml(f.getAbsolutePath()));
            return;
        }
        try {
            Desktop.getDesktop().open(f);
        } catch (Exception ex) {
            addMessage("System", "Log file: " + escapeHtml(f.getAbsolutePath()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Hilfsfunktionen
    // ═══════════════════════════════════════════════════════════════════════

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

    private static int countOccurrences(String s, String sub) {
        if (s == null) return 0;
        int count = 0, i = 0;
        while ((i = s.indexOf(sub, i)) != -1) { count++; i += sub.length(); }
        return count;
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
