package za.gov.govaccess.document.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PrivateDocumentStorage {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    static final int MAX_PAGES = 12;
    private final Path root;

    public PrivateDocumentStorage(@Value("${govaccess.documents.storage-directory:./var/private-documents}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        if (this.root.toString().replace('\\', '/').matches("(?i).*/(static|public)(/.*)?")) {
            throw new IllegalArgumentException("Document storage must be outside public and static directories");
        }
    }

    public void validate(byte[] bytes) {
        if (bytes.length < 5 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Upload a PDF of up to 8 MB.");
        }
        if (!new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) {
            throw new IllegalArgumentException("The upload is not a valid PDF.");
        }
        try (var pdf = Loader.loadPDF(bytes)) {
            if (pdf.isEncrypted()) throw new IllegalArgumentException("Password-protected PDFs cannot be uploaded.");
            if (pdf.getNumberOfPages() < 1 || pdf.getNumberOfPages() > MAX_PAGES) {
                throw new IllegalArgumentException("Upload a PDF containing one to twelve pages.");
            }
            for (var page : pdf.getPages()) {
                var box = page.getCropBox();
                if (box.getWidth() <= 0 || box.getHeight() <= 0 || box.getWidth() > 1600 || box.getHeight() > 1600) {
                    throw new IllegalArgumentException("The PDF page dimensions exceed the supported limits.");
                }
            }
        } catch (InvalidPasswordException e) {
            throw new IllegalArgumentException("Password-protected PDFs cannot be uploaded.", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("The PDF is corrupt or unreadable.", e);
        }
    }

    public void store(UUID id, byte[] bytes) throws IOException {
        boolean posix = root.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (posix) {
            Files.createDirectories(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectories(root);
        }
        var options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        var filename = path(id);
        var channel = posix
                ? Files.newByteChannel(filename, options, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.newByteChannel(filename, options);
        try (channel) {
            var remaining = ByteBuffer.wrap(bytes);
            while (remaining.hasRemaining()) channel.write(remaining);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(filename);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    public byte[] read(UUID id) throws IOException { return Files.readAllBytes(path(id)); }
    public void delete(UUID id) throws IOException { Files.deleteIfExists(path(id)); }
    private Path path(UUID id) { return root.resolve(id + ".pdf"); }
}
