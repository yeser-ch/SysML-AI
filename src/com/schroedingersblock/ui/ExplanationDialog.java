/**
 * Die grafische Benutzeroberfläche/Chat-Dialog für die Interaktion mit dem Plugin.
 */

package com.schroedingersblock.ui;

import com.schroedingersblock.api.DummyExplanationService;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement; // NEU: Wichtig für getName()
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.schroedingersblock.data_extraction.DataExtractionService;
import javax.swing.*;
import java.awt.*;

// Pop-up-Fenster (JDialog)
public class ExplanationDialog extends JDialog {
    private JPanel chatPanel;
    private JScrollPane scrollPane;
    private JTextField inputField;
    private final String mode;
    private final Element targetElement;
    private JButton runBtn; // Als Feld deklarieren

    // Hier wird die LLM Logik angebunden
    private final DummyExplanationService apiService = new DummyExplanationService();

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

        // 1. Sofortige Rückmeldung
        addMessage("System", "Starting model data export for <b>" + mode + "</b>\u00A0...");

        // 2. Export im Hintergrund starten
        runExportTask();
    }

    private void buildUI() {
        // --- Header Panel (Oben) ---
        JPanel headerPanel = new JPanel(new BorderLayout(10, 0));
        headerPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 5, 10));

        // Titel und Target-Informationen werden über die Hilfsmethode geladen
        headerPanel.add(createTitleArea(), BorderLayout.WEST);

        // Die Steuerelemente (rechts)
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));

        String[] levels = {"Basic", "Expert"};
        JComboBox<String> levelBox = new JComboBox<>(levels);

        runBtn = new JButton("RUN");
        runBtn.setEnabled(false); // Am Anfang deaktiviert, bis der Export fertig ist

        controlPanel.add(new JLabel("Detail Level: "));
        controlPanel.add(levelBox);
        controlPanel.add(runBtn);

        headerPanel.add(controlPanel, BorderLayout.EAST);
        add(headerPanel, BorderLayout.NORTH);

        // --- Chat Bereich (Mitte) ---
        chatPanel = new ScrollablePanel();
        chatPanel.setLayout(new BoxLayout(chatPanel, BoxLayout.Y_AXIS));
        scrollPane = new JScrollPane(chatPanel);

        // Scroll-Geschwindigkeit und horizontales Scrollen anpassen
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        add(scrollPane, BorderLayout.CENTER);

        // --- Eingabebereich (Süden) ---
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 10, 10));

        inputField = new JTextField();
        JButton sendBtn = new JButton("Send");

        bottomPanel.add(new JLabel("Follow-up: "), BorderLayout.WEST);
        bottomPanel.add(inputField, BorderLayout.CENTER);
        bottomPanel.add(sendBtn, BorderLayout.EAST);

        // Wenn "Send" geklickt oder Enter gedrückt wird
        sendBtn.addActionListener(e -> sendMessage());
        inputField.addActionListener(e -> sendMessage());

        add(bottomPanel, BorderLayout.SOUTH);

        // --- Logik für den RUN Button ---
        runBtn.addActionListener(e -> {
            // Ausgewähltes Level (Expert/Basic) an das Backend übergeben
            apiService.setDetailLevel((String) levelBox.getSelectedItem());

            // Neue Analyse starten
            addMessage("AI", "Generating initial SysML explanation\u00A0...");
            fetchData(mode, true);
        });
    }

    // "Send" geklickt:
    private void sendMessage() {
        String text = inputField.getText().trim();
        if (!text.isEmpty()) {
            addMessage("User", text);
            inputField.setText("");
            addMessage("AI", "Processing\u00A0..."); // Typo & Leerzeichen korrigiert
            fetchData(text, false);
        }
    }

    // Datenabfragen Multithreading (mit Hintergrund-Pfaden) - Typo korrigiert
    private void fetchData(String query, boolean isInitial) {
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() {
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // Datenübergabe an die API LLM
                if (isInitial) {
                    return apiService.fetchExplanation(targetElement, mode);
                } else {
                    return apiService.fetchFollowUp(query);
                }
            }

            @Override
            protected void done() {
                try {
                    removeLastMessage();
                    addMessage("AI", get());
                } catch (Exception e) {
                    addMessage("System", "Error: Connection failed.");
                }
            }
        };
        worker.execute();
    }

    // Nachricht im Fenster hinzufügen:
    private void addMessage(String sender, String message) {
        JPanel row = new JPanel(new BorderLayout());

        // JEditorPane-Erstellung ausgelagert.
        JEditorPane textPane = createTextPane(sender, message);

        row.add(textPane, BorderLayout.CENTER);
        chatPanel.add(row);
        chatPanel.revalidate();

        SwingUtilities.invokeLater(() -> {
            if ("AI".equals(sender)) {
                // Bei KI-Antworten: Scrolle genau zur Y-Koordinate dieser neuen Nachricht (zum Anfang)
                chatPanel.scrollRectToVisible(new Rectangle(0, row.getY(), 1, 1));
            } else {
                // Bei User oder System: Ganz nach unten scrollen
                JScrollBar bar = scrollPane.getVerticalScrollBar();
                bar.setValue(bar.getMaximum());
            }
        });
    }

    private void removeLastMessage() {
        int count = chatPanel.getComponentCount();
        if (count > 0) {
            chatPanel.remove(count - 1); // Typo "Prozessing" im Kommentar korrigiert
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

                // Bestimmung des Scopes (wie vorher in der Action)
                boolean isElementView = "Model View".equals(mode) ? false : true;
                // Der eigentliche Export-Aufruf
                return DataExtractionService.executeJsonExport(project, targetElement, isElementView);
            }

            @Override
            protected void done() {
                try {
                    if (get()) { // Wenn erfolgreich
                        addMessage("System", "Model data successfully exported.");
                        addMessage("System", "Please select a detail level and press <b>RUN</b> to start analysis.");
                        runBtn.setEnabled(true); // Jetzt darf der Nutzer die KI starten
                    } else {
                        addMessage("System", "Error: Model export failed. Please check the CATIA Log.");
                    }
                } catch (Exception e) {
                    addMessage("System", "Critical Error during export: " + e.getMessage());
                }
            }
        };
        exportWorker.execute();
    }

    // Hilfsmethode zur Erstellung des Textfeldes für Nachrichten.
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

    // Zentrale Methode für den Namen des Elements
    private String getTargetDisplayName() {
        String type = (targetElement != null) ? targetElement.getHumanType() : "Unknown";
        String name = "";
        if (targetElement instanceof NamedElement) {
            String tempName = ((NamedElement) targetElement).getName();
            if (tempName != null && !tempName.isEmpty()) {
                name = " '" + tempName + "'";
            }
        }
        return type + name;
    }

    // Hilfsmethode für den Titel-Bereich (Oben Links)
    private JPanel createTitleArea() {
        JPanel titleArea = new JPanel();
        titleArea.setLayout(new BoxLayout(titleArea, BoxLayout.Y_AXIS));
        titleArea.setOpaque(false);

        JLabel titleLabel = new JLabel("SysML Analysis: " + mode);
        titleLabel.setFont(new Font("Arial", Font.BOLD, 16));
        titleArea.add(titleLabel);

        // Nutzt jetzt die zentrale Hilfsmethode!
        JLabel subtitleLabel = new JLabel("Target: " + getTargetDisplayName());
        subtitleLabel.setFont(new Font("Arial", Font.ITALIC, 12));
        subtitleLabel.setForeground(Color.GRAY);
        titleArea.add(subtitleLabel);

        return titleArea;
    }

    // Hilfsklasse für dynamischen Zeilenumbruch
    // Implementiert das Scrollable-Interface, um dem JScrollPane zu sagen:
    // "Bitte pass meine Breite immer exakt an die Fensterbreite an!"
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