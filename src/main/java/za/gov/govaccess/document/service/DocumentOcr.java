package za.gov.govaccess.document.service;

import java.awt.image.BufferedImage;
import net.sourceforge.tess4j.ITessAPI.TessPageIteratorLevel;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.util.LoadLibs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DocumentOcr {
    private final String dataDirectory;

    public DocumentOcr(@Value("${govaccess.documents.tessdata-directory:}") String dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public record Line(String text, java.awt.Rectangle bounds) {}
    public record Result(String text, double confidence, java.util.List<Line> lines) {
        public Result(String text, double confidence) { this(text, confidence, java.util.List.of()); }
    }

    public Result read(BufferedImage image) {
        var engine = new Tesseract();
        engine.setDatapath(dataDirectory.isBlank()
                ? LoadLibs.extractTessResources("tessdata").getAbsolutePath() : dataDirectory);
        engine.setLanguage("eng");
        var words = engine.getWords(image, TessPageIteratorLevel.RIL_WORD);
        var line = new StringBuilder();
        var lines = new java.util.ArrayList<Line>();
        java.awt.Rectangle lineBounds = null;
        java.awt.Rectangle previous = null;
        for (var word : words) {
            var bounds = word.getBoundingBox();
            if (previous != null && (bounds.x < previous.x || bounds.y >= previous.y + previous.height)) {
                lines.add(new Line(line.toString(), lineBounds));
                line.setLength(0);
                lineBounds = null;
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word.getText());
            lineBounds = lineBounds == null ? new java.awt.Rectangle(bounds) : lineBounds.union(bounds);
            previous = bounds;
        }
        if (lineBounds != null) lines.add(new Line(line.toString(), lineBounds));
        return new Result(String.join("\n", lines.stream().map(Line::text).toList()),
                words.stream().mapToDouble(word -> word.getConfidence()).average().orElse(0), java.util.List.copyOf(lines));
    }
}
