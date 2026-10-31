package de.hm.sysmlaiqa.api;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Erzeugt aus einer Log-CSV eine formatierte Excel-Datei (.xlsx) daneben – ohne externe Bibliothek.
 *
 * Die CSV bleibt die Rohdatenquelle (das Plugin hängt dort nur an). Die .xlsx ist die lesbare Ansicht:
 * wichtige Spalten vorne, feste Spaltenbreiten und Zeilenhöhen, fixierte Kopfzeile, Filter, hervorgehobene
 * Bewertungsspalten mit Auswahlliste, technische Spalten gruppiert (über "+" einblendbar), zweites Blatt mit
 * Erklärung aller Spalten.
 */
public final class ExcelExport {

    private ExcelExport() { }

    /** Vorschlag für die Auswahlliste in der Spalte "rating" (andere Werte sind erlaubt). */
    static final String RATING_VALUES = "correct,partial,wrong,hallucinated,correctly_refused";

    /** Spalten, die vorne stehen und sichtbar bleiben (in dieser Reihenfolge), mit Breite in Zeichen. */
    private static final Object[][] MAIN = {
            {"run_id", 17}, {"test_id", 11}, {"category", 12}, {"repetition", 13}, {"timestamp", 20},
            {"question", 45}, {"expected_answer", 35}, {"answer", 75}, {"rating", 17}, {"rating_notes", 30},
            {"model_version", 22}, {"success", 11}, {"duration_s", 13}, {"ids_unknown", 14},
    };

    /** Spalten mit Zahlen (werden in Excel als Zahl gespeichert, damit Filter und Summen funktionieren). */
    private static final List<String> NUMERIC = Arrays.asList(
            "repetition", "temperature", "max_tokens", "export_kb", "export_elements", "export_relations",
            "prompt_tokens", "cached_tokens", "answer_tokens", "thoughts_tokens", "duration_s", "rate_limit_wait_s",
            "ids_cited", "ids_found", "ids_unknown");

    /** Kurze Erklärung für das Blatt "Columns". */
    private static final Map<String, String> DESCRIPTION = new LinkedHashMap<>();
    static {
        String[][] d = {
                {"run_id", "Testlauf (Datum-Uhrzeit) oder \"chat\" für einzeln gestellte Fragen"},
                {"test_id", "ID des Testfalls aus der CSV mit den Testfällen"},
                {"category", "A Retrieval, B Structure, C Semantics, D Uncertainty"},
                {"repetition", "Wiederholung der Frage (1, 2, 3 …)"},
                {"timestamp", "Zeitpunkt der Antwort"},
                {"question", "Frage, genau so wie sie an die KI ging"},
                {"expected_answer", "Referenzantwort (Ground Truth) aus den Testfällen"},
                {"answer", "Antwort der KI, zur besseren Lesbarkeit ohne Markdown-Zeichen (Originaltext in der CSV). Ganzer Text: Zelle anklicken und oben in der Bearbeitungsleiste lesen oder Zeile vergrößern"},
                {"rating", "Eure Bewertung (Auswahlliste, andere Werte möglich)"},
                {"rating_notes", "Begründung zur Bewertung"},
                {"model_requested", "Gewähltes KI-Modell"},
                {"model_used", "Modell, an das die Anfrage ging (anders nur mit Fallback)"},
                {"model_version", "Tatsächlich antwortendes Modell laut Google – für Modellvergleiche diese Spalte nehmen"},
                {"success", "true = Antwort erhalten, false = Fehler (Text in answer)"},
                {"finish_reason", "STOP = vollständig, MAX_TOKENS = abgeschnitten"},
                {"detail_level", "Antwortstil Basic / Expert"},
                {"temperature", "Temperatur (0 = möglichst gleichbleibend)"},
                {"max_tokens", "Maximale Antwortlänge"},
                {"thinking_level", "Eingestellte Denktiefe"},
                {"thinking_used", "Tatsächlich verwendete Denktiefe"},
                {"grounding", "Google-Suche erlaubt"},
                {"fresh_chat", "Frage ohne vorherigen Chatverlauf"},
                {"require_ids", "ID-Check eingeschaltet"},
                {"scope", "FULL_MODEL = ganzes Modell, FOCUS_NEIGHBOURHOOD = Element mit Umgebung"},
                {"focus_element", "Ausgewähltes Element (bei Fragen zu einem Element)"},
                {"system_prompt_sha", "Prüfsumme des System-Prompts (Wortlaut in archive\\prompts)"},
                {"export_file", "Name der Exportdatei"},
                {"export_sha", "Prüfsumme des Modellexports (Datei in archive\\exports)"},
                {"export_kb", "Größe des Exports in KB"},
                {"export_elements", "Anzahl Elemente im Export"},
                {"export_relations", "Anzahl Beziehungen im Export"},
                {"model_transfer", "inline = Modell mit der Frage gesendet, cache_new = hochgeladen, cache = aus dem Upload"},
                {"prompt_tokens", "Umfang der Eingabe in Tokens"},
                {"cached_tokens", "Davon aus dem hochgeladenen Modell"},
                {"answer_tokens", "Länge der Antwort in Tokens"},
                {"thoughts_tokens", "Tokens für internes Nachdenken"},
                {"duration_s", "Antwortzeit in Sekunden"},
                {"rate_limit_wait_s", "Wartezeit wegen Google-Limits in Sekunden"},
                {"ids_cited", "Anzahl genannter Element-IDs (nur mit ID-Check)"},
                {"ids_found", "Davon im Modell vorhanden"},
                {"ids_unknown", "Davon NICHT im Modell (erfunden)"},
                {"unknown_ids", "Die erfundenen IDs"},
                {"answer_sha", "Prüfsumme der Antwort – gleiche Prüfsumme = wortgleiche Antwort"},
                {"plugin_version", "Version des Plugins"},
        };
        for (String[] r : d) DESCRIPTION.put(r[0], r[1]);
    }

    // Stil-Indizes (Reihenfolge wie in cellXfs unten)
    private static final int S_TEXT = 1, S_WRAP = 2, S_NUM = 3, S_HEADER = 4, S_RATING = 5, S_BAD = 6,
            S_OK = 7, S_HEADER_RATING = 8, S_INFO_HEAD = 9;

    /**
     * Erzeugt fehlende oder veraltete .xlsx-Dateien im Ergebnisordner (z. B. für Logs aus älteren Versionen).
     * Eine .xlsx, die neuer ist als ihre CSV, wird nicht angefasst – dort eingetragene Bewertungen bleiben erhalten.
     */
    public static void refreshAll(File baseDir) {
        List<File> csvs = new ArrayList<>();
        csvs.add(new File(baseDir, "answers_log.csv"));
        File[] runs = new File(baseDir, "runs").listFiles((d, n) -> n.endsWith(".csv") && !n.contains("_while-open"));
        if (runs != null) csvs.addAll(Arrays.asList(runs));
        for (File csv : csvs) {
            if (!csv.isFile()) continue;
            File xlsx = new File(csv.getParentFile(), csv.getName().replaceAll("\\.csv$", "") + ".xlsx");
            if (!xlsx.exists() || xlsx.lastModified() < csv.lastModified()) writeFor(csv);
        }
    }

    /** Erzeugt die .xlsx neben der CSV (gleicher Name). Gibt eine Warnung zurück oder null. */
    public static String writeFor(File csv) {
        File xlsx = new File(csv.getParentFile(), csv.getName().replaceAll("\\.csv$", "") + ".xlsx");
        try {
            String text = new String(Files.readAllBytes(csv.toPath()), StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) text = text.substring(1);
            List<List<String>> rows = TestSuite.parseCsv(text, ';');
            if (rows.isEmpty()) return null;
            byte[] data = build(rows);
            File tmp = new File(xlsx.getParentFile(), xlsx.getName() + ".tmp");
            Files.write(tmp.toPath(), data);
            try {
                Files.move(tmp.toPath(), xlsx.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException locked) {
                Files.deleteIfExists(tmp.toPath());
                return xlsx.getName() + " is open in Excel – it will be updated after you close it.";
            }
            return null;
        } catch (Exception e) {
            return "Could not write " + xlsx.getName() + ": " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Aufbau der Datei
    // ═══════════════════════════════════════════════════════════════════════

    static byte[] build(List<List<String>> rows) throws IOException {
        List<String> header = rows.get(0);
        // Spaltenreihenfolge: Hauptspalten, dann alle übrigen in CSV-Reihenfolge
        List<Integer> order = new ArrayList<>();
        List<Integer> widths = new ArrayList<>();
        for (Object[] m : MAIN) {
            int i = header.indexOf((String) m[0]);
            if (i >= 0) { order.add(i); widths.add((Integer) m[1]); }
        }
        int mainCount = order.size();
        for (int i = 0; i < header.size(); i++) {
            if (!order.contains(i)) { order.add(i); widths.add(Math.max(12, Math.min(26, header.get(i).length() + 4))); }
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            put(zip, "[Content_Types].xml", CONTENT_TYPES);
            put(zip, "_rels/.rels", ROOT_RELS);
            put(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS);
            put(zip, "xl/styles.xml", STYLES);
            String lastCol = col(order.size() - 1);
            int lastRow = rows.size();
            put(zip, "xl/workbook.xml", workbook(lastCol, lastRow));
            put(zip, "xl/worksheets/sheet1.xml", answersSheet(rows, header, order, widths, mainCount));
            put(zip, "xl/worksheets/sheet2.xml", columnsSheet(header, order));
        }
        return bos.toByteArray();
    }

    private static String answersSheet(List<List<String>> rows, List<String> header, List<Integer> order,
                                       List<Integer> widths, int mainCount) {
        StringBuilder x = new StringBuilder(64 * 1024);
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
                .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
                .append("<sheetPr><outlinePr summaryRight=\"0\"/><pageSetUpPr fitToPage=\"1\"/></sheetPr>")
                .append("<sheetViews><sheetView workbookViewId=\"0\" zoomScale=\"100\">")
                .append("<pane xSplit=\"0\" ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
                .append("<selection pane=\"bottomLeft\" activeCell=\"A2\" sqref=\"A2\"/>")
                .append("</sheetView></sheetViews>")
                .append("<sheetFormatPr defaultRowHeight=\"15\" outlineLevelCol=\"1\"/>")
                .append("<cols>");
        for (int c = 0; c < order.size(); c++) {
            boolean technical = c >= mainCount;
            x.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1)
                    .append("\" width=\"").append(widths.get(c)).append("\" customWidth=\"1\"");
            if (technical) x.append(" hidden=\"1\" outlineLevel=\"1\"");
            // "+" zum Einblenden der technischen Spalten sitzt über der letzten Hauptspalte (summaryRight=0)
            if (c == mainCount - 1 && order.size() > mainCount) x.append(" collapsed=\"1\"");
            x.append("/>");
        }
        x.append("</cols><sheetData>");

        // Kopfzeile
        x.append("<row r=\"1\" ht=\"32\" customHeight=\"1\">");
        for (int c = 0; c < order.size(); c++) {
            String name = header.get(order.get(c));
            boolean rating = name.startsWith("rating");
            cell(x, c, 1, name, rating ? S_HEADER_RATING : S_HEADER, false);
        }
        x.append("</row>");

        // Daten
        int iSuccess = header.indexOf("success"), iUnknown = header.indexOf("ids_unknown");
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            int rowNo = r + 1;
            x.append("<row r=\"").append(rowNo).append("\" ht=\"90\" customHeight=\"1\">");
            boolean failed = iSuccess >= 0 && iSuccess < row.size() && "false".equals(row.get(iSuccess));
            for (int c = 0; c < order.size(); c++) {
                int src = order.get(c);
                String name = header.get(src);
                String v = src < row.size() ? row.get(src) : "";
                if (name.equals("timestamp")) v = v.replace('T', ' ').replaceAll("\\.\\d+$", "");
                if (name.equals("answer")) v = readable(v);
                int style;
                if (name.startsWith("rating")) style = S_RATING;
                else if (name.equals("question") || name.equals("expected_answer") || name.equals("answer")
                        || name.equals("unknown_ids")) style = S_WRAP;
                else if (name.equals("success")) style = failed ? S_BAD : S_OK;
                else if (name.equals("ids_unknown") && !v.isEmpty() && !v.equals("0")) style = S_BAD;
                else if (NUMERIC.contains(name)) style = S_NUM;
                else style = S_TEXT;
                if (failed && name.equals("answer")) style = S_BAD;
                boolean numeric = NUMERIC.contains(name) && isNumber(v);
                cell(x, c, rowNo, numeric ? v.replace(',', '.') : v, style, numeric);
            }
            x.append("</row>");
        }
        x.append("</sheetData>");
        String last = col(order.size() - 1) + Math.max(2, rows.size());
        x.append("<autoFilter ref=\"A1:").append(last).append("\"/>");
        int ratingCol = -1;
        for (int c = 0; c < order.size(); c++) if (header.get(order.get(c)).equals("rating")) ratingCol = c;
        if (ratingCol >= 0) {
            String ref = col(ratingCol) + "2:" + col(ratingCol) + "10000";
            x.append("<dataValidations count=\"1\"><dataValidation type=\"list\" allowBlank=\"1\" showInputMessage=\"1\" ")
                    .append("showErrorMessage=\"0\" errorStyle=\"information\" promptTitle=\"Rating\" ")
                    .append("prompt=\"Pick a value or type your own.\" sqref=\"").append(ref).append("\">")
                    .append("<formula1>\"").append(RATING_VALUES).append("\"</formula1></dataValidation></dataValidations>");
        }
        x.append("<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.5\" bottom=\"0.5\" header=\"0.3\" footer=\"0.3\"/>");
        // Drucken: Querformat, alle sichtbaren Spalten auf eine Seitenbreite
        x.append("<pageSetup paperSize=\"9\" orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>");
        x.append("</worksheet>");
        return x.toString();
    }

    private static String columnsSheet(List<String> header, List<Integer> order) {
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
                .append("<cols><col min=\"1\" max=\"1\" width=\"22\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"100\" customWidth=\"1\"/></cols>")
                .append("<sheetData><row r=\"1\" ht=\"22\" customHeight=\"1\">");
        cell(x, 0, 1, "Column", S_INFO_HEAD, false);
        cell(x, 1, 1, "Meaning", S_INFO_HEAD, false);
        x.append("</row>");
        int r = 2;
        x.append("<row r=\"").append(r).append("\">");
        cell(x, 0, r, "Note", S_TEXT, false);
        cell(x, 1, r, "This file is rebuilt automatically from the CSV next to it. Rate in a copy (File → Save as) "
                + "or in the file of a finished test run in the folder runs. Technical columns are grouped: click \"+\" above the columns to show them.", S_WRAP, false);
        x.append("</row>");
        r++;
        for (int c = 0; c < order.size(); c++) {
            String name = header.get(order.get(c));
            x.append("<row r=\"").append(r).append("\">");
            cell(x, 0, r, name, S_TEXT, false);
            cell(x, 1, r, DESCRIPTION.getOrDefault(name, ""), S_WRAP, false);
            x.append("</row>");
            r++;
        }
        x.append("</sheetData></worksheet>");
        return x.toString();
    }

    private static String workbook(String lastCol, int lastRow) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<bookViews><workbookView activeTab=\"0\"/></bookViews>"
                + "<sheets><sheet name=\"Answers\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"Columns\" sheetId=\"2\" r:id=\"rId2\"/></sheets>"
                + "<definedNames><definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"0\" hidden=\"1\">"
                + "Answers!$A$1:$" + lastCol + "$" + Math.max(2, lastRow) + "</definedName></definedNames>"
                + "</workbook>";
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Hilfsfunktionen
    // ═══════════════════════════════════════════════════════════════════════

    private static void cell(StringBuilder x, int c, int r, String v, int style, boolean numeric) {
        String ref = col(c) + r;
        if (v == null || v.isEmpty()) {
            x.append("<c r=\"").append(ref).append("\" s=\"").append(style).append("\"/>");
        } else if (numeric) {
            x.append("<c r=\"").append(ref).append("\" s=\"").append(style).append("\"><v>").append(v).append("</v></c>");
        } else {
            if (v.length() > 32000) v = v.substring(0, 32000) + " …";  // Excel-Grenze pro Zelle: 32.767 Zeichen
            x.append("<c r=\"").append(ref).append("\" s=\"").append(style).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    .append(xml(v)).append("</t></is></c>");
        }
    }

    /** Antwort ohne Markdown-Zeichen (**fett**, `code`, * Aufzählung) – der Originaltext bleibt in der CSV. */
    static String readable(String v) {
        if (v == null) return "";
        String s = v.replace("\r\n", "\n");
        s = s.replaceAll("(?m)^\\s*[*-]\\s+", "• ");
        s = s.replaceAll("(?m)^#{1,6}\\s+", "");
        s = s.replace("**", "").replace("`", "");
        s = s.replaceAll("(?<![\\w*])\\*(?![\\s*])([^*\\n]+?)(?<!\\s)\\*(?![\\w*])", "$1");
        return s;
    }

    static boolean isNumber(String v) {
        return v != null && v.matches("-?\\d+([.,]\\d+)?");
    }

    static String col(int index) {
        StringBuilder s = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            int m = (n - 1) % 26;
            s.insert(0, (char) ('A' + m));
            n = (n - 1) / 26;
        }
        return s.toString();
    }

    private static String xml(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\r': break; // Excel erwartet nur \n als Zeilenumbruch
                default:
                    if (ch < 0x20 && ch != '\n' && ch != '\t') break; // in XML nicht erlaubt
                    b.append(ch);
            }
        }
        return b.toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        OutputStream out = zip;
        out.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static final String CONTENT_TYPES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
            + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            + "<Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
            + "</Types>";

    private static final String ROOT_RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
            + "</Relationships>";

    private static final String WORKBOOK_RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/>"
            + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
            + "</Relationships>";

    /*
     * cellXfs: 0 Standard · 1 Text oben · 2 Text umbrechen oben · 3 Zahl oben · 4 Kopf · 5 Bewertung (gelb, umbrechen)
     *          6 rot (Fehler) · 7 grün (ok) · 8 Kopf Bewertung · 9 Kopf Info-Blatt
     */
    private static final String STYLES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<fonts count=\"3\">"
            + "<font><sz val=\"10\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
            + "<font><b/><sz val=\"10\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
            + "<font><b/><sz val=\"10\"/><color rgb=\"FF000000\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
            + "</fonts>"
            + "<fills count=\"7\">"
            + "<fill><patternFill patternType=\"none\"/></fill>"
            + "<fill><patternFill patternType=\"gray125\"/></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1F3864\"/><bgColor indexed=\"64\"/></patternFill></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFF2CC\"/><bgColor indexed=\"64\"/></patternFill></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFF8CBAD\"/><bgColor indexed=\"64\"/></patternFill></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE2EFDA\"/><bgColor indexed=\"64\"/></patternFill></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFD966\"/><bgColor indexed=\"64\"/></patternFill></fill>"
            + "</fills>"
            + "<borders count=\"2\">"
            + "<border><left/><right/><top/><bottom/><diagonal/></border>"
            + "<border><left style=\"thin\"><color rgb=\"FFD9D9D9\"/></left><right style=\"thin\"><color rgb=\"FFD9D9D9\"/></right>"
            + "<top style=\"thin\"><color rgb=\"FFD9D9D9\"/></top><bottom style=\"thin\"><color rgb=\"FFD9D9D9\"/></bottom><diagonal/></border>"
            + "</borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"10\">"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\"/></xf>"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\"/></xf>"
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\" wrapText=\"1\"/></xf>"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"4\" borderId=\"1\" xfId=\"0\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"5\" borderId=\"1\" xfId=\"0\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\"/></xf>"
            + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"6\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\" wrapText=\"1\"/></xf>"
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>"
            + "</cellXfs>"
            + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
            + "</styleSheet>";
}
