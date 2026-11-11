package de.hm.sysmlaiqa.ui;

import de.hm.sysmlaiqa.api.ExplanationService;
import de.hm.sysmlaiqa.api.ModelExport;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.prefs.Preferences;

/**
 * "Show request…": zeigt im Klartext, was mit der nächsten Frage an die KI geht (FR3, NFR3) –
 * System-Prompt, Modelldaten, bisheriger Chat, Frage-Nachricht und Parameter. Alles lässt sich speichern.
 * Es wird nichts im Hintergrund ergänzt, das hier nicht steht.
 */
public class RequestDialog extends JDialog {

    private final ExplanationService.RequestPreview p;
    private final ModelExport export;

    public RequestDialog(Window owner, ExplanationService.RequestPreview preview, ModelExport export) {
        super(owner, "Request preview – exactly what is sent to the AI", ModalityType.MODELESS);
        this.p = preview;
        this.export = export;
        setSize(900, 700);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(6, 6));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("1 System prompt", textTab(p.systemPrompt, "Source: " + p.systemPromptSource));
        tabs.addTab("2 Model data", textTab(p.modelMessage, String.format(
                "%s · %d elements · %,.0f KB · ~%,d tokens%s · export %s · %s",
                export.isV2() ? "SysML v2 textual notation" : "SysML v1 JSON export",
                export.elementCount, export.sizeKb(), export.approxTokens(),
                export.exactTokens >= 0 ? String.format(" (exact %,d for %s)", export.exactTokens, export.exactTokensModel) : "",
                export.sha, export.scopeDetail)));
        if (!p.history.isEmpty()) {
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < p.history.size(); i++) {
                h.append(i % 2 == 0 ? "──── You (turn " + (i / 2 + 1) + ") ────\n" : "──── AI ────\n");
                h.append(p.history.get(i)).append("\n\n");
            }
            tabs.addTab("3 Earlier chat", textTab(h.toString(), (p.history.size() / 2)
                    + " earlier question(s) of this chat are sent again (\"New chat per question\" is off)."));
        }
        tabs.addTab((p.history.isEmpty() ? "3" : "4") + " Question", textTab(p.questionMessage,
                "The question as it will be sent (incl. the selected element, if \"Mention selected element\" is on)."));
        tabs.addTab((p.history.isEmpty() ? "4" : "5") + " Parameters", textTab(p.generationConfig + "\n\n" + p.transferNote,
                "Generation settings of this request."));
        add(tabs, BorderLayout.CENTER);

        JLabel order = new JLabel("  Order of the request: system prompt → model data → earlier chat (if any) → question.");
        order.setForeground(Color.DARK_GRAY);
        add(order, BorderLayout.NORTH);

        JButton save = new JButton("Save all…");
        save.setToolTipText("Saves every part as a separate file into a folder you choose.");
        save.addActionListener(e -> saveAll());
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(save);
        buttons.add(close);
        add(buttons, BorderLayout.SOUTH);
    }

    private static JComponent textTab(String text, String info) {
        JTextArea area = new JTextArea(text == null ? "" : text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        JLabel l = new JLabel(info);
        l.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        panel.add(l, BorderLayout.NORTH);
        panel.add(new JScrollPane(area), BorderLayout.CENTER);
        return panel;
    }

    private void saveAll() {
        Preferences prefs = Preferences.userNodeForPackage(RequestDialog.class);
        JFileChooser ch = new JFileChooser(prefs.get("request_save_dir", System.getProperty("user.home")));
        ch.setDialogTitle("Choose a folder for the request files");
        ch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (ch.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File dir = new File(ch.getSelectedFile(), "request_" + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        prefs.put("request_save_dir", ch.getSelectedFile().getAbsolutePath());
        try {
            dir.mkdirs();
            write(new File(dir, "1_system_prompt.md"), p.systemPrompt);
            write(new File(dir, "2_model_data_message.txt"), p.modelMessage);           // exakt wie gesendet (mit Überschrift)
            write(new File(dir, "2_model_data" + export.extension()), export.content);  // nur die Modelldaten (gültiges JSON bzw. .sysml)
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < p.history.size(); i++) {
                h.append(i % 2 == 0 ? "### You\n" : "### AI\n").append(p.history.get(i)).append("\n\n");
            }
            if (h.length() > 0) write(new File(dir, "3_earlier_chat.md"), h.toString());
            write(new File(dir, "4_question.md"), p.questionMessage);
            write(new File(dir, "5_parameters.txt"), p.generationConfig + "\n\n" + p.transferNote
                    + "\n\nexport sha: " + export.sha + "\nexport format: " + export.format + "\nscope: " + export.scopeDetail);
            JOptionPane.showMessageDialog(this, "Saved to:\n" + dir.getAbsolutePath(), getTitle(), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage(), getTitle(), JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void write(File f, String text) throws java.io.IOException {
        Files.writeString(f.toPath(), text == null ? "" : text, StandardCharsets.UTF_8);
    }
}
