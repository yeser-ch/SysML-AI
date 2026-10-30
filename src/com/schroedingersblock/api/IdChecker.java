package com.schroedingersblock.api;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Halluzinations-Check über Element-IDs.
 *
 * Die KI soll jedes genannte Modellelement mit [id:...] kennzeichnen (Schalter "Cite IDs").
 * Hier wird geprüft, ob es diese IDs im Export wirklich gibt.
 * Zusätzlich werden "nackte" IDs im CATIA-Format erkannt (z. B. _19_0_3_8a7027a_1647253287530_520426_1).
 *
 * Grenzen: Erfasst nur erfundene IDs. Eine echte ID mit falscher Aussage dazu fällt nicht auf –
 * dafür braucht es weiterhin die manuelle Bewertung gegen die Referenzantwort.
 */
public final class IdChecker {

    private static final Pattern MARKED = Pattern.compile("\\[id:\\s*([^\\]\\s,;]+)\\s*\\]");
    private static final Pattern BARE = Pattern.compile("(?<![A-Za-z0-9_])_[A-Za-z0-9]+(?:_[A-Za-z0-9]+){3,}");

    private IdChecker() { }

    public static class Result {
        public final List<String> cited;
        public final List<String> unknown;

        Result(List<String> cited, List<String> unknown) {
            this.cited = cited;
            this.unknown = unknown;
        }

        public int found() {
            return cited.size() - unknown.size();
        }
    }

    public static Result check(String answer, Set<String> knownIds) {
        Set<String> cited = new LinkedHashSet<>();
        if (answer != null) {
            Matcher m = MARKED.matcher(answer);
            while (m.find()) cited.add(m.group(1));
            Matcher b = BARE.matcher(answer);
            while (b.find()) cited.add(b.group());
        }
        List<String> unknown = new ArrayList<>();
        for (String id : cited) {
            if (!knownIds.contains(id)) unknown.add(id);
        }
        return new Result(new ArrayList<>(cited), unknown);
    }
}
