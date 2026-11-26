package za.gov.govaccess.rules.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.gov.govaccess.programme.entity.Programme;
import za.gov.govaccess.programme.repository.ProgrammeRepository;
import za.gov.govaccess.rules.entity.FieldDefinition;
import za.gov.govaccess.rules.enums.FieldValueType;
import za.gov.govaccess.rules.service.RuleCatalogueService;
import za.gov.govaccess.security.PolicyWorkspaceAccess;

@Controller
@RequestMapping("/rules/catalogue")
class RuleCatalogueController {
    private final RuleCatalogueService catalogue;
    private final ProgrammeRepository programmes;

    RuleCatalogueController(RuleCatalogueService catalogue, ProgrammeRepository programmes) {
        this.catalogue = catalogue;
        this.programmes = programmes;
    }

    @GetMapping
    String show(Authentication viewer, HttpServletRequest request, Model model) {
        model.addAttribute("username", viewer.getName());
        model.addAttribute("cataloguePage", true);
        model.addAttribute("canEditPolicy", PolicyWorkspaceAccess.isEditing(viewer, request));
        model.addAttribute("documents", catalogue.documents());
        model.addAttribute("fields", catalogue.catalogue().fields().values().stream()
                .sorted(Comparator.comparing(FieldDefinition::getKey)).toList());
        model.addAttribute("fieldTypes", FieldValueType.values());
        model.addAttribute("demoProgrammes", programmes.findAll().stream().filter(Programme::isDemoData)
                .sorted(Comparator.comparing(Programme::getName)).toList());
        return "rules/catalogue";
    }

    @PostMapping("/documents")
    String addDocument(@RequestParam String key, @RequestParam String label, RedirectAttributes redirect) {
        try {
            catalogue.addDocument(key, label);
            redirect.addFlashAttribute("success", "Document type added.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        } catch (DataIntegrityViolationException e) {
            redirect.addFlashAttribute("error", "The document type could not be saved. Check the key and try again.");
        }
        return "redirect:/rules/catalogue";
    }

    @PostMapping("/fields")
    String addField(@RequestParam String key, @RequestParam String label, @RequestParam FieldValueType valueType,
                    @RequestParam(required = false) String unit, @RequestParam(required = false) Set<String> sources,
                    RedirectAttributes redirect) {
        try {
            catalogue.addField(key, label, valueType, unit, sources);
            redirect.addFlashAttribute("success", "Field definition added.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        } catch (DataIntegrityViolationException e) {
            redirect.addFlashAttribute("error", "The field could not be saved. Check its key and document types.");
        }
        return "redirect:/rules/catalogue";
    }
}
