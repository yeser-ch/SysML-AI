/**
 * Die grafische Benutzeroberfläche und der Chat-Dialog für die Interaktion mit dem Plugin.
 */

package com.schroedingersblock.ui;

import com.schroedingersblock.api.GeminiExplanationService;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.schroedingersblock.data_extraction.DataExtractionService;
import javax.swing.*;
import java.awt.*;

// Pop-up-Fenster (JFrame statt JDialog für Minimieren-Option)
public class ExplanationDialog extends JFrame {
    private JPanel chatPanel;
    private JScrollPane scrollPane;
    private JTextField inputField;
    private JButton runBtn;
    private JButton sendBtn;

    private final String mode;
    private final Element targetElement;

    // Anbindung der LLM-Logik
    private final GeminiExplanationService apiService = new GeminiExplanationService();

    public ExplanationDialog(String mode, Element targetElement) {
        this.mode = mode;
        this.targetElement = targetElement;

        setTitle("Schrödinger's Block - SYSML AI Analysis");
        setSize(550, 600);
        setLocationRelativeTo(null);
        setAlwaysOnTop(true);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        buildUI();

        // Sofortige Rückmeldung beim Öffnen
        addMessage("System", "Starting model data export for <b>" + mode + "</b>\u00A0...");

        // Export-Prozess im Hintergrund starten
        runExportTask();
    }

    private void buildUI() {
        // --- Header Panel (Oben) ---
        JPanel headerPanel = new JPanel(new BorderLayout(10, 0));
        headerPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 5, 10));

        // Titel und Target-Information (Oben Links)
        headerPanel.add(createTitleArea(), BorderLayout.WEST);

        // Steuerelemente (Oben Rechts)
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        String[] levels = {"Basic", "Expert"};
        JComboBox<String> levelBox = new JComboBox<>(levels);

        // Standardwert aus den Einstellungen laden
        java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(com.schroedingersblock.core.SchroedingersBlockPlugin.class);
        String defaultMode = prefs.get(com.schroedingersblock.core.SchroedingersBlockPlugin.DEFAULT_MODE_PREF, "Basic");
        levelBox.setSelectedItem(defaultMode);
        levelBox.setToolTipText("Choose how detailed you want the AI to explain the system to you.");

        levelBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JComponent comp = (JComponent) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value != null) {
                    if ("Basic".equals(value)) {
                        comp.setToolTipText("Short, concise explanations.");
                    } else if ("Expert".equals(value)) {
                        comp.setToolTipText("Long, detailed explanations.");
                    }
                }
                return comp;
            }
        });

        runBtn = new JButton("RUN");
        runBtn.setEnabled(false); // Deaktiviert bis Export abgeschlossen

        controlPanel.add(new JLabel("Detail Level: "));
        controlPanel.add(levelBox);
        controlPanel.add(runBtn);

        headerPanel.add(controlPanel, BorderLayout.EAST);
        add(headerPanel, BorderLayout.NORTH);

        // --- Chat Bereich (Mitte) ---
        chatPanel = new ScrollablePanel();
        chatPanel.setLayout(new BoxLayout(chatPanel, BoxLayout.Y_AXIS));
        scrollPane = new JScrollPane(chatPanel);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        add(scrollPane, BorderLayout.CENTER);

        // --- Eingabebereich (Süden) ---
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 10, 10));

        inputField = new JTextField();
        inputField.setEnabled(false); //Textfeld am Anfang sperren

        sendBtn = new JButton("Send");
        sendBtn.setEnabled(false); //Senden-Button am Anfang sperren

        bottomPanel.add(new JLabel("Follow-up: "), BorderLayout.WEST);
        bottomPanel.add(inputField, BorderLayout.CENTER);
        bottomPanel.add(sendBtn, BorderLayout.EAST);

        sendBtn.addActionListener(e -> sendMessage());
        inputField.addActionListener(e -> sendMessage());

        add(bottomPanel, BorderLayout.SOUTH);

        // Logik für den RUN Button (Korrekt innerhalb von buildUI platziert!)
        runBtn.setToolTipText("Start AI analysis.");
        runBtn.addActionListener(e -> {
            apiService.setDetailLevel((String) levelBox.getSelectedItem());

            addMessage("AI", "Preparing and uploading model context to AI\u00A0...");

            // Alles konsequent sperren, sobald RUN gedrückt wird!
            runBtn.setEnabled(false);
            inputField.setEnabled(false);
            sendBtn.setEnabled(false);

            // --- NEU: Startzeit direkt beim Klick auf RUN erfassen und 3 Argumente übergeben ---
            long startTime = System.currentTimeMillis();
            fetchData(mode, true, startTime);
        });
    }

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (!text.isEmpty()) {
            addMessage("User", text);
            inputField.setText("");
            addMessage("AI", "Processing\u00A0...");

            // Felder sperren
            inputField.setEnabled(false);
            sendBtn.setEnabled(false);
            runBtn.setEnabled(false);

            // --- NEU: Startzeit direkt beim Klick/Senden erfassen ---
            long startTime = System.currentTimeMillis();
            fetchData(text, false, startTime);
        }
    }

    // --- REFACTORING: Im Untergrund arbeitende Methode für den KI-Request ---
    private void fetchData(String query, boolean isInitial, long startTime) {
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() {
                if (isInitial) {
                    return apiService.fetchExplanation(targetElement, mode);
                } else {
                    return apiService.fetchFollowUp(query);
                }
            }

            @Override
            protected void done() {
                try {
                    removeLastMessage(); // Entfernt "Processing..."
                    String aiResponse = get();

                    // --- NEU: Performance-Timer berechnen ---
                    long durationMs = System.currentTimeMillis() - startTime;
                    double durationSec = durationMs / 1000.0;
                    Application.getInstance().getGUILog().log(
                            "[Schrödinger's Block] Performance-Info: Interaktion für '" + query + "' dauerte " + String.format("%.2f", durationSec) + " Sekunden."
                    );

                    // --- HIER SUBTIL EINGEBAUT: convertMarkdownToHtml umschließt die Antwort ---
                    addMessage("AI", convertMarkdownToHtml(aiResponse));

                } catch (Exception e) {
                    removeLastMessage(); // Entfernt "Processing..." im Fehlerfall

                    // --- NEU: Fehlerverschluckung verhindern (Detaillierte Ursache ermitteln) ---
                    Throwable cause = e.getCause();
                    String detailedReason = (cause != null) ? cause.getMessage() : e.getMessage();
                    if (detailedReason == null || detailedReason.isEmpty()) {
                        detailedReason = "Timeout oder Verbindungsabbruch.";
                    }

                    String errorMessage = "Error: Connection failed. (" + detailedReason + ")";

                    // Fehlermeldung in den Chat UND ins GUI-Log schreiben
                    addMessage("System", errorMessage);
                    Application.getInstance().getGUILog().log("[Schrödinger's Block] Fehler bei KI-Anfrage: " + errorMessage);

                } finally {
                    // Alles wieder freischalten
                    runBtn.setEnabled(true);
                    inputField.setEnabled(true);
                    sendBtn.setEnabled(true);
                    inputField.requestFocusInWindow();
                }
            }
        };
        worker.execute();
    }

    private void addMessage(String sender, String message) {
        JPanel row = new JPanel(new BorderLayout());
        JEditorPane textPane = createTextPane(sender, message);

        row.add(textPane, BorderLayout.CENTER);
        chatPanel.add(row);
        chatPanel.revalidate();

        SwingUtilities.invokeLater(() -> {
            if ("AI".equals(sender)) {
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

    private void runExportTask() {
        SwingWorker<Boolean, Void> exportWorker = new SwingWorker<>() {
            @Override
            protected Boolean doInBackground() {
                Project project = Application.getInstance().getProject();
                if (project == null) return false;

                boolean isElementView = !"Model View".equals(mode);
                return DataExtractionService.executeJsonExport(project, targetElement, isElementView);
            }

            @Override
            protected void done() {
                try {
                    if (get()) {
                        addMessage("System", "Model data successfully exported.");
                        addMessage("System", "Select detail level and press <b>RUN</b>.");
                        runBtn.setEnabled(true);
                    } else {
                        addMessage("System", "Error: Model export failed.");
                    }
                } catch (Exception e) {
                    addMessage("System", "Critical Error: " + e.getMessage());
                }
            }
        };
        exportWorker.execute();
    }

    private String getTargetName() {
        if (targetElement instanceof NamedElement) {
            String tempName = ((NamedElement) targetElement).getName();
            if (tempName != null && !tempName.isEmpty()) {
                return tempName;
            }
        }
        return "Unnamed Element"; // Fallback, falls der Nutzer einem Knoten keinen Namen gegeben hat
    }

    private String getTargetType() {
        return (targetElement != null) ? targetElement.getHumanType() : "Unknown Type";
    }

    private JPanel createTitleArea() {
        JPanel titleArea = new JPanel();
        titleArea.setLayout(new BoxLayout(titleArea, BoxLayout.Y_AXIS));
        titleArea.setOpaque(false);

        JLabel titleLabel = new JLabel("SysML Analysis: " + mode);
        titleLabel.setFont(new Font("Arial", Font.BOLD, 16));
        titleArea.add(titleLabel);

        // 1. Zeile: Target Name (oder "Unnamed Element")
        JLabel nameLabel = new JLabel("Target: " + getTargetName());
        nameLabel.setFont(new Font("Arial", Font.ITALIC, 12));
        nameLabel.setForeground(Color.DARK_GRAY); // Etwas dunkler zur Abgrenzung
        titleArea.add(nameLabel);

        // 2. Zeile: Typ-Deklaration direkt darunter
        JLabel typeLabel = new JLabel("ofType: " + getTargetType());
        typeLabel.setFont(new Font("Arial", Font.ITALIC, 12));
        typeLabel.setForeground(Color.GRAY);
        titleArea.add(typeLabel);

        return titleArea;
    }

    private JEditorPane createTextPane(String sender, String message) {
        JEditorPane textPane = new JEditorPane();
        textPane.setContentType("text/html");
        String htmlContent = "<html><body style='font-family: sans-serif; font-size: 11px; margin: 0; padding: 0;'>"
                + "<b>" + sender + ":</b><br>"
                + message.replace("\n", "<br>")
                + "</body></html>";
        textPane.setText(htmlContent);
        textPane.setEditable(false);
        textPane.setOpaque(false);
        textPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        return textPane;
    }

    // Hilfsmethode zum Rendern von Google-Markdown-Strukturen in HTML
    private String convertMarkdownToHtml(String markdown) {
        if (markdown == null) return "";
        String html = markdown;

        // 1. Überschriften formatieren (### Überschrift)
        html = html.replaceAll("(?m)^###\\s+(.+)$", "<h4 style='margin: 0; padding-top: 5px; color: #2C3E50;'>$1</h4>");
        html = html.replaceAll("(?m)^##\\s+(.+)$", "<h3 style='margin: 0; padding-top: 8px; color: #2C3E50;'>$1</h3>");

        // 2. Fettdruck formatieren (**text**)
        html = html.replaceAll("\\*\\*(.*?)\\*\\*", "<b>$1</b>");

        // 3. Spiegelstriche / Bulletpoints formatieren (* punkt)
        html = html.replaceAll("(?m)^\\*\\s+(.+)$", "<span style='padding-left: 10px;'>• $1</span>");

        return html;
    }

    private static class ScrollablePanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) { return 16; }
        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) { return 16; }
        @Override
        public boolean getScrollableTracksViewportWidth() { return true; }
        @Override
        public boolean getScrollableTracksViewportHeight() { return false; }
    }
}