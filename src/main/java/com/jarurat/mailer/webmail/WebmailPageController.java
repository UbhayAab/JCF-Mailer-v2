package com.jarurat.mailer.webmail;

import com.jarurat.mailer.security.AppUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the webmail screen. It is its own page rather than another tab inside the
 * console, because a three pane mail client wants the whole viewport and the console
 * is a scrolling document.
 *
 * Gated on the same permission as the data behind it, so a role without mail access
 * gets one honest 403 instead of a fully drawn screen whose every panel then fails.
 */
@Controller
public class WebmailPageController {

    @GetMapping("/mail")
    @PreAuthorize("hasAuthority('MAIL_READ')")
    public String mail(@AuthenticationPrincipal AppUserDetails user, Model model) {
        model.addAttribute("userEmail", user.getUsername());
        model.addAttribute("userName", user.getFullName());
        model.addAttribute("userRole", user.getRole().getLabel());
        model.addAttribute("permissions", user.getRole().getPermissions().stream().map(Enum::name).toList());
        return "mail";
    }
}
