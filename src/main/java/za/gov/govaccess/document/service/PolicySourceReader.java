package za.gov.govaccess.document.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PolicySourceReader {
    public static final int MAX_PDF_BYTES = 8 * 1024 * 1024;
    public static final int MAX_TEXT_LENGTH = 200_000;
    static final int MAX_PAGES = 100;
    private static final Pattern HEADING = Pattern.compile(
            "^(?:#{1,6}\\s+.+|\\d+(?:\\.\\d+)+\\.?\\s+.{1,100}|(?:SECTION|CHAPTER)\\s+.{1,95})$");
    private final int chunkSize;
    private final int maxChunks;

    public PolicySourceReader(@Value("${govaccess.policy-import.chunk-characters:12000}") int chunkSize,
                              @Value("${govaccess.policy-import.max-chunks:500}") int maxChunks) {
        if (chunkSize < 128 || chunkSize > 30_000) {
            throw new IllegalArgumentException("Policy import chunk size must be between 128 and 30000 characters");
        }
        if (maxChunks < 1 || maxChunks > 2000) {
            throw new IllegalArgumentException("Policy import maximum chunks must be between 1 and 2000");
        }
        this.chunkSize = chunkSize;
        this.maxChunks = maxChunks;
    }

    public record Chunk(int page, String section, String text) {}

    public List<Chunk> read(String text, byte[] pdf) throws IOException {
        if (pdf == null) {
            if (text == null || text.isBlank() || text.length() > MAX_TEXT_LENGTH) {
                throw new IllegalArgumentException("Provide policy text of up to 200000 characters");
            }
            return split(List.of(text));
        }
        if (pdf.length == 0 || pdf.length > MAX_PDF_BYTES) {
            throw new IllegalArgumentException("Upload a PDF of up to 8 MB");
        }
        var pages = new ArrayList<String>();
        try (var document = Loader.loadPDF(pdf)) {
            if (document.getNumberOfPages() > MAX_PAGES) {
                throw new IllegalArgumentException("The PDF has too many pages");
            }
            var reader = new PDFTextStripper();
            int length = 0;
            for (int number = 1; number <= document.getNumberOfPages(); number++) {
                reader.setStartPage(number);
                reader.setEndPage(number);
                String page = reader.getText(document);
                length += page.length();
                if (length > MAX_TEXT_LENGTH) {
                    throw new IllegalArgumentException("The PDF text exceeds the import limit");
                }
                pages.add(page);
            }
        }
        var chunks = split(pages);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("The PDF contains no extractable text");
        }
        return chunks;
    }

    private List<Chunk> split(List<String> pages) {
        var result = new ArrayList<Chunk>();
        String section = "Policy text";
        for (int index = 0; index < pages.size(); index++) {
            var segment = new StringBuilder();
            for (String line : pages.get(index).split("\\R", -1)) {
                String clean = line.strip();
                if (HEADING.matcher(clean).matches()) {
                    append(result, index + 1, section, segment.toString());
                    section = clean.replaceFirst("^#{1,6}\\s+", "");
                    segment.setLength(0);
                }
                segment.append(line).append('\n');
            }
            append(result, index + 1, section, segment.toString());
        }
        if (result.size() > maxChunks) {
            throw new IllegalArgumentException("The policy produces more than " + maxChunks
                    + " import sections. Increase govaccess.policy-import.chunk-characters or simplify the source.");
        }
        return List.copyOf(result);
    }

    private void append(List<Chunk> chunks, int page, String section, String segment) {
        String remaining = segment.strip();
        while (remaining.length() > chunkSize) {
            int end = remaining.lastIndexOf('\n', chunkSize);
            if (end < chunkSize / 2) {
                end = remaining.lastIndexOf(' ', chunkSize);
            }
            if (end < chunkSize / 2) {
                throw new IllegalArgumentException("A policy line cannot be split safely at the configured limit");
            }
            chunks.add(new Chunk(page, section, remaining.substring(0, end).strip()));
            remaining = remaining.substring(end).strip();
        }
        if (!remaining.isBlank()) {
            chunks.add(new Chunk(page, section, remaining));
        }
    }
}
