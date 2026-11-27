package za.gov.govaccess.document.controller;

import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.gov.govaccess.document.service.ApplicationDocuments;
import za.gov.govaccess.security.ActorIdentity;

@Controller
@RequestMapping("/applications/{applicationId}/documents")
class ApplicationDocumentController {
    private final ApplicationDocuments documents;

    ApplicationDocumentController(ApplicationDocuments documents) { this.documents = documents; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    String upload(Authentication actor, @PathVariable UUID applicationId, @RequestParam MultipartFile file,
                  RedirectAttributes flash) {
        try {
            var id = documents.upload(actor, applicationId, file);
            return "redirect:/applications/" + applicationId + "/documents/" + id;
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        } catch (IOException e) {
            flash.addFlashAttribute("errorMessage", "The document could not be stored. Try again.");
        }
        return "redirect:/applications/" + applicationId;
    }

    @GetMapping("/{id}")
    String view(Authentication actor, @PathVariable UUID applicationId, @PathVariable UUID id,
                @RequestParam(defaultValue = "1") int page, Model model) {
        model.addAttribute("document", documents.get(actor, applicationId, id));
        model.addAttribute("canVerify", ActorIdentity.has(actor, "EVIDENCE_CORRECT"));
        model.addAttribute("page", page);
        return "application/document";
    }

    @GetMapping("/{id}/preview")
    ResponseEntity<byte[]> preview(Authentication actor, @PathVariable UUID applicationId, @PathVariable UUID id,
                                   @RequestParam(defaultValue = "1") int page) throws IOException {
        try (var pdf = org.apache.pdfbox.Loader.loadPDF(documents.content(actor, applicationId, id));
             var output = new java.io.ByteArrayOutputStream()) {
            if (page < 1 || page > pdf.getNumberOfPages()) return ResponseEntity.notFound().build();
            javax.imageio.ImageIO.write(new org.apache.pdfbox.rendering.PDFRenderer(pdf)
                    .renderImageWithDPI(page - 1, 100), "png", output);
            return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                    .header("Cache-Control", "private, no-store").body(output.toByteArray());
        }
    }

    @GetMapping("/{id}/status")
    @ResponseBody
    java.util.Map<String, String> status(Authentication actor, @PathVariable UUID applicationId, @PathVariable UUID id) {
        return java.util.Map.of("status", documents.get(actor, applicationId, id).state());
    }

    @GetMapping("/{id}/file")
    ResponseEntity<byte[]> file(Authentication actor, @PathVariable UUID applicationId, @PathVariable UUID id)
            throws IOException {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "attachment; filename=\"document.pdf\"")
                .header("Content-Security-Policy", "sandbox")
                .header("Cache-Control", "private, no-store").body(documents.content(actor, applicationId, id));
    }

    @PostMapping("/{id}/verify")
    String verify(Authentication actor, @PathVariable UUID applicationId, @PathVariable UUID id,
                  @RequestParam String fieldKey, @RequestParam(required = false) String value,
                  @RequestParam int version, RedirectAttributes flash) {
        try {
            documents.verify(actor, applicationId, id, fieldKey, value, version);
            flash.addFlashAttribute("successMessage", "Field verified.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/applications/" + applicationId + "/documents/" + id;
    }

    @PostMapping("/{id}/retry")
    String retry(Authentication actor, @PathVariable UUID applicationId, @PathVariable UUID id) {
        documents.requeue(actor, applicationId, id);
        return "redirect:/applications/" + applicationId + "/documents/" + id;
    }
}
