package com.example.pdfsplitter;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChapterTools {

    // ------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------

    /** One line read from the TOC. printedPage is the number printed in the book. */
    record TocEntry(int number, String title, Integer printedPage) {}

    /** The winning TOC pattern (for display) and the entries it produced. */
    record DetectedToc(String shape, List<TocEntry> entries) {}

    private record Shape(String name, Pattern pattern, boolean numbered) {}

    // ------------------------------------------------------------------
    // TOC line shapes. Each pattern captures:
    //   group 1 = chapter number (empty for unnumbered shapes)
    //   group 2 = title
    //   group 3 = printed page number
    // To support a new TOC style, add another Shape to this list.
    // ------------------------------------------------------------------

    private static final int U = Pattern.UNICODE_CHARACTER_CLASS;   // \s also matches non-breaking spaces
    private static final int CI = Pattern.CASE_INSENSITIVE;

    private static final List<Shape> SHAPES = List.of(
            new Shape("1 Title ..... 19",
                    Pattern.compile("^(\\d{1,3})\\s+(?=\\D)(.+?)[\\s.·…]+(\\d{1,4})\\s*$", U), true),
            new Shape("1. Title ..... 19",
                    Pattern.compile("^(\\d{1,3})[.)]\\s+(.+?)[\\s.·…]+(\\d{1,4})\\s*$", U), true),
            new Shape("Chapter 1 Title ..... 19",
                    Pattern.compile("^(?:chapter|ch\\.?|unit|lesson|module)\\s+(\\d{1,3})\\s*[.:–—-]?\\s+(.+?)[\\s.·…]+(\\d{1,4})\\s*$",
                            U | CI), true)
    );

    // Last resort: any "Title ..... page" line that isn't a numbered sub-section like 3.1
    private static final Shape FLAT = new Shape("Title ..... 19 (no numbering)",
            Pattern.compile("^()(?!\\d+\\.\\d)(.+?)[\\s.·…]{2,}(\\d{1,4})\\s*$", U), false);

    // ------------------------------------------------------------------
    // 1. Embedded bookmarks (top level only). startPage is 0-based.
    // ------------------------------------------------------------------

    static List<Chapter> fromOutline(PDDocument doc) throws IOException {
        List<Chapter> out = new ArrayList<>();
        PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
        if (outline == null) return out;
        for (PDOutlineItem item : outline.children()) {
            PDPage page = item.findDestinationPage(doc);
            if (page == null) continue;                 // named destinations can fail to resolve
            int idx = doc.getPages().indexOf(page);
            if (idx >= 0) out.add(new Chapter(item.getTitle(), idx));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 2. TOC text: detect the best pattern and extract top-level entries
    // ------------------------------------------------------------------

    static DetectedToc detectTopLevel(PDDocument doc, int tocFirst, int tocLast) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(tocFirst + 1);            // PDFTextStripper is 1-based
        stripper.setEndPage(tocLast + 1);
        List<String> lines = List.of(stripper.getText(doc).split("\\R"));

        DetectedToc best = new DetectedToc("none", List.of());
        for (Shape s : SHAPES) {
            List<TocEntry> got = extract(lines, s);
            if (got.size() >= 3 && got.size() > best.entries().size()) {
                best = new DetectedToc(s.name(), got);
            }
        }
        if (best.entries().isEmpty()) {
            List<TocEntry> got = extract(lines, FLAT);
            if (got.size() >= 3) best = new DetectedToc(FLAT.name(), got);
        }
        return best;
    }

    private static List<TocEntry> extract(List<String> lines, Shape s) {
        List<TocEntry> out = new ArrayList<>();
        int lastNumber = -1;
        int lastPage = -1;
        for (String line : lines) {
            Matcher m = s.pattern().matcher(line.strip());   // strip() also removes non-breaking spaces
            if (!m.matches()) continue;

            int page = Integer.parseInt(m.group(3));
            if (page < lastPage) continue;                   // pages never go backwards

            int number;
            if (s.numbered()) {
                number = Integer.parseInt(m.group(1));
                boolean ok = out.isEmpty()
                        ? number <= 1                                       // first chapter is 0 or 1
                        : number > lastNumber && number <= lastNumber + 2;  // in sequence, tolerate one gap
                if (!ok) continue;
            } else {
                number = out.size() + 1;
            }

            String title = m.group(2).replaceAll("[\\s.·…]+$", "").trim();
            out.add(new TocEntry(number, title, page));
            lastNumber = number;
            lastPage = page;
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 3. Page offset: printed page numbers -> physical PDF pages
    // ------------------------------------------------------------------

    /** Returns the offset (physical index = printed - 1 + offset), or null if undetermined.
     *  entries must have startPage = PRINTED page - 1. */
    static Integer guessOffset(PDDocument doc, List<Chapter> entries, int tocLast) throws IOException {
        int total = doc.getNumberOfPages();
        PDFTextStripper stripper = new PDFTextStripper();
        Map<Integer, String> pageText = new HashMap<>();   // each page extracted at most once
        Map<Integer, Integer> votes = new HashMap<>();
        int tried = 0;

        for (Chapter c : entries) {
            if (tried >= 6) break;
            String needle = norm(c.title());
            if (needle.length() < 4) continue;
            tried++;
            for (int p = tocLast + 1; p < total; p++) {
                String text = pageText.get(p);
                if (text == null) {
                    stripper.setStartPage(p + 1);
                    stripper.setEndPage(p + 1);
                    text = norm(stripper.getText(doc));
                    pageText.put(p, text);
                }
                if (text.contains(needle)) {
                    votes.merge(p - c.startPage(), 1, Integer::sum);
                    break;
                }
            }
        }
        return votes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** Turns TOC entries into chapters with 0-based PHYSICAL start pages.
     *  Entries without a printed page are found by searching for their title. */
    static List<Chapter> resolve(PDDocument doc, List<TocEntry> entries, int tocLast, int offset)
            throws IOException {
        int total = doc.getNumberOfPages();
        PDFTextStripper stripper = new PDFTextStripper();
        Map<Integer, String> cache = new HashMap<>();
        List<Chapter> out = new ArrayList<>();
        int prev = tocLast + 1;

        for (TocEntry e : entries) {
            int physical = -1;
            if (e.printedPage() != null) {
                physical = e.printedPage() - 1 + offset;
            } else {
                String needle = norm(e.title());
                for (int p = prev; p < total; p++) {
                    String text = cache.get(p);
                    if (text == null) {
                        stripper.setStartPage(p + 1);
                        stripper.setEndPage(p + 1);
                        text = norm(stripper.getText(doc));
                        cache.put(p, text);
                    }
                    if (text.contains(needle)) { physical = p; break; }
                }
            }
            if (physical < 0 || physical >= total) continue;   // couldn't place it; skipped
            out.add(new Chapter(e.title(), physical));
            prev = Math.max(prev, physical);
        }
        return out;
    }

    private static String norm(String s) {
        return s.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------
    // 4. Splitting
    // ------------------------------------------------------------------

    /** Each chapter runs from its start page up to the page before the next one;
     *  the last runs to the end of the document. */
    static void split(PDDocument src, List<Chapter> chapters, Path outDir) throws IOException {
        List<Chapter> sorted = chapters.stream()
                .sorted(Comparator.comparingInt(Chapter::startPage)).toList();
        Files.createDirectories(outDir);
        int total = src.getNumberOfPages();

        for (int i = 0; i < sorted.size(); i++) {
            int start = sorted.get(i).startPage();
            int end = (i + 1 < sorted.size()) ? sorted.get(i + 1).startPage() : total;  // exclusive
            if (start < 0 || start >= total || end <= start) continue;

            String name = String.format("%02d - %s.pdf", i + 1, sanitize(sorted.get(i).title()));
            try (PDDocument part = new PDDocument()) {
                for (int p = start; p < end; p++) part.importPage(src.getPage(p));
                part.save(outDir.resolve(name).toFile());       // save before src closes
            }
        }
    }

    static String sanitize(String s) {
        String clean = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return clean.length() > 80 ? clean.substring(0, 80) : clean;
    }
}