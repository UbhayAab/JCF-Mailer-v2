package com.jarurat.mailer.controllers;

import com.jarurat.mailer.security.AppUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/")
    public String landing(@AuthenticationPrincipal AppUserDetails user) {
        return user == null ? "landing" : "redirect:/app";
    }

    @GetMapping("/login")
    public String login(@AuthenticationPrincipal AppUserDetails user) {
        return user == null ? "login" : "redirect:/app";
    }

    @GetMapping("/app")
    public String console(@AuthenticationPrincipal AppUserDetails user, Model model) {
        model.addAttribute("userEmail", user.getUsername());
        model.addAttribute("userName", user.getFullName());
        model.addAttribute("userRole", user.getRole().name());
        model.addAttribute("userRoleLabel", user.getRole().getLabel());
        model.addAttribute("permissions", user.getRole().getPermissions().stream().map(Enum::name).toList());
        return "console";
    }
}
